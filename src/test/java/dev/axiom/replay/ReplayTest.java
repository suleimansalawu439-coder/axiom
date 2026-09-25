package dev.axiom.replay;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentResult;
import dev.axiom.agent.ReActAgent;
import dev.axiom.capabilities.Capability;
import dev.axiom.capabilities.Ensures;
import dev.axiom.capabilities.Requires;
import dev.axiom.guardrails.CapabilityGuardrail;
import dev.axiom.guardrails.GuardrailViolationException;
import dev.axiom.guardrails.Verdict;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolParam;
import dev.axiom.tools.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic replay and fork debugging: stepping reproduces the recorded
 * trajectory exactly, forks explore alternatives with real tool dispatch,
 * the original journal stays byte-identical, and diffs pinpoint divergence.
 * All fixture-driven — no network, no keys.
 */
class ReplayTest {

    static class CalcTools {
        @Tool(name = "t_multiply", description = "Multiply two integers")
        public int multiply(@ToolParam(description = "x") int x,
                            @ToolParam(description = "y") int y) {
            return x * y;
        }

        @Tool(name = "t_add", description = "Add two integers")
        public int add(@ToolParam(description = "x") int x,
                       @ToolParam(description = "y") int y) {
            return x + y;
        }
    }

    static class OpsTools {
        @Tool(name = "t_backupDatabase", description = "Snapshot the database", capabilities = {Capability.READ, Capability.WRITE})
        @Ensures(Capability.BACKUP)
        public String backupDatabase() { return "backup-ok"; }

        @Tool(name = "t_deleteOldSnapshots", description = "Delete old snapshots", capabilities = {Capability.DESTRUCTIVE})
        @Requires(Capability.BACKUP)
        public String deleteOldSnapshots() { return "deleted"; }
    }

    private static ChatResponse turn(String content, String callId, String tool,
                                     Map<String, Object> args) {
        return new ChatResponse(content,
            callId == null ? List.of() : List.of(new ToolCallRequest(callId, tool, args)),
            new ChatResponse.TokenUsage(10, 5, 15));
    }

    private static ChatResponse finalTurn(String text) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(10, 20, 30));
    }

    /** Record a 3-turn calculator run and return its journal directory. */
    private static Path recordCalcRun(Path journalRoot) {
        ToolRegistry registry = new ToolRegistry().register(new CalcTools());
        AgentConfig config = AgentConfig.builder()
            .withClient(new ScriptedLlm(List.of(
                turn("Multiplying.", "c1", "t_multiply", Map.of("x", 17, "y", 23)),
                turn("Adding.", "c2", "t_add", Map.of("x", 391, "y", 5)),
                finalTurn("396"))))
            .withRegistry(registry)
            .withJournalRoot(journalRoot)
            .build();
        ReActAgent agent = new ReActAgent(config);
        AgentResult result = agent.run("What is 17 * 23 + 5?");
        assertEquals("396", result.output());
        Path dir = agent.journal().dir();
        agent.journal().close();
        return dir;
    }

    private static AgentConfig calcForkConfig() {
        return AgentConfig.builder()
            .withClient(new ScriptedLlm(List.of(finalTurn("unused"))))
            .withRegistry(new ToolRegistry().register(new CalcTools()))
            .withSystemPrompt("calc")
            .build();
    }

    // ------------------------------------------------------------------
    // Stepping
    // ------------------------------------------------------------------

    @Test
    void replayReproducesIdenticalTrajectory(@TempDir Path tmp) {
        Path journalDir = recordCalcRun(tmp.resolve("journals"));

        try (ReplaySession session = ReplaySession.open(journalDir)) {
            RecordedRun recorded = session.recordedRun();
            assertEquals(3, recorded.turnCount());
            assertTrue(recorded.completed());
            assertEquals("396", recorded.finalResult().output());
            assertEquals(2, recorded.toolCallsMade());
            assertEquals(60, recorded.totalUsage().totalTokens());

            ReplayState s0 = session.currentState();
            assertEquals(0, s0.turnsConsumed());
            assertFalse(s0.done());
            assertNull(s0.lastModelText());

            ReplayState s1 = session.step();
            assertEquals(1, s1.currentTurn());
            assertEquals("Multiplying.", s1.lastModelText());
            assertEquals(List.of("t_multiply(x=17, y=23)"), s1.toolSequence());
            assertEquals(15, s1.tokenUsage().totalTokens());
            assertFalse(s1.done());

            ReplayState s2 = session.step();
            assertEquals(List.of("t_multiply(x=17, y=23)", "t_add(x=391, y=5)"), s2.toolSequence());

            ReplayState s3 = session.step();
            assertTrue(s3.done());
            assertEquals("396", s3.finalResult().output());
            assertFalse(session.hasNext());
        }
    }

    @Test
    void steppingPastTheEndIsLoud(@TempDir Path tmp) {
        Path journalDir = recordCalcRun(tmp.resolve("journals"));
        try (ReplaySession session = ReplaySession.open(journalDir)) {
            while (session.hasNext()) session.step();
            assertThrows(ReplayException.class, session::step);
        }
    }

    @Test
    void openingANonJournalFailsLoudly(@TempDir Path tmp) {
        assertThrows(ReplayException.class, () -> ReplaySession.open(tmp));
    }

    // ------------------------------------------------------------------
    // Forking
    // ------------------------------------------------------------------

    @Test
    void forkProducesDivergentBranchAndLeavesOriginalUntouched(@TempDir Path tmp) throws Exception {
        Path journalDir = recordCalcRun(tmp.resolve("journals"));
        byte[] before = Files.readAllBytes(journalDir.resolve("journal.jsonl"));

        BranchResult branch;
        BranchDiff diff;
        try (ReplaySession session = ReplaySession.open(journalDir, calcForkConfig())) {
            ForkedBranch fork = session.forkAt(1,
                turn("Multiplying differently.", "f1", "t_multiply", Map.of("x", 17, "y", 24)),
                turn("Adding.", "f2", "t_add", Map.of("x", 408, "y", 5)),
                finalTurn("413"));
            assertEquals(1, fork.forkTurn());
            assertFalse(fork.isDone());
            branch = fork.runToEnd();
            assertTrue(fork.isDone());
            diff = BranchDiff.between(session.recordedRun(), branch.recordedRun());
            session.verifyUnmodified();
        }
        byte[] after = Files.readAllBytes(journalDir.resolve("journal.jsonl"));
        assertArrayEquals(before, after, "replay + fork must never modify the original journal");

        assertEquals("413", branch.result().output());
        assertEquals(3, branch.recordedRun().turnCount());
        assertTrue(Files.isRegularFile(branch.journalDir().resolve("journal.jsonl")));
        assertFalse(branch.journalDir().equals(journalDir));

        assertFalse(diff.identical());
        assertEquals(1, diff.divergenceTurn());
        String summary = diff.summary();
        assertTrue(summary.contains("divergence at turn 1"));
        assertTrue(summary.contains("\"413\""));
    }

    @Test
    void forkAtTurnTwoDivergesAtTurnTwo(@TempDir Path tmp) {
        Path journalDir = recordCalcRun(tmp.resolve("journals"));
        try (ReplaySession session = ReplaySession.open(journalDir, calcForkConfig())) {
            ForkedBranch fork = session.forkAt(2,
                turn("Adding differently.", "f2", "t_add", Map.of("x", 391, "y", 6)),
                finalTurn("397"));
            BranchResult branch = fork.runToEnd();
            BranchDiff diff = BranchDiff.between(session.recordedRun(), branch.recordedRun());
            assertEquals(2, diff.divergenceTurn());
            assertEquals("397", branch.result().output());
        }
    }

    @Test
    void forkWithIdenticalResponsesIsIdentical(@TempDir Path tmp) {
        Path journalDir = recordCalcRun(tmp.resolve("journals"));
        try (ReplaySession session = ReplaySession.open(journalDir, calcForkConfig())) {
            ForkedBranch fork = session.forkAt(1,
                turn("Multiplying.", "c1", "t_multiply", Map.of("x", 17, "y", 23)),
                turn("Adding.", "c2", "t_add", Map.of("x", 391, "y", 5)),
                finalTurn("396"));
            BranchResult branch = fork.runToEnd();
            BranchDiff diff = BranchDiff.between(session.recordedRun(), branch.recordedRun());
            assertTrue(diff.identical());
            assertEquals(-1, diff.divergenceTurn());
        }
    }

    @Test
    void forkWithoutConfigIsLoud(@TempDir Path tmp) {
        Path journalDir = recordCalcRun(tmp.resolve("journals"));
        try (ReplaySession session = ReplaySession.open(journalDir)) {
            assertThrows(ReplayException.class,
                () -> session.forkAt(1, finalTurn("x")));
        }
    }

    @Test
    void forkAtOutOfRangeTurnIsLoud(@TempDir Path tmp) {
        Path journalDir = recordCalcRun(tmp.resolve("journals"));
        try (ReplaySession session = ReplaySession.open(journalDir, calcForkConfig())) {
            assertThrows(ReplayException.class, () -> session.forkAt(0, finalTurn("x")));
            assertThrows(ReplayException.class, () -> session.forkAt(99, finalTurn("x")));
        }
    }

    @Test
    void exhaustedScriptIsLoudNotSilent(@TempDir Path tmp) {
        Path journalDir = recordCalcRun(tmp.resolve("journals"));
        try (ReplaySession session = ReplaySession.open(journalDir, calcForkConfig())) {
            // Alternative issues a tool call but no follow-up is scripted:
            // the branch needs another model turn that does not exist.
            ForkedBranch fork = session.forkAt(1,
                turn("Multiplying.", "f1", "t_multiply", Map.of("x", 17, "y", 23)));
            ReplayException e = assertThrows(ReplayException.class, fork::runToEnd);
            assertTrue(e.getMessage().contains("exhausted"));
        }
    }

    @Test
    void runningTheSameBranchTwiceIsLoud(@TempDir Path tmp) {
        Path journalDir = recordCalcRun(tmp.resolve("journals"));
        try (ReplaySession session = ReplaySession.open(journalDir, calcForkConfig())) {
            ForkedBranch fork = session.forkAt(1, finalTurn("done"));
            fork.runToEnd();
            assertThrows(ReplayException.class, fork::runToEnd);
        }
    }

    // ------------------------------------------------------------------
    // Capability interplay
    // ------------------------------------------------------------------

    @Test
    void replayOfGuardrailBlockedRunReproducesTheBlock(@TempDir Path tmp) {
        ToolRegistry registry = new ToolRegistry().register(new OpsTools());
        Path journalRoot = tmp.resolve("journals");
        AgentConfig config = AgentConfig.builder()
            .withClient(new ScriptedLlm(List.of(
                turn("Deleting without backup.", "c1", "t_deleteOldSnapshots", Map.of()))))
            .withRegistry(registry)
            .withGuardrails(CapabilityGuardrail.forRegistry(registry))
            .withJournalRoot(journalRoot)
            .build();
        ReActAgent agent = new ReActAgent(config);
        assertThrows(GuardrailViolationException.class, () -> agent.run("clean up"));
        Path journalDir = agent.journal().dir();
        agent.journal().close();

        try (ReplaySession session = ReplaySession.open(journalDir, config)) {
            RecordedRun recorded = session.recordedRun();
            assertFalse(recorded.completed());
            assertEquals(1, recorded.guardrailBlocks().size());
            RecordedRun.BlockedCall block = recorded.guardrailBlocks().get(0);
            assertEquals("capability-policy", block.guardrailName());
            assertEquals("tool", block.side());

            // Stepping surfaces the journaled block without re-executing anything.
            ReplayState s = session.step();
            assertEquals(1, s.guardrailBlocks().size());
            assertTrue(s.done());

            // And the block is reproducible from policy: a fresh guardrail with
            // no tokens still refuses the call.
            CapabilityGuardrail fresh = CapabilityGuardrail.forRegistry(registry);
            assertTrue(fresh.checkToolCall("t_deleteOldSnapshots", Map.of())
                instanceof Verdict.Block);
        }
    }

    @Test
    void forkInheritsCapabilityTokensFromThePrefix(@TempDir Path tmp) {
        ToolRegistry registry = new ToolRegistry().register(new OpsTools());
        Path journalRoot = tmp.resolve("journals");
        AgentConfig config = AgentConfig.builder()
            .withClient(new ScriptedLlm(List.of(
                turn("Backing up.", "c1", "t_backupDatabase", Map.of()),
                finalTurn("backed up"))))
            .withRegistry(registry)
            .withGuardrails(CapabilityGuardrail.forRegistry(registry))
            .withJournalRoot(journalRoot)
            .build();
        ReActAgent agent = new ReActAgent(config);
        agent.run("back up then report");
        Path journalDir = agent.journal().dir();
        agent.journal().close();

        try (ReplaySession session = ReplaySession.open(journalDir, config)) {
            // At the fork point (turn 2) the original run holds BACKUP.
            session.step();
            assertTrue(session.currentState().capabilityTokens().contains(Capability.BACKUP));

            // The fork replaces turn 2 with the destructive call: it must be
            // allowed, because the prefix earned BACKUP.
            ForkedBranch fork = session.forkAt(2,
                turn("Deleting now.", "f1", "t_deleteOldSnapshots", Map.of()),
                finalTurn("deleted it"));
            BranchResult branch = fork.runToEnd();
            assertEquals("deleted it", branch.result().output());
            assertTrue(branch.recordedRun().finalCapabilityTokens()
                .contains(Capability.BACKUP));
            // The branch's trajectory combines the replayed prefix with its own
            // turns, and capability tokens are re-tracked across the boundary:
            // the prefix's BACKUP is visible in the branch's token set.
            assertEquals(List.of("t_backupDatabase()", "t_deleteOldSnapshots()"),
                branch.recordedRun().toolSequence());
        }
    }
}

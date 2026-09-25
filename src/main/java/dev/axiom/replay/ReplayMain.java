package dev.axiom.replay;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentResult;
import dev.axiom.agent.ReActAgent;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolParam;
import dev.axiom.tools.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Self-contained demo of deterministic replay and fork debugging.
 *
 * <p>Run with {@code java -cp <axiom jar + deps> dev.axiom.replay.ReplayMain}.
 * It records a 3-turn calculator run (scripted model, real tools, journaled),
 * steps through the recorded trajectory turn by turn, then forks at turn 1
 * with an alternative model response ("what if it had multiplied 17*24?"),
 * runs the branch to end, and prints the diff.
 *
 * <p>Everything is fixture-driven: no network, no API keys, deterministic.
 */
public final class ReplayMain {

    public static class CalcTools {
        @Tool(name = "replay_multiply", description = "Multiply two integers")
        public int multiply(@ToolParam(description = "first factor") int x,
                            @ToolParam(description = "second factor") int y) {
            return x * y;
        }

        @Tool(name = "replay_add", description = "Add two integers")
        public int add(@ToolParam(description = "first addend") int x,
                       @ToolParam(description = "second addend") int y) {
            return x + y;
        }
    }

    private static ChatResponse turn(String content, String callId, String tool,
                                     Map<String, Object> args) {
        return new ChatResponse(content,
            callId == null ? List.of() : List.of(new ToolCallRequest(callId, tool, args)),
            new ChatResponse.TokenUsage(50, 10, 60));
    }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("axiom-replay-demo");
        Path journalRoot = work.resolve("journals");

        ToolRegistry registry = new ToolRegistry().register(new CalcTools());
        AgentConfig config = AgentConfig.builder()
            .withClient(new ScriptedLlm(List.of(
                turn("I'll multiply 17 by 23.", "c1", "replay_multiply", Map.of("x", 17, "y", 23)),
                turn("Now add 5.", "c2", "replay_add", Map.of("x", 391, "y", 5)),
                turn("396", null, null, null))))
            .withRegistry(registry)
            .withSystemPrompt("You are a calculator agent. Reply with just the number.")
            .withJournalRoot(journalRoot)
            .build();

        ReActAgent agent = new ReActAgent(config);
        AgentResult original = agent.run("What is 17 * 23 + 5? Reply with just the number.");
        Path journalDir = agent.journal().dir();
        agent.journal().close();
        System.out.println("recorded run -> \"" + original.output() + "\" in "
            + original.iterations() + " turns; journal: " + journalDir);

        try (ReplaySession session = ReplaySession.open(journalDir, config)) {
            System.out.println("\n-- stepping through "
                + session.recordedRun().turnCount() + " recorded turns (no LLM, no tools) --");
            while (session.hasNext()) {
                ReplayState s = session.step();
                System.out.println("turn " + s.currentTurn() + "/" + s.totalTurns()
                    + " model=" + quote(s.lastModelText())
                    + " tools=" + s.toolSequence()
                    + " tokens=" + s.tokenUsage().totalTokens());
            }

            System.out.println("\n-- fork at turn 1: what if it had multiplied 17 * 24? --");
            ForkedBranch branch = session.forkAt(1,
                turn("I'll multiply 17 by 24.", "f1", "replay_multiply", Map.of("x", 17, "y", 24)),
                turn("Now add 5.", "f2", "replay_add", Map.of("x", 408, "y", 5)),
                turn("413", null, null, null));
            BranchResult br = branch.runToEnd();
            System.out.println("\n" + BranchDiff.between(
                session.recordedRun(), br.recordedRun()).summary());
            System.out.println("\nfork journal: " + br.journalDir());
        }
        System.out.println("\noriginal journal verified byte-identical after the session.");
        System.out.println("(workspace kept at " + work + " for inspection)");
    }

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s.replace('\n', ' ') + "\"";
    }
}

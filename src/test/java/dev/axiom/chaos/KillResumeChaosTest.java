package dev.axiom.chaos;

import dev.axiom.agent.AgentConfig;
import dev.axiom.durable.AgentRun;
import dev.axiom.durable.DurableException;
import dev.axiom.durable.RunJournal;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-crash battle tests: the agent runs in a <b>separate OS process</b>
 * and the test destroys it with {@code destroyForcibly()} — a genuine
 * kill -9 — at the worst moment, then resumes from the journal in-process.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>killed <b>inside the tool body</b>, after {@code tool_call_started}
 *       was journaled but before completion (the crash window), with a
 *       non-idempotent tool → resume must refuse loudly, never double-execute;</li>
 *   <li>same kill with an {@code idempotent=true} tool → resume re-executes
 *       safely and completes;</li>
 *   <li>killed <b>mid-LLM-call</b> (after a completed tool call) → resume
 *       replays the completed call exactly once and finishes.</li>
 * </ul>
 */
class KillResumeChaosTest {

    /** In-process scripted LLM for the resume side. */
    static final class ScriptLlm implements LlmClient {
        private final Deque<ChatResponse> script = new ArrayDeque<>();

        ScriptLlm(ChatResponse... responses) {
            script.addAll(List.of(responses));
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 LlmOptions options) {
            ChatResponse r = script.poll();
            if (r == null) throw new AssertionError("resume script exhausted");
            return r;
        }

        @Override
        public String model() {
            return "chaos-resume";
        }
    }

    private static ChatResponse finalAnswer(String text) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(10, 20, 30));
    }

    private static ToolDefinition resumeTool(AtomicInteger counter, boolean idempotent) {
        return ToolDefinition.of("crashtool", "tool", Map.of("type", "object",
                "properties", Map.of(), "additionalProperties", false),
            false, 30,
            args -> {
                counter.incrementAndGet();
                return "tool-ok";
            },
            idempotent);
    }

    private AgentConfig resumeConfig(Path root, boolean idempotent, AtomicInteger counter,
                                     ChatResponse... script) {
        return AgentConfig.builder()
            .withClient(new ScriptLlm(script))
            .withToolDefinitions(resumeTool(counter, idempotent))
            .withJournalRoot(root)
            .withMaxIterations(8)
            .build();
    }

    /**
     * Launch the crash worker, wait until it signals it is inside the kill
     * window, then destroy it. Returns the run id it left behind.
     */
    private String killWorkerInWindow(Path root, Path marker, String mode,
                                      boolean idempotent) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin"
            + File.separator + "java";
        Path repo = Path.of("").toAbsolutePath();
        String cp = String.join(File.pathSeparator,
            repo.resolve("target/classes").toString(),
            repo.resolve("target/test-classes").toString(),
            repo.resolve("lib/*").toString());
        Path workerOut = root.resolve("worker-" + mode + ".log");
        Process p = new ProcessBuilder(javaBin, "-cp", cp,
            "dev.axiom.chaos.CrashWorker",
            root.toString(), String.valueOf(idempotent), mode, marker.toString())
            .redirectOutput(workerOut.toFile())
            .redirectErrorStream(true)
            .start();
        try {
            // Wait for the worker to signal it is inside the kill window.
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                if (p.waitFor(100, TimeUnit.MILLISECONDS)) {
                    fail("crash worker exited on its own (log: " + workerOut + "); "
                        + "it should only die by destroyForcibly()");
                }
                if (Files.isRegularFile(marker)) break;
            }
            assertTrue(Files.isRegularFile(marker),
                "worker never reached the kill window within 30s (log: " + workerOut + ")");
            if ("in-tool".equals(mode)) {
                // The marker is written by the tool body, which runs strictly
                // after tool_call_started was journaled+flushed on the same
                // thread — but give the flush a beat on loaded CI machines.
                Thread.sleep(500);
            }
            p.destroyForcibly();
            assertTrue(p.waitFor(15, TimeUnit.SECONDS), "worker did not die after destroyForcibly()");
        } finally {
            p.destroyForcibly();
        }
        List<String> runs = AgentRun.listRuns(root);
        assertEquals(1, runs.size(), "exactly one journal must survive the kill");
        return runs.get(0);
    }

    private static List<String> journalLines(Path root, String runId) throws Exception {
        return Files.readAllLines(root.resolve(runId).resolve("journal.jsonl"),
            StandardCharsets.UTF_8);
    }

    @Test
    @Timeout(120)
    void killInsideToolBodyWithNonIdempotentToolRefusesResume(@TempDir Path root) throws Exception {
        Path marker = root.resolve("marker");
        String runId = killWorkerInWindow(root, marker, "in-tool", false);

        List<String> lines = journalLines(root, runId);
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"tool_call_started\"")),
            "the started ledger record must have landed before the kill");
        assertFalse(lines.stream().anyMatch(l -> l.contains("\"tool_call_completed\"")),
            "no completion record may exist — the kill landed in the crash window");

        // Resume must abort loudly, never silently double-execute the tool.
        var counter = new AtomicInteger();
        DurableException boom = assertThrows(DurableException.class,
            () -> AgentRun.resumeFrom(root, runId,
                resumeConfig(root, false, counter, finalAnswer("resumed"))));
        assertTrue(boom.getMessage().contains("crashtool"),
            "refusal must name the tool, got: " + boom.getMessage());
        assertEquals(0, counter.get(),
            "the non-idempotent tool body must not execute again on resume");
    }

    @Test
    @Timeout(120)
    void killInsideToolBodyWithIdempotentToolResumesCleanly(@TempDir Path root) throws Exception {
        Path marker = root.resolve("marker");
        String runId = killWorkerInWindow(root, marker, "in-tool", true);

        var counter = new AtomicInteger();
        AgentRun resumed = AgentRun.resumeFrom(root, runId,
            resumeConfig(root, true, counter, finalAnswer("resumed-ok")));
        assertTrue(resumed.completed());
        assertEquals("resumed-ok", resumed.result().output());
        assertEquals(1, counter.get(),
            "the idempotent tool is re-executed exactly once on resume");

        // The ledger is whole again: exactly one completion per idempotency
        // key — the exactly-once result record. The killed attempt's
        // tool_call_started record remains as the faithful history of the
        // crash (the call genuinely started twice: once pre-kill, once on
        // resume), so started >= completed for a resumed crash-window call.
        List<String> lines = journalLines(root, runId);
        String key = runId + "#c1";
        assertEquals(1, lines.stream().filter(l ->
            l.contains("\"tool_call_completed\"") && l.contains(key)).count(),
            "exactly one completion record per key — the result is recorded once");
        assertTrue(lines.stream().filter(l ->
            l.contains("\"tool_call_started\"") && l.contains(key)).count() >= 1,
            "the started ledger records must survive");
    }

    @Test
    @Timeout(120)
    void killMidLlmCallReplaysCompletedToolExactlyOnce(@TempDir Path root) throws Exception {
        Path marker = root.resolve("marker");
        String runId = killWorkerInWindow(root, marker, "in-llm", false);

        // c1 completed before the kill (ledger whole); the second LLM call
        // never returned, so the run must continue from there.
        List<String> before = journalLines(root, runId);
        String key1 = runId + "#c1";
        assertEquals(1, before.stream().filter(l ->
            l.contains("\"tool_call_completed\"") && l.contains(key1)).count());

        var counter = new AtomicInteger();
        AgentRun resumed = AgentRun.resumeFrom(root, runId,
            resumeConfig(root, false, counter,
                new ChatResponse("second tool", List.of(new ToolCallRequest("c2", "crashtool", Map.of())),
                    new ChatResponse.TokenUsage(10, 5, 15)),
                finalAnswer("all-done")));
        assertTrue(resumed.completed());
        assertEquals("all-done", resumed.result().output());

        // Exactly-once across the real kill: c1's body ran once in the dead
        // worker and is replayed (not re-executed) here; c2 runs once now.
        assertEquals(1, counter.get(), "only the new c2 call executes on resume; c1 replays");
        List<String> after = journalLines(root, runId);
        assertEquals(1, after.stream().filter(l ->
            l.contains("\"tool_call_completed\"") && l.contains(key1)).count(),
            "c1 must have exactly one completion record across kill + resume");
        String key2 = runId + "#c2";
        assertEquals(1, after.stream().filter(l ->
            l.contains("\"tool_call_completed\"") && l.contains(key2)).count());
    }
}

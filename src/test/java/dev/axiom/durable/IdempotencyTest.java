package dev.axiom.durable;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentResult;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exactly-once side-effect semantics for durable resume.
 *
 * <p>The crash window — a tool body executed but the process died before its
 * {@code tool_call_completed} record was journaled — is simulated faithfully:
 * a real run executes the tool once, then the test rewrites the journal to
 * delete the completion records (exactly what a crash at that point would
 * leave behind), and resume is attempted.
 */
class IdempotencyTest {

    /** Scripted LLM for the idempotency tests. */
    static class FakeLlm implements LlmClient {
        private final Deque<ChatResponse> script = new ArrayDeque<>();
        private int crashAt = -1;
        private RuntimeException crashError;
        private int calls = 0;

        FakeLlm enqueue(ChatResponse r) { script.add(r); return this; }

        /** Throw the given error on the n-th chat call (simulates a crash). */
        FakeLlm crashOnCall(int n, RuntimeException e) {
            crashAt = n;
            crashError = e;
            return this;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 LlmOptions options) {
            if (++calls == crashAt) throw crashError;
            if (script.isEmpty()) throw new AssertionError("FakeLlm ran out of scripted responses");
            return script.poll();
        }

        @Override
        public String model() { return "fake"; }
    }

    private static ChatResponse toolCall(String id, String name, Map<String, Object> args) {
        return new ChatResponse("I'll use a tool.", List.of(new ToolCallRequest(id, name, args)),
            new ChatResponse.TokenUsage(10, 5, 15));
    }

    private static ChatResponse finalAnswer(String text) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(10, 20, 30));
    }

    /** A side-effecting tool: every body execution bumps the counter. */
    private static ToolDefinition chargeTool(AtomicInteger counter, boolean idempotent) {
        Map<String, Object> schema = Map.of(
            "type", "object",
            "properties", Map.of(),
            "additionalProperties", false);
        return ToolDefinition.of("charge", "Charge the card", schema,
            false, 30,
            args -> {
                counter.incrementAndGet();
                return "charged";
            },
            idempotent);
    }

    private static AgentConfig config(FakeLlm llm, Path root, ToolDefinition... defs) {
        return AgentConfig.builder()
            .withClient(llm)
            .withToolDefinitions(defs)
            .withJournalRoot(root)
            .build();
    }

    /**
     * Delete the completion side of the side-effect ledger for every tool
     * call — the exact journal state of a crash between the tool body
     * returning and its {@code tool_call_completed} record landing.
     */
    private static void simulateCrashInToolWindow(Path journalFile) throws Exception {
        List<String> kept = Files.readAllLines(journalFile, StandardCharsets.UTF_8).stream()
            .filter(line -> !line.contains("\"tool_call_completed\"")
                && !line.contains("\"type\":\"ToolCallFinished\""))
            .collect(Collectors.toList());
        assertTrue(kept.stream().anyMatch(l -> l.contains("\"tool_call_started\"")),
            "expected a tool_call_started record to survive the simulated crash");
        Files.write(journalFile, kept, StandardCharsets.UTF_8);
    }

    private static List<String> journalLines(Path root, String runId) throws Exception {
        return Files.readAllLines(root.resolve(runId).resolve("journal.jsonl"),
            StandardCharsets.UTF_8);
    }

    @Test
    void completedCallReplaysFromJournalWithoutReexecuting(@TempDir Path root) throws Exception {
        var counter = new AtomicInteger();
        var crashing = new FakeLlm()
            .enqueue(toolCall("c1", "charge", Map.of()))
            .crashOnCall(2, new RuntimeException("simulated crash"));

        assertThrows(RuntimeException.class,
            () -> AgentRun.begin(config(crashing, root, chargeTool(counter, false)), "Charge it"));
        assertEquals(1, counter.get());

        String runId = AgentRun.listRuns(root).get(0);
        List<String> lines = journalLines(root, runId);
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"tool_call_started\"")),
            "expected the started ledger record");
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"tool_call_completed\"")),
            "expected the completed ledger record");

        // Resume: the recorded result is replayed, the body never runs again.
        var healthy = new FakeLlm().enqueue(finalAnswer("All charged."));
        AgentRun resumed = AgentRun.resumeFrom(root, runId,
            config(healthy, root, chargeTool(counter, false)));
        assertTrue(resumed.completed());
        assertEquals("All charged.", resumed.result().output());
        assertEquals(1, counter.get(), "tool body must run exactly once across crash + resume");
    }

    @Test
    void crashWindowWithNonIdempotentToolRefusesResume(@TempDir Path root) throws Exception {
        var counter = new AtomicInteger();
        var crashing = new FakeLlm()
            .enqueue(toolCall("c1", "charge", Map.of()))
            .crashOnCall(2, new RuntimeException("simulated crash"));

        assertThrows(RuntimeException.class,
            () -> AgentRun.begin(config(crashing, root, chargeTool(counter, false)), "Charge it"));
        assertEquals(1, counter.get(), "tool body ran once before the crash");

        String runId = AgentRun.listRuns(root).get(0);
        simulateCrashInToolWindow(root.resolve(runId).resolve("journal.jsonl"));

        // Resume must fail loudly — never silently double-execute the charge.
        var healthy = new FakeLlm().enqueue(finalAnswer("All charged."));
        DurableException boom = assertThrows(DurableException.class,
            () -> AgentRun.resumeFrom(root, runId, config(healthy, root, chargeTool(counter, false))));
        assertTrue(boom.getMessage().contains("charge"),
            "error must name the tool, got: " + boom.getMessage());
        assertTrue(boom.getMessage().contains("c1"),
            "error must name the ambiguous call id, got: " + boom.getMessage());
        assertEquals(1, counter.get(),
            "non-idempotent tool body must not run a second time on resume");
    }

    @Test
    void crashWindowWithIdempotentToolReexecutesSafely(@TempDir Path root) throws Exception {
        var counter = new AtomicInteger();
        var crashing = new FakeLlm()
            .enqueue(toolCall("c1", "charge", Map.of()))
            .crashOnCall(2, new RuntimeException("simulated crash"));

        assertThrows(RuntimeException.class,
            () -> AgentRun.begin(config(crashing, root, chargeTool(counter, false)), "Charge it"));
        assertEquals(1, counter.get());

        String runId = AgentRun.listRuns(root).get(0);
        simulateCrashInToolWindow(root.resolve(runId).resolve("journal.jsonl"));

        // The tool opted into idempotency: resume re-executes it and finishes.
        var healthy = new FakeLlm().enqueue(finalAnswer("All charged."));
        AgentRun resumed = AgentRun.resumeFrom(root, runId,
            config(healthy, root, chargeTool(counter, true)));
        assertTrue(resumed.completed());
        assertEquals("All charged.", resumed.result().output());
        assertEquals(2, counter.get(),
            "idempotent tool is re-executed exactly once on resume");

        // The re-execution completed the ledger: a further resume replays.
        String runId2 = resumed.checkpointId();
        List<String> lines = journalLines(root, runId2);
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"tool_call_completed\"")),
            "re-execution must journal its completion");
    }

    @Test
    void idempotencyKeyIsStableAcrossResume(@TempDir Path root) throws Exception {
        var counter = new AtomicInteger();
        var crashing = new FakeLlm()
            .enqueue(toolCall("c1", "charge", Map.of()))
            .crashOnCall(2, new RuntimeException("simulated crash"));

        assertThrows(RuntimeException.class,
            () -> AgentRun.begin(config(crashing, root, chargeTool(counter, false)), "Charge it"));

        String runId = AgentRun.listRuns(root).get(0);
        String expectedKey = runId + "#c1";
        List<String> lines = journalLines(root, runId);
        assertTrue(lines.stream().anyMatch(
                l -> l.contains("\"tool_call_started\"") && l.contains(expectedKey)),
            "started record must carry the stable key " + expectedKey);
        assertTrue(lines.stream().anyMatch(
                l -> l.contains("\"tool_call_completed\"") && l.contains(expectedKey)),
            "completed record must carry the same stable key " + expectedKey);
    }
}

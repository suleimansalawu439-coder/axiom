package dev.axiom.durable;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentResult;
import dev.axiom.agent.ApprovalHandler;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Crash-recovery tests: a run is killed mid-flight (simulated by throwing),
 * then resumed from its journal. Completed tool calls must be replayed from
 * their recorded results — never re-executed.
 */
class DurableRunTest {

    /** Scripted LLM for the durable tests. */
    static class FakeLlm implements LlmClient {
        private final Deque<ChatResponse> script = new ArrayDeque<>();
        private int crashAt = -1;
        private RuntimeException crashError;

        FakeLlm enqueue(ChatResponse r) { script.add(r); return this; }

        /** Throw the given error on the n-th chat call (simulates a crash). */
        FakeLlm crashOnCall(int n, RuntimeException e) {
            crashAt = n;
            crashError = e;
            return this;
        }

        private int calls = 0;

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

    static class CalcTools {
        int invocations = 0;

        @Tool(name = "du_multiply", description = "Multiply two numbers")
        public int multiply(@ToolParam(description = "x") int x,
                            @ToolParam(description = "y") int y) {
            invocations++;
            return x * y;
        }
    }

    static class DangerousTools {
        int invocations = 0;

        @Tool(description = "Dangerous op", requiresApproval = true)
        public String dangerous(@ToolParam(description = "x") String x) {
            invocations++;
            return "did:" + x;
        }
    }

    private static ChatResponse toolCall(String id, String name, Map<String, Object> args) {
        return new ChatResponse("I'll use a tool.", List.of(new ToolCallRequest(id, name, args)),
            new ChatResponse.TokenUsage(10, 5, 15));
    }

    private static ChatResponse finalAnswer(String text) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(10, 20, 30));
    }

    @Test
    void beginRequiresJournalRoot() {
        var config = AgentConfig.builder()
            .withClient(new FakeLlm().enqueue(finalAnswer("hi")))
            .build();
        assertThrows(IllegalStateException.class, () -> AgentRun.begin(config, "hi"));
    }

    @Test
    void crashBetweenToolCallAndNextTurnResumesWithoutReexecutingTool(@TempDir Path root) {
        var tools = new CalcTools();
        var crashing = new FakeLlm()
            .enqueue(toolCall("c1", "du_multiply", Map.of("x", 6, "y", 7)))
            // crash on the 2nd LLM call: the tool already ran and was journaled
            .crashOnCall(2, new RuntimeException("simulated crash"));

        var config = AgentConfig.builder()
            .withClient(crashing)
            .withTools(tools)
            .withJournalRoot(root)
            .build();

        assertThrows(RuntimeException.class, () -> AgentRun.begin(config, "What is 6 times 7?"));
        assertEquals(1, tools.invocations);

        // The crashed run is discoverable for recovery.
        List<String> runs = AgentRun.listRuns(root);
        assertEquals(1, runs.size());
        String checkpointId = runs.get(0);
        assertTrue(Files.isRegularFile(root.resolve(checkpointId).resolve("journal.jsonl")));

        // Resume in a "new process": fresh client, same tools.
        var healthy = new FakeLlm().enqueue(finalAnswer("The answer is 42."));
        var config2 = AgentConfig.builder()
            .withClient(healthy)
            .withTools(tools)
            .withJournalRoot(root)
            .build();

        AgentRun resumed = AgentRun.resumeFrom(root, checkpointId, config2);
        assertTrue(resumed.completed());
        AgentResult result = resumed.result();
        assertEquals("The answer is 42.", result.output());
        assertTrue(result.completed());
        // The tool ran exactly once — resume replayed the recorded result.
        assertEquals(1, tools.invocations);
        assertEquals(1, result.toolCallsMade());
        // Usage spans the crash: turn 1 (10+5) + turn 2 (10+20) = 45 total.
        assertEquals(45, result.tokenUsage().totalTokens());
    }

    @Test
    void unfinishedToolCallIsReexecutedOnResume(@TempDir Path root) {
        var tools = new DangerousTools();
        // The approval handler dies on first use: the tool call was requested
        // (journaled) but never finished, so resume must run it.
        ApprovalHandler dying = new ApprovalHandler() {
            boolean first = true;
            @Override
            public boolean approve(ToolDefinition def, Map<String, Object> args) {
                if (first) {
                    first = false;
                    throw new AssertionError("simulated JVM death");
                }
                return true;
            }
        };
        var crashing = new FakeLlm()
            .enqueue(toolCall("c1", "dangerous", Map.of("x", "y")))
            .enqueue(finalAnswer("Recovered."));

        var config = AgentConfig.builder()
            .withClient(crashing)
            .withTools(tools)
            .withApprovalHandler(dying)
            .withJournalRoot(root)
            .build();

        assertThrows(AssertionError.class, () -> AgentRun.begin(config, "Do the dangerous thing"));
        assertEquals(0, tools.invocations); // never got to run

        String checkpointId = AgentRun.listRuns(root).get(0);
        var healthy = new FakeLlm().enqueue(finalAnswer("Recovered."));
        var config2 = AgentConfig.builder()
            .withClient(healthy)
            .withTools(tools)
            .withApprovalHandler(ApprovalHandler.allowAll())
            .withJournalRoot(root)
            .build();

        AgentRun resumed = AgentRun.resumeFrom(root, checkpointId, config2);
        assertEquals("Recovered.", resumed.result().output());
        // The unfinished call ran exactly once, on resume.
        assertEquals(1, tools.invocations);
    }

    @Test
    void checkpointAndResumeOfCompletedRunNeverTouchesLlmAgain(@TempDir Path root) {
        var tools = new CalcTools();
        var llm = new FakeLlm().enqueue(finalAnswer("done"));
        var config = AgentConfig.builder()
            .withClient(llm)
            .withTools(tools)
            .withJournalRoot(root)
            .build();

        AgentRun run = AgentRun.begin(config, "Say done");
        assertTrue(run.completed());
        String checkpointId = run.checkpoint();
        assertTrue(Files.isRegularFile(
            root.resolve(checkpointId).resolve("checkpoint.json")));

        // Resume with an LLM that explodes if called: the result must come
        // straight from the journal.
        var explosive = new FakeLlm(); // empty script -> AssertionError if called
        var config2 = AgentConfig.builder()
            .withClient(explosive)
            .withTools(tools)
            .withJournalRoot(root)
            .build();
        AgentRun resumed = AgentRun.resumeFrom(root, checkpointId, config2);
        assertEquals("done", resumed.result().output());
    }

    @Test
    void journalRoundTripsAllEventTypes(@TempDir Path root) {
        var tools = new CalcTools();
        var llm = new FakeLlm()
            .enqueue(toolCall("c1", "du_multiply", Map.of("x", 2, "y", 3)))
            .enqueue(finalAnswer("6"));
        var config = AgentConfig.builder()
            .withClient(llm)
            .withTools(tools)
            .withJournalRoot(root)
            .build();

        AgentRun run = AgentRun.begin(config, "Multiply 2 and 3");
        String id = run.checkpointId();
        run.close();

        try (RunJournal journal = RunJournal.open(root, id)) {
            List<RunJournal.Record> records = journal.readAll();
            assertTrue(records.get(0) instanceof RunJournal.RunStarted);
            long events = records.stream().filter(r -> r instanceof RunJournal.Event).count();
            assertTrue(events >= 6, "expected journaled events, got " + events);
            // Config snapshot survived the round trip.
            Map<String, Object> snap = journal.configSnapshot();
            assertEquals("fake", snap.get("model"));
            assertTrue(snap.get("toolHolders").toString().contains("CalcTools"));
        }
    }
}

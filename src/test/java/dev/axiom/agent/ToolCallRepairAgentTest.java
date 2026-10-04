package dev.axiom.agent;

import dev.axiom.durable.RunJournal;
import dev.axiom.llm.*;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end: blank tool-call names are repaired by signature matching
 * before dispatch and before history is recorded; unrecoverable blank
 * names are dropped and the agent continues instead of committing the
 * turn's placeholder narration as its answer.
 */
class ToolCallRepairAgentTest {

    static class ScriptLlm implements LlmClient {
        private final Deque<ChatResponse> script = new ArrayDeque<>();
        final List<List<ChatMessage>> seenMessages = new ArrayList<>();
        int calls;

        ScriptLlm enqueue(ChatResponse r) { script.add(r); return this; }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<dev.axiom.tools.ToolDefinition> tools,
                                 LlmOptions options) {
            calls++;
            seenMessages.add(List.copyOf(messages));
            if (script.isEmpty()) throw new AssertionError("ScriptLlm ran out of scripted responses");
            return script.poll();
        }

        @Override
        public String model() { return "script"; }
    }

    static class SearchTools {
        @Tool(description = "search the web")
        public String web_search(@ToolParam(description = "query") String query) {
            return "results for " + query;
        }
    }

    private static final ChatResponse.TokenUsage U = new ChatResponse.TokenUsage(10, 5, 15);

    private static ChatResponse toolCall(String id, String name, Map<String, Object> args) {
        return new ChatResponse("using a tool",
            List.of(new ToolCallRequest(id, name, args)), U);
    }

    private static ChatResponse text(String content) {
        return new ChatResponse(content, List.of(), U);
    }

    private static ReActAgent agent(ScriptLlm llm, List<AgentEvent> events, int maxIterations) {
        var b = AgentConfig.builder()
            .withClient(llm)
            .withTools(new SearchTools())
            .withMaxIterations(maxIterations);
        if (events != null) b.onEvent(events::add);
        return new ReActAgent(b.build());
    }

    @Test
    void blankToolNameIsRepairedAndExecuted() {
        var events = new ArrayList<AgentEvent>();
        var llm = new ScriptLlm()
            .enqueue(toolCall("c1", "", Map.of("query", "capital of France")))
            .enqueue(text("done"));
        var agent = agent(llm, events, 15);

        AgentResult r = agent.run("task");

        assertEquals("done", r.output());
        // The repair was announced…
        var repaired = events.stream()
            .filter(e -> e instanceof AgentEvent.ToolCallRepaired)
            .map(e -> (AgentEvent.ToolCallRepaired) e)
            .toList();
        assertEquals(1, repaired.size());
        assertEquals("c1", repaired.get(0).callId());
        assertEquals("web_search", repaired.get(0).recoveredName());
        // …the tool actually ran with the recovered name…
        var finished = events.stream()
            .filter(e -> e instanceof AgentEvent.ToolCallFinished)
            .map(e -> (AgentEvent.ToolCallFinished) e)
            .toList();
        assertEquals(1, finished.size());
        assertEquals("web_search", finished.get(0).call().name());
        assertTrue(finished.get(0).result().contains("results for capital of France"));
        // …and history records the repaired name, so the next request never
        // echoes a blank function name back to the provider.
        List<ChatMessage> secondTurn = llm.seenMessages.get(1);
        var assistantMsg = secondTurn.stream()
            .filter(m -> m.toolCalls() != null && !m.toolCalls().isEmpty())
            .findFirst().orElseThrow();
        assertEquals("web_search", assistantMsg.toolCalls().get(0).name());
    }

    @Test
    void unrecoverableBlankNameIsDroppedNotDispatched() {
        // Fix 7: an unrecoverable blank name must never reach dispatch or
        // history — the provider rejects blank names in requests with 400.
        // The turn's narration ("using a tool") is placeholder text, not an
        // answer: the agent continues and commits the next valid response.
        var events = new ArrayList<AgentEvent>();
        var llm = new ScriptLlm()
            .enqueue(toolCall("c1", "", Map.of("nonsense", 1)))
            .enqueue(text("done"));
        var agent = agent(llm, events, 15);

        AgentResult r = agent.run("task");

        // The placeholder narration is never committed as the answer.
        assertEquals("done", r.output());
        assertEquals(2, llm.calls);
        // No unknown-tool dispatch for the dropped call.
        assertTrue(events.stream().noneMatch(e ->
            e instanceof AgentEvent.ToolCallFinished f
                && f.result().contains("unknown tool")));
        assertTrue(events.stream().noneMatch(e ->
            e instanceof AgentEvent.ToolCallStarted));
        // The drop is recorded.
        assertTrue(events.stream().anyMatch(e ->
            e instanceof AgentEvent.ToolCallRepaired rep
                && rep.recoveredName().contains("dropped")));
        // History never carries a blank tool-call name to the provider.
        for (List<ChatMessage> seen : llm.seenMessages) {
            for (ChatMessage m : seen) {
                if (m.toolCalls() != null) {
                    for (var tc : m.toolCalls()) {
                        assertFalse(tc.name() == null || tc.name().isBlank(),
                            "blank tool-call name leaked into provider request");
                    }
                }
            }
        }
    }

    @Test
    void droppedPlaceholderCanNeverPassAsAnswer() {
        // Even when the run ends right after the dropped turn, the narration
        // must not be committed: the bounded closing call decides the answer.
        var llm = new ScriptLlm()
            .enqueue(toolCall("c1", "", Map.of("nonsense", 1)))
            .enqueue(text("final"));
        var agent = agent(llm, null, 1);

        AgentResult r = agent.run("task");

        assertEquals("final", r.output(),
            "placeholder narration from a dropped turn must never become the answer");
        assertEquals(2, llm.calls); // dropped turn + closing call
    }

    @Test
    void blankResponseNudgesAndContinuesWithAccounting() {
        // Main loop: a turn with neither tool calls nor text nudges the
        // model instead of committing an empty answer.
        var llm = new ScriptLlm()
            .enqueue(text(""))
            .enqueue(text("answer"));
        var agent = agent(llm, null, 15);

        AgentResult r = agent.run("task");

        assertEquals("answer", r.output());
        assertEquals(2, llm.calls);
        assertEquals(2, r.iterations());
        assertEquals(0, r.toolCallsMade());
        // The nudged turn's usage is accounted, not lost.
        assertEquals(new ChatResponse.TokenUsage(20, 10, 30), r.tokenUsage());
        assertTrue(r.completed());
    }

    @Test
    void repeatedBlankResponsesTerminate() {
        // A model that never produces content must terminate at
        // maxIterations + closing call + one forced retry — never hang.
        var llm = new ScriptLlm()
            .enqueue(text(""))
            .enqueue(text("  "))
            .enqueue(new ChatResponse(null, List.of(), U)) // closing call (blank)
            .enqueue(new ChatResponse(null, List.of(), U)); // forced retry (blank)
        var agent = agent(llm, null, 2);

        AgentResult r = agent.run("task");

        assertEquals("", r.output());
        assertEquals(4, llm.calls); // 2 nudged turns + closing + forced retry: bounded
        assertFalse(r.completed());
    }

    @Test
    void closingCallRecoversAfterBlankTurn() {
        var llm = new ScriptLlm()
            .enqueue(text(""))
            .enqueue(text("recovered"));
        var agent = agent(llm, null, 1);

        AgentResult r = agent.run("task");

        assertEquals("recovered", r.output());
        assertEquals(2, llm.calls);
    }

    @Test
    void runFromStateWithAllDroppedPendingResponseContinues() {
        // Pending-response path: the supplied turn's calls are repaired
        // like any live turn; when all are dropped the narration is not
        // committed — the loop continues for a fresh model turn.
        var llm = new ScriptLlm().enqueue(text("done"));
        var agent = agent(llm, null, 15);

        AgentResult r = agent.runFromState("task",
            List.of(ChatMessage.system("sys"), ChatMessage.user("task")),
            0, ChatResponse.TokenUsage.empty(), 0,
            toolCall("c1", "", Map.of("nonsense", 1)), Map.of());

        assertEquals("done", r.output());
        assertEquals(1, llm.calls); // only the fresh continuation turn
    }

    @Test
    void runFromStateWithBlankPendingResponseContinues() {
        var llm = new ScriptLlm().enqueue(text("done"));
        var agent = agent(llm, null, 15);

        AgentResult r = agent.runFromState("task",
            List.of(ChatMessage.system("sys"), ChatMessage.user("task")),
            0, ChatResponse.TokenUsage.empty(), 0,
            text(""), Map.of());

        assertEquals("done", r.output());
        assertEquals(1, llm.calls);
    }

    @Test
    void resumeDropsBlankNameFromLegacyJournal(@TempDir Path tmp) {
        // Resume path: a legacy journal may carry a blank tool-call name in
        // its last LlmResponse (journaled before the repair existed). Replay
        // sanitizes it without emitting events; the call is never
        // dispatched and the run continues.
        var journal = RunJournal.create(tmp);
        journal.appendRunStarted("task", Map.of("model", "script"));
        journal.appendEvent(new AgentEvent.LlmResponse(Instant.now(), 1,
            toolCall("c1", "", Map.of("nonsense", 1))));

        var events = new ArrayList<AgentEvent>();
        var llm = new ScriptLlm().enqueue(text("done"));
        var agent = agent(llm, events, 15);

        AgentResult r = agent.resume(journal);

        assertEquals("done", r.output());
        // Never dispatched…
        assertTrue(events.stream().noneMatch(e ->
            e instanceof AgentEvent.ToolCallStarted));
        // …and replay itself emits nothing (no duplicate repair events).
        assertTrue(events.stream().noneMatch(e ->
            e instanceof AgentEvent.ToolCallRepaired));
    }
}

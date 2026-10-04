package dev.axiom.agent;

import dev.axiom.llm.*;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolParam;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end: a model stuck repeating the identical tool call is cut off
 * by the stagnation controller well before maxIterations, emits
 * StagnationStopped, and still yields a best-effort answer via the normal
 * out-of-steps closing call.
 */
class StagnationAgentTest {

    static class ScriptLlm implements LlmClient {
        private final Deque<ChatResponse> script = new ArrayDeque<>();
        int calls;
        final java.util.List<String> userMessages = new java.util.ArrayList<>();

        ScriptLlm enqueue(ChatResponse r) { script.add(r); return this; }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<dev.axiom.tools.ToolDefinition> tools,
                                 LlmOptions options) {
            calls++;
            // Record the last user message (the nudge) for assertions.
            for (int i = messages.size() - 1; i >= 0; i--) {
                var m = messages.get(i);
                if (m.role() == dev.axiom.llm.ChatRole.USER) {
                    userMessages.add(m.content());
                    break;
                }
            }
            if (script.isEmpty()) throw new AssertionError("ScriptLlm ran out of scripted responses");
            return script.poll();
        }

        @Override
        public String model() { return "script"; }
    }

    static class SearchTools {
        @Tool(description = "search the web")
        public String stag_search(@ToolParam(description = "query") String query) {
            return "results for " + query;
        }
    }

    private static ChatResponse toolCall(String id, String name, Map<String, Object> args) {
        return new ChatResponse("trying again",
            List.of(new ToolCallRequest(id, name, args)),
            new ChatResponse.TokenUsage(10, 5, 15));
    }

    @Test
    void repeatingModelIsCutOffEarly() {
        var events = new ArrayList<AgentEvent>();
        var llm = new ScriptLlm();
        Map<String, Object> sameArgs = Map.of("query", "same question");
        // The model repeats the identical call; stagnation must fire at the
        // 3rd. Then the closing call (no tools) gets a final answer.
        for (int i = 1; i <= 3; i++) llm.enqueue(toolCall("c" + i, "stag_search", sameArgs));
        llm.enqueue(new ChatResponse("best effort answer", List.of(),
            new ChatResponse.TokenUsage(10, 5, 15)));

        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withTools(new SearchTools())
            .withMaxIterations(15)
            // Explicit limits: the suite default is disabled-by-default, so
            // tests that exercise the controller pin the limits they need.
            .withStagnationLimits(3, 4, 6)
            .onEvent(events::add)
            .build());

        AgentResult r = agent.run("task");

        // Stagnation fired…
        var stopped = events.stream()
            .filter(e -> e instanceof AgentEvent.StagnationStopped)
            .map(e -> (AgentEvent.StagnationStopped) e)
            .toList();
        assertEquals(1, stopped.size());
        assertTrue(stopped.get(0).reason().contains("repeated"));
        // …after 3 tool-call turns plus the closing call, not 15…
        assertEquals(4, llm.calls);
        // …and the run still produced the best-effort answer.
        assertEquals("best effort answer", r.output());
    }

    @Test
    void errorLoopIsCutOffEarly() {
        var events = new ArrayList<AgentEvent>();
        var llm = new ScriptLlm();
        // Unknown tool -> ERROR: unknown tool … four times in a row.
        for (int i = 1; i <= 4; i++) {
            llm.enqueue(toolCall("c" + i, "nope_" + i, Map.of("q", "q" + i)));
        }
        llm.enqueue(new ChatResponse("giving up gracefully", List.of(),
            new ChatResponse.TokenUsage(10, 5, 15)));

        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withTools(new SearchTools())
            .withMaxIterations(15)
            // Explicit limits: the suite default is disabled-by-default, so
            // tests that exercise the controller pin the limits they need.
            .withStagnationLimits(3, 4, 6)
            .onEvent(events::add)
            .build());

        AgentResult r = agent.run("task");

        var stopped = events.stream()
            .filter(e -> e instanceof AgentEvent.StagnationStopped)
            .map(e -> (AgentEvent.StagnationStopped) e)
            .toList();
        assertEquals(1, stopped.size());
        assertTrue(stopped.get(0).reason().contains("consecutive tool errors"));
        assertEquals(5, llm.calls); // 4 error turns + closing call
        assertEquals("giving up gracefully", r.output());
    }

    @Test
    void variedSuccessfulRunIsNotCutOff() {
        var events = new ArrayList<AgentEvent>();
        var llm = new ScriptLlm()
            .enqueue(toolCall("c1", "stag_search", Map.of("query", "one")))
            .enqueue(toolCall("c2", "stag_search", Map.of("query", "two")))
            .enqueue(new ChatResponse("final", List.of(),
                new ChatResponse.TokenUsage(10, 5, 15)));

        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withTools(new SearchTools())
            .withMaxIterations(15)
            .onEvent(events::add)
            .build());

        AgentResult r = agent.run("final task");

        assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.StagnationStopped));
        assertEquals("final", r.output());
        assertEquals(3, llm.calls);
    }

    @Test
    void stagnationIsEnabledByDefault() {
        // The suite default is enabled (3/4/6): a model repeating the same
        // tool call is cut off early instead of burning every iteration.
        // Evidence 2026-10-02: re-enabling the controller took the 5-task
        // DeepSeek-Pro probe from 1/5 (363k tokens) to 3/5 (153k tokens).
        var llm = new ScriptLlm();
        Map<String, Object> sameArgs = Map.of("query", "same");
        for (int i = 1; i <= 4; i++) llm.enqueue(toolCall("c" + i, "stag_search", sameArgs));
        llm.enqueue(new ChatResponse("done", List.of(),
            new ChatResponse.TokenUsage(10, 5, 15)));

        var events = new ArrayList<AgentEvent>();
        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withTools(new SearchTools())
            .withMaxIterations(4)
            .onEvent(events::add)
            .build());

        agent.run("task");
        assertTrue(events.stream().anyMatch(
            e -> e instanceof AgentEvent.StagnationStopped));
        assertTrue(llm.calls < 5); // cut off before all 4 iterations + closing call
    }

    @Test
    void stagnationCanBeDisabled() {
        var llm = new ScriptLlm();
        Map<String, Object> sameArgs = Map.of("query", "same");
        for (int i = 1; i <= 4; i++) llm.enqueue(toolCall("c" + i, "stag_search", sameArgs));
        llm.enqueue(new ChatResponse("done", List.of(),
            new ChatResponse.TokenUsage(10, 5, 15)));

        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withTools(new SearchTools())
            .withMaxIterations(4)
            .withStagnationLimits(0, 0, 0)
            .build());

        agent.run("task");
        // All 4 iterations ran (no early cutoff), then the closing call.
        assertEquals(5, llm.calls);
    }

    @Test
    void forcedAnswerNudgeAfterThreeBlanks() {
        var llm = new ScriptLlm();
        // Three consecutive blank responses, then a real answer.
        for (int i = 0; i < 3; i++) {
            llm.enqueue(new ChatResponse("", List.of(),
                new ChatResponse.TokenUsage(5, 0, 5)));
        }
        llm.enqueue(new ChatResponse("forced answer", List.of(),
            new ChatResponse.TokenUsage(5, 5, 10)));

        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withTools(new SearchTools())
            .withMaxIterations(10)
            .withStagnationLimits(10, 10, 10) // don't let stagnation interfere
            .build());

        var result = agent.run("task");
        // The third nudge (index 3: task=0, nudge1=1, nudge2=2, nudge3=3)
        // must be the FORCED variant.
        String thirdNudge = llm.userMessages.get(3);
        assertTrue(thirdNudge.contains("MUST now provide"),
            "expected forced nudge, got: " + thirdNudge);
        assertEquals("forced answer", result.output());
    }
}

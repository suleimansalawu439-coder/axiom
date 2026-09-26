package dev.axiom.agent;

import dev.axiom.llm.*;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolParam;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Terminal tools: invoking a configured terminal tool ends the run and
 * commits its answer argument as the output.
 */
class TerminalToolTest {

    /** Scripted LLM: returns queued responses in order. */
    static class FakeLlm implements LlmClient {
        private final Deque<ChatResponse> script = new ArrayDeque<>();
        int calls;

        FakeLlm enqueue(ChatResponse r) { script.add(r); return this; }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 LlmOptions options) {
            calls++;
            if (script.isEmpty()) throw new AssertionError("FakeLlm ran out of scripted responses");
            return script.poll();
        }

        @Override
        public String model() { return "fake"; }
    }

    static class AnswerTools {
        @Tool(description = "commit the final answer")
        public String answer(@ToolParam(description = "the answer") String answer) {
            return "Answer recorded: " + answer;
        }

        @Tool(description = "an ordinary tool")
        public String lookup(@ToolParam(description = "q") String q) {
            return "result for " + q;
        }
    }

    private static ChatResponse toolCallResponse(String id, String name, Map<String, Object> args) {
        return new ChatResponse("I'll use a tool.",
            List.of(new ToolCallRequest(id, name, args)),
            new ChatResponse.TokenUsage(10, 5, 15));
    }

    private static ChatResponse finalResponse(String text) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(10, 20, 30));
    }

    private ReActAgent agent(FakeLlm llm, String... terminalTools) {
        return new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withTools(new AnswerTools())
            .withTerminalTools(terminalTools)
            .build());
    }

    @Test
    void terminalToolCallEndsRunWithCommittedAnswer() {
        var llm = new FakeLlm()
            .enqueue(toolCallResponse("call_1", "answer", Map.of("answer", "17")));
        // No second response enqueued: the loop must NOT ask the model again.

        AgentResult result = agent(llm, "answer").run("What is the answer?");

        assertTrue(result.completed());
        assertEquals("17", result.output());
        assertEquals(1, result.iterations());
        assertEquals(1, result.toolCallsMade());
        assertEquals(1, llm.calls);
    }

    @Test
    void nonTerminalToolCallDoesNotEndRun() {
        var llm = new FakeLlm()
            .enqueue(toolCallResponse("call_1", "lookup", Map.of("q", "x")))
            .enqueue(finalResponse("done"));

        AgentResult result = agent(llm, "answer").run("task");

        assertEquals("done", result.output());
        assertEquals(2, result.iterations());
    }

    @Test
    void terminalToolWithoutConfigurationBehavesNormally() {
        var llm = new FakeLlm()
            .enqueue(toolCallResponse("call_1", "answer", Map.of("answer", "17")))
            .enqueue(finalResponse("The answer is 17."));

        AgentResult result = agent(llm /* no terminal tools */).run("task");

        assertEquals("The answer is 17.", result.output());
    }

    @Test
    void committedAnswerPrefersAnswerKeyOverFirstArg() {
        var llm = new FakeLlm()
            .enqueue(toolCallResponse("call_1", "answer",
                Map.of("answer", "right", "other", "wrong")));

        AgentResult result = agent(llm, "answer").run("task");

        assertEquals("right", result.output());
    }

    @Test
    void committedAnswerFallsBackToFirstArgument() {
        var llm = new FakeLlm()
            .enqueue(toolCallResponse("call_1", "answer", Map.of("foo", "bar")));

        AgentResult result = agent(llm, "answer").run("task");

        assertEquals("bar", result.output());
    }

    @Test
    void terminalToolWithNoArgumentsCommitsEmpty() {
        var llm = new FakeLlm()
            .enqueue(toolCallResponse("call_1", "answer", Map.of()));

        AgentResult result = agent(llm, "answer").run("task");

        assertEquals("", result.output());
        assertTrue(result.completed());
    }

    @Test
    void runFinishedEventFiresOnTerminalCommit() {
        var llm = new FakeLlm()
            .enqueue(toolCallResponse("call_1", "answer", Map.of("answer", "17")));
        var events = new java.util.ArrayList<AgentEvent>();
        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withTools(new AnswerTools())
            .withTerminalTools("answer")
            .onEvent(events::add)
            .build());

        agent.run("task");

        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.RunFinished));
        var finished = events.stream()
            .filter(e -> e instanceof AgentEvent.RunFinished)
            .map(e -> (AgentEvent.RunFinished) e)
            .findFirst().orElseThrow();
        assertEquals("17", finished.result().output());
    }
}

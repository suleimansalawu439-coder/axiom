package dev.axiom.agent;

import dev.axiom.llm.*;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolParam;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the full ReAct loop offline with a scripted fake LLM.
 */
class ReActAgentTest {

    /** A scripted LLM: returns queued responses in order. */
    static class FakeLlm implements LlmClient {
        private final Deque<ChatResponse> script = new ArrayDeque<>();

        FakeLlm enqueue(ChatResponse r) { script.add(r); return this; }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools, LlmOptions options) {
            if (script.isEmpty()) throw new AssertionError("FakeLlm ran out of scripted responses");
            return script.poll();
        }

        @Override
        public String model() { return "fake"; }
    }

    static class CalcTools {
        @Tool(description = "Multiply two numbers")
        public int multiply(
                @ToolParam(description = "x") int x,
                @ToolParam(description = "y") int y) {
            return x * y;
        }
    }

    private static ChatResponse toolCallResponse(String id, String name, java.util.Map<String, Object> args) {
        return new ChatResponse("I'll use a tool.",
            List.of(new ToolCallRequest(id, name, args)),
            new ChatResponse.TokenUsage(10, 5, 15));
    }

    private static ChatResponse finalResponse(String text) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(10, 20, 30));
    }

    @Test
    void runsToolThenAnswers() {
        var llm = new FakeLlm()
            .enqueue(toolCallResponse("call_1", "multiply", java.util.Map.of("x", 6, "y", 7)))
            .enqueue(finalResponse("The answer is 42."));

        var events = new java.util.ArrayList<AgentEvent>();
        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withTools(new CalcTools())
            .onEvent(events::add)
            .build());

        AgentResult result = agent.run("What is 6 times 7?");

        assertTrue(result.completed());
        assertEquals("The answer is 42.", result.output());
        assertEquals(1, result.toolCallsMade());
        assertEquals(45, result.tokenUsage().totalTokens());
        // Observability: every step emitted an event.
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolCallStarted));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolCallFinished));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.RunFinished));
    }

    @Test
    void toolErrorIsFedBackNotThrown() {
        var llm = new FakeLlm()
            // LLM calls a tool that doesn't exist -> error becomes an observation.
            .enqueue(toolCallResponse("call_1", "nonexistent", java.util.Map.of()))
            .enqueue(finalResponse("I couldn't do that."));

        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withTools(new CalcTools())
            .build());

        AgentResult result = agent.run("Do something impossible");
        assertTrue(result.completed());
        assertEquals("I couldn't do that.", result.output());
    }

    @Test
    void deniedApprovalIsReportedToLlm() {
        var llm = new FakeLlm()
            .enqueue(toolCallResponse("call_1", "multiply", java.util.Map.of("x", 2, "y", 3)))
            .enqueue(finalResponse("Denied, so I answered directly."));

        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withTools(new CalcTools())
            .withApprovalHandler(ApprovalHandler.denyAll())
            .build());

        AgentResult result = agent.run("Multiply 2 and 3");
        assertTrue(result.completed());
        assertEquals("Denied, so I answered directly.", result.output());
    }

    @Test
    void givesUpGracefullyAfterMaxIterations() {        var llm = new FakeLlm()
            .enqueue(toolCallResponse("c1", "multiply", java.util.Map.of("x", 1, "y", 1)))
            .enqueue(toolCallResponse("c2", "multiply", java.util.Map.of("x", 1, "y", 1)))
            .enqueue(finalResponse("Best effort answer."));

        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withTools(new CalcTools())
            .withMaxIterations(2)
            .build());

        AgentResult result = agent.run("Loop forever");
        assertFalse(result.completed());
        assertEquals("Best effort answer.", result.output());
    }

    // ------------------------------------------------------------------
    // Structured output
    // ------------------------------------------------------------------

    record Sum(int sum) {}

    @Test
    void runForParsesTypedOutput() {
        var llm = new FakeLlm()
            .enqueue(finalResponse("The sum is 42."))
            .enqueue(finalResponse("{\"sum\": 42}"));

        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withTools(new CalcTools())
            .build());

        Sum result = agent.runFor("What is 40 + 2?", Sum.class);
        assertEquals(42, result.sum());
    }

    @Test
    void runForRejectsMalformedJson() {
        var llm = new FakeLlm()
            .enqueue(finalResponse("The sum is 42."))
            .enqueue(finalResponse("not json at all"));

        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withTools(new CalcTools())
            .build());

        assertThrows(dev.axiom.output.StructuredOutputException.class,
            () -> agent.runFor("What is 40 + 2?", Sum.class));
    }

    // ------------------------------------------------------------------
    // Timeouts
    // ------------------------------------------------------------------

    static class SlowTools {
        @Tool(description = "Hangs forever", timeoutSeconds = 1)
        public String hang(@ToolParam(description = "x") String x) throws InterruptedException {
            Thread.sleep(30_000);
            return "never";
        }
    }

    @Test
    void hangingToolTimesOutInsteadOfWedgingLoop() {
        var llm = new FakeLlm()
            .enqueue(toolCallResponse("c1", "hang", java.util.Map.of("x", "y")))
            .enqueue(finalResponse("The tool timed out."));

        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withTools(new SlowTools())
            .build());

        long start = System.currentTimeMillis();
        AgentResult result = agent.run("Hang");
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result.completed());
        assertTrue(elapsed < 15_000, "run took " + elapsed + "ms; timeout did not fire");
    }

    // ------------------------------------------------------------------
    // Memory
    // ------------------------------------------------------------------

    @Test
    void memoryCarriesConversationAcrossRuns() {
        List<List<ChatMessage>> seenByLlm = new ArrayList<>();
        LlmClient spying = new LlmClient() {
            final FakeLlm delegate = new FakeLlm()
                .enqueue(finalResponse("First answer."))
                .enqueue(finalResponse("Second answer."));

            @Override
            public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools, LlmOptions options) {
                seenByLlm.add(List.copyOf(messages));
                return delegate.chat(messages, tools, options);
            }

            @Override
            public String model() { return "spy"; }
        };

        var memory = new dev.axiom.memory.SlidingWindowMemory(20);
        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(spying)
            .withTools(new CalcTools())
            .withMemory(memory)
            .build());

        agent.run("Hello");
        agent.run("And now?");

        // The second run's prompt must contain the first run's exchange.
        List<ChatMessage> secondPrompt = seenByLlm.get(1);
        assertTrue(secondPrompt.stream().anyMatch(m ->
            m.role() == dev.axiom.llm.ChatRole.USER && m.content().equals("Hello")));
        assertTrue(secondPrompt.stream().anyMatch(m ->
            m.role() == dev.axiom.llm.ChatRole.ASSISTANT && m.content().equals("First answer.")));
    }

    @Test
    void stripFormattingCruftHandlesScreenplaySluglines() {
        assertEquals("THE CASTLE",
            ReActAgent.stripFormattingCruft("INT. THE CASTLE - DAY"));
        assertEquals("THE CASTLE",
            ReActAgent.stripFormattingCruft("EXT. THE CASTLE - NIGHT"));
        assertEquals("quoted",
            ReActAgent.stripFormattingCruft("\"quoted\""));
        assertEquals("plain", ReActAgent.stripFormattingCruft("plain"));
    }

    @Test
    void isProseAnswerDetectsThinking() {
        // Third-person self-reference
        assertTrue(ReActAgent.isProseAnswer(
            "When the agent was asked a question, it began searching for"));
        // Incomplete (trailing open quote)
        assertTrue(ReActAgent.isProseAnswer(
            "Merriam-Webster Word of the Day: **\"picaresque"));
        // Normal short answers are NOT prose
        assertFalse(ReActAgent.isProseAnswer("Guatemala"));
        assertFalse(ReActAgent.isProseAnswer("3"));
        assertFalse(ReActAgent.isProseAnswer("THE CASTLE"));
    }
}

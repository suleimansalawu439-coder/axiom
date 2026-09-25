package dev.axiom.chaos;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentEvent;
import dev.axiom.agent.AgentResult;
import dev.axiom.agent.ReActAgent;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tools: the ReAct loop must self-correct or fail gracefully —
 * never hang, never leak an exception, never corrupt agent state — when tools
 * misbehave. The tool error is journaled as the call's completion, so a
 * hostile tool can neither wedge the loop nor poison a later resume.
 */
class AdversarialToolTest {

    static final class ScriptLlm implements LlmClient {
        private final Deque<ChatResponse> script = new ArrayDeque<>();

        ScriptLlm(ChatResponse... responses) {
            script.addAll(List.of(responses));
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 LlmOptions options) {
            ChatResponse r = script.poll();
            if (r == null) throw new AssertionError("script exhausted");
            return r;
        }

        @Override
        public String model() {
            return "chaos-tools";
        }
    }

    private static ChatResponse toolCall(String id, String name, Map<String, Object> args) {
        return new ChatResponse("using tool", List.of(new ToolCallRequest(id, name, args)),
            new ChatResponse.TokenUsage(10, 5, 15));
    }

    private static ChatResponse finalAnswer(String text) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(10, 20, 30));
    }

    /** Annotated holder exercising the real reflective arg-coercion path. */
    static final class StrictTools {
        @Tool(name = "strict_add", description = "Add two ints")
        public int strictAdd(@ToolParam(description = "x") int x,
                             @ToolParam(description = "y") int y) {
            return x + y;
        }
    }

    private static ToolDefinition synthetic(String name, long timeoutSeconds,
                                            dev.axiom.tools.ToolInvoker invoker) {
        return ToolDefinition.of(name, "adversarial tool",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
            false, timeoutSeconds, invoker);
    }

    /** Capture the observation the agent saw for a finished tool call. */
    private static String finishedObservation(List<AgentEvent> events, String callId) {
        return events.stream()
            .filter(e -> e instanceof AgentEvent.ToolCallFinished f && f.call().id().equals(callId))
            .map(e -> ((AgentEvent.ToolCallFinished) e).result())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no ToolCallFinished for " + callId));
    }

    private AgentResult runWith(ToolDefinition def, List<AgentEvent> events, ChatResponse... script) {
        AgentConfig config = AgentConfig.builder()
            .withClient(new ScriptLlm(script))
            .withToolDefinitions(def)
            .onEvent(events::add)
            .withMaxIterations(5)
            .build();
        return new ReActAgent(config).run("adversarial probe");
    }

    @Test
    @Timeout(60)
    void uncheckedExceptionBecomesErrorObservationNotACrash() {
        var events = new ArrayList<AgentEvent>();
        AgentResult r = runWith(
            synthetic("rager", 30, args -> {
                throw new IllegalStateException("boom-chaos-unchecked");
            }),
            events,
            toolCall("c1", "rager", Map.of()),
            finalAnswer("recovered"));
        assertTrue(r.completed(), "the loop must survive a throwing tool");
        String obs = finishedObservation(events, "c1");
        assertTrue(obs.startsWith("ERROR:"), "observation must be an error, got: " + obs);
        assertTrue(obs.contains("boom-chaos-unchecked"),
            "the root cause must reach the model for self-correction, got: " + obs);
        assertEquals("recovered", r.output());
    }

    @Test
    @Timeout(60)
    void javaErrorInToolBodyIsContained() {
        var events = new ArrayList<AgentEvent>();
        AgentResult r = runWith(
            synthetic("errorer", 30, args -> {
                throw new StackOverflowError("chaos-stack");
            }),
            events,
            toolCall("c1", "errorer", Map.of()),
            finalAnswer("recovered"));
        assertTrue(r.completed(), "even an Error must not escape the agent loop");
        String obs = finishedObservation(events, "c1");
        assertTrue(obs.startsWith("ERROR:"), "got: " + obs);
    }

    @Test
    @Timeout(60)
    void hangingToolTripsItsTimeoutInsteadOfWedgingTheLoop() {
        var events = new ArrayList<AgentEvent>();
        long start = System.currentTimeMillis();
        AgentResult r = runWith(
            synthetic("hangs", 2, args -> {
                Thread.sleep(120_000);
                return "never";
            }),
            events,
            toolCall("c1", "hangs", Map.of()),
            finalAnswer("recovered"));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(r.completed());
        String obs = finishedObservation(events, "c1");
        assertTrue(obs.contains("timed out after 2s"), "got: " + obs);
        assertTrue(elapsed < 30_000,
            "the 2s tool timeout must fire promptly; run took " + elapsed + "ms");
        assertEquals("recovered", r.output());
    }

    @Test
    @Timeout(60)
    void multiMegabytePayloadDoesNotCorruptTheRun() {
        var events = new ArrayList<AgentEvent>();
        String huge = "x".repeat(5_000_000);
        AgentResult r = runWith(synthetic("firehose", 30, args -> huge), events,
            toolCall("c1", "firehose", Map.of()),
            finalAnswer("recovered"));
        assertTrue(r.completed());
        String obs = finishedObservation(events, "c1");
        assertEquals(5_000_000, obs.length(), "large payload must pass through intact");
        assertEquals("recovered", r.output());
    }

    @Test
    @Timeout(60)
    void nullAndStructuredReturnsAreStringified() {
        var events = new ArrayList<AgentEvent>();
        AgentResult r = runWith(synthetic("nuller", 30, args -> null), events,
            toolCall("c1", "nuller", Map.of()),
            finalAnswer("recovered"));
        assertTrue(r.completed());
        assertEquals("null", finishedObservation(events, "c1"));

        var events2 = new ArrayList<AgentEvent>();
        AgentResult r2 = runWith(
            synthetic("struct", 30, args -> Map.of("a", 1, "b", List.of("x", "y"))),
            events2,
            toolCall("c1", "struct", Map.of()),
            finalAnswer("recovered"));
        assertTrue(r2.completed());
        String obs2 = finishedObservation(events2, "c1");
        assertTrue(obs2.contains("\"a\":1") && obs2.contains("\"b\":[\"x\",\"y\"]"),
            "structured returns must be JSON-stringified, got: " + obs2);
    }

    @Test
    @Timeout(60)
    void missingRequiredArgFeedsBackAClearError() {
        var events = new ArrayList<AgentEvent>();
        AgentConfig config = AgentConfig.builder()
            .withClient(new ScriptLlm(
                // strict_add needs x and y; the model sends nothing.
                toolCall("c1", "strict_add", Map.of()),
                finalAnswer("recovered")))
            .withTools(new StrictTools())
            .onEvent(events::add)
            .withMaxIterations(5)
            .build();
        AgentResult r = new ReActAgent(config).run("adversarial probe");
        assertTrue(r.completed());
        String obs = finishedObservation(events, "c1");
        assertTrue(obs.startsWith("ERROR:"), "got: " + obs);
        assertTrue(obs.contains("Missing required argument"),
            "the model must get an actionable schema error, got: " + obs);
        assertEquals("recovered", r.output());
    }

    @Test
    @Timeout(60)
    void wrongTypedArgFeedsBackAClearError() {
        var events = new ArrayList<AgentEvent>();
        AgentConfig config = AgentConfig.builder()
            .withClient(new ScriptLlm(
                toolCall("c1", "strict_add", Map.of("x", "not-a-number", "y", 2)),
                finalAnswer("recovered")))
            .withTools(new StrictTools())
            .onEvent(events::add)
            .withMaxIterations(5)
            .build();
        AgentResult r = new ReActAgent(config).run("adversarial probe");
        assertTrue(r.completed());
        String obs = finishedObservation(events, "c1");
        assertTrue(obs.startsWith("ERROR:"), "got: " + obs);
        assertEquals("recovered", r.output());
    }
}

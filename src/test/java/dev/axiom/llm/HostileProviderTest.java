package dev.axiom.llm;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentEvent;
import dev.axiom.agent.AgentResult;
import dev.axiom.agent.ReActAgent;
import dev.axiom.tools.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hostile-provider battle tests against {@link OpenAiCompatibleClient}'s
 * wire parsing: truncated streams, garbage chunks, empty bodies, absurd
 * {@code Retry-After} values, and model-issued calls the framework cannot
 * honor.
 *
 * <p>Contract under test: hostility surfaces as a loud, typed
 * {@link LlmException} (never a hang, never a silently half-built response),
 * and at the agent level an uninvokable call becomes an error observation the
 * model can self-correct from.
 *
 * <p>Parse-level (no sockets): deterministic and immune to sandbox proxy
 * configuration.
 */
class HostileProviderTest {

    /** Client instance used only for its parse methods — never sends HTTP. */
    private final OpenAiCompatibleClient parser =
        new OpenAiCompatibleClient("http://127.0.0.1:9/v1", "", "hostile-probe");

    private static ByteArrayInputStream sse(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @Timeout(30)
    void garbageSseDataLineThrowsLoudly() {
        var seen = new ArrayList<String>();
        Exception boom = assertThrows(Exception.class,
            () -> parser.parseSseStream(
                sse("data: this is not json at all\n\ndata: [DONE]\n"), seen::add),
            "a garbage SSE data line must not be silently skipped");
        assertNotNull(boom.getMessage());
    }

    @Test
    @Timeout(30)
    void truncatedToolCallArgumentsThrowLoudly() {
        // Stream cut mid-arguments: the accumulated JSON is incomplete.
        String cut = "data: {\"choices\":[{\"delta\":{\"tool_calls\":["
            + "{\"index\":0,\"id\":\"c1\",\"function\":"
            + "{\"name\":\"t\",\"arguments\":\"{\\\"x\\\":\"}}]}}]}\n";
        assertThrows(Exception.class,
            () -> parser.parseSseStream(sse(cut), t -> {}),
            "truncated tool-call arguments must fail loudly, not produce a half-built call");
    }

    @Test
    @Timeout(30)
    void cleanStreamWithoutDoneAssembles() throws Exception {
        var seen = new ArrayList<String>();
        ChatResponse r = parser.parseSseStream(
            sse("data: {\"choices\":[{\"delta\":{\"content\":\"he\"}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"llo\"}}]}\n"),
            seen::add);
        assertEquals("hello", r.content());
        assertEquals(List.of("he", "llo"), seen);
    }

    @Test
    @Timeout(30)
    void sseUsageBlockIsPickedUp() throws Exception {
        ChatResponse r = parser.parseSseStream(
            sse("data: {\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,"
                + "\"total_tokens\":10},\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n"
                + "data: [DONE]\n"),
            t -> {});
        assertEquals(10, r.usage().totalTokens());
    }

    @Test
    @Timeout(30)
    void emptyBodyThrowsLoudly() {
        assertThrows(Exception.class, () -> parser.parseResponse(""),
            "an empty 200 body must not parse as an empty response");
    }

    @Test
    @Timeout(30)
    void bodyWithoutChoicesThrowsLoudly() {
        assertThrows(Exception.class,
            () -> parser.parseResponse("{\"unexpected\":\"shape\"}"),
            "a body without choices must not parse silently");
    }

    @Test
    @Timeout(30)
    void absurdRetryAfterIsClampedToTenMinutes() {
        assertEquals(600, OpenAiCompatibleClient.parseRetryAfterSeconds("99999999"),
            "an absurd Retry-After must be clamped, or a hostile provider parks the client");
        assertEquals(600, OpenAiCompatibleClient.parseRetryAfterSeconds("600"),
            "the boundary value passes through");
        assertEquals(30, OpenAiCompatibleClient.parseRetryAfterSeconds("30"));
        assertEquals(LlmException.NO_STATUS, OpenAiCompatibleClient.parseRetryAfterSeconds("garbage"));
        assertEquals(LlmException.NO_STATUS, OpenAiCompatibleClient.parseRetryAfterSeconds(null));
    }

    // ------------------------------------------------------------------
    // Agent level: the model asks for something the framework cannot do.
    // ------------------------------------------------------------------

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
            return "hostile-script";
        }
    }

    private static ChatResponse toolCall(String id, String name, Map<String, Object> args) {
        return new ChatResponse("using tool", List.of(new ToolCallRequest(id, name, args)),
            new ChatResponse.TokenUsage(10, 5, 15));
    }

    private static ChatResponse finalAnswer(String text) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(10, 20, 30));
    }

    private static String observationFor(List<AgentEvent> events, String callId) {
        return events.stream()
            .filter(e -> e instanceof AgentEvent.ToolCallFinished f && f.call().id().equals(callId))
            .map(e -> ((AgentEvent.ToolCallFinished) e).result())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no ToolCallFinished for " + callId));
    }

    @Test
    @Timeout(60)
    void unknownToolNameBecomesRecoverableObservation() {
        var events = new ArrayList<AgentEvent>();
        ToolDefinition real = ToolDefinition.of("real_tool", "real",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
            false, 30, args -> "real-ok");
        AgentConfig config = AgentConfig.builder()
            .withClient(new ScriptLlm(
                toolCall("c1", "hallucinated_tool", Map.of("x", 1)),
                toolCall("c2", "real_tool", Map.of()),
                finalAnswer("recovered")))
            .withToolDefinitions(real)
            .onEvent(events::add)
            .withMaxIterations(6)
            .build();
        AgentResult r = new ReActAgent(config).run("hostile probe");
        assertTrue(r.completed(), "an unknown tool must not kill the run");
        String obs = observationFor(events, "c1");
        assertTrue(obs.contains("unknown tool") && obs.contains("hallucinated_tool"),
            "the model must learn the tool does not exist, got: " + obs);
        assertEquals("recovered", r.output());
        assertEquals(2, r.toolCallsMade());
    }

    @Test
    @Timeout(60)
    void nullArgumentsMapDoesNotBlowUpInvocation() {
        var events = new ArrayList<AgentEvent>();
        ToolDefinition lenient = ToolDefinition.of("lenient", "lenient",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
            false, 30, args -> "args-were-" + (args == null ? "null" : "present"));
        AgentConfig config = AgentConfig.builder()
            .withClient(new ScriptLlm(
                new ChatResponse("using tool",
                    List.of(new ToolCallRequest("c1", "lenient", null)),
                    new ChatResponse.TokenUsage(10, 5, 15)),
                finalAnswer("recovered")))
            .withToolDefinitions(lenient)
            .onEvent(events::add)
            .withMaxIterations(5)
            .build();
        AgentResult r = new ReActAgent(config).run("hostile probe");
        assertTrue(r.completed());
        // Null args must degrade to an error observation, not an NPE crash.
        String obs = observationFor(events, "c1");
        assertTrue(obs.startsWith("ERROR:") || obs.startsWith("args-were-"),
            "null arguments must be handled, got: " + obs);
    }
}

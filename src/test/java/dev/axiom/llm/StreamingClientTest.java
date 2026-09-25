package dev.axiom.llm;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentEvent;
import dev.axiom.agent.AgentResult;
import dev.axiom.agent.ReActAgent;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolParam;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Streaming tests: SSE parsing/assembly in {@link OpenAiCompatibleClient}
 * (against a real local HTTP server) and {@code StreamToken} events in the
 * ReAct loop.
 */
class StreamingClientTest {

    // ------------------------------------------------------------------
    // Fake streaming LLM for the agent loop
    // ------------------------------------------------------------------

    static class FakeStreamingLlm implements StreamingLlmClient {
        private record Turn(ChatResponse response, List<String> tokens) {}
        private final Deque<Turn> script = new ArrayDeque<>();

        FakeStreamingLlm enqueue(ChatResponse r, String... tokens) {
            script.add(new Turn(r, List.of(tokens)));
            return this;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 LlmOptions options) {
            throw new UnsupportedOperationException("streaming fake: use chatStream");
        }

        @Override
        public ChatResponse chatStream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                       LlmOptions options, TokenListener listener) {
            if (script.isEmpty()) throw new AssertionError("FakeStreamingLlm out of script");
            Turn t = script.poll();
            for (String tok : t.tokens()) listener.onToken(tok);
            return t.response();
        }

        @Override
        public String model() { return "fake-stream"; }
    }

    static class CalcTools {
        @Tool(description = "Multiply two numbers")
        public int multiply(@ToolParam(description = "x") int x,
                            @ToolParam(description = "y") int y) {
            return x * y;
        }
    }

    @Test
    void reactLoopEmitsStreamTokensButActsOnCompleteTurn() {
        var llm = new FakeStreamingLlm()
            .enqueue(new ChatResponse("I'll multiply.",
                    List.of(new ToolCallRequest("c1", "multiply", java.util.Map.of("x", 6, "y", 7))),
                    new ChatResponse.TokenUsage(10, 5, 15)),
                "I'll ", "multiply.")
            .enqueue(new ChatResponse("The answer is 42.", List.of(),
                    new ChatResponse.TokenUsage(10, 20, 30)),
                "The ", "answer ", "is 42.");

        var events = new ArrayList<AgentEvent>();
        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withTools(new CalcTools())
            .onEvent(events::add)
            .build());

        AgentResult result = agent.run("What is 6 times 7?");

        assertTrue(result.completed());
        assertEquals("The answer is 42.", result.output());
        // Tokens arrived in order…
        List<String> tokens = events.stream()
            .filter(e -> e instanceof AgentEvent.StreamToken)
            .map(e -> ((AgentEvent.StreamToken) e).token())
            .toList();
        assertEquals(List.of("I'll ", "multiply.", "The ", "answer ", "is 42."), tokens);
        // …but the agent still acted on complete turns only.
        assertEquals(1, result.toolCallsMade());
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.LlmResponse));
    }

    @Test
    void nonStreamingClientsStillWork() {
        // A plain LlmClient must keep working through the same loop.
        LlmClient stub = new LlmClient() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                     LlmOptions options) {
                return new ChatResponse("done", List.of(),
                    new ChatResponse.TokenUsage(1, 1, 2));
            }

            @Override
            public String model() { return "stub"; }
        };
        var agent = new ReActAgent(AgentConfig.builder().withClient(stub).build());
        assertEquals("done", agent.run("hi").output());
    }

    // ------------------------------------------------------------------
    // SSE parsing: pure function, tested without a socket (the sandbox
    // blocks all TCP, even loopback).
    // ------------------------------------------------------------------

    private static String chunk(String deltaJson) {
        return """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"stub",\
            "choices":[{"index":0,"delta":%s,"finish_reason":null}]}""".formatted(deltaJson);
    }

    @Test
    void parsesSseStreamAssemblingTokensAndToolCalls() throws Exception {
        String sse = String.join("",
            "data: " + chunk("{\"role\":\"assistant\",\"content\":\"Hello\"}") + "\n\n",
            "data: " + chunk("{\"content\":\" world\"}") + "\n\n",
            // tool call arguments arrive fragmented across chunks
            "data: " + chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                + "\"function\":{\"name\":\"multiply\",\"arguments\":\"{\\\"x\\\":\"}}]}") + "\n\n",
            "data: " + chunk("{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"6,\\\"y\\\":7}\"}}]}") + "\n\n",
            // usage-only final chunk (stream_options include_usage)
            "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"stub\",\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":10,\"total_tokens\":60}}\n\n",
            "data: [DONE]\n\n");

        var client = new OpenAiCompatibleClient("http://127.0.0.1:9/v1", "", "stub");
        assertInstanceOf(StreamingLlmClient.class, client);

        var tokens = new ArrayList<String>();
        ChatResponse resp = client.parseSseStream(
            new java.io.ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
            tokens::add);

        assertEquals(List.of("Hello", " world"), tokens);
        assertEquals("Hello world", resp.content());
        assertTrue(resp.hasToolCalls());
        assertEquals(1, resp.toolCalls().size());
        assertEquals("call_1", resp.toolCalls().get(0).id());
        assertEquals("multiply", resp.toolCalls().get(0).name());
        assertEquals(6, resp.toolCalls().get(0).arguments().get("x"));
        assertEquals(7, resp.toolCalls().get(0).arguments().get("y"));
        assertEquals(60, resp.usage().totalTokens());
    }

    @Test
    void malformedChunkFailsTheParse() {
        var client = new OpenAiCompatibleClient("http://127.0.0.1:9/v1", "", "stub");
        String sse = "data: {this is not json}\n\n";
        assertThrows(Exception.class, () -> client.parseSseStream(
            new java.io.ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
            t -> {}));
    }
}

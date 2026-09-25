package dev.axiom.llm;

import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolParam;
import dev.axiom.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire-format tests for {@link OpenAiCompatibleClient}: request bodies and
 * response parsing, without any network I/O.
 */
class OpenAiCompatibleClientTest {

    static class MathTools {
        @Tool(description = "Multiply")
        public int multiply(@ToolParam(description = "x") int x,
                            @ToolParam(description = "y") int y) {
            return x * y;
        }
    }

    private OpenAiCompatibleClient client() {
        // Never sends HTTP in these tests; base URL is irrelevant.
        return new OpenAiCompatibleClient("https://example.invalid/v1", "key", "stub");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolSchemaSentInOpenAiFormat() {
        var tools = List.copyOf(new ToolRegistry().register(new MathTools()).all());
        Map<String, Object> body = client().buildRequestBody(
            List.of(ChatMessage.user("hi")), tools, LlmClient.LlmOptions.defaults());

        assertEquals("stub", body.get("model"));
        var wireTools = (List<Map<String, Object>>) body.get("tools");
        assertEquals(1, wireTools.size());
        var fn = (Map<String, Object>) wireTools.get(0).get("function");
        assertEquals("multiply", fn.get("name"));
        var params = (Map<String, Object>) fn.get("parameters");
        var props = (Map<String, Object>) params.get("properties");
        assertEquals("integer", ((Map<String, Object>) props.get("x")).get("type"));
        assertEquals("auto", body.get("tool_choice"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void structuredOutputAddsResponseFormat() {
        Map<String, Object> body = client().buildRequestBody(
            List.of(ChatMessage.user("hi")), List.of(),
            LlmClient.LlmOptions.defaults().withJsonSchema("{\"type\":\"object\"}"));

        var rf = (Map<String, Object>) body.get("response_format");
        assertEquals("json_schema", rf.get("type"));
        var js = (Map<String, Object>) rf.get("json_schema");
        assertEquals(true, js.get("strict"));
    }

    @Test
    void parsesToolCallResponse() throws Exception {
        String json = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"stub",
             "choices":[{"index":0,"message":{
                "role":"assistant","content":null,
                "tool_calls":[{"id":"call_1","type":"function",
                  "function":{"name":"multiply","arguments":"{\\"x\\":6,\\"y\\":7}"}}]},
               "finish_reason":"tool_calls"}],
             "usage":{"prompt_tokens":50,"completion_tokens":10,"total_tokens":60}}
            """;
        ChatResponse resp = client().parseResponse(json);
        assertTrue(resp.hasToolCalls());
        assertEquals(1, resp.toolCalls().size());
        assertEquals("call_1", resp.toolCalls().get(0).id());
        assertEquals("multiply", resp.toolCalls().get(0).name());
        assertEquals(6, resp.toolCalls().get(0).arguments().get("x"));
        assertEquals(60, resp.usage().totalTokens());
    }

    @Test
    void parsesPlainTextResponse() throws Exception {
        String json = """
            {"id":"chatcmpl-2","object":"chat.completion","created":1,"model":"stub",
             "choices":[{"index":0,"message":{"role":"assistant","content":"Hello!"},
               "finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
            """;
        ChatResponse resp = client().parseResponse(json);
        assertFalse(resp.hasToolCalls());
        assertEquals("Hello!", resp.content());
    }

    @Test
    @SuppressWarnings("unchecked")
    void assistantToolCallsEchoedOnWire() {
        var tc = new ToolCallRequest("call_9", "multiply", Map.of("x", 2, "y", 3));
        Map<String, Object> body = client().buildRequestBody(
            List.of(ChatMessage.assistantWithToolCalls(null, List.of(tc))),
            List.of(), LlmClient.LlmOptions.defaults());

        var messages = (List<Map<String, Object>>) body.get("messages");
        var wireCalls = (List<Map<String, Object>>) messages.get(0).get("tool_calls");
        assertEquals(1, wireCalls.size());
        assertEquals("call_9", wireCalls.get(0).get("id"));
    }
}

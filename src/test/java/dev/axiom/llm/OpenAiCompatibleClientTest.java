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
        @Tool(name = "oc_multiply", description = "Multiply")
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
        assertEquals("oc_multiply", fn.get("name"));
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
                  "function":{"name":"oc_multiply","arguments":"{\\"x\\":6,\\"y\\":7}"}}]},
               "finish_reason":"tool_calls"}],
             "usage":{"prompt_tokens":50,"completion_tokens":10,"total_tokens":60}}
            """;
        ChatResponse resp = client().parseResponse(json);
        assertTrue(resp.hasToolCalls());
        assertEquals(1, resp.toolCalls().size());
        assertEquals("call_1", resp.toolCalls().get(0).id());
        assertEquals("oc_multiply", resp.toolCalls().get(0).name());
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
        var tc = new ToolCallRequest("call_9", "oc_multiply", Map.of("x", 2, "y", 3));
        Map<String, Object> body = client().buildRequestBody(
            List.of(ChatMessage.assistantWithToolCalls(null, List.of(tc))),
            List.of(), LlmClient.LlmOptions.defaults());

        var messages = (List<Map<String, Object>>) body.get("messages");
        var wireCalls = (List<Map<String, Object>>) messages.get(0).get("tool_calls");
        assertEquals(1, wireCalls.size());
        assertEquals("call_9", wireCalls.get(0).get("id"));
    }

    // ------------------------------------------------------------------
    // Thought signatures (Gemini 3 "thinking" models)
    // ------------------------------------------------------------------

    @Test
    void thoughtSignatureCapturedFromExtraContent() throws Exception {
        String sse = """
            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"oc_multiply","arguments":"{\\"x\\":6,\\"y\\":7}"},"extra_content":{"google":{"thought_signature":"AgQKA-sig-1"}}}]}}]}
            data: [DONE]
            """;
        ChatResponse resp = client().parseSseStream(
            new java.io.ByteArrayInputStream(sse.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            token -> {});
        assertEquals(1, resp.toolCalls().size());
        assertEquals("AgQKA-sig-1", resp.toolCalls().get(0).thoughtSignature());
    }

    @Test
    void thoughtSignatureCapturedFromTopLevelSibling() throws Exception {
        String json = """
            {"id":"chatcmpl-3","object":"chat.completion","created":1,"model":"stub",
             "choices":[{"index":0,"message":{
                "role":"assistant","content":null,
                "tool_calls":[{"id":"call_2","type":"function","thoughtSignature":"top-sig",
                  "function":{"name":"oc_multiply","arguments":"{}"}}]},
               "finish_reason":"tool_calls"}]}
            """;
        ChatResponse resp = client().parseResponse(json);
        assertEquals("top-sig", resp.toolCalls().get(0).thoughtSignature());
    }

    @Test
    @SuppressWarnings("unchecked")
    void thoughtSignatureReplayedOnWireInBothShapes() {
        var tc = new ToolCallRequest("call_7", "oc_multiply", Map.of("x", 2), "AgQKA-sig-7");
        Map<String, Object> body = client().buildRequestBody(
            List.of(ChatMessage.assistantWithToolCalls(null, List.of(tc))),
            List.of(), LlmClient.LlmOptions.defaults());

        var messages = (List<Map<String, Object>>) body.get("messages");
        var wireCalls = (List<Map<String, Object>>) messages.get(0).get("tool_calls");
        var wire = wireCalls.get(0);
        // Google's envelope shape...
        var extra = (Map<String, Object>) wire.get("extra_content");
        var google = (Map<String, Object>) extra.get("google");
        assertEquals("AgQKA-sig-7", google.get("thought_signature"));
        // ...and the top-level sibling shape some proxies use.
        assertEquals("AgQKA-sig-7", wire.get("thoughtSignature"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void noSignatureMeansNoExtraFieldsOnWire() {
        var tc = new ToolCallRequest("call_8", "oc_multiply", Map.of("x", 2));
        Map<String, Object> body = client().buildRequestBody(
            List.of(ChatMessage.assistantWithToolCalls(null, List.of(tc))),
            List.of(), LlmClient.LlmOptions.defaults());

        var messages = (List<Map<String, Object>>) body.get("messages");
        var wireCalls = (List<Map<String, Object>>) messages.get(0).get("tool_calls");
        var wire = wireCalls.get(0);
        assertFalse(wire.containsKey("extra_content"));
        assertFalse(wire.containsKey("thoughtSignature"));
        assertEquals(java.util.Set.of("id", "type", "function"), wire.keySet());
    }

    @Test
    @SuppressWarnings("unchecked")
    void signatureStaysOnExactlyTheCallItArrivedOn() throws Exception {
        String sse = """
            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"oc_multiply","arguments":"{}"},"extra_content":{"google":{"thought_signature":"sig-A"}}},{"index":1,"id":"call_b","type":"function","function":{"name":"oc_multiply","arguments":"{}"}}]}}]}
            data: [DONE]
            """;
        ChatResponse resp = client().parseSseStream(
            new java.io.ByteArrayInputStream(sse.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            token -> {});
        assertEquals(2, resp.toolCalls().size());
        assertEquals("sig-A", resp.toolCalls().get(0).thoughtSignature());
        assertNull(resp.toolCalls().get(1).thoughtSignature());

        // Replaying must not copy the signature onto the unsigned call.
        Map<String, Object> body = client().buildRequestBody(
            List.of(ChatMessage.assistantWithToolCalls(null, resp.toolCalls())),
            List.of(), LlmClient.LlmOptions.defaults());
        var messages = (List<Map<String, Object>>) body.get("messages");
        var wireCalls = (List<Map<String, Object>>) messages.get(0).get("tool_calls");
        assertTrue(wireCalls.get(0).containsKey("extra_content"));
        assertFalse(wireCalls.get(1).containsKey("extra_content"));
        assertFalse(wireCalls.get(1).containsKey("thoughtSignature"));
    }
}

package dev.axiom.llm;

import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolParam;
import dev.axiom.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire-format tests for {@link GeminiNativeClient}: request bodies and
 * response parsing, without any network I/O.
 */
class GeminiNativeClientTest {

    static class MathTools {
        @Tool(name = "g_multiply", description = "Multiply")
        public int multiply(@ToolParam(description = "x") int x,
                            @ToolParam(description = "y") int y) {
            return x * y;
        }
    }

    private GeminiNativeClient client() {
        // Never sends HTTP in these tests; the key is irrelevant.
        return new GeminiNativeClient("key", "stub");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolSchemaSentAsFunctionDeclarations() {
        var tools = List.copyOf(new ToolRegistry().register(new MathTools()).all());
        Map<String, Object> body = client().buildRequestBody(
            List.of(ChatMessage.user("hi")), tools, LlmClient.LlmOptions.defaults());

        assertFalse(body.containsKey("model"), "native API takes the model in the URL, not the body");
        var toolWrappers = (List<Map<String, Object>>) body.get("tools");
        assertEquals(1, toolWrappers.size());
        var decls = (List<Map<String, Object>>) toolWrappers.get(0).get("functionDeclarations");
        assertEquals(1, decls.size());
        assertEquals("g_multiply", decls.get(0).get("name"));
        var params = (Map<String, Object>) decls.get(0).get("parameters");
        var props = (Map<String, Object>) params.get("properties");
        assertEquals("integer", ((Map<String, Object>) props.get("x")).get("type"));
        var genConfig = (Map<String, Object>) body.get("generationConfig");
        assertNotNull(genConfig.get("maxOutputTokens"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rolesMappedToNativeShapes() {
        var tools = List.<ToolDefinition>of();
        var messages = List.of(
            ChatMessage.system("be brief"),
            ChatMessage.user("hello"),
            ChatMessage.toolResult("gemini-call-0", "g_multiply", "42"),
            ChatMessage.assistantWithToolCalls("on it", List.of(
                new ToolCallRequest("gemini-call-1", "g_multiply", Map.of("x", 6, "y", 7), "sig123"))));
        Map<String, Object> body = client().buildRequestBody(messages, tools, LlmClient.LlmOptions.defaults());

        var sys = (Map<String, Object>) body.get("systemInstruction");
        var sysParts = (List<Map<String, Object>>) sys.get("parts");
        assertEquals("be brief", sysParts.get(0).get("text"));

        var contents = (List<Map<String, Object>>) body.get("contents");
        assertEquals(3, contents.size());
        assertEquals("user", contents.get(0).get("role"));
        assertEquals("user", contents.get(1).get("role")); // tool result -> user role
        var fnResp = (Map<String, Object>)
            ((List<Map<String, Object>>) contents.get(1).get("parts")).get(0).get("functionResponse");
        assertEquals("g_multiply", fnResp.get("name"));

        assertEquals("model", contents.get(2).get("role")); // assistant -> model
        var parts = (List<Map<String, Object>>) contents.get(2).get("parts");
        assertEquals(2, parts.size());
        assertEquals("on it", parts.get(0).get("text"));
        var callPart = parts.get(1);
        var fnCall = (Map<String, Object>) callPart.get("functionCall");
        assertEquals("g_multiply", fnCall.get("name"));
        assertEquals("sig123", callPart.get("thoughtSignature"),
            "thought signature must ride on the part carrying its call");
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankToolCallNameNeverEmitted() {
        var messages = List.of(ChatMessage.assistantWithToolCalls("x",
            List.of(new ToolCallRequest("id1", "  ", Map.of()))));
        Map<String, Object> body = client().buildRequestBody(
            messages, List.of(), LlmClient.LlmOptions.defaults());
        var contents = (List<Map<String, Object>>) body.get("contents");
        assertEquals(1, contents.size()); // only the text part survives
        var parts = (List<Map<String, Object>>) contents.get(0).get("parts");
        assertEquals(1, parts.size());
        assertEquals("x", parts.get(0).get("text"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parseTextAndFunctionCall() throws Exception {
        String json = """
            {"candidates":[{"content":{"role":"model","parts":[
              {"text":"The answer is "},
              {"functionCall":{"name":"g_multiply","args":{"x":6,"y":7}},"thoughtSignature":"sig9"}
            ]},"finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"totalTokenCount":15}}""";
        ChatResponse r = client().parseResponse(json);
        assertEquals("The answer is ", r.content());
        assertEquals(1, r.toolCalls().size());
        var tc = r.toolCalls().get(0);
        assertEquals("g_multiply", tc.name());
        assertEquals(6, tc.arguments().get("x"));
        assertEquals("sig9", tc.thoughtSignature());
        assertEquals("gemini-call-0", tc.id());
        assertEquals(10, r.usage().promptTokens());
        assertEquals(5, r.usage().completionTokens());
        assertEquals(15, r.usage().totalTokens());
    }

    @Test
    void parseBlockedResponseYieldsEmpty() throws Exception {
        String json = """
            {"promptFeedback":{"blockReason":"SAFETY"}}""";
        ChatResponse r = client().parseResponse(json);
        assertEquals("", r.content());
        assertTrue(r.toolCalls().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void structuredOutputUsesResponseSchema() {
        Map<String, Object> body = client().buildRequestBody(
            List.of(ChatMessage.user("hi")), List.of(),
            LlmClient.LlmOptions.defaults().withJsonSchema("{\"type\":\"object\"}"));
        var genConfig = (Map<String, Object>) body.get("generationConfig");
        assertEquals("application/json", genConfig.get("responseMimeType"));
        assertNotNull(genConfig.get("responseSchema"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaSanitizedToGeminiSubset() {
        var tools = List.copyOf(new ToolRegistry().register(new MathTools()).all());
        Map<String, Object> body = client().buildRequestBody(
            List.of(ChatMessage.user("hi")), tools, LlmClient.LlmOptions.defaults());
        var toolWrappers = (List<Map<String, Object>>) body.get("tools");
        var decls = (List<Map<String, Object>>) toolWrappers.get(0).get("functionDeclarations");
        var params = (Map<String, Object>) decls.get(0).get("parameters");
        assertFalse(params.containsKey("additionalProperties"),
            "Gemini rejects additionalProperties with HTTP 400");
        var props = (Map<String, Object>) params.get("properties");
        assertTrue(props.containsKey("x"), "property names must survive sanitization");
        assertEquals("integer", ((Map<String, Object>) props.get("x")).get("type"));
    }

    @Test
    void sanitizeSchemaKeepsAllowlistedFieldsRecursively() {
        Map<String, Object> schema = Map.of(
            "type", "object",
            "additionalProperties", false,
            "$schema", "http://json-schema.org/draft-07/schema#",
            "properties", Map.of("q", Map.of(
                "type", "string", "description", "query", "title", "Q")));
        var out = GeminiNativeClient.sanitizeSchema(schema);
        assertEquals("object", out.get("type"));
        assertFalse(out.containsKey("additionalProperties"));
        assertFalse(out.containsKey("$schema"));
        var props = (Map<String, Object>) out.get("properties");
        var q = (Map<String, Object>) props.get("q");
        assertEquals("string", q.get("type"));
        assertEquals("query", q.get("description"));
        assertFalse(q.containsKey("title"));
    }

    @Test
    void sanitizeSchemaGivesArrayItemsAType() {
        var out = GeminiNativeClient.sanitizeSchema(Map.of(
            "type", "array", "description", "argv",
            "items", Map.of()));
        var items = (Map<String, Object>) out.get("items");
        assertEquals("string", items.get("type"),
            "Gemini rejects array items without a declared type");
    }
}

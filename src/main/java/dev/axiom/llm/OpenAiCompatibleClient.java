package dev.axiom.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.tools.ToolDefinition;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Chat client for any OpenAI-compatible {@code /v1/chat/completions}
 * endpoint: OpenAI, Azure OpenAI, Ollama, vLLM, LM Studio, Together, etc.
 * Uses only {@code java.net.http} — no SDK dependencies.
 */
public final class OpenAiCompatibleClient implements LlmClient {
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final URI endpoint;
    private final String apiKey;
    private final String model;

    public OpenAiCompatibleClient(String baseUrl, String apiKey, String model) {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        String normalized = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.endpoint = URI.create(normalized + "chat/completions");
        this.apiKey = apiKey;
        this.model = model;
    }

    /** Convenience constructor reading {@code OPENAI_API_KEY} from the environment. */
    public OpenAiCompatibleClient(String model) {
        this("https://api.openai.com/v1",
             System.getenv().getOrDefault("OPENAI_API_KEY", ""),
             model);
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools, LlmOptions options) {
        try {
            String json = mapper.writeValueAsString(buildRequestBody(messages, tools, options));
            HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
            if (apiKey != null && !apiKey.isBlank()) {
                req.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new LlmException(
                    "LLM request failed with HTTP %d: %s".formatted(resp.statusCode(), truncate(resp.body(), 500)));
            }
            return parseResponse(resp.body());
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("LLM request failed: " + e.getMessage(), e);
        }
    }

    /** Build the chat-completions request body (pure function — unit-testable). */
    Map<String, Object> buildRequestBody(List<ChatMessage> messages, List<ToolDefinition> tools,
                                         LlmOptions options) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", toWireMessages(messages));
        body.put("temperature", options.temperature());
        body.put("max_tokens", options.maxTokens());
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(ToolDefinition::toFunctionDefinition).toList());
            body.put("tool_choice", "auto");
        }
        if (options.jsonSchema() != null) {
            try {
                Object schemaObj = mapper.readValue(options.jsonSchema(), Object.class);
                body.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                        "name", "axiom_result",
                        "strict", true,
                        "schema", schemaObj)));
            } catch (Exception e) {
                throw new LlmException("Invalid JSON schema for structured output: " + e.getMessage(), e);
            }
        }
        return body;
    }

    private List<Map<String, Object>> toWireMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatMessage m : messages) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("role", m.role().name().toLowerCase());
            w.put("content", m.content());
            if (m.toolCallId() != null) w.put("tool_call_id", m.toolCallId());
            if (m.name() != null) w.put("name", m.name());
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                List<Map<String, Object>> wireCalls = new ArrayList<>();
                for (ToolCallRequest tc : m.toolCalls()) {
                    try {
                        wireCalls.add(Map.of(
                            "id", tc.id(),
                            "type", "function",
                            "function", Map.of(
                                "name", tc.name(),
                                "arguments", mapper.writeValueAsString(tc.arguments()))));
                    } catch (Exception e) {
                        throw new LlmException("Failed to serialize tool call: " + e.getMessage(), e);
                    }
                }
                w.put("tool_calls", wireCalls);
            }
            out.add(w);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    ChatResponse parseResponse(String json) throws Exception {
        Map<String, Object> root = mapper.readValue(json, new TypeReference<>() {});
        Map<String, Object> choice = ((List<Map<String, Object>>) root.get("choices")).get(0);
        Map<String, Object> message = (Map<String, Object>) choice.get("message");

        String content = (String) message.get("content");
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        Object rawToolCalls = message.get("tool_calls");
        if (rawToolCalls instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> tc = (Map<String, Object>) o;
                Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                String argsJson = (String) fn.get("arguments");
                Map<String, Object> args = (argsJson == null || argsJson.isBlank())
                    ? Map.of()
                    : mapper.readValue(argsJson, new TypeReference<>() {});
                toolCalls.add(new ToolCallRequest(
                    (String) tc.get("id"), (String) fn.get("name"), args));
            }
        }

        ChatResponse.TokenUsage usage = ChatResponse.TokenUsage.empty();
        if (root.get("usage") instanceof Map<?, ?> u) {
            Map<String, Object> um = (Map<String, Object>) u;
            usage = new ChatResponse.TokenUsage(
                num(um.get("prompt_tokens")), num(um.get("completion_tokens")), num(um.get("total_tokens")));
        }
        return new ChatResponse(content, toolCalls, usage);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }
}

package dev.axiom.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.tools.ToolDefinition;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Chat client for any OpenAI-compatible {@code /v1/chat/completions}
 * endpoint: OpenAI, Azure OpenAI, Ollama, vLLM, LM Studio, Together, etc.
 * Uses only {@code java.net.http} — no SDK dependencies.
 */
public final class OpenAiCompatibleClient implements StreamingLlmClient {
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
                    "LLM request failed with HTTP %d: %s".formatted(resp.statusCode(), truncate(resp.body(), 500)),
                    resp.statusCode(), retryAfterSeconds(resp));
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
            usage = readUsage((Map<String, Object>) u);
        }
        return new ChatResponse(content, toolCalls, usage);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static ChatResponse.TokenUsage readUsage(Map<String, Object> um) {
        return new ChatResponse.TokenUsage(
            num(um.get("prompt_tokens")), num(um.get("completion_tokens")), num(um.get("total_tokens")));
    }

    // ------------------------------------------------------------------
    // Streaming (Server-Sent Events)
    // ------------------------------------------------------------------

    /**
     * Chat with {@code stream: true}: tokens are delivered to the listener in
     * arrival order and the fully assembled {@link ChatResponse} is returned.
     * Fragmented {@code tool_calls} deltas are merged by index (arguments are
     * concatenated, then parsed as JSON once complete).
     */
    @Override
    public ChatResponse chatStream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                   LlmOptions options, TokenListener listener) {
        try {
            Map<String, Object> body = buildRequestBody(messages, tools, options);
            body.put("stream", true);
            body.put("stream_options", Map.of("include_usage", true));
            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Authorization", apiKey != null && !apiKey.isBlank() ? "Bearer " + apiKey : "")
                .build();
            // Drop the empty Authorization header when no key is set.
            if (apiKey == null || apiKey.isBlank()) {
                req = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            }

            HttpResponse<java.io.InputStream> resp =
                http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                String err;
                try (var in = resp.body()) {
                    err = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                throw new LlmException(
                    "LLM streaming request failed with HTTP %d: %s".formatted(resp.statusCode(), truncate(err, 500)),
                    resp.statusCode(), retryAfterSeconds(resp));
            }
            return parseSseStream(resp.body(), listener);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("LLM streaming request failed: " + e.getMessage(), e);
        }
    }

    /** Accumulates one tool call's fragments across SSE chunks. */
    private static final class ToolCallDelta {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    /** Parse an SSE stream into the assembled response (pure function — unit-testable). */
    @SuppressWarnings("unchecked")
    ChatResponse parseSseStream(java.io.InputStream in, TokenListener listener) throws Exception {
        StringBuilder content = new StringBuilder();
        Map<Integer, ToolCallDelta> deltas = new TreeMap<>();
        ChatResponse.TokenUsage usage = ChatResponse.TokenUsage.empty();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith(":")) continue; // heartbeat / comment
                if (!t.startsWith("data:")) continue;
                String data = t.substring(5).trim();
                if (data.equals("[DONE]")) break;
                Map<String, Object> chunk = mapper.readValue(data, new TypeReference<>() {});
                if (chunk.get("usage") instanceof Map<?, ?> u) {
                    usage = readUsage((Map<String, Object>) u);
                }
                Object rawChoices = chunk.get("choices");
                if (!(rawChoices instanceof List<?> choices) || choices.isEmpty()) continue;
                Object rawDelta = ((Map<String, Object>) choices.get(0)).get("delta");
                if (!(rawDelta instanceof Map<?, ?> rawDeltaMap)) continue;
                Map<String, Object> delta = (Map<String, Object>) rawDeltaMap;
                Object c = delta.get("content");
                if (c instanceof String s && !s.isEmpty()) {
                    content.append(s);
                    listener.onToken(s);
                }
                Object rawToolCalls = delta.get("tool_calls");
                if (rawToolCalls instanceof List<?> list) {
                    for (Object o : list) {
                        Map<String, Object> d = (Map<String, Object>) o;
                        int index = d.get("index") instanceof Number n ? n.intValue() : 0;
                        ToolCallDelta acc = deltas.computeIfAbsent(index, k -> new ToolCallDelta());
                        if (d.get("id") instanceof String id) acc.id = id;
                        Object fn = d.get("function");
                        if (fn instanceof Map<?, ?> fmRaw) {
                            Map<String, Object> fm = (Map<String, Object>) fmRaw;
                            if (fm.get("name") instanceof String name) acc.name = name;
                            if (fm.get("arguments") instanceof String a) acc.arguments.append(a);
                        }
                    }
                }
            }
        }
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        for (ToolCallDelta d : deltas.values()) {
            String argsJson = d.arguments.toString();
            Map<String, Object> args = (argsJson == null || argsJson.isBlank())
                ? Map.of()
                : mapper.readValue(argsJson, new TypeReference<>() {});
            toolCalls.add(new ToolCallRequest(d.id, d.name, args));
        }
        return new ChatResponse(content.toString(), toolCalls, usage);
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** Upper bound for a honored Retry-After delay: ten minutes. */
    private static final long MAX_RETRY_AFTER_SECONDS = 600;

    private static long retryAfterSeconds(HttpResponse<?> resp) {
        return resp.headers().firstValue("Retry-After")
            .map(OpenAiCompatibleClient::parseRetryAfterSeconds)
            .orElse((long) LlmException.NO_STATUS);
    }

    /**
     * Parse a {@code Retry-After} header value: either delay-seconds or an
     * HTTP-date. Returns the delay in seconds clamped to
     * [0, {@value #MAX_RETRY_AFTER_SECONDS}], or {@link LlmException#NO_STATUS}
     * when the value is missing or unparseable.
     */
    public static long parseRetryAfterSeconds(String value) {
        if (value == null || value.isBlank()) return LlmException.NO_STATUS;
        String v = value.trim();
        try {
            long secs = Long.parseLong(v);
            return Math.max(0, Math.min(secs, MAX_RETRY_AFTER_SECONDS));
        } catch (NumberFormatException ignored) {
            // fall through to HTTP-date parsing
        }
        try {
            long secs = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().getEpochSecond() - Instant.now().getEpochSecond();
            return Math.max(0, Math.min(secs, MAX_RETRY_AFTER_SECONDS));
        } catch (Exception ignored) {
            return LlmException.NO_STATUS;
        }
    }
}

package dev.axiom.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.tools.ToolDefinition;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Native Gemini API client ({@code generateContent}).
 *
 * <p>Why native instead of the OpenAI-compatible shim: the
 * {@code custom.gemini} Secure Vault credential declares placement
 * {@code query_param: key}, so the egress proxy only swaps the surrogate
 * for the real API key when it appears as the {@code ?key=} query
 * parameter. The compat endpoint demands the key in the
 * {@code Authorization} header (where the proxy never swaps it) and
 * rejects a query-only key — verified 2026-10-02: Bearer+surrogate on the
 * compat endpoint returns HTTP 400 "Please pass a valid API key", while
 * native {@code generateContent?key=<surrogate>} returns HTTP 200.
 *
 * <p>Uses only {@code java.net.http} — no SDK dependencies. Wire mapping:
 * <ul>
 *   <li>SYSTEM messages become {@code systemInstruction}; ASSISTANT becomes
 *       {@code role: "model"}; TOOL results become {@code functionResponse}
 *       parts on {@code role: "user"} messages.</li>
 *   <li>Tool definitions become {@code functionDeclarations}.</li>
 *   <li>Thought signatures ride on the part carrying the
 *       {@code functionCall}, exactly as the native API issues them, and
 *       are echoed back verbatim on replay.</li>
 *   <li>The native API returns no call ids; ids are synthesized per turn
 *       ({@code "gemini-call-<n>"}) and stay consistent through the
 *       agent's tool-result linkage within a run.</li>
 * </ul>
 */
public final class GeminiNativeClient implements LlmClient {
    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String model;

    public GeminiNativeClient(String apiKey, String model) {
        var proxySettings = ProxyConfig.fromEnv();
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30));
        // Same proxy discipline as OpenAiCompatibleClient: honor the proxy
        // env vars, preemptive proxy auth via system properties, never an
        // Authenticator (which would withhold our headers).
        proxySettings.ifPresent(s -> {
            builder.proxy(ProxyConfig.selectorFor(s));
            ProxyConfig.applyPreemptiveProxyAuth(s);
        });
        this.http = builder.build();
        this.apiKey = apiKey;
        this.model = model;
    }

    /** Convenience constructor reading {@code GEMINI_API_KEY} from the environment. */
    public GeminiNativeClient(String model) {
        this(System.getenv().getOrDefault("GEMINI_API_KEY", ""), model);
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools, LlmOptions options) {
        try {
            String url = API_BASE + model + ":generateContent?key="
                + URLEncoder.encode(apiKey == null ? "" : apiKey, StandardCharsets.UTF_8);
            String json = mapper.writeValueAsString(buildRequestBody(messages, tools, options));
            // No Authorization header: the key travels in ?key=, which is the
            // only placement the vault/proxy honors for this credential.
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new LlmException(
                    "Gemini request failed with HTTP %d: %s".formatted(resp.statusCode(), truncate(resp.body(), 500)),
                    resp.statusCode());
            }
            return parseResponse(resp.body());
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Gemini request failed: " + e.getMessage(), e);
        }
    }

    /** Build the generateContent request body (pure function — unit-testable). */
    Map<String, Object> buildRequestBody(List<ChatMessage> messages, List<ToolDefinition> tools,
                                         LlmOptions options) {
        Map<String, Object> body = new LinkedHashMap<>();

        List<String> systemTexts = new ArrayList<>();
        List<Map<String, Object>> contents = new ArrayList<>();
        for (ChatMessage m : messages) {
            switch (m.role()) {
                case SYSTEM -> {
                    if (m.content() != null && !m.content().isBlank()) systemTexts.add(m.content());
                }
                case TOOL -> contents.add(Map.of("role", "user", "parts", List.of(
                    Map.of("functionResponse", Map.of(
                        "name", m.name() == null ? "" : m.name(),
                        "response", Map.of("result", m.content() == null ? "" : m.content()))))));
                default -> {
                    String role = m.role() == ChatRole.ASSISTANT ? "model" : "user";
                    List<Map<String, Object>> parts = new ArrayList<>();
                    if (m.content() != null && !m.content().isBlank()) {
                        parts.add(Map.of("text", m.content()));
                    }
                    if (m.toolCalls() != null) {
                        for (ToolCallRequest tc : m.toolCalls()) {
                            // Blank function names are rejected by the API
                            // (HTTP 400); the agent repairs these at parse
                            // time, but replayed history must never emit one.
                            if (tc.name() == null || tc.name().isBlank()) continue;
                            Map<String, Object> call = new LinkedHashMap<>();
                            call.put("functionCall", Map.of(
                                "name", tc.name(),
                                "args", tc.arguments() == null ? Map.of() : tc.arguments()));
                            if (tc.thoughtSignature() != null && !tc.thoughtSignature().isBlank()) {
                                // Echo the opaque signature verbatim on the
                                // part carrying its call, exactly as issued.
                                // Never synthesized.
                                call.put("thoughtSignature", tc.thoughtSignature());
                            }
                            parts.add(call);
                        }
                    }
                    if (!parts.isEmpty()) {
                        contents.add(Map.of("role", role, "parts", parts));
                    }
                }
            }
        }
        if (!systemTexts.isEmpty()) {
            body.put("systemInstruction", Map.of("parts",
                List.of(Map.of("text", String.join("\n\n", systemTexts)))));
        }
        body.put("contents", contents);

        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> declarations = new ArrayList<>();
            for (ToolDefinition t : tools) {
                Map<String, Object> decl = new LinkedHashMap<>();
                decl.put("name", t.name());
                if (t.description() != null) decl.put("description", t.description());
                // The native API accepts only a documented subset of JSON
                // Schema in function parameters; fields like
                // "additionalProperties" are rejected with HTTP 400
                // INVALID_ARGUMENT (verified 2026-10-02). Sanitize.
                if (t.jsonSchema() != null) decl.put("parameters", sanitizeSchema(t.jsonSchema()));
                declarations.add(decl);
            }
            body.put("tools", List.of(Map.of("functionDeclarations", declarations)));
        }

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", options.temperature());
        generationConfig.put("maxOutputTokens", options.maxTokens());
        if (options.jsonSchema() != null) {
            try {
                Object schemaObj = mapper.readValue(options.jsonSchema(), Object.class);
                generationConfig.put("responseMimeType", "application/json");
                generationConfig.put("responseSchema", schemaObj);
            } catch (Exception e) {
                throw new LlmException("Invalid JSON schema for structured output: " + e.getMessage(), e);
            }
        }
        body.put("generationConfig", generationConfig);
        return body;
    }

    /** Parse a generateContent response into a ChatResponse (pure — unit-testable). */
    @SuppressWarnings("unchecked")
    ChatResponse parseResponse(String json) throws Exception {
        Map<String, Object> root = mapper.readValue(json, new TypeReference<>() {});
        Object rawCandidates = root.get("candidates");
        if (!(rawCandidates instanceof List<?> candidates) || candidates.isEmpty()) {
            return new ChatResponse("", List.of(), ChatResponse.TokenUsage.empty());
        }
        Map<String, Object> first = (Map<String, Object>) candidates.get(0);
        Map<String, Object> content = (Map<String, Object>) first.get("content");

        StringBuilder text = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        if (content != null && content.get("parts") instanceof List<?> parts) {
            int callIndex = 0;
            for (Object o : parts) {
                Map<String, Object> part = (Map<String, Object>) o;
                if (part.get("text") instanceof String t) {
                    text.append(t);
                }
                if (part.get("functionCall") instanceof Map<?, ?> rawFn) {
                    Map<String, Object> fn = (Map<String, Object>) rawFn;
                    Object rawArgs = fn.get("args");
                    Map<String, Object> args = rawArgs instanceof Map<?, ?> m
                        ? (Map<String, Object>) m : Map.of();
                    Object sig = part.get("thoughtSignature");
                    toolCalls.add(new ToolCallRequest(
                        "gemini-call-" + (callIndex++),
                        (String) fn.get("name"),
                        args,
                        sig instanceof String s && !s.isBlank() ? s : null));
                }
            }
        }

        ChatResponse.TokenUsage usage = ChatResponse.TokenUsage.empty();
        if (root.get("usageMetadata") instanceof Map<?, ?> um) {
            usage = new ChatResponse.TokenUsage(
                num(um.get("promptTokenCount")),
                num(um.get("candidatesTokenCount")),
                num(um.get("totalTokenCount")));
        }
        return new ChatResponse(text.toString(), toolCalls, usage);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * The subset of JSON Schema the Gemini {@code functionDeclarations}
     * parameters accept. Everything else (notably
     * {@code additionalProperties}, which Axiom's schemas carry) is
     * rejected with HTTP 400. Applied recursively.
     */
    private static final Set<String> SCHEMA_ALLOWLIST = Set.of(
        "type", "format", "description", "nullable", "enum",
        "items", "properties", "required");

    @SuppressWarnings("unchecked")
    static Map<String, Object> sanitizeSchema(Map<String, Object> schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : schema.entrySet()) {
            if (!SCHEMA_ALLOWLIST.contains(e.getKey())) continue;
            Object v = e.getValue();
            if ("properties".equals(e.getKey()) && v instanceof Map<?, ?> props) {
                // Keys here are property NAMES, not schema keywords: keep
                // the names, sanitize each property's sub-schema.
                Map<String, Object> np = new LinkedHashMap<>();
                for (var pe : ((Map<?, ?>) props).entrySet()) {
                    Object pv = pe.getValue();
                    np.put(String.valueOf(pe.getKey()),
                        pv instanceof Map<?, ?> m ? sanitizeSchema((Map<String, Object>) m) : pv);
                }
                out.put(e.getKey(), np);
            } else if (v instanceof Map<?, ?> m) {
                out.put(e.getKey(), sanitizeSchema((Map<String, Object>) m));
            } else if (v instanceof List<?> l) {
                List<Object> nl = new ArrayList<>();
                for (Object item : l) {
                    nl.add(item instanceof Map<?, ?> m ? sanitizeSchema((Map<String, Object>) m) : item);
                }
                out.put(e.getKey(), nl);
            } else {
                out.put(e.getKey(), v);
            }
        }
        // The native API requires array items to declare a type; Axiom's
        // schemas leave items empty. The only List<> tool parameter in the
        // codebase is List<String>, so string is the safe default.
        if ("array".equals(out.get("type"))) {
            Object items = out.get("items");
            if (!(items instanceof Map<?, ?> im) || !((Map<?, ?>) im).containsKey("type")) {
                out.put("items", Map.of("type", "string"));
            }
        }
        return out;
    }
}

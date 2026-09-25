package dev.axiom.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.LlmClient;
import dev.axiom.tools.ToolDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Deterministic cache keys for chat requests: SHA-256 over the model id,
 * messages, tool definitions, and options. Two semantically identical
 * requests always hit the same key; anything that changes the request
 * changes the key.
 */
public final class CacheKeys {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CacheKeys() {}

    public static String forChat(String model, List<ChatMessage> messages,
                                 List<ToolDefinition> tools, LlmClient.LlmOptions options) {
        try {
            String canonical = MAPPER.writeValueAsString(new RequestView(
                model,
                messages.stream().map(m -> List.of(
                    m.role().name(), m.content(),
                    String.valueOf(m.toolCallId()), String.valueOf(m.name()),
                    String.valueOf(m.toolCalls()))).toList(),
                tools.stream().map(t -> t.name() + ":" + t.description()
                    + ":" + t.jsonSchema()).toList(),
                options.temperature(), options.maxTokens(), options.jsonSchema()));
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "axiom-chat-" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build cache key", e);
        }
    }

    private record RequestView(String model, List<List<String>> messages,
                               List<String> tools, double temperature,
                               int maxTokens, String jsonSchema) {}
}

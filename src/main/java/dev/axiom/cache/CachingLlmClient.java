package dev.axiom.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.LlmException;
import dev.axiom.llm.StreamingLlmClient;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.ToolDefinition;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An {@link LlmClient} decorator that caches chat responses. Identical
 * requests (same model, messages, tools, options) hit the cache instead of
 * the provider — deterministic agent runs, eval suites, and demos get fast
 * and free. Only non-streaming {@link #chat} calls are cached; streaming
 * passes through to the delegate.
 *
 * <p>Cache keys are content hashes ({@link CacheKeys}), so a cached entry
 * can never be served for a different request.
 */
public final class CachingLlmClient implements StreamingLlmClient {
    private final LlmClient delegate;
    private final ResponseCache cache;
    private final ObjectMapper mapper = new ObjectMapper();

    public CachingLlmClient(LlmClient delegate, ResponseCache cache) {
        this.delegate = Objects.requireNonNull(delegate);
        this.cache = Objects.requireNonNull(cache);
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                             LlmOptions options) {
        String key = CacheKeys.forChat(delegate.model(), messages, tools, options);
        Optional<String> hit = cache.get(key);
        if (hit.isPresent()) {
            return deserialize(hit.get());
        }
        ChatResponse response = delegate.chat(messages, tools, options);
        cache.put(key, serialize(response));
        return response;
    }

    @Override
    public ChatResponse chatStream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                   LlmOptions options, TokenListener listener) {
        if (delegate instanceof StreamingLlmClient s) {
            return s.chatStream(messages, tools, options, listener);
        }
        return chat(messages, tools, options);
    }

    private String serialize(ChatResponse r) {
        try {
            return mapper.writeValueAsString(new StoredResponse(
                r.content(),
                r.toolCalls().stream()
                    .map(tc -> new StoredToolCall(tc.id(), tc.name(), tc.arguments()))
                    .toList(),
                r.usage().promptTokens(), r.usage().completionTokens(),
                r.usage().totalTokens()));
        } catch (Exception e) {
            throw new LlmException("Failed to serialize cached response", e);
        }
    }

    private ChatResponse deserialize(String json) {
        try {
            StoredResponse s = mapper.readValue(json, StoredResponse.class);
            return new ChatResponse(
                s.content(),
                s.toolCalls().stream()
                    .map(tc -> new ToolCallRequest(tc.id(), tc.name(), tc.arguments()))
                    .toList(),
                new ChatResponse.TokenUsage(
                    s.promptTokens(), s.completionTokens(), s.totalTokens()));
        } catch (Exception e) {
            throw new LlmException("Failed to deserialize cached response", e);
        }
    }

    private record StoredResponse(String content, List<StoredToolCall> toolCalls,
                                  long promptTokens, long completionTokens,
                                  long totalTokens) {}
    private record StoredToolCall(String id, String name,
                                  java.util.Map<String, Object> arguments) {}
}

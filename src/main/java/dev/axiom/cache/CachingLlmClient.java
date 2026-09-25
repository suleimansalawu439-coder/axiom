package dev.axiom.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.LlmException;
import dev.axiom.llm.StreamingLlmClient;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An {@link LlmClient} decorator that caches chat responses. Identical
 * requests (same model, messages, tools, options) hit the cache instead of
 * the provider — deterministic agent runs, eval suites, and demos get fast
 * and free.
 *
 * <p>Both {@link #chat} and {@link #chatStream} are cached under the same
 * content-hash key ({@link CacheKeys}). Streaming responses are stored
 * together with their token chunks: a cache hit replays the stored chunks
 * through the listener in order and returns the stored response, so callers
 * can't tell a hit from a live stream except by speed. Entries written
 * before token chunks existed (or by {@link #chat}, which has no chunks)
 * are treated as a miss by {@link #chatStream} — the delegate is called
 * and the entry is rewritten with chunks. Corrupt entries are likewise
 * treated as a miss, never a crash.
 *
 * <p>Cache keys are content hashes, so a cached entry can never be served
 * for a different request.
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
            try {
                return toChatResponse(deserialize(hit.get()));
            } catch (Exception e) {
                // Corrupt entry: fall through and overwrite it below.
            }
        }
        ChatResponse response = delegate.chat(messages, tools, options);
        cache.put(key, serialize(response, null));
        return response;
    }

    @Override
    public ChatResponse chatStream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                   LlmOptions options, TokenListener listener) {
        String key = CacheKeys.forChat(delegate.model(), messages, tools, options);
        Optional<String> hit = cache.get(key);
        if (hit.isPresent()) {
            StoredResponse stored = tryDeserialize(hit.get());
            if (stored != null && stored.tokens() != null) {
                for (String token : stored.tokens()) {
                    listener.onToken(token);
                }
                return toChatResponse(stored);
            }
            // Legacy entry without token chunks (or corrupt): treat as a miss
            // and rewrite it with chunks below.
        }
        List<String> buffered = new ArrayList<>();
        ChatResponse response;
        if (delegate instanceof StreamingLlmClient s) {
            response = s.chatStream(messages, tools, options, token -> {
                buffered.add(token);
                listener.onToken(token);
            });
        } else {
            response = delegate.chat(messages, tools, options);
            if (response.content() != null && !response.content().isEmpty()) {
                buffered.add(response.content());
                listener.onToken(response.content());
            }
        }
        cache.put(key, serialize(response, buffered));
        return response;
    }

    /** Deserialize, returning null instead of throwing on corrupt data. */
    private StoredResponse tryDeserialize(String json) {
        try {
            return deserialize(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String serialize(ChatResponse r, List<String> tokens) {
        try {
            return mapper.writeValueAsString(new StoredResponse(
                r.content(),
                r.toolCalls().stream()
                    .map(tc -> new StoredToolCall(tc.id(), tc.name(), tc.arguments()))
                    .toList(),
                r.usage().promptTokens(), r.usage().completionTokens(),
                r.usage().totalTokens(),
                tokens));
        } catch (Exception e) {
            throw new LlmException("Failed to serialize cached response", e);
        }
    }

    private StoredResponse deserialize(String json) {
        try {
            return mapper.readValue(json, StoredResponse.class);
        } catch (Exception e) {
            throw new LlmException("Failed to deserialize cached response", e);
        }
    }

    private static ChatResponse toChatResponse(StoredResponse s) {
        return new ChatResponse(
            s.content(),
            s.toolCalls().stream()
                .map(tc -> new ToolCallRequest(tc.id(), tc.name(), tc.arguments()))
                .toList(),
            new ChatResponse.TokenUsage(
                s.promptTokens(), s.completionTokens(), s.totalTokens()));
    }

    /**
     * Stored form of a response. {@code tokens} is null for entries written
     * by {@link #chat} or by older Axiom versions; {@link #chatStream}
     * treats those as a miss.
     */
    private record StoredResponse(String content, List<StoredToolCall> toolCalls,
                                  long promptTokens, long completionTokens,
                                  long totalTokens, List<String> tokens) {}
    private record StoredToolCall(String id, String name,
                                  java.util.Map<String, Object> arguments) {}
}

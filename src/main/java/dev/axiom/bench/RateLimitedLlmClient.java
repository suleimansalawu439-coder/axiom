package dev.axiom.bench;

import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.StreamingLlmClient;
import dev.axiom.tools.ToolDefinition;

import java.util.List;

/**
 * Draws one rate-limiter permit per LLM call before delegating.
 *
 * <p>Wrap the <em>outermost</em> client (outside the retry decorator) so
 * retries also consume permits — otherwise a burst of retries can trip the
 * very per-minute limit the limiter exists to respect.
 */
public final class RateLimitedLlmClient implements StreamingLlmClient {
    private final StreamingLlmClient delegate;
    private final RateLimiter limiter;

    public RateLimitedLlmClient(StreamingLlmClient delegate, RateLimiter limiter) {
        this.delegate = delegate;
        this.limiter = limiter;
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                             LlmClient.LlmOptions options) {
        limiter.acquire();
        return delegate.chat(messages, tools, options);
    }

    @Override
    public ChatResponse chatStream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                   LlmClient.LlmOptions options, TokenListener listener) {
        limiter.acquire();
        return delegate.chatStream(messages, tools, options, listener);
    }

    @Override
    public String model() {
        return delegate.model();
    }
}

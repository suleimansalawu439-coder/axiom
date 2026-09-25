package dev.axiom.resilience;

import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.LlmException;
import dev.axiom.llm.StreamingLlmClient;
import dev.axiom.tools.ToolDefinition;

import java.util.List;
import java.util.Objects;

/**
 * An {@link LlmClient} decorator that retries transient failures according
 * to a {@link RetryPolicy}. Streaming retries from scratch (partial tokens
 * are discarded) — listeners therefore only see the successful attempt's
 * tokens.
 *
 * <pre>{@code
 * var client = new RetryingLlmClient(
 *     new OpenAiCompatibleClient("gpt-4o"),
 *     RetryPolicy.builder().maxAttempts(5).build());
 * }</pre>
 */
public final class RetryingLlmClient implements StreamingLlmClient {
    private final LlmClient delegate;
    private final RetryPolicy policy;
    private final Sleeper sleeper;

    /** Swappable sleep for tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public RetryingLlmClient(LlmClient delegate, RetryPolicy policy) {
        this(delegate, policy, Thread::sleep);
    }

    RetryingLlmClient(LlmClient delegate, RetryPolicy policy, Sleeper sleeper) {
        this.delegate = Objects.requireNonNull(delegate);
        this.policy = Objects.requireNonNull(policy);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                             LlmOptions options) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return delegate.chat(messages, tools, options);
            } catch (Exception e) {
                if (!policy.shouldRetry(e, attempt)) {
                    throw e instanceof RuntimeException re ? re : new LlmException(e.getMessage(), e);
                }
                sleepBeforeRetry(attempt);
            }
        }
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            sleeper.sleep(policy.backoffForAttempt(attempt + 1).toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmException("Retry interrupted", ie);
        }
    }

    /** Streaming is delegated without retry (a partial stream can't resume). */
    @Override
    public ChatResponse chatStream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                   LlmOptions options, TokenListener listener) {
        if (delegate instanceof StreamingLlmClient s) {
            return s.chatStream(messages, tools, options, listener);
        }
        return chat(messages, tools, options);
    }
}

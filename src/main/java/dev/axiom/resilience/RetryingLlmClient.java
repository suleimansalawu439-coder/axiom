package dev.axiom.resilience;

import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.LlmException;
import dev.axiom.llm.StreamingLlmClient;
import dev.axiom.tools.ToolDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An {@link LlmClient} decorator that retries transient failures according
 * to a {@link RetryPolicy}.
 *
 * <p>Streaming retries from scratch: each attempt's tokens are buffered and
 * forwarded to the listener only when that attempt succeeds, so the listener
 * sees exactly one complete token sequence — partial tokens from failed
 * attempts are discarded, never replayed. A failed attempt therefore costs
 * the caller nothing observable: no tokens, no partial response.
 *
 * <p>If the delegate is not a {@link StreamingLlmClient}, its
 * {@link #chat} is retried under the same policy and the successful
 * response's content is delivered to the listener as a single token.
 *
 * <p>When a failed attempt carries the provider's {@code Retry-After} hint
 * (see {@link LlmException#retryAfterSeconds()}), the wait before the next
 * attempt is the longer of the policy backoff and the hinted delay.
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

    /**
     * Upper bound for a provider-hinted retry delay: ten minutes. The
     * {@code Retry-After} hint is advisory, not a command — a hostile or
     * buggy provider (or a custom {@link LlmClient} that mis-reports
     * {@code retryAfterSeconds}) must not be able to park the retry loop
     * effectively forever.
     */
    private static final long MAX_HINTED_WAIT_SECONDS = 600;

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
                // Daily/plan quota exhaustion never recovers within a run —
                // fail fast instead of burning minutes on doomed retries.
                if (LlmException.isQuotaExhausted(e)) {
                    throw e instanceof RuntimeException re ? re : new LlmException(e.getMessage(), e);
                }
                if (!policy.shouldRetry(e, attempt)) {
                    throw e instanceof RuntimeException re ? re : new LlmException(e.getMessage(), e);
                }
                sleepBeforeRetry(e, attempt);
            }
        }
    }

    @Override
    public ChatResponse chatStream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                   LlmOptions options, TokenListener listener) {
        int attempt = 0;
        while (true) {
            attempt++;
            List<String> buffered = new ArrayList<>();
            try {
                ChatResponse response;
                if (delegate instanceof StreamingLlmClient s) {
                    // Buffer this attempt's tokens; only replay them on success.
                    response = s.chatStream(messages, tools, options, buffered::add);
                    for (String token : buffered) {
                        listener.onToken(token);
                    }
                } else {
                    response = delegate.chat(messages, tools, options);
                    if (response.content() != null && !response.content().isEmpty()) {
                        listener.onToken(response.content());
                    }
                }
                return response;
            } catch (Exception e) {
                // buffered tokens are dropped here — the next attempt starts clean.
                // Daily/plan quota exhaustion never recovers within a run — fail fast.
                if (LlmException.isQuotaExhausted(e)) {
                    throw e instanceof RuntimeException re ? re : new LlmException(e.getMessage(), e);
                }
                if (!policy.shouldRetry(e, attempt)) {
                    throw e instanceof RuntimeException re ? re : new LlmException(e.getMessage(), e);
                }
                sleepBeforeRetry(e, attempt);
            }
        }
    }

    private void sleepBeforeRetry(Exception failure, int attempt) {
        Duration wait = policy.backoffForAttempt(attempt + 1);
        if (failure instanceof LlmException le && le.retryAfterSeconds() >= 0) {
            // Cap the provider's hint: without this, a single absurd
            // Retry-After (hours, years) would wedge the run in sleep.
            long hintedSecs = Math.min(le.retryAfterSeconds(), MAX_HINTED_WAIT_SECONDS);
            Duration hinted = Duration.ofSeconds(hintedSecs);
            if (hinted.compareTo(wait) > 0) {
                wait = hinted;
            }
        }
        try {
            sleeper.sleep(wait.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmException("Retry interrupted", ie);
        }
    }
}

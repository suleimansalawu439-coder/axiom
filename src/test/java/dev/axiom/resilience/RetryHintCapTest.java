package dev.axiom.resilience;

import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.LlmException;
import dev.axiom.tools.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the chaos-found bug: {@link RetryingLlmClient} used to
 * honor a provider's {@code Retry-After} hint <b>uncapped</b>, so a hostile
 * or buggy provider (or a misbehaving custom {@link LlmClient}) could park
 * the retry loop in sleep effectively forever — a hang with no diagnostic.
 * The hint is now capped at ten minutes.
 *
 * <p>Uses the injectable {@link RetryingLlmClient.Sleeper} seam: no real
 * waiting, fully deterministic.
 */
class RetryHintCapTest {

    /** Fails once with a 429 carrying the given Retry-After, then succeeds. */
    static final class FlakyHostileLlm implements LlmClient {
        private final long retryAfterSeconds;
        private final AtomicInteger calls = new AtomicInteger();

        FlakyHostileLlm(long retryAfterSeconds) {
            this.retryAfterSeconds = retryAfterSeconds;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 LlmOptions options) {
            if (calls.getAndIncrement() == 0) {
                // Deliberately NOT quota-exhaustion phrasing: this is a
                // transient 429 the policy will retry.
                throw new LlmException("HTTP 429: slow down", 429, retryAfterSeconds);
            }
            return new ChatResponse("recovered", List.of(),
                new ChatResponse.TokenUsage(1, 1, 2));
        }

        @Override
        public String model() {
            return "flaky-hostile";
        }
    }

    private RetryingLlmClient clientWith(LlmClient delegate, AtomicLong slept) {
        return new RetryingLlmClient(delegate,
            RetryPolicy.builder()
                .maxAttempts(2)
                .initialBackoff(Duration.ofMillis(1))
                .jitter(0)
                .build(),
            slept::set);
    }

    @Test
    @Timeout(30)
    void absurdRetryAfterHintIsCappedAtTenMinutes() {
        var slept = new AtomicLong(-1);
        var client = clientWith(new FlakyHostileLlm(99_999_999L), slept);

        ChatResponse r = client.chat(List.of(), List.of(), LlmClient.LlmOptions.defaults());

        assertEquals("recovered", r.content(), "the retry must still happen and succeed");
        assertEquals(600_000L, slept.get(),
            "a 99,999,999s hint must be capped at 10 minutes, not slept literally");
    }

    @Test
    @Timeout(30)
    void saneRetryAfterHintIsStillHonored() {
        var slept = new AtomicLong(-1);
        var client = clientWith(new FlakyHostileLlm(5L), slept);

        ChatResponse r = client.chat(List.of(), List.of(), LlmClient.LlmOptions.defaults());

        assertEquals("recovered", r.content());
        assertEquals(5_000L, slept.get(),
            "a sane 5s hint (longer than the 2ms policy backoff) must be honored");
    }

    @Test
    @Timeout(30)
    void missingHintFallsBackToPolicyBackoff() {
        var slept = new AtomicLong(-1);
        var calls = new AtomicInteger();
        LlmClient noHint = new LlmClient() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                     LlmOptions options) {
                if (calls.getAndIncrement() == 0) {
                    // NO_STATUS 429-ish message: retryable, but no hint.
                    throw new LlmException("HTTP 429: slow down");
                }
                return new ChatResponse("recovered", List.of(),
                    new ChatResponse.TokenUsage(1, 1, 2));
            }

            @Override
            public String model() {
                return "no-hint";
            }
        };
        var client = new RetryingLlmClient(noHint,
            RetryPolicy.builder()
                .maxAttempts(2)
                .initialBackoff(Duration.ofMillis(50))
                .jitter(0)
                .build(),
            slept::set);

        ChatResponse r = client.chat(List.of(), List.of(), LlmClient.LlmOptions.defaults());

        assertEquals("recovered", r.content());
        // Policy backoff before attempt 2: 50ms * 2^1 = 100ms, no hint to override it.
        assertEquals(100L, slept.get(),
            "with no provider hint the policy backoff must apply, got " + slept.get());
    }
}

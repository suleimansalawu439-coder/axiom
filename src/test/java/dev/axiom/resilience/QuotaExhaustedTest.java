package dev.axiom.resilience;

import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.LlmException;
import dev.axiom.llm.StreamingLlmClient;
import dev.axiom.tools.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Daily/plan quota exhaustion must fail fast: no retries, no backoff sleep.
 * A quota that refills in hours is not a transient 429.
 */
class QuotaExhaustedTest {

    private static final String GOOGLE_DAILY_QUOTA_MSG =
        "LLM streaming request failed with HTTP 429: [{\n  \"error\": {\n"
            + "    \"code\": 429,\n"
            + "    \"message\": \"You exceeded your current quota, please check your plan and billing details. "
            + "For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. "
            + "\\n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20, model: gemini-3.8-flash\\n"
            + "Please retry in 39.549806969s.\",\n"
            + "    \"status\": \"RESOURCE_EXHAUSTED\",\n  }\n}]";

    @Test
    void classifierRecognizesDailyQuota429() {
        assertTrue(new LlmException(GOOGLE_DAILY_QUOTA_MSG, 429).isQuotaExhausted());
    }

    @Test
    void classifierIgnoresTransient429() {
        assertFalse(new LlmException("Rate limit exceeded, retry in 30s", 429).isQuotaExhausted());
    }

    @Test
    void classifierIgnoresNon429() {
        assertFalse(new LlmException("You exceeded your current quota, please check your plan and billing details.", 400)
            .isQuotaExhausted());
        assertFalse(new LlmException("boom").isQuotaExhausted());
    }

    @Test
    void classifierWalksCauseChain() {
        RuntimeException wrapped = new RuntimeException("wrapper",
            new LlmException(GOOGLE_DAILY_QUOTA_MSG, 429));
        assertTrue(LlmException.isQuotaExhausted(wrapped));
        assertFalse(LlmException.isQuotaExhausted(new RuntimeException("nope")));
        assertFalse(LlmException.isQuotaExhausted(null));
    }

    /** Always fails with the daily-quota 429, counting attempts. */
    static class QuotaBomb implements StreamingLlmClient {
        final AtomicInteger attempts = new AtomicInteger();

        @Override
        public ChatResponse chatStream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                       LlmOptions options, TokenListener listener) {
            attempts.incrementAndGet();
            throw new LlmException(GOOGLE_DAILY_QUOTA_MSG, 429, 39);
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 LlmOptions options) {
            attempts.incrementAndGet();
            throw new LlmException(GOOGLE_DAILY_QUOTA_MSG, 429, 39);
        }

        @Override
        public String model() { return "quota-bomb"; }
    }

    @Test
    void retryingClientDoesNotRetryQuotaExhaustion() {
        QuotaBomb bomb = new QuotaBomb();
        var client = new RetryingLlmClient(bomb, RetryPolicy.builder()
            .maxAttempts(6).initialBackoff(Duration.ofMillis(50)).build());
        long start = System.nanoTime();
        assertThrows(LlmException.class, () -> client.chatStream(
            List.of(), List.of(), LlmClient.LlmOptions.defaults(), t -> {}));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertEquals(1, bomb.attempts.get(), "quota exhaustion must not be retried");
        assertTrue(elapsedMs < 2000, "must fail fast without backoff sleep, took " + elapsedMs + "ms");
    }

    @Test
    void nonStreamingChatAlsoFailsFast() {
        QuotaBomb bomb = new QuotaBomb();
        var client = new RetryingLlmClient(bomb, RetryPolicy.builder()
            .maxAttempts(6).initialBackoff(Duration.ofMillis(50)).build());
        assertThrows(LlmException.class, () -> client.chat(
            List.of(), List.of(), LlmClient.LlmOptions.defaults()));
        assertEquals(1, bomb.attempts.get());
    }
}

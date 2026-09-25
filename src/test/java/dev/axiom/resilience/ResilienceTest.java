package dev.axiom.resilience;

import dev.axiom.llm.*;
import dev.axiom.tools.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResilienceTest {

    // ---------- RetryPolicy ----------

    @Test
    void backoffGrowsExponentially() {
        var p = RetryPolicy.builder()
            .initialBackoff(Duration.ofMillis(1000))
            .multiplier(2.0)
            .jitter(0)
            .build();
        assertEquals(Duration.ofMillis(1000), p.baseBackoffForAttempt(1));
        assertEquals(Duration.ofMillis(2000), p.baseBackoffForAttempt(2));
        assertEquals(Duration.ofMillis(4000), p.baseBackoffForAttempt(3));
    }

    @Test
    void jitterStaysWithinBounds() {
        var p = RetryPolicy.builder()
            .initialBackoff(Duration.ofMillis(1000))
            .multiplier(1.0)
            .jitter(0.2)
            .build();
        for (int i = 0; i < 200; i++) {
            long ms = p.backoffForAttempt(1).toMillis();
            assertTrue(ms >= 800 && ms <= 1200, "jitter out of bounds: " + ms);
        }
    }

    @Test
    void respectsMaxAttempts() {
        var p = RetryPolicy.builder().maxAttempts(3).build();
        var e = new LlmException("HTTP 503 overloaded");
        assertTrue(p.shouldRetry(e, 1));
        assertTrue(p.shouldRetry(e, 2));
        assertFalse(p.shouldRetry(e, 3));
    }

    @Test
    void clientErrorsAreNotRetried() {
        var p = RetryPolicy.defaults();
        assertFalse(p.shouldRetry(new LlmException("LLM request failed with HTTP 400: bad request"), 1));
        assertFalse(p.shouldRetry(new LlmException("LLM request failed with HTTP 429: slow down"), 1));
        assertTrue(p.shouldRetry(new LlmException("LLM request failed with HTTP 500: boom"), 1));
        assertTrue(p.shouldRetry(new LlmException("LLM request failed: connection reset"), 1));
    }

    @Test
    void invalidPolicyRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> RetryPolicy.builder().maxAttempts(0).build());
        assertThrows(IllegalArgumentException.class,
            () -> RetryPolicy.builder().jitter(1.5).build());
    }

    // ---------- RetryingLlmClient ----------

    static class FlakyLlm implements LlmClient {
        private final Deque<Object> script = new ArrayDeque<>();
        int calls;

        FlakyLlm enqueue(Object o) { script.add(o); return this; }

        @Override
        public ChatResponse chat(List<ChatMessage> m, List<ToolDefinition> t, LlmOptions o) {
            calls++;
            Object next = script.poll();
            if (next instanceof RuntimeException e) throw e;
            return (ChatResponse) next;
        }

        @Override
        public String model() { return "flaky"; }
    }

    private static ChatResponse ok() {
        return new ChatResponse("done", List.of(), new ChatResponse.TokenUsage(1, 1, 2));
    }

    @Test
    void retriesTransientFailuresThenSucceeds() {
        var sleeps = new java.util.ArrayList<Long>();
        var flaky = new FlakyLlm()
            .enqueue(new LlmException("HTTP 503 overloaded"))
            .enqueue(new LlmException("HTTP 500 boom"))
            .enqueue(ok());
        var client = new RetryingLlmClient(flaky, RetryPolicy.defaults(),
            millis -> sleeps.add(millis));

        ChatResponse r = client.chat(List.of(), List.of(), LlmClient.LlmOptions.defaults());

        assertEquals("done", r.content());
        assertEquals(3, flaky.calls);
        assertEquals(2, sleeps.size());
        assertTrue(sleeps.get(1) > sleeps.get(0), "backoff should grow");
    }

    @Test
    void givesUpAfterMaxAttempts() {
        var flaky = new FlakyLlm()
            .enqueue(new LlmException("HTTP 503 x"))
            .enqueue(new LlmException("HTTP 503 x"))
            .enqueue(new LlmException("HTTP 503 x"))
            .enqueue(new LlmException("HTTP 503 x"));
        var client = new RetryingLlmClient(flaky,
            RetryPolicy.builder().maxAttempts(3).jitter(0).build(), m -> {});

        assertThrows(LlmException.class,
            () -> client.chat(List.of(), List.of(), LlmClient.LlmOptions.defaults()));
        assertEquals(3, flaky.calls);
    }

    @Test
    void doesNotRetryClientErrors() {
        var flaky = new FlakyLlm()
            .enqueue(new LlmException("LLM request failed with HTTP 400: bad request"));
        var client = new RetryingLlmClient(flaky, RetryPolicy.defaults(), m -> {});

        assertThrows(LlmException.class,
            () -> client.chat(List.of(), List.of(), LlmClient.LlmOptions.defaults()));
        assertEquals(1, flaky.calls);
    }

    @Test
    void delegatesModelName() {
        var client = new RetryingLlmClient(new FlakyLlm(), RetryPolicy.defaults(), m -> {});
        assertEquals("flaky", client.model());
    }
}

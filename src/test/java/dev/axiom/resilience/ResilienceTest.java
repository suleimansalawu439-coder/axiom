package dev.axiom.resilience;

import dev.axiom.llm.*;
import dev.axiom.tools.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
    void only429AmongClientErrorsIsRetried() {
        var p = RetryPolicy.defaults();
        // Message-only legacy form …
        assertFalse(p.shouldRetry(new LlmException("LLM request failed with HTTP 400: bad request"), 1));
        assertTrue(p.shouldRetry(new LlmException("LLM request failed with HTTP 429: slow down"), 1));
        assertTrue(p.shouldRetry(new LlmException("LLM request failed with HTTP 500: boom"), 1));
        // … and status-code form.
        assertFalse(p.shouldRetry(new LlmException("bad request", 400, -1), 1));
        assertFalse(p.shouldRetry(new LlmException("unauthorized", 401, -1), 1));
        assertTrue(p.shouldRetry(new LlmException("rate limited", 429, -1), 1));
        assertTrue(p.shouldRetry(new LlmException("server error", 500, -1), 1));
        assertTrue(p.shouldRetry(new LlmException("overloaded", 503, -1), 1));
        assertFalse(p.shouldRetry(new LlmException("not implemented", 501, -1), 1));
        assertTrue(p.shouldRetry(new LlmException("connection reset"), 1));
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

    // ---------- Streaming retry ----------

    /** Fake streaming delegate: emits partial tokens, then either fails or streams a script. */
    static class FlakyStreamingLlm implements StreamingLlmClient {
        private final Deque<Object> script = new ArrayDeque<>();
        int calls;

        FlakyStreamingLlm enqueue(Object o) { script.add(o); return this; }

        @Override
        @SuppressWarnings("unchecked")
        public ChatResponse chatStream(List<ChatMessage> m, List<ToolDefinition> t,
                                       LlmOptions o, TokenListener l) {
            calls++;
            Object next = script.poll();
            if (next instanceof RuntimeException e) {
                // Simulate a stream that dies mid-response.
                l.onToken("partial-1");
                l.onToken("partial-2");
                throw e;
            }
            List<String> tokens = (List<String>) next;
            for (String tok : tokens) l.onToken(tok);
            return new ChatResponse(String.join("", tokens), List.of(),
                new ChatResponse.TokenUsage(1, 1, 2));
        }

        @Override
        public ChatResponse chat(List<ChatMessage> m, List<ToolDefinition> t, LlmOptions o) {
            throw new UnsupportedOperationException("streaming fake");
        }

        @Override
        public String model() { return "flaky-stream"; }
    }

    @Test
    void streamingRetryDiscardsPartialTokensAndDeliversOneFullSequence() {
        var seen = new ArrayList<String>();
        var flaky = new FlakyStreamingLlm()
            .enqueue(new LlmException("HTTP 503 overloaded"))
            .enqueue(List.of("x", "y"));
        var client = new RetryingLlmClient(flaky, RetryPolicy.defaults(), m -> {});

        ChatResponse r = client.chatStream(
            List.of(), List.of(), LlmClient.LlmOptions.defaults(), seen::add);

        assertEquals("xy", r.content());
        assertEquals(2, flaky.calls);
        assertEquals(List.of("x", "y"), seen,
            "listener must see exactly the successful attempt's tokens");
    }

    @Test
    void streamingRetryGivesUpWithoutLeakingPartialTokens() {
        var seen = new ArrayList<String>();
        var flaky = new FlakyStreamingLlm()
            .enqueue(new LlmException("HTTP 503 x"))
            .enqueue(new LlmException("HTTP 503 x"));
        var client = new RetryingLlmClient(flaky,
            RetryPolicy.builder().maxAttempts(2).jitter(0).build(), m -> {});

        assertThrows(LlmException.class, () -> client.chatStream(
            List.of(), List.of(), LlmClient.LlmOptions.defaults(), seen::add));
        assertEquals(2, flaky.calls);
        assertTrue(seen.isEmpty(), "failed attempts must not leak partial tokens");
    }

    @Test
    void streamingRetriesNonStreamingDelegate() {
        var seen = new ArrayList<String>();
        var flaky = new FlakyLlm()
            .enqueue(new LlmException("HTTP 500 boom"))
            .enqueue(ok());
        var client = new RetryingLlmClient(flaky, RetryPolicy.defaults(), m -> {});

        ChatResponse r = client.chatStream(
            List.of(), List.of(), LlmClient.LlmOptions.defaults(), seen::add);

        assertEquals("done", r.content());
        assertEquals(2, flaky.calls);
        assertEquals(List.of("done"), seen,
            "non-streaming delegate delivers content as a single token");
    }

    // ---------- 429 + Retry-After ----------

    @Test
    void rateLimited429IsRetried() {
        var flaky = new FlakyLlm()
            .enqueue(new LlmException("rate limited", 429, -1))
            .enqueue(ok());
        var client = new RetryingLlmClient(flaky, RetryPolicy.defaults(), m -> {});

        ChatResponse r = client.chat(List.of(), List.of(), LlmClient.LlmOptions.defaults());

        assertEquals("done", r.content());
        assertEquals(2, flaky.calls);
    }

    @Test
    void retryAfterHintIsHonoredOverPolicyBackoff() {
        var sleeps = new ArrayList<Long>();
        var flaky = new FlakyLlm()
            .enqueue(new LlmException("HTTP 429 slow down", 429, 5))
            .enqueue(ok());
        var client = new RetryingLlmClient(flaky,
            RetryPolicy.builder().initialBackoff(Duration.ofMillis(10)).jitter(0).build(),
            sleeps::add);

        client.chat(List.of(), List.of(), LlmClient.LlmOptions.defaults());

        assertEquals(1, sleeps.size());
        assertTrue(sleeps.get(0) >= 5000,
            "must wait the Retry-After delay, waited " + sleeps.get(0));
    }

    @Test
    void policyBackoffWinsWhenRetryAfterIsShorter() {
        var sleeps = new ArrayList<Long>();
        var flaky = new FlakyLlm()
            .enqueue(new LlmException("HTTP 429 slow down", 429, 1))
            .enqueue(ok());
        var client = new RetryingLlmClient(flaky,
            RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(2000)).multiplier(1.0).jitter(0).build(),
            sleeps::add);

        client.chat(List.of(), List.of(), LlmClient.LlmOptions.defaults());

        assertEquals(1, sleeps.size());
        assertEquals(2000, sleeps.get(0),
            "policy backoff is the floor; a shorter hint must not shorten it");
    }

    @Test
    void retryAfterHeaderParsing() {
        assertEquals(30, OpenAiCompatibleClient.parseRetryAfterSeconds("30"));
        assertEquals(0, OpenAiCompatibleClient.parseRetryAfterSeconds("0"));
        assertEquals(600, OpenAiCompatibleClient.parseRetryAfterSeconds("99999"),
            "absurd delays are clamped");
        assertEquals(0, OpenAiCompatibleClient.parseRetryAfterSeconds(
            "Wed, 21 Oct 2015 07:28:00 GMT"), "past HTTP-date means no wait");
        assertEquals(LlmException.NO_STATUS,
            OpenAiCompatibleClient.parseRetryAfterSeconds("garbage"));
        assertEquals(LlmException.NO_STATUS,
            OpenAiCompatibleClient.parseRetryAfterSeconds(null));
    }
}

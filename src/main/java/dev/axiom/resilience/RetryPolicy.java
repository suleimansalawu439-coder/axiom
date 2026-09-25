package dev.axiom.resilience;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Retry policy for transient failures (rate limits, 5xx, network blips).
 * Exponential backoff with jitter so a fleet of agents doesn't retry in
 * lockstep and amplify an outage.
 */
public final class RetryPolicy {
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final double multiplier;
    private final double jitter;
    private final Predicate<Exception> retryable;

    private RetryPolicy(Builder b) {
        this.maxAttempts = b.maxAttempts;
        this.initialBackoff = b.initialBackoff;
        this.multiplier = b.multiplier;
        this.jitter = b.jitter;
        this.retryable = b.retryable;
    }

    public int maxAttempts() { return maxAttempts; }

    /** True when the failure should be retried on the given attempt (1-based). */
    public boolean shouldRetry(Exception e, int attempt) {
        return attempt < maxAttempts && retryable.test(e);
    }

    /** Backoff before the given attempt (1-based), with ±jitter applied. */
    public Duration backoffForAttempt(int attempt) {
        double base = initialBackoff.toMillis() * Math.pow(multiplier, attempt - 1);
        double factor = 1.0 + (Math.random() * 2 - 1) * jitter;
        return Duration.ofMillis(Math.max(0, (long) (base * factor)));
    }

    /** Deterministic backoff without jitter — for tests and scheduling. */
    public Duration baseBackoffForAttempt(int attempt) {
        return Duration.ofMillis(
            (long) (initialBackoff.toMillis() * Math.pow(multiplier, attempt - 1)));
    }

    public static Builder builder() { return new Builder(); }

    /** Sensible default: 4 attempts, 1s → 2s → 4s backoff, 20% jitter. */
    public static RetryPolicy defaults() {
        return builder().build();
    }

    public static final class Builder {
        private int maxAttempts = 4;
        private Duration initialBackoff = Duration.ofSeconds(1);
        private double multiplier = 2.0;
        private double jitter = 0.2;
        private Predicate<Exception> retryable = RetryPolicy::defaultRetryable;

        public Builder maxAttempts(int n) {
            if (n < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
            this.maxAttempts = n; return this;
        }
        public Builder initialBackoff(Duration d) {
            this.initialBackoff = Objects.requireNonNull(d); return this;
        }
        public Builder multiplier(double m) {
            if (m < 1) throw new IllegalArgumentException("multiplier must be >= 1");
            this.multiplier = m; return this;
        }
        /** Jitter fraction in [0, 1]; 0 disables jitter. */
        public Builder jitter(double j) {
            if (j < 0 || j > 1) throw new IllegalArgumentException("jitter must be in [0,1]");
            this.jitter = j; return this;
        }
        public Builder retryable(Predicate<Exception> p) {
            this.retryable = Objects.requireNonNull(p); return this;
        }
        public RetryPolicy build() { return new RetryPolicy(this); }
    }

    /**
     * Default predicate: retry {@link dev.axiom.llm.LlmException}s except
     * client errors (HTTP 4xx — retrying those never helps). Everything else
     * (network failures wrapped by the client) is retried.
     */
    static boolean defaultRetryable(Exception e) {
        String msg = e.getMessage();
        if (msg != null && msg.matches("(?s).*HTTP 4\\d\\d.*")) return false;
        return e instanceof dev.axiom.llm.LlmException;
    }
}

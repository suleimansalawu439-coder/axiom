package dev.axiom.bench;

/**
 * How a benchmark run executes: task parallelism, request pacing, and
 * quota-exhaustion policy.
 *
 * @param parallelism     tasks running concurrently (1 = sequential)
 * @param rateLimiter     shared token bucket for LLM calls, or null for
 *                        unlimited (fixture runs, local models)
 * @param failFastOnQuota when a task dies to daily/plan quota exhaustion,
 *                        abort the remaining tasks immediately instead of
 *                        letting each one fail the same way
 */
public record BenchRunConfig(int parallelism, RateLimiter rateLimiter,
                             boolean failFastOnQuota) {

    public BenchRunConfig {
        if (parallelism < 1) throw new IllegalArgumentException("parallelism < 1");
    }

    /** Legacy behavior: one task at a time, abort fast on daily quota. */
    public static BenchRunConfig sequential() {
        return new BenchRunConfig(1, null, true);
    }

    /** Parallel tasks sharing one rate limiter. */
    public static BenchRunConfig parallel(int parallelism, RateLimiter rateLimiter) {
        return new BenchRunConfig(parallelism, rateLimiter, true);
    }
}

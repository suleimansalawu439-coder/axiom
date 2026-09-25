package dev.axiom.bench;

/**
 * Token-bucket rate limiter for benchmark LLM traffic.
 *
 * <p>A fixed sleep between tasks paces the <em>start</em> of tasks but does
 * nothing about bursts <em>within</em> a task (an agent turn can fire
 * several LLM calls back-to-back). A shared bucket smooths every call —
 * across turns and, in parallel runs, across tasks — to a configured
 * requests-per-minute, which is what free-tier limits actually constrain.
 *
 * <p>Thread-safe. {@link #acquire()} blocks until a permit is available.
 */
public final class RateLimiter {
    private final double permitsPerNanos;
    private final double maxStored;
    private double stored;
    private long lastRefillNanos;

    /**
     * @param permitsPerMinute sustained rate; {@code <= 0} means unlimited
     *                         (acquire returns immediately)
     * @param maxBurst         maximum permits that can accumulate, i.e. the
     *                         allowed burst size
     */
    public RateLimiter(double permitsPerMinute, double maxBurst) {
        if (permitsPerMinute <= 0) {
            this.permitsPerNanos = 0;
            this.maxStored = Double.MAX_VALUE;
        } else {
            this.permitsPerNanos = permitsPerMinute / 60.0 / 1_000_000_000.0;
            this.maxStored = Math.max(1.0, maxBurst);
        }
        this.stored = this.maxStored;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * @param permitsPerMinute sustained rate; burst defaults to one second's
     *                         worth of permits (minimum 1)
     */
    public RateLimiter(double permitsPerMinute) {
        this(permitsPerMinute, Math.max(1.0, permitsPerMinute / 60.0));
    }

    /** True when no limiting is applied. */
    public boolean isUnlimited() {
        return permitsPerNanos == 0;
    }

    /** Blocks until one permit is available, then consumes it. */
    public void acquire() {
        if (isUnlimited()) return;
        synchronized (this) {
            refill();
            while (stored < 1.0) {
                long waitNanos = (long) ((1.0 - stored) / permitsPerNanos) + 1_000_000L;
                try {
                    this.wait(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BenchException("Rate limiter wait interrupted", ie);
                }
                refill();
            }
            stored -= 1.0;
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double added = (now - lastRefillNanos) * permitsPerNanos;
        if (added > 0) {
            stored = Math.min(maxStored, stored + added);
            lastRefillNanos = now;
        }
    }
}

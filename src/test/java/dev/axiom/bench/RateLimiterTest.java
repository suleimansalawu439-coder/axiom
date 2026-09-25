package dev.axiom.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Token-bucket behavior: bursts allowed, sustained rate enforced. */
class RateLimiterTest {

    @Test
    void unlimitedNeverBlocks() {
        RateLimiter limiter = new RateLimiter(0);
        assertTrue(limiter.isUnlimited());
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) limiter.acquire();
        assertTrue((System.nanoTime() - start) < 500_000_000L, "unlimited acquire must be instant");
    }

    @Test
    void burstThenThrottledToConfiguredRate() {
        // 120/min = 2/sec, burst of 1: three acquires need ~1s of refill.
        RateLimiter limiter = new RateLimiter(120, 1);
        assertFalse(limiter.isUnlimited());
        long start = System.nanoTime();
        limiter.acquire(); // burst
        limiter.acquire(); // waits ~500ms
        limiter.acquire(); // waits ~500ms
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs >= 900, "expected ~1000ms of throttling, got " + elapsedMs);
        assertTrue(elapsedMs < 4000, "throttling took too long: " + elapsedMs);
    }

    @Test
    void firstBurstIsImmediate() {
        // Burst of 3 at 60/min: first three acquires are instant, fourth waits ~1s.
        RateLimiter limiter = new RateLimiter(60, 3);
        long start = System.nanoTime();
        limiter.acquire();
        limiter.acquire();
        limiter.acquire();
        long burstMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(burstMs < 500, "burst should be immediate, took " + burstMs + "ms");
    }

    @Test
    void invalidParallelismRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BenchRunConfig(0, null, true));
    }
}

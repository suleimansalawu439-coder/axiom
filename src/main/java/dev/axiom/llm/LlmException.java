package dev.axiom.llm;

/**
 * Raised when the LLM provider call fails.
 *
 * <p>Carries the HTTP status code (when the failure came from an HTTP
 * response) and the provider's {@code Retry-After} hint in seconds (when the
 * provider sent one), so retry policies can distinguish "slow down" (429)
 * from "bad request" (400) without parsing message strings.
 */
public class LlmException extends RuntimeException {
    /** Sentinel for "no status code" — the failure wasn't an HTTP response. */
    public static final int NO_STATUS = -1;

    private final int statusCode;
    private final long retryAfterSeconds;

    public LlmException(String message) {
        this(message, NO_STATUS, NO_STATUS);
    }

    public LlmException(String message, Throwable cause) {
        this(message, NO_STATUS, NO_STATUS, cause);
    }

    public LlmException(String message, int statusCode) {
        this(message, statusCode, NO_STATUS);
    }

    public LlmException(String message, int statusCode, long retryAfterSeconds) {
        this(message, statusCode, retryAfterSeconds, null);
    }

    public LlmException(String message, int statusCode, long retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * The HTTP status code from the provider, or {@link #NO_STATUS} when the
     * failure wasn't an HTTP response (network failure, timeout, …).
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * The provider's {@code Retry-After} hint in seconds, or
     * {@link #NO_STATUS} when the provider didn't send one.
     */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * Heuristic: true when this looks like a <em>daily or plan</em> quota
     * exhaustion rather than a transient per-minute rate limit. Retrying a
     * daily quota is futile — the bucket refills in hours, not seconds —
     * so callers use this to fail fast instead of burning minutes on
     * doomed retries.
     *
     * <p>Matches on provider phrasing (e.g. Google's "check your plan and
     * billing details" / "Quota exceeded for metric"), so it is deliberately
     * conservative: an unrecognized 429 is treated as transient.
     */
    public boolean isQuotaExhausted() {
        if (statusCode != 429) return false;
        String m = String.valueOf(getMessage()).toLowerCase(java.util.Locale.ROOT);
        return m.contains("check your plan and billing")
            || m.contains("quota exceeded");
    }

    /**
     * Walks the causal chain for a quota-exhaustion failure (see
     * {@link #isQuotaExhausted()}), since providers' errors are sometimes
     * wrapped by intermediate layers.
     */
    public static boolean isQuotaExhausted(Throwable t) {
        while (t != null) {
            if (t instanceof LlmException le && le.isQuotaExhausted()) return true;
            t = t.getCause();
        }
        return false;
    }
}

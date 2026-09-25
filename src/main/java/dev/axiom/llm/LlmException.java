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
}

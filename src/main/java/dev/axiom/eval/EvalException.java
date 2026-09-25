package dev.axiom.eval;

/** Unchecked wrapper for eval harness failures (I/O, malformed reports). */
public class EvalException extends RuntimeException {
    public EvalException(String message) {
        super(message);
    }

    public EvalException(String message, Throwable cause) {
        super(message, cause);
    }
}

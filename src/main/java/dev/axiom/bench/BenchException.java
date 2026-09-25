package dev.axiom.bench;

/** Unchecked wrapper for benchmark harness failures. */
public class BenchException extends RuntimeException {
    public BenchException(String message) {
        super(message);
    }

    public BenchException(String message, Throwable cause) {
        super(message, cause);
    }
}

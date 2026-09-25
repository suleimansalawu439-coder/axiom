package dev.axiom.durable;

/** Unchecked wrapper for journal I/O failures. */
public class DurableException extends RuntimeException {
    public DurableException(String message) {
        super(message);
    }

    public DurableException(String message, Throwable cause) {
        super(message, cause);
    }
}

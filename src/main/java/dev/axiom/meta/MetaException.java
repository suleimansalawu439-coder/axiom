package dev.axiom.meta;

/** Unchecked failure inside the {@code dev.axiom.meta} package. */
public final class MetaException extends RuntimeException {
    public MetaException(String message) { super(message); }
    public MetaException(String message, Throwable cause) { super(message, cause); }
}

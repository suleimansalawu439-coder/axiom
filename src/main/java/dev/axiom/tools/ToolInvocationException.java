package dev.axiom.tools;

/** Raised when a tool cannot be invoked or fails during execution. */
public class ToolInvocationException extends RuntimeException {
    public ToolInvocationException(String message) { super(message); }
    public ToolInvocationException(String message, Throwable cause) { super(message, cause); }
}

package dev.axiom.mcp;

/** Failure of an MCP operation: transport, protocol, or server-side error. */
public class McpException extends RuntimeException {
    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}

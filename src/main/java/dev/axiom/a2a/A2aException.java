package dev.axiom.a2a;

/** Unchecked failure of an A2A call: transport errors and JSON-RPC errors. */
public class A2aException extends RuntimeException {
    /** JSON-RPC error code, or -1 for transport failures. */
    private final int code;

    public A2aException(String message) {
        super(message);
        this.code = -1;
    }

    public A2aException(int code, String message) {
        super("A2A error %d: %s".formatted(code, message));
        this.code = code;
    }

    public A2aException(String message, Throwable cause) {
        super(message, cause);
        this.code = -1;
    }

    public int code() {
        return code;
    }
}

package dev.axiom.output;

/** Raised when the model's structured output cannot be parsed into the target type. */
public class StructuredOutputException extends RuntimeException {
    private final String rawOutput;

    public StructuredOutputException(String message, String rawOutput, Throwable cause) {
        super(message, cause);
        this.rawOutput = rawOutput;
    }

    /** The raw text the model produced, for debugging. */
    public String rawOutput() { return rawOutput; }
}

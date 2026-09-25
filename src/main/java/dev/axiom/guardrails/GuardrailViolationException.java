package dev.axiom.guardrails;

/** Thrown when a guardrail blocks the task or the agent's final answer. */
public final class GuardrailViolationException extends RuntimeException {
    private final String guardrailName;

    public GuardrailViolationException(String guardrailName, String reason) {
        super("Guardrail '%s' blocked the run: %s".formatted(guardrailName, reason));
        this.guardrailName = guardrailName;
    }

    public String guardrailName() {
        return guardrailName;
    }
}

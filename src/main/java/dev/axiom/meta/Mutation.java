package dev.axiom.meta;

/**
 * One recorded knob change: which operator made it, which knob moved, the
 * before/after values, and a human-readable rationale. Every mutation the
 * optimizer tries lands in the {@link OptimizationReceipt}, so the whole
 * optimization run is auditable.
 */
public record Mutation(String operator, String knob, String before, String after, String rationale) {
    public Mutation {
        if (operator == null || operator.isBlank()) throw new IllegalArgumentException("operator is required");
        if (knob == null || knob.isBlank()) throw new IllegalArgumentException("knob is required");
    }

    @Override
    public String toString() {
        return "%s: %s %s -> %s (%s)".formatted(operator, knob, before, after, rationale);
    }
}

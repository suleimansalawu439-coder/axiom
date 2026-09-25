package dev.axiom.budget;

import dev.axiom.llm.ChatResponse;

/**
 * Thrown when an agent run breaches its {@link Budget} — too many tokens or
 * too many dollars. Carries the full budget snapshot so callers can report,
 * log, or surface exactly what ran out. This is a hard abort: the run stops
 * instead of silently burning money.
 */
public final class BudgetExceededException extends RuntimeException {
    private final String model;
    private final long tokensUsed;
    private final long maxTokens;
    private final double costUsd;
    private final double maxCostUsd;
    private final boolean tokenLimitBreached;
    private final boolean costLimitBreached;

    public BudgetExceededException(String model, long tokensUsed, long maxTokens,
                                   double costUsd, double maxCostUsd,
                                   boolean tokenLimitBreached, boolean costLimitBreached) {
        super(describe(model, tokensUsed, maxTokens, costUsd, maxCostUsd,
            tokenLimitBreached, costLimitBreached));
        this.model = model;
        this.tokensUsed = tokensUsed;
        this.maxTokens = maxTokens;
        this.costUsd = costUsd;
        this.maxCostUsd = maxCostUsd;
        this.tokenLimitBreached = tokenLimitBreached;
        this.costLimitBreached = costLimitBreached;
    }

    private static String describe(String model, long tokensUsed, long maxTokens,
                                   double costUsd, double maxCostUsd,
                                   boolean tokenLimitBreached, boolean costLimitBreached) {
        StringBuilder sb = new StringBuilder("Budget exceeded for model '").append(model).append("': ");
        if (tokenLimitBreached) {
            sb.append("token limit breached (%d used, max %d)".formatted(tokensUsed, maxTokens));
        }
        if (costLimitBreached) {
            if (tokenLimitBreached) sb.append("; ");
            sb.append("cost limit breached ($%.4f used, max $%.4f)".formatted(costUsd, maxCostUsd));
        }
        return sb.toString();
    }

    public String model() { return model; }
    public long tokensUsed() { return tokensUsed; }
    public long maxTokens() { return maxTokens; }
    public double costUsd() { return costUsd; }
    public double maxCostUsd() { return maxCostUsd; }
    public boolean tokenLimitBreached() { return tokenLimitBreached; }
    public boolean costLimitBreached() { return costLimitBreached; }
}

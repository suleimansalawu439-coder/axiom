package dev.axiom.budget;

import dev.axiom.llm.ChatResponse;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-run token and cost accounting. Attach to an agent via
 * {@code AgentConfig.Builder#withBudget}; the agent charges every LLM call
 * against the budget and aborts with {@link BudgetExceededException} the
 * moment a limit is breached.
 *
 * <pre>{@code
 * Budget budget = Budget.builder()
 *     .maxTokens(50_000)
 *     .maxCostUsd(1.00)
 *     .prices(ModelPrices.defaults())
 *     .build();
 *
 * var agent = Axiom.agent().withModel("gpt-4o")
 *     .withBudget(budget)
 *     .buildAgent();
 * try {
 *     agent.run("Research everything about quantum batteries");
 * } catch (BudgetExceededException e) {
 *     System.out.println("Stopped: " + e.getMessage());
 * }
 * }</pre>
 *
 * <p>Thread-safe: token/cost counters are atomic, so one budget can be shared
 * across a supervisor and its workers to cap a whole team.
 */
public final class Budget {
    /** Effectively unlimited. */
    public static final long UNLIMITED_TOKENS = Long.MAX_VALUE;
    /** Effectively unlimited. */
    public static final double UNLIMITED_COST = Double.MAX_VALUE;

    private final long maxTokens;
    private final double maxCostUsd;
    private final ModelPrices prices;
    private final ModelPrices.Price fallbackPrice;

    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicReference<Double> costUsd = new AtomicReference<>(0.0);

    private Budget(Builder b) {
        this.maxTokens = b.maxTokens;
        this.maxCostUsd = b.maxCostUsd;
        this.prices = b.prices;
        this.fallbackPrice = b.fallbackPrice;
    }

    /**
     * Charge one LLM call against the budget. Emits nothing itself — the
     * agent emits {@code AgentEvent.BudgetUpdated} — but throws
     * {@link BudgetExceededException} when a limit is breached.
     */
    public void charge(String model, ChatResponse.TokenUsage usage) {
        long in = inputTokens.addAndGet(usage.promptTokens());
        long out = outputTokens.addAndGet(usage.completionTokens());
        long total = in + out;
        double cost = costUsd.updateAndGet(c -> c + costOf(model, usage));

        boolean tokensHit = total > maxTokens;
        boolean costHit = cost > maxCostUsd;
        if (tokensHit || costHit) {
            throw new BudgetExceededException(model, total, maxTokens, cost, maxCostUsd,
                tokensHit, costHit);
        }
    }

    private double costOf(String model, ChatResponse.TokenUsage usage) {
        return prices.costUsd(model, usage.promptTokens(), usage.completionTokens())
            .orElseGet(() -> fallbackPrice.costOf(usage.promptTokens(), usage.completionTokens()));
    }

    /** Snapshot of current spend — safe to call any time, including from event listeners. */
    public Snapshot snapshot() {
        long in = inputTokens.get();
        long out = outputTokens.get();
        return new Snapshot(in, out, in + out, costUsd.get(), maxTokens, maxCostUsd);
    }

    public long maxTokens() { return maxTokens; }
    public double maxCostUsd() { return maxCostUsd; }

    /** Point-in-time view of a budget. */
    public record Snapshot(long inputTokens, long outputTokens, long totalTokens,
                           double costUsd, long maxTokens, double maxCostUsd) {
        public double tokensUsedFraction() {
            return maxTokens == UNLIMITED_TOKENS ? 0.0 : Math.min(1.0, totalTokens / (double) maxTokens);
        }

        public double costUsedFraction() {
            return maxCostUsd == UNLIMITED_COST ? 0.0 : Math.min(1.0, costUsd / maxCostUsd);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long maxTokens = UNLIMITED_TOKENS;
        private double maxCostUsd = UNLIMITED_COST;
        private ModelPrices prices = ModelPrices.defaults();
        private ModelPrices.Price fallbackPrice = new ModelPrices.Price(0.002, 0.008);

        /** Hard cap on total (input + output) tokens for the run. */
        public Builder maxTokens(long maxTokens) {
            if (maxTokens < 1) throw new IllegalArgumentException("maxTokens must be >= 1");
            this.maxTokens = maxTokens;
            return this;
        }

        /** Hard cap on USD spend for the run, priced via {@link #prices}. */
        public Builder maxCostUsd(double maxCostUsd) {
            if (maxCostUsd < 0) throw new IllegalArgumentException("maxCostUsd must be >= 0");
            this.maxCostUsd = maxCostUsd;
            return this;
        }

        public Builder prices(ModelPrices prices) {
            this.prices = prices;
            return this;
        }

        /** Price used when a model isn't in the table. Defaults to gpt-4.1-class pricing. */
        public Builder fallbackPrice(ModelPrices.Price fallbackPrice) {
            this.fallbackPrice = fallbackPrice;
            return this;
        }

        public Budget build() {
            return new Budget(this);
        }
    }
}

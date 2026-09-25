package dev.axiom.budget;

import java.util.Map;
import java.util.Optional;

/**
 * Per-model price table (USD per 1K tokens), used to turn token usage into
 * dollars for {@link Budget}. Prices are snapshots of public provider pricing
 * and go stale — override with {@link #withPrice} for private deployments,
 * negotiated rates, or newer models.
 */
public final class ModelPrices {
    /** USD per 1K input tokens and per 1K output tokens. */
    public record Price(double inputPer1k, double outputPer1k) {
        public Price {
            if (inputPer1k < 0 || outputPer1k < 0)
                throw new IllegalArgumentException("Prices must be non-negative");
        }

        /** Cost of one call with the given token counts. */
        public double costOf(long inputTokens, long outputTokens) {
            return inputTokens / 1000.0 * inputPer1k + outputTokens / 1000.0 * outputPer1k;
        }
    }

    private final Map<String, Price> table;

    private ModelPrices(Map<String, Price> table) {
        this.table = Map.copyOf(table);
    }

    /** The bundled snapshot of public pricing. */
    public static ModelPrices defaults() {
        return new ModelPrices(Map.ofEntries(
            // OpenAI
            Map.entry("gpt-4o", new Price(0.0025, 0.01)),
            Map.entry("gpt-4o-mini", new Price(0.00015, 0.0006)),
            Map.entry("gpt-4.1", new Price(0.002, 0.008)),
            Map.entry("gpt-4.1-mini", new Price(0.0004, 0.0016)),
            Map.entry("o1", new Price(0.015, 0.06)),
            Map.entry("o3-mini", new Price(0.0011, 0.0044)),
            // Anthropic
            Map.entry("claude-sonnet-4-5", new Price(0.003, 0.015)),
            Map.entry("claude-opus-4-1", new Price(0.015, 0.075)),
            Map.entry("claude-haiku-4-5", new Price(0.001, 0.005)),
            // xAI / Google / DeepSeek (OpenAI-compatible endpoints)
            Map.entry("grok-4", new Price(0.003, 0.015)),
            Map.entry("gemini-2.5-pro", new Price(0.00125, 0.01)),
            Map.entry("gemini-2.5-flash", new Price(0.0003, 0.0025)),
            Map.entry("deepseek-chat", new Price(0.00027, 0.0011)),
            Map.entry("deepseek-reasoner", new Price(0.00055, 0.00219))
        ));
    }

    /** Look up a model; matches on prefix so dated snapshots like
     * {@code "gpt-4o-2024-08-06"} resolve to {@code "gpt-4o"}. */
    public Optional<Price> lookup(String model) {
        if (model == null) return Optional.empty();
        Price exact = table.get(model);
        if (exact != null) return Optional.of(exact);
        String lower = model.toLowerCase();
        return table.entrySet().stream()
            .filter(e -> lower.startsWith(e.getKey().toLowerCase()))
            .map(Map.Entry::getValue)
            .findFirst();
    }

    /** Copy of this table with an added/overridden model price. */
    public ModelPrices withPrice(String model, Price price) {
        var copy = new java.util.HashMap<>(table);
        copy.put(model, price);
        return new ModelPrices(copy);
    }

    /** USD cost of a call, or empty when the model has no known price. */
    public Optional<Double> costUsd(String model, long inputTokens, long outputTokens) {
        return lookup(model).map(p -> p.costOf(inputTokens, outputTokens));
    }
}

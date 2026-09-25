package dev.axiom.meta;

import dev.axiom.Axiom;
import dev.axiom.eval.EvalReport;
import dev.axiom.eval.EvalRunner;
import dev.axiom.eval.EvalSuite;
import dev.axiom.llm.LlmClient;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A {@link StrategyOptimizer.Evaluator} that scores a strategy by building a
 * real agent from it and running an {@link EvalSuite} through
 * {@link EvalRunner}. The LLM client comes from a factory so every
 * evaluation gets a fresh scripted fixture — free, deterministic, offline.
 */
public final class StrategyEvaluator implements StrategyOptimizer.Evaluator {
    private final EvalSuite suite;
    private final Supplier<LlmClient> clientFactory;
    private final List<Object> toolHolders;
    private final String modelName;

    public StrategyEvaluator(EvalSuite suite, Supplier<LlmClient> clientFactory,
                             String modelName, Object... toolHolders) {
        this.suite = Objects.requireNonNull(suite, "suite is required");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory is required");
        this.modelName = Objects.requireNonNull(modelName, "modelName is required");
        this.toolHolders = List.of(toolHolders);
    }

    @Override
    public EvalReport evaluate(Strategy strategy) {
        Axiom.Agent agent = strategy.buildAgent(clientFactory, toolHolders.toArray());
        return EvalRunner.run(suite, EvalRunner.AgentFactory.of(agent), modelName);
    }
}

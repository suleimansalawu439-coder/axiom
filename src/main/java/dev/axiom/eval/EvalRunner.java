package dev.axiom.eval;

import dev.axiom.Axiom;
import dev.axiom.agent.ReActAgent;
import dev.axiom.budget.ModelPrices;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs an {@link EvalSuite}: each case is executed against the agent factory,
 * graded by its scorer, and recorded with pass/fail, score, token usage, USD
 * cost, and latency. Exceptions (including
 * {@link dev.axiom.output.StructuredOutputException}) become failed cases,
 * never crashed runs.
 */
public final class EvalRunner {

    /**
     * Supplies agents to the runner. The default adapter wraps an
     * {@link Axiom.Agent} and reports real token usage per case.
     */
    public interface AgentFactory {
        <T> ReActAgent.TypedRun<T> runFor(String task, Class<T> outputType);

        /** Adapt an {@link Axiom.Agent}; usage and latency are measured per case. */
        static AgentFactory of(Axiom.Agent agent) {
            return new AgentFactory() {
                @Override
                public <T> ReActAgent.TypedRun<T> runFor(String task, Class<T> outputType) {
                    return agent.runForWithStats(task, outputType);
                }
            };
        }
    }

    private EvalRunner() {}

    public static EvalReport run(EvalSuite suite, AgentFactory factory,
                                 String model, ModelPrices prices) {
        List<EvalReport.CaseResult> results = new ArrayList<>();
        for (EvalCase<?> c : suite.cases()) {
            results.add(runOne(c, factory, model, prices));
        }
        return new EvalReport(suite.name(), java.time.Instant.now(), model, results);
    }

    /** Convenience overload with default pricing. */
    public static EvalReport run(EvalSuite suite, AgentFactory factory, String model) {
        return run(suite, factory, model, ModelPrices.defaults());
    }

    private static <T> EvalReport.CaseResult runOne(EvalCase<T> kase, AgentFactory factory,
                                                    String model, ModelPrices prices) {
        long start = System.currentTimeMillis();
        try {
            ReActAgent.TypedRun<T> r = factory.runFor(kase.task(), kase.expectedOutputType());
            long latencyMs = System.currentTimeMillis() - start;
            ScoreResult s = kase.scorer().score(r.value(),
                new EvalContext(kase.task(), latencyMs, r.usage()));
            double cost = prices.costUsd(model,
                r.usage().promptTokens(), r.usage().completionTokens()).orElse(0.0);
            return new EvalReport.CaseResult(kase.id(), s.passed(), s.score(),
                s.explanation(), r.usage().promptTokens(), r.usage().completionTokens(),
                cost, latencyMs, null);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            String detail = e.toString();
            if (detail.length() > 500) detail = detail.substring(0, 500) + "…";
            return new EvalReport.CaseResult(kase.id(), false, 0.0,
                "case raised: " + e.getMessage(), 0, 0, 0.0, latencyMs, detail);
        }
    }
}

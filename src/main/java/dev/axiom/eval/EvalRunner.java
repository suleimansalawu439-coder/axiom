package dev.axiom.eval;

import dev.axiom.Axiom;
import dev.axiom.agent.AgentEvent;
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

        /**
         * Run the case and capture the tool-call trajectory alongside the
         * typed output. The default implementation delegates to
         * {@link #runFor} with an {@link Trajectory#empty() empty} trajectory,
         * so existing factories keep working unchanged; override it (or use
         * {@link #recording}) to grade agent behavior, not just final
         * outputs.
         */
        default <T> TrajectoryRun<T> runForWithTrajectory(String task, Class<T> outputType) {
            return new TrajectoryRun<>(runFor(task, outputType), Trajectory.empty());
        }

        /** Adapt an {@link Axiom.Agent}; usage and latency are measured per case. */
        static AgentFactory of(Axiom.Agent agent) {
            return new AgentFactory() {
                @Override
                public <T> ReActAgent.TypedRun<T> runFor(String task, Class<T> outputType) {
                    return agent.runForWithStats(task, outputType);
                }
            };
        }

        /**
         * Adapt an {@link Axiom.Agent} whose config records events into
         * {@code eventSink} (e.g. via
         * {@code AgentConfig.Builder.onEvent(eventSink::add)}). Each case
         * clears the sink first, so the trajectory holds only that case's
         * tool calls.
         *
         * <pre>{@code
         * List<AgentEvent> events = new CopyOnWriteArrayList<>();
         * var agent = Axiom.agent(AgentConfig.builder()
         *     .onEvent(events::add)
         *     ...build());
         * var factory = AgentFactory.recording(agent, events);
         * EvalReport report = EvalRunner.run(suite, factory, "gpt-4o-mini");
         * }</pre>
         */
        static AgentFactory recording(Axiom.Agent agent, List<AgentEvent> eventSink) {
            if (agent == null) throw new IllegalArgumentException("agent is required");
            if (eventSink == null) throw new IllegalArgumentException("eventSink is required");
            return new AgentFactory() {
                @Override
                public <T> ReActAgent.TypedRun<T> runFor(String task, Class<T> outputType) {
                    return agent.runForWithStats(task, outputType);
                }

                @Override
                public <T> TrajectoryRun<T> runForWithTrajectory(String task, Class<T> outputType) {
                    eventSink.clear();
                    ReActAgent.TypedRun<T> r = agent.runForWithStats(task, outputType);
                    return new TrajectoryRun<>(r, Trajectory.fromEvents(List.copyOf(eventSink)));
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
            TrajectoryRun<T> t = factory.runForWithTrajectory(kase.task(), kase.expectedOutputType());
            ReActAgent.TypedRun<T> r = t.run();
            long latencyMs = System.currentTimeMillis() - start;
            ScoreResult s = kase.scorer().score(r.value(),
                new EvalContext(kase.task(), latencyMs, r.usage(), t.trajectory()));
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

package dev.axiom.bench;

import dev.axiom.agent.AgentEvent;
import dev.axiom.agent.AgentResult;

import java.util.List;

/** Runs one benchmark task and returns the agent's result. */
@FunctionalInterface
public interface BenchAgent {
    AgentResult run(String task);

    /**
     * Agent events recorded during the last {@link #run} call, in emission
     * order. Empty when the agent does not capture events — the benchmark
     * then records an empty trace instead of failing.
     */
    default List<AgentEvent> events() { return List.of(); }
}

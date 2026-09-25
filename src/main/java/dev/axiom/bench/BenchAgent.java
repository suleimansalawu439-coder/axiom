package dev.axiom.bench;

import dev.axiom.agent.AgentResult;

/** Runs one benchmark task and returns the agent's result. */
@FunctionalInterface
public interface BenchAgent {
    AgentResult run(String task);
}

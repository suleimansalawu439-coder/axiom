package dev.axiom.eval;

import dev.axiom.agent.ReActAgent;

/**
 * A typed agent run plus the tool-call trajectory captured alongside it.
 * Produced by {@link EvalRunner.AgentFactory#runForWithTrajectory}; the
 * trajectory is {@link Trajectory#empty()} when the factory cannot observe
 * agent events.
 */
public record TrajectoryRun<T>(ReActAgent.TypedRun<T> run, Trajectory trajectory) {
    public TrajectoryRun {
        if (run == null) throw new IllegalArgumentException("run is required");
        trajectory = trajectory == null ? Trajectory.empty() : trajectory;
    }

    /** The agent's coerced output value. */
    public T value() {
        return run.value();
    }
}

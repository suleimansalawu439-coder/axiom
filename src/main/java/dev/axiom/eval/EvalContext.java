package dev.axiom.eval;

import dev.axiom.llm.ChatResponse;

/**
 * What a scorer gets to see about the run behind one case: the task, the
 * measured latency and token usage, and the captured tool-call
 * {@link Trajectory}. Trajectory scorers read {@link #trajectory()}; output
 * scorers ignore it.
 */
public record EvalContext(String task, long latencyMs, ChatResponse.TokenUsage usage,
                          Trajectory trajectory) {

    /** Backward-compatible: no trajectory captured. */
    public EvalContext(String task, long latencyMs, ChatResponse.TokenUsage usage) {
        this(task, latencyMs, usage, Trajectory.empty());
    }

    public EvalContext {
        if (trajectory == null) {
            throw new IllegalArgumentException("trajectory is required (use Trajectory.empty())");
        }
    }
}

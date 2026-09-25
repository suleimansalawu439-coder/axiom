package dev.axiom.eval;

import dev.axiom.agent.AgentEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The recorded tool-call trajectory of one eval case run: which tools the
 * agent called, in what order, with which arguments, and what each call
 * returned. Trajectory scorers (see {@link Scorers}) grade <i>behavior</i> —
 * the path the agent took — while output scorers grade the final answer.
 *
 * <p>A trajectory is captured from agent events (see
 * {@link #fromEvents(List)}); when the eval factory cannot observe events,
 * the trajectory is {@link #empty()} and trajectory scorers fail with a
 * clear explanation instead of passing vacuously.
 */
public record Trajectory(List<ToolCall> calls) {

    /** One tool invocation, in call order. */
    public record ToolCall(String name, Map<String, Object> arguments, String result) {
        public ToolCall {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    public Trajectory {
        calls = calls == null ? List.of() : List.copyOf(calls);
    }

    /** An empty trajectory: no tools called, or the trajectory was not captured. */
    public static Trajectory empty() {
        return new Trajectory(List.of());
    }

    /**
     * Build a trajectory from agent events in emission order. Uses
     * {@link AgentEvent.ToolCallFinished} records, so each entry carries the
     * tool name, the arguments the model supplied, and the observed result.
     * Attach a listener via {@code AgentConfig.Builder.onEvent(...)} and pass
     * the recorded events here.
     */
    public static Trajectory fromEvents(List<AgentEvent> events) {
        List<ToolCall> calls = new ArrayList<>();
        if (events != null) {
            for (AgentEvent e : events) {
                if (e instanceof AgentEvent.ToolCallFinished tf) {
                    calls.add(new ToolCall(
                        tf.call().name(), tf.call().arguments(), tf.result()));
                }
            }
        }
        return new Trajectory(calls);
    }

    /** Tool names in call order. */
    public List<String> toolNames() {
        return calls.stream().map(ToolCall::name).toList();
    }

    /** True when the named tool was called at least once. */
    public boolean calledTool(String name) {
        return calls.stream().anyMatch(c -> c.name().equals(name));
    }

    /** All calls to the named tool, in order. */
    public List<ToolCall> callsTo(String name) {
        return calls.stream().filter(c -> c.name().equals(name)).toList();
    }

    public int size() {
        return calls.size();
    }
}

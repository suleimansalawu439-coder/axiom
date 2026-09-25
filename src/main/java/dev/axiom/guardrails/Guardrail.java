package dev.axiom.guardrails;

import java.util.Map;

/**
 * A policy check applied to agent inputs and outputs. Guardrails run inside
 * the ReAct loop: {@link #checkInput} gates the user's task before the run
 * starts, {@link #checkOutput} gates the final answer before it is returned.
 * Both default to allow, so a guardrail can implement only the side it cares
 * about.
 *
 * <p>{@link #checkToolCall} gates every individual tool call before dispatch
 * (wired in by the agent loop); {@link #onToolCompleted} is a lifecycle hook
 * fired after each tool call completes, so stateful guardrails (capability
 * tokens, rate counters) can track the session. Both default to no-ops.
 */
public interface Guardrail {

    /** Human-readable name, used in violation messages and tracing. */
    String name();

    default Verdict checkInput(String task) {
        return Verdict.allow();
    }

    default Verdict checkOutput(String output) {
        return Verdict.allow();
    }

    /**
     * Check a single tool call before it is dispatched. A {@link Verdict#block}
     * aborts the run with {@link GuardrailViolationException} (fail-closed);
     * {@link Verdict#replace} is not meaningful for tool calls and is treated
     * as a block rather than silently ignored.
     */
    default Verdict checkToolCall(String toolName, Map<String, Object> arguments) {
        return Verdict.allow();
    }

    /**
     * Notified after a tool call completes — including completions replayed
     * from the durable journal on resume. Default: no-op.
     */
    default void onToolCompleted(String toolName) {
    }
}

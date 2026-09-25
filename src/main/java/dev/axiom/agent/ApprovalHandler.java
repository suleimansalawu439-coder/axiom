package dev.axiom.agent;

import dev.axiom.tools.ToolDefinition;

import java.util.Map;

/**
 * Human-in-the-loop gate. Return {@code true} to allow the tool call,
 * {@code false} to deny it (the denial is reported back to the LLM so it
 * can adjust its plan).
 */
@FunctionalInterface
public interface ApprovalHandler {
    boolean approve(ToolDefinition tool, Map<String, Object> arguments);

    /** Approves everything — useful for trusted local runs and tests. */
    static ApprovalHandler allowAll() {
        return (tool, args) -> true;
    }

    /** Denies everything — the agent must complete the task without tools. */
    static ApprovalHandler denyAll() {
        return (tool, args) -> false;
    }
}

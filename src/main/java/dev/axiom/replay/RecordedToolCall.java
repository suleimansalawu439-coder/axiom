package dev.axiom.replay;

import dev.axiom.llm.ToolCallRequest;

/**
 * One tool call as recorded in a run journal: the call the model issued
 * plus the observation the agent saw. {@code result} is null only when the
 * journal ends mid-turn (torn tail) — a call that never completed.
 */
public record RecordedToolCall(ToolCallRequest call, String result, long durationMs) {

    /** True when the call completed and its result was journaled. */
    public boolean completed() {
        return result != null;
    }

    /**
     * Compact one-line rendering for diffs and demos: {@code name(k=v, …)}
     * with argument keys sorted, so rendering is stable regardless of map
     * iteration order (tool arguments are unordered maps).
     */
    public String describe() {
        StringBuilder sb = new StringBuilder(call.name()).append('(');
        boolean first = true;
        for (var e : new java.util.TreeMap<>(call.arguments()).entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(')').toString();
    }
}

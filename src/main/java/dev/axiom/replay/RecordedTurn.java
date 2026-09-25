package dev.axiom.replay;

import dev.axiom.capabilities.Capability;
import dev.axiom.llm.ChatResponse;

import java.util.List;
import java.util.Set;

/**
 * One recorded model turn: the iteration number, the model's full response
 * (text, tool calls, token usage — served back verbatim from the journal),
 * and the tool calls the agent dispatched for it with their recorded
 * results.
 *
 * <p>{@code capabilityTokensAfter} is the session token set after this
 * turn's tool calls completed — empty unless the run was parsed with a tool
 * registry (see {@link RecordedRun}). It lets the debugger show, and forks
 * inherit, the exact policy state the original run had at every point.
 */
public record RecordedTurn(int iteration, ChatResponse response,
                           List<RecordedToolCall> toolCalls,
                           Set<Capability> capabilityTokensAfter) {

    public RecordedTurn {
        toolCalls = List.copyOf(toolCalls);
        capabilityTokensAfter = Set.copyOf(capabilityTokensAfter);
    }

    /** Token usage charged for this turn in the original run. */
    public ChatResponse.TokenUsage usage() {
        return response.usage() == null
            ? ChatResponse.TokenUsage.empty() : response.usage();
    }
}

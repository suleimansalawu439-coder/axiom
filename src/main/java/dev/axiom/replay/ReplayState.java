package dev.axiom.replay;

import dev.axiom.agent.AgentResult;
import dev.axiom.capabilities.Capability;
import dev.axiom.llm.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The debugger's view of a {@link ReplaySession} after stepping: how many
 * recorded turns have been consumed, what the model said and did so far,
 * cumulative token usage, capability tokens held at this point, and —
 * once the last turn is consumed — the final result and any journaled
 * guardrail blocks.
 *
 * <p>Everything here comes from the journal. No LLM is called, no tool
 * executes: this is the recorded past, served back deterministically.
 */
public record ReplayState(int turnsConsumed, int totalTurns,
                          String lastModelText,
                          List<RecordedToolCall> toolCallsSoFar,
                          ChatResponse.TokenUsage tokenUsage,
                          Set<Capability> capabilityTokens,
                          boolean done, AgentResult finalResult,
                          List<RecordedRun.BlockedCall> guardrailBlocks) {

    public ReplayState {
        toolCallsSoFar = List.copyOf(toolCallsSoFar);
        capabilityTokens = Set.copyOf(capabilityTokens);
        guardrailBlocks = List.copyOf(guardrailBlocks);
    }

    /** 1-based index of the most recently consumed turn, or 0 before the first step. */
    public int currentTurn() {
        return turnsConsumed;
    }

    /** Compact rendering of every tool call consumed so far, in order. */
    public List<String> toolSequence() {
        List<String> out = new ArrayList<>(toolCallsSoFar.size());
        for (RecordedToolCall c : toolCallsSoFar) out.add(c.describe());
        return out;
    }
}

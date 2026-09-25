package dev.axiom.agent;

import dev.axiom.budget.Budget;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.ToolCallRequest;

import java.time.Instant;
import java.util.Map;

/**
 * Events emitted during an agent run. Subscribe via
 * {@link AgentConfig.Builder#onEvent} for tracing, UIs, and logging —
 * this is the backbone of Axiom's observability story.
 */
public sealed interface AgentEvent permits
        AgentEvent.RunStarted, AgentEvent.LlmRequest, AgentEvent.LlmResponse,
        AgentEvent.StreamToken,
        AgentEvent.ToolCallStarted, AgentEvent.ToolCallFinished,
        AgentEvent.ApprovalRequested, AgentEvent.BudgetUpdated, AgentEvent.RunFinished {

    Instant timestamp();

    record RunStarted(Instant timestamp, String task) implements AgentEvent {}
    record LlmRequest(Instant timestamp, int iteration) implements AgentEvent {}
    record LlmResponse(Instant timestamp, int iteration, ChatResponse response) implements AgentEvent {}
    /**
     Emitted as model tokens stream in (only when the client supports
     * streaming). UIs render live output from these; the agent still acts
     * only on the complete turn ({@link LlmResponse}). Ephemeral: not
     * written to the durable run journal.
     */
    record StreamToken(Instant timestamp, int iteration, String token) implements AgentEvent {}
    record ToolCallStarted(Instant timestamp, ToolCallRequest call) implements AgentEvent {}
    record ToolCallFinished(Instant timestamp, ToolCallRequest call, String result, long durationMs)
        implements AgentEvent {}
    record ApprovalRequested(Instant timestamp, String toolName, Map<String, Object> arguments)
        implements AgentEvent {}
    /**
     * Emitted after every LLM call when a {@link Budget} is configured:
     * the delta just charged and the run's cumulative spend. UIs can render
     * live cost meters from this alone.
     */
    record BudgetUpdated(Instant timestamp, ChatResponse.TokenUsage charged,
                         Budget.Snapshot snapshot) implements AgentEvent {}
    record RunFinished(Instant timestamp, AgentResult result) implements AgentEvent {}
}

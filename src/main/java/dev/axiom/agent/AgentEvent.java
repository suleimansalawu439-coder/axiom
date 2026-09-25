package dev.axiom.agent;

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
        AgentEvent.ToolCallStarted, AgentEvent.ToolCallFinished,
        AgentEvent.ApprovalRequested, AgentEvent.RunFinished {

    Instant timestamp();

    record RunStarted(Instant timestamp, String task) implements AgentEvent {}
    record LlmRequest(Instant timestamp, int iteration) implements AgentEvent {}
    record LlmResponse(Instant timestamp, int iteration, ChatResponse response) implements AgentEvent {}
    record ToolCallStarted(Instant timestamp, ToolCallRequest call) implements AgentEvent {}
    record ToolCallFinished(Instant timestamp, ToolCallRequest call, String result, long durationMs)
        implements AgentEvent {}
    record ApprovalRequested(Instant timestamp, String toolName, Map<String, Object> arguments)
        implements AgentEvent {}
    record RunFinished(Instant timestamp, AgentResult result) implements AgentEvent {}
}

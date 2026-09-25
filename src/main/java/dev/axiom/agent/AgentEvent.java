package dev.axiom.agent;

import dev.axiom.budget.Budget;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.verify.Certificate;

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
        AgentEvent.ApprovalRequested, AgentEvent.BudgetUpdated,
        AgentEvent.GuardrailBlocked, AgentEvent.RunFinished,
        AgentEvent.CertificateIssued, AgentEvent.CertificateVerified {

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
    /**
     * Emitted when a guardrail blocks the task, a tool call, or the final answer. The run
     * aborts with {@link dev.axiom.guardrails.GuardrailViolationException}
     * right after this event. {@code side} is "input", "output", or "tool".
     */
    record GuardrailBlocked(Instant timestamp, String guardrailName,
                            String side, String reason) implements AgentEvent {}
    /**
     * Emitted right after an attested tool's body returns: the verifier's
     * independent observation of the tool's effect, captured as a
     * {@link Certificate}. See {@code dev.axiom.verify}.
     */
    record CertificateIssued(Instant timestamp, Certificate certificate)
        implements AgentEvent {}
    /**
     * Emitted after a certificate is independently re-verified. {@code ok}
     * is false when verification failed — the run aborts fail-closed right
     * after this event with a {@link dev.axiom.verify.VerificationException}.
     */
    record CertificateVerified(Instant timestamp, String callId, String toolName,
                               String verifierKind, boolean ok, String detail)
        implements AgentEvent {}
    record RunFinished(Instant timestamp, AgentResult result) implements AgentEvent {}
}

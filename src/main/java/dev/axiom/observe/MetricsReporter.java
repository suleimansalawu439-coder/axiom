package dev.axiom.observe;

import dev.axiom.agent.AgentEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory rollup of run telemetry: counts, token totals, tool latency.
 * Attach with {@code .onEvent(metrics.asListener())}, then call
 * {@link #summary()} for a human-readable report or {@link #snapshot()} for
 * the raw numbers (Prometheus/Grafana adapters read this).
 */
public final class MetricsReporter implements EventExporter {
    private final AtomicLong runsStarted = new AtomicLong();
    private final AtomicLong runsFinished = new AtomicLong();
    private final AtomicLong runsCompleted = new AtomicLong();
    private final AtomicLong llmCalls = new AtomicLong();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong toolErrors = new AtomicLong();
    private final AtomicLong approvalsRequested = new AtomicLong();
    private final AtomicLong guardrailBlocks = new AtomicLong();
    private final AtomicLong budgetBreaches = new AtomicLong();
    private final AtomicLong totalToolLatencyMs = new AtomicLong();
    private final Map<String, AtomicLong> toolCounts = new ConcurrentHashMap<>();
    private volatile Instant firstEvent;
    private volatile Instant lastEvent;

    @Override
    public void export(AgentEvent event) {
        if (firstEvent == null) firstEvent = event.timestamp();
        lastEvent = event.timestamp();
        if (event instanceof AgentEvent.RunStarted) {
            runsStarted.incrementAndGet();
        } else if (event instanceof AgentEvent.LlmResponse e) {
            llmCalls.incrementAndGet();
            promptTokens.addAndGet(e.response().usage().promptTokens());
            completionTokens.addAndGet(e.response().usage().completionTokens());
        } else if (event instanceof AgentEvent.ToolCallStarted e) {
            toolCalls.incrementAndGet();
            toolCounts.computeIfAbsent(e.call().name(), k -> new AtomicLong())
                .incrementAndGet();
        } else if (event instanceof AgentEvent.ToolCallFinished e) {
            totalToolLatencyMs.addAndGet(e.durationMs());
            if (e.result() != null && e.result().startsWith("ERROR")) {
                toolErrors.incrementAndGet();
            }
        } else if (event instanceof AgentEvent.ApprovalRequested) {
            approvalsRequested.incrementAndGet();
        } else if (event instanceof AgentEvent.GuardrailBlocked) {
            guardrailBlocks.incrementAndGet();
        } else if (event instanceof AgentEvent.BudgetUpdated e) {
            if (e.snapshot().tokensUsedFraction() >= 1.0
                || e.snapshot().costUsedFraction() >= 1.0) {
                budgetBreaches.incrementAndGet();
            }
        } else if (event instanceof AgentEvent.RunFinished e) {
            runsFinished.incrementAndGet();
            if (e.result().completed()) runsCompleted.incrementAndGet();
        }
    }

    /** Raw counters, safe to poll from monitoring threads. */
    public Map<String, Long> snapshot() {
        Map<String, Long> m = new java.util.LinkedHashMap<>();
        m.put("runsStarted", runsStarted.get());
        m.put("runsFinished", runsFinished.get());
        m.put("runsCompleted", runsCompleted.get());
        m.put("llmCalls", llmCalls.get());
        m.put("promptTokens", promptTokens.get());
        m.put("completionTokens", completionTokens.get());
        m.put("toolCalls", toolCalls.get());
        m.put("toolErrors", toolErrors.get());
        m.put("approvalsRequested", approvalsRequested.get());
        m.put("guardrailBlocks", guardrailBlocks.get());
        m.put("budgetBreaches", budgetBreaches.get());
        m.put("totalToolLatencyMs", totalToolLatencyMs.get());
        return Map.copyOf(m);
    }

    /** Per-tool call counts. */
    public Map<String, Long> toolCounts() {
        Map<String, Long> m = new java.util.LinkedHashMap<>();
        toolCounts.forEach((k, v) -> m.put(k, v.get()));
        return Map.copyOf(m);
    }

    /** One-paragraph human-readable summary. */
    public String summary() {
        long tc = toolCalls.get();
        double avgLatency = tc == 0 ? 0.0 : totalToolLatencyMs.get() / (double) tc;
        Duration window = (firstEvent != null && lastEvent != null)
            ? Duration.between(firstEvent, lastEvent) : Duration.ZERO;
        return "Axiom metrics over %s: %d runs started, %d finished (%d completed), "
            .formatted(window, runsStarted.get(), runsFinished.get(), runsCompleted.get())
            + "%d LLM calls (%d prompt + %d completion tokens), %d tool calls "
            .formatted(llmCalls.get(), promptTokens.get(), completionTokens.get(), tc)
            + "(%d errors, avg %.1fms), %d approvals, %d guardrail blocks, %d budget breaches."
            .formatted(toolErrors.get(), avgLatency, approvalsRequested.get(),
                guardrailBlocks.get(), budgetBreaches.get());
    }

    public void reset() {
        runsStarted.set(0); runsFinished.set(0); runsCompleted.set(0);
        llmCalls.set(0); promptTokens.set(0); completionTokens.set(0);
        toolCalls.set(0); toolErrors.set(0); approvalsRequested.set(0);
        guardrailBlocks.set(0); budgetBreaches.set(0); totalToolLatencyMs.set(0);
        toolCounts.clear(); firstEvent = null; lastEvent = null;
    }
}

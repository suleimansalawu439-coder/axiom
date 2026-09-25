package dev.axiom.observe;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.agent.AgentEvent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Appends every event as one JSON line — the universal adapter. Ship the
 * file to Loki, Elasticsearch, Datadog, or just {@code jq} it:
 * {@code jq -c 'select(.type=="ToolCallFinished")' events.jsonl}.
 */
public final class JsonLinesExporter implements EventExporter {
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonLinesExporter(Path file) {
        this.file = file;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void export(AgentEvent event) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timestamp", event.timestamp().toString());
            m.put("type", event.getClass().getSimpleName());
            describe(event, m);
            String line = mapper.writeValueAsString(m) + "\n";
            Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void describe(AgentEvent event, Map<String, Object> m) {
        if (event instanceof AgentEvent.RunStarted e) {
            m.put("task", e.task());
        } else if (event instanceof AgentEvent.LlmRequest e) {
            m.put("iteration", e.iteration());
        } else if (event instanceof AgentEvent.LlmResponse e) {
            m.put("iteration", e.iteration());
            m.put("content", truncate(e.response().content()));
            m.put("toolCalls", e.response().toolCalls().stream()
                .map(tc -> tc.name()).toList());
            m.put("promptTokens", e.response().usage().promptTokens());
            m.put("completionTokens", e.response().usage().completionTokens());
        } else if (event instanceof AgentEvent.StreamToken e) {
            m.put("iteration", e.iteration());
            m.put("token", e.token());
        } else if (event instanceof AgentEvent.ToolCallStarted e) {
            m.put("tool", e.call().name());
            m.put("arguments", e.call().arguments());
        } else if (event instanceof AgentEvent.ToolCallFinished e) {
            m.put("tool", e.call().name());
            m.put("result", truncate(e.result()));
            m.put("durationMs", e.durationMs());
        } else if (event instanceof AgentEvent.ApprovalRequested e) {
            m.put("tool", e.toolName());
        } else if (event instanceof AgentEvent.BudgetUpdated e) {
            m.put("chargedPrompt", e.charged().promptTokens());
            m.put("chargedCompletion", e.charged().completionTokens());
            m.put("totalTokens", e.snapshot().totalTokens());
            m.put("totalCostUsd", e.snapshot().costUsd());
        } else if (event instanceof AgentEvent.GuardrailBlocked e) {
            m.put("guardrail", e.guardrailName());
            m.put("side", e.side());
            m.put("reason", e.reason());
        } else if (event instanceof AgentEvent.RunFinished e) {
            m.put("answer", truncate(e.result().output()));
            m.put("iterations", e.result().iterations());
            m.put("toolCallsMade", e.result().toolCallsMade());
            m.put("totalTokens", e.result().tokenUsage().totalTokens());
            m.put("completed", e.result().completed());
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}

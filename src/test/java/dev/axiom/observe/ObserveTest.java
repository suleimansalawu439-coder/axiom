package dev.axiom.observe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.agent.AgentEvent;
import dev.axiom.llm.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ObserveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ChatResponse resp(String content) {
        return new ChatResponse(content, List.of(), new ChatResponse.TokenUsage(100, 50, 150));
    }

    @Test
    void jsonLinesExporterWritesParseableEvents(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("events.jsonl");
        var exporter = new JsonLinesExporter(file);

        exporter.export(new AgentEvent.RunStarted(Instant.now(), "do things"));
        exporter.export(new AgentEvent.LlmResponse(Instant.now(), 1, resp("hello")));
        exporter.export(new AgentEvent.GuardrailBlocked(Instant.now(), "g1", "input", "bad"));

        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size());

        Map<String, Object> first = MAPPER.readValue(lines.get(0), new TypeReference<>() {});
        assertEquals("RunStarted", first.get("type"));
        assertEquals("do things", first.get("task"));
        assertNotNull(first.get("timestamp"));

        Map<String, Object> third = MAPPER.readValue(lines.get(2), new TypeReference<>() {});
        assertEquals("GuardrailBlocked", third.get("type"));
        assertEquals("g1", third.get("guardrail"));
        assertEquals("input", third.get("side"));
    }

    @Test
    void exporterSurvivesAsListener() {
        var exporter = new JsonLinesExporter(Path.of("/nonexistent-dir-xyz/events.jsonl"));
        // asListener must never throw, even when the sink is broken.
        assertDoesNotThrow(() -> exporter.asListener()
            .accept(new AgentEvent.RunStarted(Instant.now(), "x")));
    }

    @Test
    void metricsReporterCountsEverything() {
        var m = new MetricsReporter();

        m.export(new AgentEvent.RunStarted(Instant.now(), "task"));
        m.export(new AgentEvent.LlmResponse(Instant.now(), 1, resp("a")));
        m.export(new AgentEvent.LlmResponse(Instant.now(), 2, resp("b")));
        var call = new dev.axiom.llm.ToolCallRequest("id1", "search", Map.of());
        m.export(new AgentEvent.ToolCallStarted(Instant.now(), call));
        m.export(new AgentEvent.ToolCallFinished(Instant.now(), call, "ERROR: blew up", 120));
        m.export(new AgentEvent.ToolCallStarted(Instant.now(), call));
        m.export(new AgentEvent.ToolCallFinished(Instant.now(), call, "fine", 30));
        m.export(new AgentEvent.ApprovalRequested(Instant.now(), "search", Map.of()));
        m.export(new AgentEvent.GuardrailBlocked(Instant.now(), "g", "output", "r"));
        m.export(new AgentEvent.RunFinished(Instant.now(),
            new dev.axiom.agent.AgentResult("done", 2, 2,
                new ChatResponse.TokenUsage(200, 100, 300), true)));

        var snap = m.snapshot();
        assertEquals(1L, snap.get("runsStarted"));
        assertEquals(1L, snap.get("runsFinished"));
        assertEquals(1L, snap.get("runsCompleted"));
        assertEquals(2L, snap.get("llmCalls"));
        assertEquals(200L, snap.get("promptTokens"));
        assertEquals(100L, snap.get("completionTokens"));
        assertEquals(2L, snap.get("toolCalls"));
        assertEquals(1L, snap.get("toolErrors"));
        assertEquals(1L, snap.get("approvalsRequested"));
        assertEquals(1L, snap.get("guardrailBlocks"));
        assertEquals(2L, m.toolCounts().get("search"));

        String summary = m.summary();
        assertTrue(summary.contains("1 runs started"));
        assertTrue(summary.contains("2 LLM calls"));
    }

    @Test
    void metricsReporterHandlesEmptyStream() {
        var m = new MetricsReporter();
        assertEquals(0L, m.snapshot().get("runsStarted"));
        assertDoesNotThrow(m::summary);
    }
}

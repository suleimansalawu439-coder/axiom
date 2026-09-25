package dev.axiom.bench;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A machine-readable benchmark receipt: framework, version, model, mode, and
 * per-task pass/fail with tokens, USD cost, and latency — plus totals. Saved
 * as JSON so results are comparable across runs, models, and frameworks.
 */
public final class BenchReceipt {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * One task's measured outcome, with the full detail needed to audit it:
     * the prompt, what was expected, the step-by-step trace, the SWE test
     * output (when applicable), and the complete error (when the harness
     * itself failed).
     */
    public record TaskResult(String taskId, String kind, boolean passed, String output,
                             long promptTokens, long completionTokens, double costUsd,
                             long latencyMs, String detail,
                             String prompt, String expected,
                             List<Map<String, Object>> trace,
                             String testOutput, String error) {
        public long totalTokens() { return promptTokens + completionTokens; }

        public TaskResult {
            trace = trace == null ? List.of() : List.copyOf(trace);
        }
    }

    private final String framework;
    private final String frameworkVersion;
    private final String model;
    private final String mode;
    private final Instant timestamp;
    private final List<TaskResult> results;
    /** Honesty disclosure: subset scope, provider tier, cost basis, limits. */
    private final String notes;

    public BenchReceipt(String framework, String frameworkVersion, String model,
                        String mode, Instant timestamp, List<TaskResult> results) {
        this(framework, frameworkVersion, model, mode, timestamp, results, "");
    }

    public BenchReceipt(String framework, String frameworkVersion, String model,
                        String mode, Instant timestamp, List<TaskResult> results,
                        String notes) {
        this.framework = framework;
        this.frameworkVersion = frameworkVersion;
        this.model = model;
        this.mode = mode;
        this.timestamp = timestamp;
        this.results = List.copyOf(results);
        this.notes = notes == null ? "" : notes;
    }

    public String framework() { return framework; }
    public String frameworkVersion() { return frameworkVersion; }
    public String model() { return model; }
    public String mode() { return mode; }
    public Instant timestamp() { return timestamp; }
    /** Honesty disclosure recorded with the receipt (may be blank). */
    public String notes() { return notes; }
    public List<TaskResult> results() { return results; }

    public int passed() { return (int) results.stream().filter(TaskResult::passed).count(); }
    public int failed() { return results.size() - passed(); }
    public double passRate() {
        return results.isEmpty() ? 0.0 : (double) passed() / results.size();
    }
    public double totalCostUsd() {
        return results.stream().mapToDouble(TaskResult::costUsd).sum();
    }
    public long totalTokens() {
        return results.stream().mapToLong(TaskResult::totalTokens).sum();
    }
    public long totalLatencyMs() {
        return results.stream().mapToLong(TaskResult::latencyMs).sum();
    }

    /** Persist as pretty JSON. */
    public void save(Path path) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(toJsonMap()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BenchException("Failed to save benchmark receipt to " + path, e);
        }
    }

    public Map<String, Object> toJsonMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("framework", framework);
        m.put("frameworkVersion", frameworkVersion);
        m.put("model", model);
        m.put("mode", mode);
        m.put("timestamp", timestamp.toString());
        if (!notes.isBlank()) m.put("notes", notes);
        List<Map<String, Object>> rs = new ArrayList<>();
        for (TaskResult r : results) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("taskId", r.taskId());
            rm.put("kind", r.kind());
            rm.put("passed", r.passed());
            rm.put("output", r.output());
            rm.put("promptTokens", r.promptTokens());
            rm.put("completionTokens", r.completionTokens());
            rm.put("totalTokens", r.totalTokens());
            rm.put("costUsd", r.costUsd());
            rm.put("latencyMs", r.latencyMs());
            rm.put("detail", r.detail());
            rm.put("prompt", r.prompt());
            rm.put("expected", r.expected());
            rm.put("trace", r.trace());
            rm.put("testOutput", r.testOutput());
            rm.put("error", r.error());
            rs.add(rm);
        }
        m.put("results", rs);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("tasks", results.size());
        totals.put("passed", passed());
        totals.put("failed", failed());
        totals.put("passRate", passRate());
        totals.put("totalTokens", totalTokens());
        totals.put("totalCostUsd", totalCostUsd());
        totals.put("totalLatencyMs", totalLatencyMs());
        m.put("totals", totals);
        return m;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Benchmark receipt: %s %s | model=%s mode=%s%n"
            .formatted(framework, frameworkVersion, model, mode));
        for (TaskResult r : results) {
            sb.append("  [%s] %-20s %s (%d tokens, $%.4f, %dms)%n".formatted(
                r.passed() ? "PASS" : "FAIL", r.taskId(), r.kind(),
                r.totalTokens(), r.costUsd(), r.latencyMs()));
        }
        sb.append("Totals: %d/%d passed (%.0f%%), %d tokens, $%.4f, %dms".formatted(
            passed(), results.size(), passRate() * 100, totalTokens(), totalCostUsd(),
            totalLatencyMs()));
        if (!notes.isBlank()) sb.append("%nNotes: %s".formatted(notes));
        return sb.toString();
    }
}

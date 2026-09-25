package dev.axiom.eval;

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
 * The persisted outcome of one {@link EvalRunner#run}: per-case pass/fail,
 * scores, token usage, USD cost, and latency, plus totals. Saved as JSON so
 * CI can archive it and {@link #diff} can compare runs over time.
 */
public final class EvalReport {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One case's measured outcome. */
    public record CaseResult(String caseId, boolean passed, double score, String explanation,
                             long promptTokens, long completionTokens, double costUsd,
                             long latencyMs, String errorDetail) {
        public long totalTokens() { return promptTokens + completionTokens; }
    }

    private final String suiteName;
    private final Instant timestamp;
    private final String model;
    private final List<CaseResult> results;

    public EvalReport(String suiteName, Instant timestamp, String model, List<CaseResult> results) {
        this.suiteName = suiteName;
        this.timestamp = timestamp;
        this.model = model;
        this.results = List.copyOf(results);
    }

    public String suiteName() { return suiteName; }
    public Instant timestamp() { return timestamp; }
    public String model() { return model; }
    public List<CaseResult> results() { return results; }

    public int passed() { return (int) results.stream().filter(CaseResult::passed).count(); }
    public int failed() { return results.size() - passed(); }

    public double passRate() {
        return results.isEmpty() ? 0.0 : (double) passed() / results.size();
    }

    public double meanScore() {
        return results.isEmpty() ? 0.0
            : results.stream().mapToDouble(CaseResult::score).average().orElse(0.0);
    }

    public double totalCostUsd() {
        return results.stream().mapToDouble(CaseResult::costUsd).sum();
    }

    public long totalTokens() {
        return results.stream().mapToLong(CaseResult::totalTokens).sum();
    }

    /** Compare against a baseline run; flags pass→fail flips and score drops. */
    public EvalDiff diff(EvalReport baseline) {
        List<EvalDiff.Regression> regressions = new ArrayList<>();
        Map<String, CaseResult> base = new LinkedHashMap<>();
        for (CaseResult r : baseline.results()) base.put(r.caseId(), r);
        for (CaseResult r : results) {
            CaseResult b = base.get(r.caseId());
            if (b == null) continue; // new case: nothing to regress against
            if (b.passed() && !r.passed()) {
                regressions.add(new EvalDiff.Regression(r.caseId(), "pass_to_fail",
                    true, false, r.score() - b.score()));
            } else if (r.score() < b.score() - 0.05) {
                regressions.add(new EvalDiff.Regression(r.caseId(), "score_drop",
                    b.passed(), r.passed(), r.score() - b.score()));
            }
        }
        return new EvalDiff(regressions);
    }

    /** Persist as pretty JSON. */
    public void save(Path path) {
        try {
            Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(toJsonMap()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new EvalException("Failed to save eval report to " + path, e);
        }
    }

    /** Load a report previously written with {@link #save}. */
    @SuppressWarnings("unchecked")
    public static EvalReport load(Path path) {
        try {
            Map<String, Object> m = MAPPER.readValue(
                Files.readString(path, StandardCharsets.UTF_8), Map.class);
            List<CaseResult> results = new ArrayList<>();
            for (Object o : (List<Object>) m.getOrDefault("results", List.of())) {
                Map<String, Object> r = (Map<String, Object>) o;
                results.add(new CaseResult(
                    str(r.get("caseId")), Boolean.TRUE.equals(r.get("passed")),
                    dbl(r.get("score")), strOrNull(r.get("explanation")),
                    num(r.get("promptTokens")), num(r.get("completionTokens")),
                    dbl(r.get("costUsd")), num(r.get("latencyMs")),
                    strOrNull(r.get("errorDetail"))));
            }
            return new EvalReport(str(m.get("suite")), parseInstant(m.get("timestamp")),
                str(m.get("model")), results);
        } catch (Exception e) {
            throw new EvalException("Failed to load eval report from " + path, e);
        }
    }

    public Map<String, Object> toJsonMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("suite", suiteName);
        m.put("timestamp", timestamp.toString());
        m.put("model", model);
        List<Map<String, Object>> rs = new ArrayList<>();
        for (CaseResult r : results) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("caseId", r.caseId());
            rm.put("passed", r.passed());
            rm.put("score", r.score());
            rm.put("explanation", r.explanation());
            rm.put("promptTokens", r.promptTokens());
            rm.put("completionTokens", r.completionTokens());
            rm.put("totalTokens", r.totalTokens());
            rm.put("costUsd", r.costUsd());
            rm.put("latencyMs", r.latencyMs());
            if (r.errorDetail() != null) rm.put("errorDetail", r.errorDetail());
            rs.add(rm);
        }
        m.put("results", rs);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("cases", results.size());
        totals.put("passed", passed());
        totals.put("failed", failed());
        totals.put("passRate", passRate());
        totals.put("meanScore", meanScore());
        totals.put("totalTokens", totalTokens());
        totals.put("totalCostUsd", totalCostUsd());
        m.put("totals", totals);
        return m;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String strOrNull(Object o) { return o == null ? null : String.valueOf(o); }
    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
    private static double dbl(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }

    private static Instant parseInstant(Object o) {
        try { return o == null ? Instant.now() : Instant.parse(String.valueOf(o)); }
        catch (Exception e) { return Instant.now(); }
    }
}

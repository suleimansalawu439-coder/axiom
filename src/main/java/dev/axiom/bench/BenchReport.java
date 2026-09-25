package dev.axiom.bench;

import dev.axiom.bench.BenchReceipt.TaskResult;

import java.util.List;
import java.util.Map;

/**
 * Renders a {@link BenchReceipt} as a fully detailed, human-readable Markdown
 * report: per-task prompts, expectations, metrics, the complete step-by-step
 * trace (model text and every tool call with arguments and results), SWE test
 * output, and full harness errors. Saved next to the JSON receipt by
 * {@link BenchMain}.
 */
public final class BenchReport {

    private BenchReport() {}

    /** Render the whole receipt as Markdown. */
    public static String markdown(BenchReceipt receipt) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Benchmark report\n\n");
        sb.append("| Field | Value |\n|---|---|\n");
        row(sb, "Framework", receipt.framework() + " " + receipt.frameworkVersion());
        row(sb, "Model", receipt.model());
        row(sb, "Mode", receipt.mode());
        row(sb, "Timestamp", receipt.timestamp().toString());
        row(sb, "Result",
            "%d/%d attempted passed (%.0f%% of attempted); %d failed, %d unattempted".formatted(
                receipt.passed(), receipt.attempted(), receipt.passRate() * 100,
                receipt.failed(), receipt.unattempted()));
        row(sb, "Total tokens", String.valueOf(receipt.totalTokens()));
        row(sb, "Total cost", "$%.4f".formatted(receipt.totalCostUsd()));
        row(sb, "Total latency", "%dms".formatted(receipt.totalLatencyMs()));
        sb.append("\n");
        if (!receipt.notes().isBlank()) {
            sb.append("> ").append(receipt.notes().replace("\n", "\n> ")).append("\n\n");
        }
        sb.append("## Tasks\n\n");
        for (TaskResult r : receipt.results()) {
            taskSection(sb, r);
        }
        return sb.toString();
    }

    private static void taskSection(StringBuilder sb, TaskResult r) {
        String mark = switch (r.status()) {
            case PASSED -> "PASS";
            case FAILED -> "FAIL";
            case UNATTEMPTED -> "UNATTEMPTED";
        };
        sb.append("### [%s] %s (%s)\n\n".formatted(mark, r.taskId(), r.kind()));
        sb.append("- **Prompt:** ").append(inline(r.prompt())).append("\n");
        if (r.expected() != null) {
            sb.append("- **Expected:** ").append(inline(r.expected())).append("\n");
        }
        sb.append("- **Metrics:** %d tokens (prompt %d / completion %d), $%.4f, %dms\n".formatted(
            r.totalTokens(), r.promptTokens(), r.completionTokens(),
            r.costUsd(), r.latencyMs()));
        sb.append("- **Verdict:** ").append(inline(r.detail())).append("\n\n");

        List<Map<String, Object>> trace = r.trace();
        if (trace != null && !trace.isEmpty()) {
            sb.append("#### Trace\n\n");
            for (Map<String, Object> step : trace) {
                sb.append("**Iteration ").append(step.get("iteration")).append("**\n\n");
                Object modelText = step.get("modelText");
                if (modelText instanceof String s && !s.isBlank()) {
                    sb.append("Model: ").append(inline(s)).append("\n\n");
                }
                Object rawCalls = step.get("toolCalls");
                if (rawCalls instanceof List<?> calls && !calls.isEmpty()) {
                    for (Object o : calls) {
                        if (o instanceof Map<?, ?> tm) {
                            sb.append("- `").append(tm.get("name")).append("`(")
                              .append(inline(String.valueOf(tm.get("arguments")))).append(")");
                            Object dur = tm.get("durationMs");
                            if (dur != null) sb.append(" — ").append(dur).append("ms");
                            sb.append("\n");
                            Object res = tm.get("result");
                            if (res instanceof String rs && !rs.isBlank()) {
                                sb.append("  → ").append(inline(truncate(rs, 500))).append("\n");
                            }
                        }
                    }
                    sb.append("\n");
                }
            }
        }

        if (r.output() != null) {
            sb.append("#### Final output\n\n```\n")
              .append(truncate(r.output(), 2000)).append("\n```\n\n");
        }
        if (r.testOutput() != null && !r.testOutput().isBlank()) {
            sb.append("#### Test command output\n\n```\n")
              .append(truncate(r.testOutput(), 2000)).append("\n```\n\n");
        }
        if (r.error() != null && !r.error().isBlank()) {
            sb.append("#### Harness error\n\n```\n")
              .append(truncate(r.error(), 4000)).append("\n```\n\n");
        }
        sb.append("---\n\n");
    }

    private static void row(StringBuilder sb, String k, String v) {
        sb.append("| ").append(k).append(" | ").append(inline(v)).append(" |\n");
    }

    /** Collapse whitespace so a value fits on one Markdown line. */
    private static String inline(String s) {
        if (s == null) return "—";
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }
}

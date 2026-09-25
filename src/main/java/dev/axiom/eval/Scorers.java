package dev.axiom.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** Built-in scorers: exact match, keyword coverage, schema conformance, LLM judge, trajectories. */
public final class Scorers {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Scorers() {}

    /** Passes when the output equals {@code expected} after trimming. */
    public static Scorer<String> exactMatch(String expected) {
        String want = expected == null ? "" : expected.trim();
        return (actual, ctx) -> {
            String got = actual == null ? "" : actual.trim();
            return got.equals(want)
                ? ScoreResult.pass("exact match")
                : ScoreResult.fail("expected <%s> but got <%s>".formatted(want, got));
        };
    }

    /** Case-insensitive variant of {@link #exactMatch}. */
    public static Scorer<String> exactMatchIgnoreCase(String expected) {
        String want = expected == null ? "" : expected.trim().toLowerCase(Locale.ROOT);
        return (actual, ctx) -> {
            String got = actual == null ? "" : actual.trim().toLowerCase(Locale.ROOT);
            return got.equals(want)
                ? ScoreResult.pass("exact match (case-insensitive)")
                : ScoreResult.fail("expected <%s> but got <%s>".formatted(want, got));
        };
    }

    /** Passes when the output contains every keyword (case-insensitive). */
    public static Scorer<String> containsAll(String... keywords) {
        return (actual, ctx) -> {
            String got = actual == null ? "" : actual.toLowerCase(Locale.ROOT);
            for (String k : keywords) {
                if (!got.contains(k.toLowerCase(Locale.ROOT))) {
                    return ScoreResult.fail("output is missing keyword <%s>".formatted(k));
                }
            }
            return ScoreResult.pass("all %d keywords present".formatted(keywords.length));
        };
    }

    /**
     * JSON-schema conformance: passes when the output deserializes into
     * {@code type} (the same schema the agent was constrained to). This is the
     * type-safety backstop for structured outputs.
     */
    public static <T> Scorer<T> parsesAs(Class<T> type) {
        return (actual, ctx) -> {
            try {
                String json = actual instanceof String s ? s : MAPPER.writeValueAsString(actual);
                MAPPER.readValue(json, type);
                return ScoreResult.pass("output parses as " + type.getSimpleName());
            } catch (Exception e) {
                return ScoreResult.fail(
                    "output does not parse as %s: %s".formatted(type.getSimpleName(), e.getMessage()));
            }
        };
    }

    /**
     * LLM-as-judge: grades with a judge model and passes at or above
     * {@code threshold} (0..1).
     */
    public static <T> Scorer<T> llmJudge(LlmJudge judge, double threshold) {
        if (threshold < 0 || threshold > 1) throw new IllegalArgumentException("threshold must be in [0,1]");
        return (actual, ctx) -> {
            String rendered;
            try {
                rendered = actual instanceof String s ? s : MAPPER.writeValueAsString(actual);
            } catch (Exception e) {
                rendered = String.valueOf(actual);
            }
            LlmJudge.JudgeVerdict v = judge.judge(ctx.task(), rendered);
            boolean passed = v.score() >= threshold;
            return passed
                ? ScoreResult.pass(v.score(), "judge %.2f >= %.2f: %s".formatted(v.score(), threshold, v.rationale()))
                : ScoreResult.fail(v.score(), "judge %.2f < %.2f: %s".formatted(v.score(), threshold, v.rationale()));
        };
    }

    // ------------------------------------------------------------------
    // Trajectory scorers: grade behavior (the path), not just the answer.
    // They read EvalContext.trajectory(); with an empty trajectory they fail
    // with a clear explanation rather than passing vacuously.
    // ------------------------------------------------------------------

    /** Passes when the agent called the named tool at least once. */
    public static <T> Scorer<T> calledTool(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        return (actual, ctx) -> ctx.trajectory().calledTool(name)
            ? ScoreResult.pass("called tool <%s>".formatted(name))
            : ScoreResult.fail("never called tool <%s>; trajectory: %s"
                .formatted(name, ctx.trajectory().toolNames()));
    }

    /**
     * Passes when the named tools were called in the given order. The calls
     * need not be consecutive — other tool calls may appear in between — but
     * the relative order must match.
     */
    public static <T> Scorer<T> calledInOrder(String... names) {
        if (names == null || names.length == 0) throw new IllegalArgumentException("at least one name is required");
        List<String> want = List.of(names);
        return (actual, ctx) -> {
            List<String> have = ctx.trajectory().toolNames();
            int i = 0;
            for (String h : have) {
                if (i < want.size() && h.equals(want.get(i))) i++;
            }
            return i == want.size()
                ? ScoreResult.pass("called in order %s".formatted(want))
                : ScoreResult.fail("expected call order %s but saw %s".formatted(want, have));
        };
    }

    /**
     * Passes when at least one call to the named tool had arguments satisfying
     * {@code args}. Fails when the tool was never called or no call's
     * arguments matched.
     */
    public static <T> Scorer<T> toolArgsMatch(String name, Predicate<Map<String, Object>> args) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (args == null) throw new IllegalArgumentException("args predicate is required");
        return (actual, ctx) -> {
            List<Trajectory.ToolCall> calls = ctx.trajectory().callsTo(name);
            if (calls.isEmpty()) {
                return ScoreResult.fail("never called tool <%s>; trajectory: %s"
                    .formatted(name, ctx.trajectory().toolNames()));
            }
            boolean any = calls.stream().anyMatch(c -> {
                try {
                    return args.test(c.arguments());
                } catch (Exception e) {
                    return false;
                }
            });
            return any
                ? ScoreResult.pass("tool <%s> called with matching args".formatted(name))
                : ScoreResult.fail("tool <%s> called %d time(s) but no call matched; args seen: %s"
                    .formatted(name, calls.size(),
                        calls.stream().map(Trajectory.ToolCall::arguments).toList()));
        };
    }

    /** Passes when the named tool was never called. */
    public static <T> Scorer<T> neverCalledTool(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        return (actual, ctx) -> !ctx.trajectory().calledTool(name)
            ? ScoreResult.pass("never called tool <%s>".formatted(name))
            : ScoreResult.fail("unexpectedly called tool <%s>".formatted(name));
    }

    /**
     * AND-combinator: every scorer must pass. The combined score is the
     * minimum of the parts, so one weak link drags the case down; the
     * explanation joins each part's note. Use it to pair trajectory
     * assertions with output scorers:
     *
     * <pre>{@code
     * Scorer<Invoice> s = Scorers.allOf(
     *     Scorers.calledTool("fetchOrder"),
     *     Scorers.toolArgsMatch("fetchOrder", a -> "ORD-1".equals(a.get("orderId"))),
     *     Scorers.parsesAs(Invoice.class));
     * }</pre>
     */
    @SafeVarargs
    public static <T> Scorer<T> allOf(Scorer<T>... scorers) {
        if (scorers == null || scorers.length == 0) {
            throw new IllegalArgumentException("at least one scorer is required");
        }
        return (actual, ctx) -> {
            List<String> notes = new ArrayList<>();
            double min = 1.0;
            boolean ok = true;
            for (Scorer<T> s : scorers) {
                if (s == null) throw new IllegalArgumentException("scorer must not be null");
                ScoreResult r = s.score(actual, ctx);
                notes.add(r.explanation());
                min = Math.min(min, r.score());
                ok &= r.passed();
            }
            String explanation = String.join("; ", notes);
            return ok ? ScoreResult.pass(min, explanation) : ScoreResult.fail(min, explanation);
        };
    }
}

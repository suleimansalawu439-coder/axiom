package dev.axiom.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

/** Built-in scorers: exact match, keyword coverage, schema conformance, LLM judge. */
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
}

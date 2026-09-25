package dev.axiom.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CI gate for eval suites: fails the build when the current report regressed
 * against a saved baseline. A regression is any case whose score dropped
 * below baseline (beyond tolerance), any case that flipped from pass to
 * fail, and any <i>new</i> case that fails with no baseline to compare
 * against. Cases that already failed in the baseline and did not get worse
 * are not regressions — fix them, don't gate on them.
 *
 * <p>Typical CI usage:
 * <pre>{@code
 * // 1. Run the suite.
 * EvalReport current = EvalRunner.run(suite, factory, model);
 * // 2. Gate against the committed baseline; throws on regression.
 * EvalGate.assertNoRegression(current, EvalReport.load(Paths.get("eval/baseline.json")));
 * // 3. On green, the current report can be promoted to the new baseline.
 * current.save(Paths.get("eval/baseline.json"));
 * }</pre>
 *
 * <p>Or as a JUnit test that fails the build:
 * <pre>{@code
 * @Test
 * void evalSuiteHasNoRegressions(@TempDir Path dir) {
 *     EvalReport current = EvalRunner.run(suite(), factory(), "gpt-4o-mini");
 *     EvalReport baseline = EvalReport.load(Paths.get("eval/baseline.json"));
 *     EvalGate.assertNoRegression(current, baseline); // throws EvalGateException on regression
 * }
 * }</pre>
 */
public final class EvalGate {
    /** Default score-drop tolerance, mirroring {@link EvalReport#diff}. */
    public static final double DEFAULT_SCORE_TOLERANCE = 0.05;

    private EvalGate() {}

    /**
     * Throw {@link EvalGateException} when {@code report} regressed against
     * {@code baseline}. Uses {@link #DEFAULT_SCORE_TOLERANCE}.
     */
    public static void assertNoRegression(EvalReport report, EvalReport baseline) {
        assertNoRegression(report, baseline, DEFAULT_SCORE_TOLERANCE);
    }

    /**
     * Throw {@link EvalGateException} when {@code report} regressed against
     * {@code baseline}, treating a score drop larger than
     * {@code scoreTolerance} as a regression.
     */
    public static void assertNoRegression(EvalReport report, EvalReport baseline,
                                          double scoreTolerance) {
        if (report == null) throw new IllegalArgumentException("report is required");
        if (baseline == null) throw new IllegalArgumentException("baseline is required");
        if (scoreTolerance < 0) throw new IllegalArgumentException("scoreTolerance must be >= 0");

        List<String> problems = new ArrayList<>();
        Map<String, EvalReport.CaseResult> base = new LinkedHashMap<>();
        for (EvalReport.CaseResult r : baseline.results()) base.put(r.caseId(), r);

        for (EvalReport.CaseResult r : report.results()) {
            EvalReport.CaseResult b = base.get(r.caseId());
            if (b == null) {
                if (!r.passed()) {
                    problems.add("new case '%s' fails with no passing baseline (score %.2f: %s)"
                        .formatted(r.caseId(), r.score(), r.explanation()));
                }
                continue;
            }
            if (b.passed() && !r.passed()) {
                problems.add("case '%s': pass_to_fail (was passing, now failing; score %.2f -> %.2f)"
                    .formatted(r.caseId(), b.score(), r.score()));
            } else if (r.score() < b.score() - scoreTolerance) {
                problems.add("case '%s': score_drop (%.2f -> %.2f, delta %+.2f)"
                    .formatted(r.caseId(), b.score(), r.score(), r.score() - b.score()));
            }
        }

        if (!problems.isEmpty()) {
            StringBuilder sb = new StringBuilder("eval gate failed: %d regression(s) vs baseline '%s'"
                .formatted(problems.size(), baseline.suiteName()));
            for (String p : problems) sb.append("\n  - ").append(p);
            throw new EvalGateException(sb.toString());
        }
    }
}

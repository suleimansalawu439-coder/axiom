package dev.axiom.eval;

import java.util.List;

/** The difference between an eval report and its baseline: the regressions. */
public final class EvalDiff {

    /**
     * @param caseId     the regressed case
     * @param kind       {@code pass_to_fail} or {@code score_drop}
     * @param wasPassed  baseline verdict
     * @param nowPassed  current verdict
     * @param scoreDelta current score minus baseline score (negative)
     */
    public record Regression(String caseId, String kind, boolean wasPassed,
                             boolean nowPassed, double scoreDelta) {}

    private final List<Regression> regressions;

    public EvalDiff(List<Regression> regressions) {
        this.regressions = List.copyOf(regressions);
    }

    public List<Regression> regressions() { return regressions; }
    public boolean hasRegressions() { return !regressions.isEmpty(); }

    @Override
    public String toString() {
        if (regressions.isEmpty()) return "EvalDiff{no regressions}";
        StringBuilder sb = new StringBuilder("EvalDiff{regressions=[");
        for (Regression r : regressions) {
            sb.append("\n  ").append(r.caseId()).append(": ").append(r.kind())
              .append(" (score Δ ").append(String.format("%+.2f", r.scoreDelta())).append(")");
        }
        return sb.append("\n]}").toString();
    }
}

package dev.axiom.eval;

/** The verdict of one scorer on one case: pass/fail, a 0..1 score, and why. */
public record ScoreResult(boolean passed, double score, String explanation) {

    public static ScoreResult pass(double score, String explanation) {
        if (score < 0 || score > 1) throw new IllegalArgumentException("score must be in [0,1]");
        return new ScoreResult(true, score, explanation);
    }

    public static ScoreResult pass(String explanation) {
        return new ScoreResult(true, 1.0, explanation);
    }

    public static ScoreResult fail(String explanation) {
        return new ScoreResult(false, 0.0, explanation);
    }

    public static ScoreResult fail(double score, String explanation) {
        if (score < 0 || score > 1) throw new IllegalArgumentException("score must be in [0,1]");
        return new ScoreResult(false, score, explanation);
    }
}

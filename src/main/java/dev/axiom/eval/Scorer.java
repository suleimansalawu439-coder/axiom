package dev.axiom.eval;

/**
 * Grades one eval case's typed output. The type parameter is bound to the
 * case's {@code expectedOutputType}, so a scorer for {@code Invoice} cannot
 * be attached to a case producing {@code Summary} — that mismatch is a
 * compile error, not a runtime surprise.
 */
@FunctionalInterface
public interface Scorer<T> {
    ScoreResult score(T actual, EvalContext context);
}

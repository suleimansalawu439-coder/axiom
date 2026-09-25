package dev.axiom.eval;

/**
 * Assertion-style failure thrown by {@link EvalGate} when an eval report
 * regressed against its baseline. Extends {@link AssertionError} so it
 * fails builds and CI jobs the way a failed test assertion does.
 */
public class EvalGateException extends AssertionError {
    public EvalGateException(String message) {
        super(message);
    }
}

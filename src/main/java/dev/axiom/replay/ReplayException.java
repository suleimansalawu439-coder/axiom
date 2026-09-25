package dev.axiom.replay;

/**
 * Thrown when a replay session or fork cannot proceed: the journal is
 * missing or malformed for replay, the recorded trajectory is exhausted,
 * a fork was attempted without the configuration needed for real tool
 * dispatch, or a scripted branch ran out of model responses.
 *
 * <p>Replay failures are always loud — a debugger that silently shows a
 * wrong trajectory would be worse than no debugger at all.
 */
public final class ReplayException extends RuntimeException {
    public ReplayException(String message) {
        super(message);
    }

    public ReplayException(String message, Throwable cause) {
        super(message, cause);
    }
}

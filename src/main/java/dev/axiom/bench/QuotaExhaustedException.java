package dev.axiom.bench;

/**
 * A benchmark task died to provider daily/plan quota exhaustion — a failure
 * no retry can fix within the run. The runner converts this into an
 * immediate abort of the remaining tasks (when configured) so a dead quota
 * fails the run in seconds, not minutes.
 */
public final class QuotaExhaustedException extends BenchException {
    public QuotaExhaustedException(String taskId, Throwable cause) {
        super("Task <" + taskId + "> hit provider daily/plan quota exhaustion; "
            + "aborting remaining tasks instead of retrying a dead quota", cause);
    }
}

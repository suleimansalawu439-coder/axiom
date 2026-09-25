package dev.axiom.eval;

/**
 * One regression case: a task, the compile-time type the agent must produce,
 * and the scorer that grades it.
 *
 * <pre>{@code
 * EvalCase<Summary> c = EvalCase.of(
 *     "summarize-q3",
 *     "Summarize the Q3 report in two bullet points",
 *     Summary.class,
 *     (summary, ctx) -> summary.points().size() == 2
 *         ? ScoreResult.pass("two points")
 *         : ScoreResult.fail("expected 2 points, got " + summary.points().size()));
 * }</pre>
 */
public final class EvalCase<T> {
    private final String id;
    private final String task;
    private final Class<T> expectedOutputType;
    private final Scorer<T> scorer;

    private EvalCase(String id, String task, Class<T> expectedOutputType, Scorer<T> scorer) {
        this.id = id;
        this.task = task;
        this.expectedOutputType = expectedOutputType;
        this.scorer = scorer;
    }

    /**
     * Define a case. The compiler ties {@code scorer} to
     * {@code expectedOutputType}: a scorer for the wrong type does not compile.
     */
    public static <T> EvalCase<T> of(String id, String task, Class<T> expectedOutputType,
                                     Scorer<T> scorer) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (task == null || task.isBlank()) throw new IllegalArgumentException("task is required");
        if (expectedOutputType == null) throw new IllegalArgumentException("expectedOutputType is required");
        if (scorer == null) throw new IllegalArgumentException("scorer is required");
        return new EvalCase<>(id, task, expectedOutputType, scorer);
    }

    public String id() { return id; }
    public String task() { return task; }
    public Class<T> expectedOutputType() { return expectedOutputType; }
    public Scorer<T> scorer() { return scorer; }

    @Override
    public String toString() {
        return "EvalCase{id='%s', outputType=%s}".formatted(id, expectedOutputType.getSimpleName());
    }
}

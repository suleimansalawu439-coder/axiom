package dev.axiom.bench;

/**
 * One benchmark task.
 *
 * <ul>
 *   <li><b>gaia</b> — GAIA-style: a natural-language task the agent solves
 *       with tools; pass = the final output contains
 *       {@code expectedOutputContains}.</li>
 *   <li><b>swe</b> — SWE-bench-style: the agent gets a sandboxed shell rooted
 *       at a scratch copy of {@code filesResource}; after the run,
 *       {@code testCommand} executes in that copy and pass = exit code 0.</li>
 * </ul>
 */
public record BenchTask(String id, String kind, String prompt, String filesResource,
                        String testCommand, String expectedOutputContains) {

    /** GAIA-style task: no files, answer checked against expected text. */
    public static BenchTask gaia(String id, String prompt, String expectedOutputContains) {
        return new BenchTask(id, "gaia", prompt, null, null, expectedOutputContains);
    }

    /** GAIA-style task with a data directory copied to a scratch workspace. */
    public static BenchTask gaiaWithFiles(String id, String prompt, String filesResource,
                                          String expectedOutputContains) {
        return new BenchTask(id, "gaia", prompt, filesResource, null, expectedOutputContains);
    }

    /** SWE-bench-style task: fix the repo copy so {@code testCommand} passes. */
    public static BenchTask swe(String id, String prompt, String filesResource, String testCommand) {
        return new BenchTask(id, "swe", prompt, filesResource, testCommand, null);
    }

    public boolean isSwe() {
        return "swe".equals(kind);
    }

    public boolean hasFiles() {
        return filesResource != null;
    }
}

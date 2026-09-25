package dev.axiom.bench;

import dev.axiom.Version;
import dev.axiom.agent.AgentResult;
import dev.axiom.bench.BenchReceipt.TaskResult;
import dev.axiom.budget.ModelPrices;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * Executes benchmark tasks and records machine-readable receipts.
 *
 * <p>GAIA-style tasks pass when the agent's output contains the expected text.
 * SWE-bench-style tasks give the agent a sandboxed shell on a scratch copy of
 * a repo; pass = {@code testCommand} exits 0 afterwards.
 */
public final class BenchRunner {

    private BenchRunner() {}

    /**
     * @param tasks        the tasks to run, in order
     * @param agentFactory builds the agent for each task; receives the task
     *                     and its scratch workdir (temp copy of
     *                     {@code filesResource}, or null when the task has no files)
     * @param prices       pricing table for USD costs
     * @param model        model name recorded in the receipt (pricing lookup)
     * @param mode         {@code "fixture"} or {@code "live"}
     */
    public static BenchReceipt run(List<BenchTask> tasks,
                                   BiFunction<BenchTask, Path, BenchAgent> agentFactory,
                                   ModelPrices prices, String model, String mode) {
        return run(tasks, agentFactory, prices, model, mode, 0, "");
    }

    /**
     * Full run with pacing and receipt notes.
     *
     * @param pacingMs pause between tasks (never before the first), so
     *                 free-tier rate limits are respected instead of tripped
     * @param notes    honesty disclosure recorded verbatim in the receipt
     *                 (subset scope, provider tier, cost basis, limitations)
     */
    public static BenchReceipt run(List<BenchTask> tasks,
                                   BiFunction<BenchTask, Path, BenchAgent> agentFactory,
                                   ModelPrices prices, String model, String mode,
                                   long pacingMs, String notes) {
        List<TaskResult> results = new ArrayList<>();
        boolean first = true;
        for (BenchTask task : tasks) {
            if (!first && pacingMs > 0) {
                try {
                    Thread.sleep(pacingMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BenchException("Benchmark pacing interrupted", e);
                }
            }
            first = false;
            results.add(runOne(task, agentFactory, prices, model));
        }
        return new BenchReceipt("axiom", Version.CURRENT, model, mode,
            Instant.now(), results, notes == null ? "" : notes);
    }

    private static TaskResult runOne(BenchTask task,
                                     BiFunction<BenchTask, Path, BenchAgent> agentFactory,
                                     ModelPrices prices, String model) {
        Path workdir = null;
        long start = System.currentTimeMillis();
        try {
            if (task.hasFiles()) {
                workdir = BenchResources.copyToTemp(task.filesResource());
            } else if (task.isSwe()) {
                // A test command still needs somewhere to run.
                workdir = BenchResources.copyToTemp(null);
            }
            BenchAgent agent = agentFactory.apply(task, workdir);
            AgentResult result = agent.run(task.prompt());
            boolean passed;
            String detail;
            if (task.isSwe()) {
                int exit = runTestCommand(workdir, task.testCommand());
                passed = exit == 0;
                detail = "testCommand <%s> exited %d".formatted(task.testCommand(), exit);
            } else {
                passed = result.output() != null
                    && result.output().contains(task.expectedOutputContains());
                detail = passed ? "output contained expected text"
                    : "output did not contain <" + task.expectedOutputContains() + ">";
            }
            long latencyMs = System.currentTimeMillis() - start;
            double cost = prices.costUsd(model, result.tokenUsage().promptTokens(),
                result.tokenUsage().completionTokens()).orElse(0.0);
            return new TaskResult(task.id(), task.kind(), passed, result.output(),
                result.tokenUsage().promptTokens(), result.tokenUsage().completionTokens(),
                cost, latencyMs, detail);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            String detail = e.toString();
            if (detail.length() > 500) detail = detail.substring(0, 500) + "…";
            return new TaskResult(task.id(), task.kind(), false, null, 0, 0, 0.0,
                latencyMs, "harness error: " + detail);
        }
    }

    private static int runTestCommand(Path workdir, String testCommand) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", testCommand);
        pb.directory(workdir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // Drain output so a chatty test can't block on a full pipe.
        byte[] out = p.getInputStream().readAllBytes();
        boolean finished = p.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new BenchException("testCommand timed out: " + testCommand);
        }
        if (out.length > 0 && System.getenv("AXIOM_BENCH_VERBOSE") != null) {
            System.out.println(new String(out));
        }
        return p.exitValue();
    }
}

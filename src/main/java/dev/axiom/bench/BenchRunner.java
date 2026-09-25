package dev.axiom.bench;

import dev.axiom.Version;
import dev.axiom.agent.AgentResult;
import dev.axiom.bench.BenchReceipt.TaskResult;
import dev.axiom.budget.ModelPrices;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
     * Full run with pacing and receipt notes (legacy sequential behavior).
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
        return run(tasks, agentFactory, prices, model, mode, pacingMs, notes,
            BenchRunConfig.sequential());
    }

    /**
     * Full run under an explicit execution config: task parallelism, a
     * shared request rate limiter, and the daily-quota fail-fast policy.
     * The receipt records wall-clock time and the parallelism used, so the
     * "how long did the benchmark take" question is answered honestly.
     *
     * @param pacingMs honored only when {@code config.parallelism() == 1}
     *                 (legacy sequential pacing); parallel runs pace via the
     *                 config's rate limiter instead
     */
    public static BenchReceipt run(List<BenchTask> tasks,
                                   BiFunction<BenchTask, Path, BenchAgent> agentFactory,
                                   ModelPrices prices, String model, String mode,
                                   long pacingMs, String notes, BenchRunConfig config) {
        long wallStart = System.currentTimeMillis();
        List<TaskResult> results = config.parallelism() <= 1
            ? runSequential(tasks, agentFactory, prices, model, pacingMs, config)
            : runParallel(tasks, agentFactory, prices, model, config);
        long wallClockMs = System.currentTimeMillis() - wallStart;
        return new BenchReceipt("axiom", Version.CURRENT, model, mode,
            Instant.now(), results, notes == null ? "" : notes,
            wallClockMs, config.parallelism());
    }

    /** One task at a time, in order, with optional pacing between tasks. */
    private static List<TaskResult> runSequential(List<BenchTask> tasks,
                                                  BiFunction<BenchTask, Path, BenchAgent> agentFactory,
                                                  ModelPrices prices, String model,
                                                  long pacingMs, BenchRunConfig config) {
        List<TaskResult> results = new ArrayList<>();
        boolean first = true;
        for (int i = 0; i < tasks.size(); i++) {
            BenchTask task = tasks.get(i);
            if (!first && pacingMs > 0) {
                try {
                    Thread.sleep(pacingMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BenchException("Benchmark pacing interrupted", e);
                }
            }
            first = false;
            try {
                results.add(runOne(task, agentFactory, prices, model));
            } catch (QuotaExhaustedException qe) {
                if (!config.failFastOnQuota()) throw qe;
                results.add(quotaResult(task, qe));
                for (int j = i + 1; j < tasks.size(); j++) {
                    results.add(abortedResult(tasks.get(j)));
                }
                break;
            }
        }
        return results;
    }

    /**
     * Tasks run concurrently in a fixed pool; results are collected in task
     * order so receipts stay comparable across runs. On daily-quota
     * exhaustion the remaining tasks are cancelled and recorded as aborted
     * instead of each failing the same way.
     */
    private static List<TaskResult> runParallel(List<BenchTask> tasks,
                                                BiFunction<BenchTask, Path, BenchAgent> agentFactory,
                                                ModelPrices prices, String model,
                                                BenchRunConfig config) {
        ExecutorService pool = Executors.newFixedThreadPool(config.parallelism());
        try {
            List<Future<TaskResult>> futures = new ArrayList<>();
            for (BenchTask task : tasks) {
                futures.add(pool.submit(() -> runOne(task, agentFactory, prices, model)));
            }
            List<TaskResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (ExecutionException ee) {
                    if (ee.getCause() instanceof QuotaExhaustedException qe
                        && config.failFastOnQuota()) {
                        results.add(quotaResult(tasks.get(i), qe));
                        for (int j = i + 1; j < futures.size(); j++) {
                            futures.get(j).cancel(true);
                        }
                        for (int j = i + 1; j < tasks.size(); j++) {
                            results.add(abortedResult(tasks.get(j)));
                        }
                        break;
                    }
                    throw new BenchException("Benchmark task failed unexpectedly", ee.getCause());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    for (Future<TaskResult> f : futures) f.cancel(true);
                    throw new BenchException("Benchmark run interrupted", ie);
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The quota-hit task's own result: failed, with the cause recorded. */
    private static TaskResult quotaResult(BenchTask task, QuotaExhaustedException qe) {
        String summary = qe.getCause() == null ? qe.toString() : qe.getCause().toString();
        if (summary.length() > 500) summary = summary.substring(0, 500) + "…";
        return new TaskResult(task.id(), task.kind(), false, null, 0, 0, 0.0,
            0, "daily quota exhausted, run aborted: " + summary,
            task.prompt(), task.expectedOutputContains(), List.of(),
            null, stackTrace(qe));
    }

    /** A task that never ran because a sibling exhausted the daily quota. */
    private static TaskResult abortedResult(BenchTask task) {
        return new TaskResult(task.id(), task.kind(), false, null, 0, 0, 0.0,
            0, "aborted: not started — sibling task exhausted the provider's daily quota",
            task.prompt(), task.expectedOutputContains(), List.of(),
            null, null);
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
            TaskTrace trace = TaskTrace.fromEvents(agent.events());
            boolean passed;
            String detail;
            String testOutput = null;
            if (task.isSwe()) {
                TestOutcome outcome = runTestCommand(workdir, task.testCommand());
                testOutput = outcome.output();
                passed = outcome.exitCode() == 0;
                detail = "testCommand <%s> exited %d".formatted(task.testCommand(), outcome.exitCode());
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
                cost, latencyMs, detail,
                task.prompt(), task.expectedOutputContains(), trace.toJsonList(),
                testOutput, null);
        } catch (Exception e) {
            // Daily/plan quota exhaustion is unrecoverable within a run:
            // propagate so the runner can abort siblings immediately
            // instead of letting every task fail the same slow way.
            if (dev.axiom.llm.LlmException.isQuotaExhausted(e)) {
                throw new QuotaExhaustedException(task.id(), e);
            }
            long latencyMs = System.currentTimeMillis() - start;
            String summary = e.toString();
            if (summary.length() > 500) summary = summary.substring(0, 500) + "…";
            return new TaskResult(task.id(), task.kind(), false, null, 0, 0, 0.0,
                latencyMs, "harness error: " + summary,
                task.prompt(), task.expectedOutputContains(), List.of(),
                null, stackTrace(e));
        }
    }

    /** Full stack trace for the detailed report (the summary stays in {@code detail}). */
    private static String stackTrace(Exception e) {
        var sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 8000 ? s.substring(0, 8000) + "…[truncated]" : s;
    }

    /** Test command outcome: exit code plus captured output. */
    private record TestOutcome(int exitCode, String output) {}

    private static TestOutcome runTestCommand(Path workdir, String testCommand) throws Exception {
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
        String text = new String(out, java.nio.charset.StandardCharsets.UTF_8);
        if (!text.isBlank() && System.getenv("AXIOM_BENCH_VERBOSE") != null) {
            System.out.println(text);
        }
        if (text.length() > 4000) text = text.substring(0, 4000) + "…[truncated]";
        return new TestOutcome(p.exitValue(), text);
    }
}

package dev.axiom.bench;

import dev.axiom.agent.AgentResult;
import dev.axiom.budget.ModelPrices;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parallel execution: wall clock becomes the slowest task, results stay
 * ordered, and daily-quota exhaustion aborts the run instead of failing
 * every task the slow way.
 */
class BenchRunnerParallelTest {

    private static AgentResult ok(String output) {
        return new AgentResult(output, 1, 0, ChatResponse.TokenUsage.empty(), true);
    }

    private static BenchTask gaia(String id, String expected) {
        return BenchTask.gaia(id, "prompt for " + id, expected);
    }

    /** Factory whose agents sleep to simulate model latency, then answer correctly. */
    private BiFunction<BenchTask, Path, BenchAgent> sleepingFactory(long sleepMs) {
        return (task, workdir) -> prompt -> {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new BenchException("sleep interrupted", ie);
            }
            // Answer contains the expected text: task id suffix maps to expectation.
            return ok("answer-" + task.id());
        };
    }

    @Test
    void parallelRunIsFasterThanSequential() {
        List<BenchTask> tasks = List.of(
            gaia("t1", "answer-t1"), gaia("t2", "answer-t2"),
            gaia("t3", "answer-t3"), gaia("t4", "answer-t4"));
        long sleepMs = 600;

        BenchReceipt sequential = BenchRunner.run(tasks, sleepingFactory(sleepMs),
            ModelPrices.defaults(), "stub", "test",
            0, "", BenchRunConfig.sequential());
        BenchReceipt parallel = BenchRunner.run(tasks, sleepingFactory(sleepMs),
            ModelPrices.defaults(), "stub", "test",
            0, "", BenchRunConfig.parallel(4, null));

        assertEquals(4, sequential.passed());
        assertEquals(4, parallel.passed());
        assertTrue(sequential.wallClockMs() >= 4 * sleepMs,
            "sequential should take >= sum of tasks, took " + sequential.wallClockMs());
        assertTrue(parallel.wallClockMs() < 2 * sleepMs,
            "parallel wall clock should approach slowest task, took " + parallel.wallClockMs());
        assertEquals(4, parallel.parallelism());
        // Results stay in task order regardless of completion order.
        assertEquals(List.of("t1", "t2", "t3", "t4"),
            parallel.results().stream().map(BenchReceipt.TaskResult::taskId).toList());
    }

    @Test
    void quotaExhaustionAbortsRemainingTasksFast() {
        List<BenchTask> tasks = List.of(
            gaia("q1", "answer-q1"), gaia("q2", "answer-q2"),
            gaia("q3", "answer-q3"), gaia("q4", "answer-q4"));
        BiFunction<BenchTask, Path, BenchAgent> factory = (task, workdir) -> prompt -> {
            if (task.id().equals("q1")) {
                throw new LlmException(
                    "You exceeded your current quota, please check your plan and billing details.",
                    429);
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new BenchException("sleep interrupted", ie);
            }
            return ok("answer-" + task.id());
        };

        long start = System.nanoTime();
        BenchReceipt receipt = BenchRunner.run(tasks, factory,
            ModelPrices.defaults(), "stub", "test",
            0, "", BenchRunConfig.sequential());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(4, receipt.results().size());
        assertEquals(0, receipt.passed());
        assertTrue(receipt.results().get(0).detail().contains("daily quota exhausted"),
            "first task records the quota failure: " + receipt.results().get(0).detail());
        for (int i = 1; i < 4; i++) {
            assertTrue(receipt.results().get(i).detail().startsWith("aborted:"),
                "task " + i + " should be aborted, got: " + receipt.results().get(i).detail());
        }
        assertTrue(elapsedMs < 4000,
            "fail-fast must not wait out the 5s sleeps, took " + elapsedMs + "ms");
    }

    @Test
    void receiptCarriesWallClockAndParallelism() {
        List<BenchTask> tasks = List.of(gaia("t1", "answer-t1"));
        BenchReceipt receipt = BenchRunner.run(tasks, sleepingFactory(10),
            ModelPrices.defaults(), "stub", "test");
        assertTrue(receipt.wallClockMs() >= 0);
        assertEquals(1, receipt.parallelism());
        assertTrue(receipt.toJsonMap().containsKey("wallClockMs"));
        assertTrue(receipt.toJsonMap().containsKey("parallelism"));
    }
}

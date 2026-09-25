package dev.axiom.eval;

import dev.axiom.agent.AgentEvent;
import dev.axiom.agent.ReActAgent;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.ToolCallRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Depth tests: trajectory assertions, judge-verdict caching, CI gate. */
class EvalDepthTest {

    private static final ChatResponse.TokenUsage USAGE = new ChatResponse.TokenUsage(10, 5, 15);

    private static EvalContext ctx(Trajectory t) {
        return new EvalContext("task", 5, USAGE, t);
    }

    private static AgentEvent.ToolCallFinished finished(String id, String name,
                                                        Map<String, Object> args, String result) {
        return new AgentEvent.ToolCallFinished(Instant.now(),
            new ToolCallRequest(id, name, args), result, 12);
    }

    private static Trajectory sampleTrajectory() {
        return Trajectory.fromEvents(List.of(
            finished("1", "search", Map.of("q", "axiom"), "found docs"),
            finished("2", "calculator", Map.of("expr", "2+2"), "4"),
            finished("3", "search", Map.of("q", "mcp"), "found mcp")));
    }

    // ------------------------------------------------------------------
    // Trajectory.fromEvents
    // ------------------------------------------------------------------

    @Test
    void fromEventsExtractsToolCallsInOrder() {
        Trajectory t = sampleTrajectory();
        assertEquals(List.of("search", "calculator", "search"), t.toolNames());
        assertEquals(3, t.size());
        assertEquals(2, t.callsTo("search").size());
        assertEquals("2+2", t.callsTo("calculator").get(0).arguments().get("expr"));
        assertEquals("4", t.callsTo("calculator").get(0).result());
    }

    @Test
    void fromEventsIgnoresNonToolEvents() {
        List<AgentEvent> events = new ArrayList<>();
        events.add(new AgentEvent.RunStarted(Instant.now(), "task"));
        events.add(finished("1", "search", Map.of("q", "x"), "r"));
        Trajectory t = Trajectory.fromEvents(events);
        assertEquals(List.of("search"), t.toolNames());
    }

    @Test
    void emptyTrajectoryHelpers() {
        assertTrue(Trajectory.empty().toolNames().isEmpty());
        assertFalse(Trajectory.empty().calledTool("x"));
    }

    // ------------------------------------------------------------------
    // Trajectory scorers
    // ------------------------------------------------------------------

    @Test
    void calledToolPassesAndFails() {
        Trajectory t = sampleTrajectory();
        assertTrue(Scorers.<String>calledTool("search").score("x", ctx(t)).passed());
        ScoreResult fail = Scorers.<String>calledTool("email").score("x", ctx(t));
        assertFalse(fail.passed());
        assertTrue(fail.explanation().contains("email"));
    }

    @Test
    void calledToolFailsOnEmptyTrajectory() {
        ScoreResult r = Scorers.<String>calledTool("search").score("x", ctx(Trajectory.empty()));
        assertFalse(r.passed(), "must not pass vacuously without a trajectory");
    }

    @Test
    void calledInOrderSubsequenceSemantics() {
        Trajectory t = sampleTrajectory();
        assertTrue(Scorers.<String>calledInOrder("search", "calculator").score("x", ctx(t)).passed());
        assertTrue(Scorers.<String>calledInOrder("search", "search").score("x", ctx(t)).passed());
        assertTrue(Scorers.<String>calledInOrder("calculator").score("x", ctx(t)).passed());
        // "calculator" then "search" is also a subsequence here (search is called again last)
        assertTrue(Scorers.<String>calledInOrder("calculator", "search").score("x", ctx(t)).passed());
        ScoreResult missing = Scorers.<String>calledInOrder("search", "email").score("x", ctx(t));
        assertFalse(missing.passed());
        assertTrue(missing.explanation().contains("email"));
    }

    @Test
    void calledInOrderRejectsReversedOrder() {
        Trajectory t = Trajectory.fromEvents(List.of(
            finished("1", "b", Map.of(), "r"),
            finished("2", "a", Map.of(), "r")));
        assertFalse(Scorers.<String>calledInOrder("a", "b").score("x", ctx(t)).passed());
        assertTrue(Scorers.<String>calledInOrder("b", "a").score("x", ctx(t)).passed());
    }

    @Test
    void toolArgsMatch() {
        Trajectory t = sampleTrajectory();
        assertTrue(Scorers.<String>toolArgsMatch("calculator", a -> "2+2".equals(a.get("expr")))
            .score("x", ctx(t)).passed());
        assertFalse(Scorers.<String>toolArgsMatch("calculator", a -> "3*3".equals(a.get("expr")))
            .score("x", ctx(t)).passed());
        ScoreResult never = Scorers.<String>toolArgsMatch("email", a -> true).score("x", ctx(t));
        assertFalse(never.passed());
        assertTrue(never.explanation().contains("never called"));
    }

    @Test
    void neverCalledTool() {
        Trajectory t = sampleTrajectory();
        assertTrue(Scorers.<String>neverCalledTool("email").score("x", ctx(t)).passed());
        assertFalse(Scorers.<String>neverCalledTool("search").score("x", ctx(t)).passed());
    }

    @Test
    void allOfCombinesTrajectoryAndOutputScorers() {
        Trajectory t = sampleTrajectory();
        Scorer<String> s = Scorers.allOf(
            Scorers.calledTool("search"),
            Scorers.toolArgsMatch("calculator", a -> "2+2".equals(a.get("expr"))),
            Scorers.exactMatch("ok"));
        ScoreResult pass = s.score("ok", ctx(t));
        assertTrue(pass.passed());
        assertEquals(1.0, pass.score(), 1e-9);

        ScoreResult fail = s.score("wrong", ctx(t));
        assertFalse(fail.passed());
        assertTrue(fail.explanation().contains("expected <ok>"));
    }

    @Test
    void allOfTakesMinimumScore() {
        Scorer<String> s = Scorers.allOf(
            (actual, c) -> ScoreResult.pass(0.6, "weak pass"),
            Scorers.exactMatch("ok"));
        ScoreResult r = s.score("ok", ctx(Trajectory.empty()));
        assertTrue(r.passed());
        assertEquals(0.6, r.score(), 1e-9);
    }

    // ------------------------------------------------------------------
    // Runner threads the trajectory through
    // ------------------------------------------------------------------

    /** Factory that only implements runFor: default must yield an empty trajectory. */
    @Test
    void defaultFactoryYieldsEmptyTrajectory() {
        EvalRunner.AgentFactory f = new EvalRunner.AgentFactory() {
            @Override
            public <T> ReActAgent.TypedRun<T> runFor(String task, Class<T> outputType) {
                return new ReActAgent.TypedRun<>(outputType.cast("v"), USAGE, 1);
            }
        };
        TrajectoryRun<String> t = f.runForWithTrajectory("task", String.class);
        assertTrue(t.trajectory().toolNames().isEmpty());
        assertEquals("v", t.value());
    }

    static class TrajFactory implements EvalRunner.AgentFactory {
        private final Trajectory trajectory;

        TrajFactory(Trajectory trajectory) { this.trajectory = trajectory; }

        @Override
        @SuppressWarnings("unchecked")
        public <T> ReActAgent.TypedRun<T> runFor(String task, Class<T> outputType) {
            return new ReActAgent.TypedRun<>((T) "done", USAGE, 3);
        }

        @Override
        public <T> TrajectoryRun<T> runForWithTrajectory(String task, Class<T> outputType) {
            return new TrajectoryRun<>(runFor(task, outputType), trajectory);
        }
    }

    @Test
    void runnerExposesTrajectoryToScorers() {
        var suite = EvalSuite.of("traj",
            EvalCase.of("c1", "do things", String.class,
                Scorers.allOf(
                    Scorers.calledInOrder("search", "calculator"),
                    Scorers.exactMatch("done"))));
        EvalReport report = EvalRunner.run(suite, new TrajFactory(sampleTrajectory()), "fake");
        assertEquals(1, report.passed());
        assertEquals(0, report.failed());
    }

    @Test
    void runnerReportsTrajectoryAssertionFailure() {
        var suite = EvalSuite.of("traj",
            EvalCase.of("c1", "do things", String.class,
                Scorers.calledTool("email")));
        EvalReport report = EvalRunner.run(suite, new TrajFactory(sampleTrajectory()), "fake");
        assertEquals(0, report.passed());
        assertTrue(report.results().get(0).explanation().contains("email"));
    }

    // ------------------------------------------------------------------
    // CachedJudge
    // ------------------------------------------------------------------

    static class CountingJudge implements LlmJudge {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public JudgeVerdict judge(String task, String actualOutput) {
            calls.incrementAndGet();
            return new JudgeVerdict(0.9, "good-" + actualOutput.length());
        }
    }

    @Test
    void cachedJudgeCallsDelegateOncePerDistinctInput(@TempDir Path dir) {
        Path cacheFile = dir.resolve("judge-cache.json");
        var counting = new CountingJudge();
        var judge = new CachedJudge(counting, cacheFile);

        var v1 = judge.judge("task", "answer");
        var v2 = judge.judge("task", "answer");
        assertEquals(1, counting.calls.get());
        assertEquals(v1, v2);

        judge.judge("task", "different answer");
        assertEquals(2, counting.calls.get());

        assertTrue(Files.isRegularFile(cacheFile));
        assertEquals(2, judge.cachedVerdicts());
    }

    @Test
    void cachedJudgeSurvivesAcrossInstances(@TempDir Path dir) {
        Path cacheFile = dir.resolve("judge-cache.json");
        var first = new CountingJudge();
        new CachedJudge(first, cacheFile).judge("task", "answer");
        assertEquals(1, first.calls.get());

        var second = new CountingJudge();
        var reloaded = new CachedJudge(second, cacheFile);
        var v = reloaded.judge("task", "answer");
        assertEquals(0, second.calls.get(), "verdict must come from disk, not the delegate");
        assertEquals(0.9, v.score(), 1e-9);
        assertEquals("good-6", v.rationale());
    }

    @Test
    void cachedJudgeKeysOnTaskAndOutput(@TempDir Path dir) {
        Path cacheFile = dir.resolve("judge-cache.json");
        var counting = new CountingJudge();
        var judge = new CachedJudge(counting, cacheFile);
        judge.judge("task-a", "answer");
        judge.judge("task-b", "answer");
        assertEquals(2, counting.calls.get(), "different tasks must not share a verdict");
    }

    // ------------------------------------------------------------------
    // EvalGate
    // ------------------------------------------------------------------

    private static EvalReport reportOf(String suite, boolean[] passed, double[] scores) {
        var results = new ArrayList<EvalReport.CaseResult>();
        for (int i = 0; i < passed.length; i++) {
            results.add(new EvalReport.CaseResult("case-" + i, passed[i], scores[i],
                "e", 10, 5, 0.001, 3, null));
        }
        return new EvalReport(suite, Instant.now(), "m", results);
    }

    @Test
    void gatePassesOnIdenticalReports() {
        EvalReport baseline = reportOf("s", new boolean[]{true, true}, new double[]{1.0, 0.8});
        EvalReport current = reportOf("s", new boolean[]{true, true}, new double[]{1.0, 0.8});
        assertDoesNotThrow(() -> EvalGate.assertNoRegression(current, baseline));
    }

    @Test
    void gateFailsOnScoreDrop() {
        EvalReport baseline = reportOf("s", new boolean[]{true}, new double[]{1.0});
        EvalReport current = reportOf("s", new boolean[]{true}, new double[]{0.5});
        EvalGateException e = assertThrows(EvalGateException.class,
            () -> EvalGate.assertNoRegression(current, baseline));
        assertTrue(e.getMessage().contains("case-0"));
        assertTrue(e.getMessage().contains("score_drop"));
        assertTrue(e instanceof AssertionError);
    }

    @Test
    void gateFailsOnPassToFail() {
        EvalReport baseline = reportOf("s", new boolean[]{true}, new double[]{1.0});
        EvalReport current = reportOf("s", new boolean[]{false}, new double[]{0.0});
        EvalGateException e = assertThrows(EvalGateException.class,
            () -> EvalGate.assertNoRegression(current, baseline));
        assertTrue(e.getMessage().contains("pass_to_fail"));
    }

    @Test
    void gateFailsOnNewFailingCase() {
        EvalReport baseline = reportOf("s", new boolean[]{true}, new double[]{1.0});
        var results = new ArrayList<EvalReport.CaseResult>(baseline.results());
        results.add(new EvalReport.CaseResult("case-new", false, 0.0, "boom", 1, 1, 0.0, 1, null));
        EvalReport current = new EvalReport("s", Instant.now(), "m", results);
        EvalGateException e = assertThrows(EvalGateException.class,
            () -> EvalGate.assertNoRegression(current, baseline));
        assertTrue(e.getMessage().contains("case-new"));
    }

    @Test
    void gateIgnoresPreExistingFailuresAndNoise() {
        // Failed in baseline too, same score: not a *new* failure, no drop.
        EvalReport baseline = reportOf("s", new boolean[]{false, true}, new double[]{0.2, 1.0});
        EvalReport current = reportOf("s", new boolean[]{false, true}, new double[]{0.2, 0.97});
        assertDoesNotThrow(() -> EvalGate.assertNoRegression(current, baseline));
    }

    @Test
    void gateHonorsCustomTolerance() {
        EvalReport baseline = reportOf("s", new boolean[]{true}, new double[]{1.0});
        EvalReport current = reportOf("s", new boolean[]{true}, new double[]{0.9});
        // Default tolerance (0.05) flags a 0.10 drop...
        assertThrows(EvalGateException.class,
            () -> EvalGate.assertNoRegression(current, baseline));
        // ...but a looser tolerance lets it through.
        assertDoesNotThrow(() -> EvalGate.assertNoRegression(current, baseline, 0.15));
    }
}

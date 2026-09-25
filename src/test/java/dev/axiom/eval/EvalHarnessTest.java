package dev.axiom.eval;

import dev.axiom.agent.ReActAgent;
import dev.axiom.budget.ModelPrices;
import dev.axiom.llm.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Offline tests for the eval harness: scorers, runner, persistence, diff. */
class EvalHarnessTest {

    record Sum(int sum) {}

    private static EvalContext ctx() {
        return new EvalContext("task", 5, new ChatResponse.TokenUsage(10, 5, 15));
    }

    // ------------------------------------------------------------------
    // Built-in scorers
    // ------------------------------------------------------------------

    @Test
    void exactMatch() {
        assertTrue(Scorers.exactMatch("42").score(" 42 ", ctx()).passed());
        assertFalse(Scorers.exactMatch("42").score("43", ctx()).passed());
        assertEquals(1.0, Scorers.exactMatch("a").score("a", ctx()).score());
    }

    @Test
    void containsAll() {
        var s = Scorers.containsAll("batteries", "solid-state");
        assertTrue(s.score("Solid-state batteries are promising", ctx()).passed());
        assertFalse(s.score("Batteries are promising", ctx()).passed());
    }

    @Test
    void parsesAsConformance() {
        var s = Scorers.parsesAs(Sum.class);
        assertTrue(s.score(new Sum(42), ctx()).passed());
        // The raw-JSON path the scorer supports: reach it through an
        // unchecked cast, since the type system normally prevents it.
        @SuppressWarnings("unchecked")
        Scorer<Object> raw = (Scorer<Object>) (Scorer<?>) s;
        assertTrue(raw.score("{\"sum\": 1}", ctx()).passed());
        ScoreResult bad = raw.score("not json", ctx());
        assertFalse(bad.passed());
        assertTrue(bad.explanation().contains("Sum"));
    }

    @Test
    void llmJudgeScorerHonorsThreshold() {
        LlmJudge judge = (task, output) -> new LlmJudge.JudgeVerdict(0.8, "pretty good");
        assertTrue(Scorers.llmJudge(judge, 0.5).score("x", ctx()).passed());
        assertFalse(Scorers.llmJudge(judge, 0.9).score("x", ctx()).passed());
    }

    @Test
    void openAiJudgeParsesScoreAndRationale() {
        var fake = new dev.axiom.llm.LlmClient() {
            @Override
            public dev.axiom.llm.ChatResponse chat(
                    java.util.List<dev.axiom.llm.ChatMessage> messages,
                    java.util.List<dev.axiom.tools.ToolDefinition> tools,
                    LlmOptions options) {
                return new dev.axiom.llm.ChatResponse(
                    "SCORE: 0.75\nRATIONALE: covers the main points",
                    java.util.List.of(), ChatResponse.TokenUsage.empty());
            }

            @Override
            public String model() { return "fake-judge"; }
        };
        LlmJudge.JudgeVerdict v = new OpenAiLlmJudge(fake).judge("task", "answer");
        assertEquals(0.75, v.score(), 1e-9);
        assertEquals("covers the main points", v.rationale());
    }

    // ------------------------------------------------------------------
    // Runner
    // ------------------------------------------------------------------

    /** Stub factory: no LLM, deterministic typed outputs. */
    static class StubFactory implements EvalRunner.AgentFactory {
        private final java.util.Map<String, Object> answers;

        StubFactory(java.util.Map<String, Object> answers) { this.answers = answers; }

        @Override
        @SuppressWarnings("unchecked")
        public <T> ReActAgent.TypedRun<T> runFor(String task, Class<T> outputType) {
            Object value = answers.get(task);
            if (value == null) throw new IllegalStateException("no stub for task: " + task);
            return new ReActAgent.TypedRun<>(outputType.cast(value),
                new ChatResponse.TokenUsage(100, 20, 120), 7);
        }
    }

    private EvalSuite suite() {
        return EvalSuite.of("arith",
            EvalCase.of("sum-ok", "sum", Sum.class,
                (s, c) -> s.sum() == 42 ? ScoreResult.pass("correct") : ScoreResult.fail("wrong")),
            EvalCase.of("sum-bad", "sum", Sum.class,
                (s, c) -> s.sum() == 43 ? ScoreResult.pass("correct") : ScoreResult.fail("wrong")));
    }

    @Test
    void runnerGradesCasesAndRecordsCostAndTokens() {
        var factory = new StubFactory(java.util.Map.of("sum", new Sum(42)));
        EvalReport report = EvalRunner.run(suite(), factory, "gpt-4o-mini");

        assertEquals(2, report.results().size());
        assertEquals(1, report.passed());
        assertEquals(1, report.failed());
        assertEquals(0.5, report.passRate(), 1e-9);
        // Real token accounting: 120 tokens/case * 2 cases.
        assertEquals(240, report.totalTokens());
        double expected = 2 * ModelPrices.defaults()
            .costUsd("gpt-4o-mini", 100, 20).orElseThrow();
        assertEquals(expected, report.totalCostUsd(), 1e-9);
        assertTrue(report.results().stream().allMatch(r -> r.latencyMs() >= 0));
        assertEquals("gpt-4o-mini", report.model());
    }

    @Test
    void caseExceptionsBecomeFailuresNotCrashes() {
        var factory = new StubFactory(java.util.Map.of()); // every task throws
        EvalReport report = EvalRunner.run(suite(), factory, "gpt-4o-mini");
        assertEquals(0, report.passed());
        assertEquals(2, report.failed());
        assertTrue(report.results().get(0).explanation().contains("case raised"));
    }

    // ------------------------------------------------------------------
    // Persistence + diff
    // ------------------------------------------------------------------

    @Test
    void saveLoadRoundTrip(@TempDir Path dir) {
        var factory = new StubFactory(java.util.Map.of("sum", new Sum(42)));
        EvalReport report = EvalRunner.run(suite(), factory, "gpt-4o-mini");
        Path p = dir.resolve("report.json");
        report.save(p);

        EvalReport loaded = EvalReport.load(p);
        assertEquals(report.suiteName(), loaded.suiteName());
        assertEquals(report.passed(), loaded.passed());
        assertEquals(report.totalTokens(), loaded.totalTokens());
        assertEquals(report.results().size(), loaded.results().size());
        assertEquals(report.results().get(0).caseId(), loaded.results().get(0).caseId());
    }

    private EvalReport reportOf(boolean... passed) {
        var results = new java.util.ArrayList<EvalReport.CaseResult>();
        for (int i = 0; i < passed.length; i++) {
            results.add(new EvalReport.CaseResult("case-" + i, passed[i],
                passed[i] ? 1.0 : 0.2, "e", 10, 5, 0.001, 3, null));
        }
        return new EvalReport("s", Instant.now(), "m", results);
    }

    @Test
    void diffFlagsPassToFailAndScoreDrops() {
        EvalReport baseline = reportOf(true, true, true);
        EvalReport current = new EvalReport("s", Instant.now(), "m", List.of(
            new EvalReport.CaseResult("case-0", false, 0.0, "regressed", 10, 5, 0.001, 3, null),
            new EvalReport.CaseResult("case-1", true, 0.9, "slipped", 10, 5, 0.001, 3, null),
            new EvalReport.CaseResult("case-2", true, 1.0, "fine", 10, 5, 0.001, 3, null)));

        EvalDiff diff = current.diff(baseline);
        assertTrue(diff.hasRegressions());
        assertEquals(2, diff.regressions().size());
        assertTrue(diff.regressions().stream()
            .anyMatch(r -> r.caseId().equals("case-0") && r.kind().equals("pass_to_fail")));
        assertTrue(diff.regressions().stream()
            .anyMatch(r -> r.caseId().equals("case-1") && r.kind().equals("score_drop")));
    }

    @Test
    void diffWithNoRegressionsIsClean() {
        EvalDiff diff = reportOf(true, true).diff(reportOf(true, true));
        assertFalse(diff.hasRegressions());
        assertTrue(diff.regressions().isEmpty());
    }
}

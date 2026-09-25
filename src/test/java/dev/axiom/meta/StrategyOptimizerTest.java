package dev.axiom.meta;

import dev.axiom.eval.EvalCase;
import dev.axiom.eval.EvalReport;
import dev.axiom.eval.EvalSuite;
import dev.axiom.eval.Scorers;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixture-driven tests for the self-improving loop: no network, no keys,
 * deterministic. The synthetic task family needs N sequential tool calls,
 * so {@code maxIterations} is a knob with a real, measurable effect.
 */
class StrategyOptimizerTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    public static class ChainTools {
        @Tool(description = "Run chain step N")
        public String step(@ToolParam(description = "1-based step number") int n) {
            return "step-" + n + "-done";
        }
    }

    /** Routes scripted responses by a [marker] found anywhere in the conversation. */
    static final class TaskScriptLlm implements LlmClient {
        private final Map<String, Deque<ChatResponse>> scripts;

        TaskScriptLlm(Map<String, List<ChatResponse>> scripts) {
            this.scripts = new LinkedHashMap<>();
            scripts.forEach((k, v) -> this.scripts.put(k, new ArrayDeque<>(v)));
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 LlmOptions options) {
            String convo = messages.stream()
                .map(m -> m.content() == null ? "" : m.content())
                .reduce("", (a, b) -> a + "\n" + b);
            for (Map.Entry<String, Deque<ChatResponse>> e : scripts.entrySet()) {
                if (convo.contains(e.getKey())) {
                    ChatResponse r = e.getValue().poll();
                    if (r == null) throw new AssertionError("script exhausted for " + e.getKey());
                    return r;
                }
            }
            throw new AssertionError("no script matches conversation");
        }

        @Override
        public String model() { return "test-task-script"; }
    }

    private static ChatResponse toolCall(String id, int step) {
        return new ChatResponse("calling step " + step,
            List.of(new ToolCallRequest(id, "step", Map.of("n", step))),
            new ChatResponse.TokenUsage(10, 5, 15));
    }

    private static ChatResponse finalAnswer(String text) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(10, 20, 30));
    }

    private static List<ChatResponse> chainScript(int steps, String doneText) {
        List<ChatResponse> script = new ArrayList<>();
        for (int i = 1; i <= steps; i++) script.add(toolCall("c" + i, i));
        script.add(finalAnswer(doneText));
        // runFor coerces the answer through one extra "format as JSON" LLM call,
        // even for String outputs — the script must cover it.
        script.add(finalAnswer("\"" + doneText + "\""));
        return script;
    }

    private static EvalCase<String> chainCase(String marker, int steps, String doneText) {
        return EvalCase.of("chain-" + steps,
            "Run " + steps + " chain steps in order using the step tool, then reply exactly "
                + doneText + ". [" + marker + "]",
            String.class, Scorers.exactMatch(doneText));
    }

    private static Supplier<LlmClient> fixtureFactory() {
        return () -> new TaskScriptLlm(Map.of(
            "[chain-4]", chainScript(4, "CHAIN-4-DONE"),
            "[chain-2]", chainScript(2, "CHAIN-2-DONE"),
            "[chain-3]", chainScript(3, "CHAIN-3-DONE")));
    }

    private static EvalSuite trainSuite() {
        return EvalSuite.of("chain-train",
            chainCase("chain-4", 4, "CHAIN-4-DONE"),
            chainCase("chain-2", 2, "CHAIN-2-DONE"));
    }

    private static EvalSuite holdoutSuite() {
        return EvalSuite.of("chain-holdout", chainCase("chain-3", 3, "CHAIN-3-DONE"));
    }

    private static Strategy seedTwoIterations() {
        return new Strategy(2, 4, 1_000, 2.0,
            Strategy.PlanningHint.NONE, Strategy.GuardrailStrictness.STANDARD);
    }

    private static StrategyOptimizer.Config testConfig() {
        return new StrategyOptimizer.Config(8, 6, 2, 1e-9, 0.05, 7L);
    }

    private static StrategyOptimizer.OptimizationResult runOptimization(Strategy seed) {
        var trainEval = new StrategyEvaluator(trainSuite(), fixtureFactory(), "test-fixture",
            new ChainTools());
        var holdoutEval = new StrategyEvaluator(holdoutSuite(), fixtureFactory(), "test-fixture",
            new ChainTools());
        return StrategyOptimizer.optimize(seed, trainEval, holdoutEval,
            MutationOperators.all(), testConfig());
    }

    // ------------------------------------------------------------------
    // The loop improves a real knob
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    void optimizerRaisesMaxIterationsToPassChain() {
        Strategy seed = seedTwoIterations();
        double seedMean = new StrategyEvaluator(trainSuite(), fixtureFactory(), "t", new ChainTools())
            .evaluate(seed).meanScore();
        assertEquals(0.5, seedMean, 1e-9, "seed (2 iterations) should pass chain-2 but fail chain-4");

        StrategyOptimizer.OptimizationResult result = runOptimization(seed);

        assertTrue(result.champion().maxIterations() >= 4,
            "champion should allow at least 4 turns, got " + result.champion().maxIterations());
        assertEquals(1.0, result.trainReport().meanScore(), 1e-9);
        assertEquals(1.0, result.holdoutReport().meanScore(), 1e-9,
            "improvement should generalize to the unseen chain-3 holdout");
        assertTrue(result.receipt().improved());
        assertFalse(result.receipt().attempts().isEmpty());
        // The audit trail names the winning mutation.
        boolean namesBump = result.receipt().attempts().stream()
            .flatMap(a -> a.generations().stream())
            .flatMap(g -> g.candidates().stream())
            .anyMatch(c -> c.verdict().startsWith("accepted")
                && c.mutation().knob().equals("maxIterations"));
        assertTrue(namesBump, "receipt should record the accepted maxIterations mutation");
    }

    @Test
    @Timeout(120)
    void seedAlreadyOptimalIsAHonestNoImprovement() {
        Strategy seed = Strategy.defaults(); // 15 iterations: passes the easy chain-2 task
        EvalSuite easy = EvalSuite.of("easy", chainCase("chain-2", 2, "CHAIN-2-DONE"));
        var trainEval = new StrategyEvaluator(easy, fixtureFactory(), "t", new ChainTools());
        var holdoutEval = new StrategyEvaluator(easy, fixtureFactory(), "t", new ChainTools());
        StrategyOptimizer.OptimizationResult result = StrategyOptimizer.optimize(
            seed, trainEval, holdoutEval, MutationOperators.all(), testConfig());

        assertEquals(seed, result.champion(), "nothing should beat an already-optimal seed");
        assertFalse(result.receipt().improved());
        assertTrue(result.receipt().notes().contains("No mutation beat the seed"));
    }

    // ------------------------------------------------------------------
    // The gate never lets a regression through
    // ------------------------------------------------------------------

    private static EvalReport reportOf(boolean c1Pass, boolean c2Pass) {
        return new EvalReport("s", Instant.now(), "m", List.of(
            new EvalReport.CaseResult("c1", c1Pass, c1Pass ? 1.0 : 0.0, "c1", 0, 0, 0.0, 0, null),
            new EvalReport.CaseResult("c2", c2Pass, c2Pass ? 1.0 : 0.0, "c2", 0, 0, 0.0, 0, null)));
    }

    @Test
    void evalGateBlocksRegressingChallenger() {
        Strategy seed = Strategy.defaults();
        EvalReport good = reportOf(true, true);
        EvalReport regressed = reportOf(true, false); // c2 flips pass -> fail
        StrategyOptimizer.Evaluator fake = s -> s.equals(seed) ? good : regressed;

        StrategyOptimizer.OptimizationResult result = StrategyOptimizer.optimize(
            seed, fake, fake, MutationOperators.all(), testConfig());

        assertEquals(seed, result.champion(),
            "a challenger that regresses any case must never become champion");
    }

    @Test
    void judgeAcceptsStrictImprovementAndTieBreaksTowardSimplicity() {
        var cfg = new StrategyOptimizer.Config(1, 1, 0, 1e-9, 0.05, 1L);
        Strategy best = Strategy.defaults();
        EvalReport bestReport = reportOf(true, true);

        // Strictly better mean score: accepted.
        Strategy better = new Strategy(10, 4, 1_000, 2.0,
            Strategy.PlanningHint.NONE, Strategy.GuardrailStrictness.STANDARD);
        String v = StrategyOptimizer.judge(better, reportOf(true, true), best, bestReport, cfg);
        // identical reports here: tie at complexity 1 vs 0 -> rejected (not simpler)
        assertTrue(v.startsWith("rejected"), "tie with higher complexity must be rejected, got: " + v);

        // Tie where the challenger is simpler: accepted.
        Strategy complexBest = new Strategy(10, 3, 1_000, 2.0,
            Strategy.PlanningHint.FEWER_TOOLS, Strategy.GuardrailStrictness.STANDARD);
        String v2 = StrategyOptimizer.judge(best, bestReport, complexBest, bestReport, cfg);
        assertTrue(v2.startsWith("accepted"), "tie with simpler strategy must be accepted, got: " + v2);

        // Regression: rejected even with a higher mean elsewhere.
        EvalReport mixed = new EvalReport("s", Instant.now(), "m", List.of(
            new EvalReport.CaseResult("c1", false, 0.0, "regressed", 0, 0, 0.0, 0, null),
            new EvalReport.CaseResult("c2", true, 1.0, "ok", 0, 0, 0.0, 0, null)));
        String v3 = StrategyOptimizer.judge(better, mixed, best, bestReport, cfg);
        assertTrue(v3.startsWith("rejected: regression"), "regression must be rejected, got: " + v3);
    }

    // ------------------------------------------------------------------
    // Knobs, operators, persistence
    // ------------------------------------------------------------------

    @Test
    void strategyJsonRoundTrips() {
        Strategy s = new Strategy(7, 3, 500, 1.5,
            Strategy.PlanningHint.VERIFY_BEFORE_ANSWERING, Strategy.GuardrailStrictness.STRICT);
        assertEquals(s, Strategy.fromJson(s.toJson()));
        assertEquals(Strategy.defaults(), Strategy.fromJson(Strategy.defaults().toJson()));
    }

    @Test
    void strategyValidationRejectsOutOfBounds() {
        assertThrows(IllegalArgumentException.class, () ->
            new Strategy(0, 4, 1_000, 2.0, Strategy.PlanningHint.NONE, Strategy.GuardrailStrictness.STANDARD));
        assertThrows(IllegalArgumentException.class, () ->
            new Strategy(15, 9, 1_000, 2.0, Strategy.PlanningHint.NONE, Strategy.GuardrailStrictness.STANDARD));
    }

    @Test
    void planningHintLandsInSystemPrompt() {
        assertEquals(Strategy.BASE_SYSTEM_PROMPT, Strategy.defaults().systemPrompt());
        Strategy hinted = new Strategy(15, 4, 1_000, 2.0,
            Strategy.PlanningHint.FEWER_TOOLS, Strategy.GuardrailStrictness.STANDARD);
        assertTrue(hinted.systemPrompt().startsWith(Strategy.BASE_SYSTEM_PROMPT));
        assertTrue(hinted.systemPrompt().contains("few tool calls"));
    }

    @Test
    void operatorsRespectBoundsUnderFuzz() {
        Random rng = new Random(1234);
        List<Strategy> seeds = List.of(
            Strategy.defaults(),
            new Strategy(1, 1, 50, 1.0, Strategy.PlanningHint.NONE, Strategy.GuardrailStrictness.STANDARD),
            new Strategy(30, 8, 30_000, 4.0, Strategy.PlanningHint.THINK_STEP_BY_STEP,
                Strategy.GuardrailStrictness.STRICT));
        for (MutationOperator op : MutationOperators.all()) {
            for (Strategy seed : seeds) {
                for (int i = 0; i < 50; i++) {
                    Optional<MutatedStrategy> m = op.mutate(seed, rng);
                    // No exception thrown (record validation would fail the test),
                    // and empty means "not applicable", which is always legal.
                    m.ifPresent(ms -> {
                        assertNotNull(ms.mutation().knob());
                        assertNotEquals(ms.strategy(), seed);
                    });
                }
            }
        }
    }

    @Test
    void receiptSaveLoadRoundTrips(@TempDir Path dir) {
        StrategyOptimizer.OptimizationResult result = runOptimization(seedTwoIterations());
        Path receiptPath = dir.resolve("optimization-receipt.json");
        result.receipt().save(receiptPath);
        assertTrue(Files.exists(receiptPath));

        OptimizationReceipt loaded = OptimizationReceipt.load(receiptPath);
        assertEquals(result.receipt().championStrategy(), loaded.championStrategy());
        assertEquals(result.receipt().seedStrategy(), loaded.seedStrategy());
        assertEquals(result.receipt().championTrain().meanScore(),
            loaded.championTrain().meanScore(), 1e-9);
        assertEquals(result.receipt().championHoldout().meanScore(),
            loaded.championHoldout().meanScore(), 1e-9);
    }

    @Test
    @Timeout(180)
    void optimizationIsReproducible(@TempDir Path dir) {
        StrategyOptimizer.OptimizationResult r1 = runOptimization(seedTwoIterations());
        StrategyOptimizer.OptimizationResult r2 = runOptimization(seedTwoIterations());
        assertEquals(r1.champion(), r2.champion(),
            "same seed + same fixtures must yield the same champion");
        assertEquals(r1.trainReport().meanScore(), r2.trainReport().meanScore(), 1e-9);
    }

    @Test
    void complexityCountsNonDefaultKnobs() {
        assertEquals(0, Strategy.defaults().complexity());
        assertEquals(1, seedTwoIterations().complexity());
        assertEquals(6, new Strategy(7, 3, 500, 1.5,
            Strategy.PlanningHint.FEWER_TOOLS, Strategy.GuardrailStrictness.STRICT).complexity());
    }
}

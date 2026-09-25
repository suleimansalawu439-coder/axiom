package dev.axiom.demo;

import dev.axiom.eval.EvalCase;
import dev.axiom.eval.EvalReport;
import dev.axiom.eval.EvalSuite;
import dev.axiom.eval.Scorers;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.meta.MutationOperators;
import dev.axiom.meta.OptimizationReceipt;
import dev.axiom.meta.Strategy;
import dev.axiom.meta.StrategyEvaluator;
import dev.axiom.meta.StrategyOptimizer;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Demo: eval-driven strategy mutation (the "self-improving loop").
 *
 * <p>A synthetic task family needs N sequential tool calls. The seed
 * strategy caps the ReAct loop at 2 iterations, so the 4-step chain fails.
 * The optimizer mutates harness knobs, measures each challenger on the
 * train suite with fixture LLMs (free, deterministic), keeps winners only
 * when {@code EvalGate} reports no regression, and finally validates the
 * champion on held-out tasks.
 *
 * <p>Run with:
 * <pre>
 *   java -cp target/axiom-0.9.0.jar:lib/jackson-databind-2.17.2.jar:lib/jackson-core-2.17.2.jar:lib/jackson-annotations-2.17.2.jar:lib/slf4j-api-2.0.13.jar:lib/slf4j-simple-2.0.13.jar \
 *     dev.axiom.demo.StrategyOptimizerDemo
 * </pre>
 * No network, no API keys.
 */
public final class StrategyOptimizerDemo {

    // ------------------------------------------------------------------
    // Tools: a chain where step N must run before step N+1 can.
    // ------------------------------------------------------------------

    public static class ChainTools {
        @Tool(description = "Run chain step N; returns the token for the next step")
        public String step(@ToolParam(description = "1-based step number") int n) {
            return "step-" + n + "-done";
        }
    }

    // ------------------------------------------------------------------
    // Scripted LLM: routes by a [marker] in the task text, so one factory
    // serves a whole suite with a fresh script per case per evaluation.
    // ------------------------------------------------------------------

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
        public String model() { return "demo-task-script"; }
    }

    private static ChatResponse toolCall(String id, int step) {
        return new ChatResponse("calling step " + step,
            List.of(new ToolCallRequest(id, "step", Map.of("n", step))),
            new ChatResponse.TokenUsage(10, 5, 15));
    }

    private static ChatResponse finalAnswer(String text) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(10, 20, 30));
    }

    /** Script for a chain of {@code steps} ending with {@code doneText}. */
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

    // ------------------------------------------------------------------
    // Demo
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        Supplier<LlmClient> fixtureFactory = () -> new TaskScriptLlm(Map.of(
            "[chain-4]", chainScript(4, "CHAIN-4-DONE"),
            "[chain-2]", chainScript(2, "CHAIN-2-DONE"),
            "[chain-3]", chainScript(3, "CHAIN-3-DONE")));

        EvalSuite train = EvalSuite.of("chain-train",
            chainCase("chain-4", 4, "CHAIN-4-DONE"),
            chainCase("chain-2", 2, "CHAIN-2-DONE"));
        EvalSuite holdout = EvalSuite.of("chain-holdout",
            chainCase("chain-3", 3, "CHAIN-3-DONE"));

        var trainEval = new StrategyEvaluator(train, fixtureFactory, "demo-fixture", new ChainTools());
        var holdoutEval = new StrategyEvaluator(holdout, fixtureFactory, "demo-fixture", new ChainTools());

        Strategy seed = new Strategy(2, 4, 1_000, 2.0,
            Strategy.PlanningHint.NONE, Strategy.GuardrailStrictness.STANDARD);

        System.out.println("=== Axiom self-improving loop demo (v0.9.0) ===");
        System.out.println("Seed strategy : " + seed.toJson());

        EvalReport seedReport = trainEval.evaluate(seed);
        System.out.printf("Seed train    : mean %.2f, pass rate %.2f (%d/%d)%n",
            seedReport.meanScore(), seedReport.passRate(), seedReport.passed(),
            seedReport.results().size());

        var config = new StrategyOptimizer.Config(
            /* generations */ 8, /* challengersPerGeneration */ 6,
            /* restarts */ 2, /* minDelta */ 1e-9,
            /* scoreTolerance */ 0.05, /* seed */ 7L);

        StrategyOptimizer.OptimizationResult result = StrategyOptimizer.optimize(
            seed, trainEval, holdoutEval, MutationOperators.all(), config);

        System.out.println("Champion      : " + result.champion().toJson());
        System.out.printf("Champion train: mean %.2f, pass rate %.2f%n",
            result.trainReport().meanScore(), result.trainReport().passRate());
        System.out.printf("Champion holdout (unseen chain-3): mean %.2f, pass rate %.2f%n",
            result.holdoutReport().meanScore(), result.holdoutReport().passRate());

        System.out.println("--- accepted mutations ---");
        for (OptimizationReceipt.AttemptRecord a : result.receipt().attempts()) {
            for (OptimizationReceipt.GenerationRecord g : a.generations()) {
                for (OptimizationReceipt.CandidateRecord c : g.candidates()) {
                    if (c.verdict().startsWith("accepted")) {
                        System.out.println("  [gen " + g.generation() + "] " + c.mutation()
                            + " -> " + c.verdict());
                    }
                }
            }
        }
        System.out.println("Notes: " + result.receipt().notes());

        Path outDir = Files.createTempDirectory("axiom-meta-demo");
        Files.writeString(outDir.resolve("strategy.json"), result.champion().toJson());
        result.receipt().save(outDir.resolve("optimization-receipt.json"));
        System.out.println("Wrote strategy.json + optimization-receipt.json to " + outDir);
    }
}

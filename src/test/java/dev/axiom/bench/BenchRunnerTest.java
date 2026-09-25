package dev.axiom.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.Axiom;
import dev.axiom.agent.ApprovalHandler;
import dev.axiom.budget.ModelPrices;
import dev.axiom.tools.SubprocessTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/** Offline benchmark tests: fixture loading, both task styles, receipts. */
class BenchRunnerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private BiFunction<BenchTask, Path, BenchAgent> factory() {
        return (task, workdir) -> {
            var b = Axiom.agent()
                .withClient(FixtureLlm.loadResource("/bench/fixtures/" + task.id() + ".json"))
                .withApprovalHandler(ApprovalHandler.allowAll());
            switch (task.id()) {
                case "gaia-arithmetic", "gaia-two-step" -> b.withTools(new BenchMain.CalcTools());
                case "gaia-file-lookup" -> b.withTools(new BenchMain.FileTools(workdir));
                case "swe-fix-greeting" -> b.withTools(SubprocessTool.builder(workdir)
                    .allowCommands("sh", "grep", "cat", "ls", "printf", "echo")
                    .build());
                default -> throw new BenchException("Unknown task: " + task.id());
            }
            return new Axiom.Agent(b.build())::run;
        };
    }

    @Test
    void fixtureBenchmarkRunsGaiaAndSweTasksToGreen() {
        List<BenchTask> tasks = List.of(
            BenchTask.gaia("gaia-arithmetic", "What is 17 * 23 + 5? Reply with just the number.", "396"),
            BenchTask.gaia("gaia-two-step", "What is 6 * 7? Then add 8 to your result. Reply with just the final number.", "50"),
            BenchTask.gaiaWithFiles("gaia-file-lookup",
                "Read the file 'data.txt' in the workspace and tell me which city "
                    + "is named as the capital. Reply with just the city name.",
                "/bench/tasks/file-lookup", "Abuja"),
            BenchTask.swe("swe-fix-greeting",
                "The file greet.txt must contain exactly 'Hello, world!'. "
                    + "Use the run tool to fix it, then reply Done.",
                "/bench/tasks/swe-greeting", "sh test.sh"));

        BenchReceipt receipt = BenchRunner.run(tasks, factory(),
            ModelPrices.defaults(), "fixture", "fixture");

        assertEquals(4, receipt.results().size());
        assertEquals(4, receipt.passed(), () -> receipt.toString());
        assertEquals(0, receipt.failed());
        assertEquals("fixture", receipt.mode());
        assertEquals("axiom", receipt.framework());
        assertEquals(dev.axiom.Version.CURRENT, receipt.frameworkVersion());
        // Tokens and latency were recorded for every task.
        assertTrue(receipt.results().stream().allMatch(r -> r.latencyMs() >= 0));
    }

    @Test
    void missingFixtureIsAHarnessErrorNotAHang(@TempDir Path dir) {
        List<BenchTask> tasks = List.of(
            BenchTask.gaia("no-such-task", "x", "y"));
        BenchReceipt receipt = BenchRunner.run(tasks, factory(),
            ModelPrices.defaults(), "fixture", "fixture");
        assertEquals(1, receipt.failed());
        assertTrue(receipt.results().get(0).detail().contains("harness error"));
    }

    @Test
    void sweTaskFailsWhenTestCommandFails(@TempDir Path dir) {
        List<BenchTask> tasks = List.of(
            BenchTask.swe("broken", "do nothing", null, "exit 3"));
        BiFunction<BenchTask, Path, BenchAgent> f = (task, workdir) ->
            new Axiom.Agent(Axiom.agent().withClient(new dev.axiom.llm.LlmClient() {
                @Override
                public dev.axiom.llm.ChatResponse chat(
                        java.util.List<dev.axiom.llm.ChatMessage> messages,
                        java.util.List<dev.axiom.tools.ToolDefinition> tools,
                        LlmOptions options) {
                    return new dev.axiom.llm.ChatResponse("Done", java.util.List.of(),
                        dev.axiom.llm.ChatResponse.TokenUsage.empty());
                }

                @Override
                public String model() { return "stub"; }
            }).build())::run;
        BenchReceipt receipt = BenchRunner.run(tasks, f, ModelPrices.defaults(), "stub", "test");
        assertEquals(1, receipt.failed());
        assertTrue(receipt.results().get(0).detail().contains("exited 3"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void receiptPersistsAsMachineReadableJson(@TempDir Path dir) throws Exception {
        List<BenchTask> tasks = List.of(
            BenchTask.gaia("gaia-arithmetic", "What is 17 * 23 + 5? Reply with just the number.", "396"));
        BenchReceipt receipt = BenchRunner.run(tasks, factory(),
            ModelPrices.defaults(), "fixture", "fixture");

        Path p = dir.resolve("receipt.json");
        receipt.save(p);
        assertTrue(Files.isRegularFile(p));

        Map<String, Object> m = JSON.readValue(p.toFile(), Map.class);
        assertEquals("axiom", m.get("framework"));
        assertEquals(dev.axiom.Version.CURRENT, m.get("frameworkVersion"));
        assertEquals("fixture", m.get("mode"));
        assertEquals(1, ((Map<String, Object>) m.get("totals")).get("passed"));
        List<Map<String, Object>> results = (List<Map<String, Object>>) m.get("results");
        assertEquals(1, results.size());
        assertEquals("gaia-arithmetic", results.get(0).get("taskId"));
        assertEquals(true, results.get(0).get("passed"));
        assertTrue(results.get(0).containsKey("promptTokens"));
        assertTrue(results.get(0).containsKey("latencyMs"));
    }

    @Test
    void freeTierPresetsResolve() {
        BenchProvider.Preset gemini = BenchProvider.of("gemini");
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", gemini.baseUrl());
        assertEquals("GEMINI_API_KEY", gemini.apiKeyEnv());
        assertTrue(gemini.needsKey());

        BenchProvider.Preset ollama = BenchProvider.of("ollama");
        assertFalse(ollama.needsKey());

        assertThrows(BenchException.class, () -> BenchProvider.of("nope"));
    }

    @Test
    void pacingAndNotesLandInReceipt() {
        List<BenchTask> tasks = List.of(
            BenchTask.gaia("gaia-arithmetic", "What is 17 * 23 + 5? Reply with just the number.", "396"),
            BenchTask.gaia("gaia-two-step", "What is 6 * 7? Then add 8 to your result. Reply with just the final number.", "50"));
        long pacingMs = 250;
        long start = System.currentTimeMillis();
        BenchReceipt receipt = BenchRunner.run(tasks, factory(),
            ModelPrices.defaults(), "gemini-2.0-flash", "live-gemini", pacingMs,
            "honesty disclosure for tests");
        long elapsed = System.currentTimeMillis() - start;

        // One pause between the two tasks.
        assertTrue(elapsed >= pacingMs,
            "expected at least " + pacingMs + "ms of pacing, took " + elapsed + "ms");
        assertEquals("live-gemini", receipt.mode());
        assertEquals("honesty disclosure for tests", receipt.notes());
        assertTrue(receipt.toString().contains("honesty disclosure for tests"));
    }
}

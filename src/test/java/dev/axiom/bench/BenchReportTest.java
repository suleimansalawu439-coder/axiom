package dev.axiom.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.Axiom;
import dev.axiom.agent.AgentEvent;
import dev.axiom.agent.AgentResult;
import dev.axiom.agent.ApprovalHandler;
import dev.axiom.bench.BenchReceipt.TaskResult;
import dev.axiom.budget.ModelPrices;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.LlmClient.LlmOptions;
import dev.axiom.tools.SubprocessTool;
import dev.axiom.tools.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/** Fully detailed reports: per-task traces in JSON and the Markdown report. */
class BenchReportTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Factory mirroring BenchMain: captures agent events for the trace. */
    private BiFunction<BenchTask, Path, BenchAgent> factory() {
        return (task, workdir) -> {
            List<AgentEvent> events = new ArrayList<>();
            var b = Axiom.agent()
                .withClient(FixtureLlm.loadResource("/bench/fixtures/" + task.id() + ".json"))
                .withApprovalHandler(ApprovalHandler.allowAll())
                .onEvent(events::add);
            switch (task.id()) {
                case "gaia-arithmetic", "gaia-two-step" -> b.withTools(new BenchMain.CalcTools());
                case "gaia-file-lookup" -> b.withTools(new BenchMain.FileTools(workdir));
                case "swe-fix-greeting" -> b.withTools(SubprocessTool.builder(workdir)
                    .allowCommands("sh", "grep", "cat", "ls", "printf", "echo")
                    .build());
                default -> throw new BenchException("Unknown task: " + task.id());
            }
            var agent = new Axiom.Agent(b.build());
            return new BenchAgent() {
                @Override
                public AgentResult run(String prompt) { return agent.run(prompt); }

                @Override
                public List<AgentEvent> events() { return List.copyOf(events); }
            };
        };
    }

    private BenchReceipt fixtureReceipt() {
        List<BenchTask> tasks = List.of(
            BenchTask.gaia("gaia-arithmetic",
                "What is 17 * 23 + 5? Reply with just the number.", "396"));
        return BenchRunner.run(tasks, factory(), ModelPrices.defaults(), "fixture", "fixture");
    }

    @Test
    void traceCapturesModelTextAndToolCalls() {
        TaskResult r = fixtureReceipt().results().get(0);
        assertTrue(r.passed());
        assertFalse(r.trace().isEmpty(), "expected a step-by-step trace");

        Map<String, Object> first = r.trace().get(0);
        assertEquals(1, first.get("iteration"), "agent iterations are 1-based");
        assertTrue(((String) first.get("modelText")).contains("multiply"),
            "model text recorded: " + first.get("modelText"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) first.get("toolCalls");
        assertEquals(1, calls.size());
        assertEquals("bench_multiply", calls.get(0).get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) calls.get(0).get("arguments");
        assertEquals(17, ((Number) args.get("x")).intValue());
        assertEquals(23, ((Number) args.get("y")).intValue());
        assertEquals("391", String.valueOf(calls.get(0).get("result")).trim());
    }

    @Test
    void receiptJsonCarriesFullDetail() throws Exception {
        BenchReceipt receipt = fixtureReceipt();
        String json = JSON.writeValueAsString(receipt.toJsonMap());

        @SuppressWarnings("unchecked")
        Map<String, Object> m = JSON.readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) ((List<?>) m.get("results")).get(0);
        assertEquals("What is 17 * 23 + 5? Reply with just the number.", r.get("prompt"));
        assertEquals("396", r.get("expected"));
        assertTrue(r.containsKey("trace"));
        assertTrue(r.containsKey("testOutput"));
        assertTrue(r.containsKey("error"));
        assertFalse(((List<?>) r.get("trace")).isEmpty());
    }

    @Test
    void markdownReportIsFullyDetailed() {
        String md = BenchReport.markdown(fixtureReceipt());
        assertTrue(md.contains("# Benchmark report"));
        assertTrue(md.contains("gaia-arithmetic"));
        assertTrue(md.contains("[PASS]"));
        assertTrue(md.contains("What is 17 * 23 + 5?"));
        assertTrue(md.contains("bench_multiply"), "tool call in trace");
        assertTrue(md.contains("391"), "tool result in trace");
        assertTrue(md.contains("Iteration 1"));
        assertTrue(md.contains("396"), "final output in report");
    }

    @Test
    void sweTaskCapturesTestCommandOutput() {
        LlmClient stub = new LlmClient() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                     LlmOptions options) {
                return new ChatResponse("Done", List.of(), ChatResponse.TokenUsage.empty());
            }

            @Override
            public String model() { return "stub"; }
        };
        BiFunction<BenchTask, Path, BenchAgent> f = (task, workdir) ->
            new Axiom.Agent(Axiom.agent().withClient(stub).build())::run;
        List<BenchTask> tasks = List.of(
            BenchTask.swe("noisy", "do nothing", null, "echo hello-test-output; exit 3"));
        BenchReceipt receipt = BenchRunner.run(tasks, f, ModelPrices.defaults(), "stub", "test");

        TaskResult r = receipt.results().get(0);
        assertFalse(r.passed());
        assertTrue(r.detail().contains("exited 3"));
        assertNotNull(r.testOutput());
        assertTrue(r.testOutput().contains("hello-test-output"),
            "test command output captured: " + r.testOutput());

        String md = BenchReport.markdown(receipt);
        assertTrue(md.contains("hello-test-output"));
    }

    @Test
    void harnessErrorCarriesFullStackTrace() {
        List<BenchTask> tasks = List.of(BenchTask.gaia("no-such-task", "x", "y"));
        BenchReceipt receipt = BenchRunner.run(tasks, factory(),
            ModelPrices.defaults(), "fixture", "fixture");

        TaskResult r = receipt.results().get(0);
        assertFalse(r.passed());
        assertTrue(r.detail().startsWith("harness error:"));
        assertNotNull(r.error());
        assertTrue(r.error().contains("at dev.axiom."),
            "full stack trace recorded, got: " + r.error().substring(0, Math.min(200, r.error().length())));

        String md = BenchReport.markdown(receipt);
        assertTrue(md.contains("Harness error"));
    }

    @Test
    void agentsWithoutEventsGetAnEmptyTrace() {
        // A plain method-ref BenchAgent exposes no events: the trace must be
        // empty, not a failure.
        BiFunction<BenchTask, Path, BenchAgent> f = (task, workdir) ->
            new Axiom.Agent(Axiom.agent()
                .withClient(FixtureLlm.loadResource("/bench/fixtures/" + task.id() + ".json"))
                .withApprovalHandler(ApprovalHandler.allowAll())
                .withTools(new BenchMain.CalcTools())
                .build())::run;
        List<BenchTask> tasks = List.of(
            BenchTask.gaia("gaia-arithmetic",
                "What is 17 * 23 + 5? Reply with just the number.", "396"));
        BenchReceipt receipt = BenchRunner.run(tasks, f, ModelPrices.defaults(), "fixture", "fixture");
        TaskResult r = receipt.results().get(0);
        assertTrue(r.passed());
        assertTrue(r.trace().isEmpty());
    }
}

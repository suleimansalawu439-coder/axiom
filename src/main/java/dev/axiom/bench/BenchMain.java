package dev.axiom.bench;

import dev.axiom.Axiom;
import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.ApprovalHandler;
import dev.axiom.budget.ModelPrices;
import dev.axiom.tools.SubprocessTool;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Runnable benchmark entry point.
 *
 * <pre>
 * # Offline / deterministic (default): scripted model fixtures, real tool execution
 * java -cp "target/axiom-0.3.0.jar:lib/*" dev.axiom.bench.BenchMain
 *
 * # Live: real model via OPENAI_API_KEY (or any OpenAI-compatible endpoint)
 * AXIOM_BENCH_MODEL=gpt-4o-mini OPENAI_API_KEY=sk-... \
 *   java -cp "target/axiom-0.3.0.jar:lib/*" dev.axiom.bench.BenchMain --live
 * </pre>
 *
 * Writes a machine-readable receipt to
 * {@code benchmarks/receipts/receipt-<mode>-<timestamp>.json} and prints a
 * human-readable summary.
 */
public final class BenchMain {

    /** The bundled benchmark tasks: 3 GAIA-style + 1 SWE-bench-style. */
    public static List<BenchTask> tasks() {
        return List.of(
            BenchTask.gaia("gaia-arithmetic",
                "What is 17 * 23 + 5? Reply with just the number.",
                "396"),
            BenchTask.gaia("gaia-two-step",
                "What is 6 * 7? Then add 8 to your result. Reply with just the final number.",
                "50"),
            BenchTask.gaiaWithFiles("gaia-file-lookup",
                "Read the file 'data.txt' in the workspace and tell me which city "
                    + "is named as the capital. Reply with just the city name.",
                "/bench/tasks/file-lookup",
                "Abuja"),
            BenchTask.swe("swe-fix-greeting",
                "The file greet.txt must contain exactly 'Hello, world!'. "
                    + "Use the run tool to fix it, then reply Done.",
                "/bench/tasks/swe-greeting",
                "sh test.sh")
        );
    }

    public static void main(String[] args) {
        boolean live = List.of(args).contains("--live");
        String model = System.getenv().getOrDefault("AXIOM_BENCH_MODEL", "gpt-4o-mini");
        String mode = live ? "live" : "fixture";

        BiFunction<BenchTask, Path, BenchAgent> factory = (task, workdir) -> {
            AgentConfig.Builder b = Axiom.agent()
                .withApprovalHandler(ApprovalHandler.allowAll());
            if (live) {
                b.withModel(model);
            } else {
                b.withClient(FixtureLlm.loadResource("/bench/fixtures/" + task.id() + ".json"));
            }
            switch (task.id()) {
                case "gaia-arithmetic", "gaia-two-step" -> b.withTools(new CalcTools());
                case "gaia-file-lookup" -> b.withTools(new FileTools(workdir));
                case "swe-fix-greeting" -> b.withTools(SubprocessTool.builder(workdir)
                    .allowCommands("sh", "grep", "cat", "ls", "printf", "echo")
                    .build());
                default -> throw new BenchException("Unknown task: " + task.id());
            }
            return new Axiom.Agent(b.build())::run;
        };

        String receiptModel = live ? model : "fixture";
        BenchReceipt receipt = BenchRunner.run(tasks(), factory,
            ModelPrices.defaults(), receiptModel, mode);

        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC).format(Instant.now());
        Path receiptPath = Paths.get("benchmarks", "receipts",
            "receipt-" + mode + "-" + stamp + ".json");
        receipt.save(receiptPath);

        System.out.println(receipt);
        System.out.println("Receipt: " + receiptPath.toAbsolutePath());
    }

    // ------------------------------------------------------------------
    // Benchmark tool holders (validated at compile time by ToolProcessor)
    // ------------------------------------------------------------------

    static class CalcTools {
        @Tool(description = "Multiply two numbers")
        public int multiply(@ToolParam(description = "x") int x,
                            @ToolParam(description = "y") int y) {
            return x * y;
        }

        @Tool(description = "Add two numbers")
        public int add(@ToolParam(description = "x") int x,
                       @ToolParam(description = "y") int y) {
            return x + y;
        }
    }

    static class FileTools {
        private final Path root;

        FileTools(Path root) {
            this.root = root;
        }

        @Tool(description = "Read a text file from the workspace")
        public String readFile(@ToolParam(description = "File name, e.g. data.txt") String name)
                throws Exception {
            Path p = root.resolve(name).normalize();
            if (!p.startsWith(root)) throw new SecurityException("path escapes workspace");
            return Files.readString(p);
        }
    }
}

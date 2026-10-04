package dev.axiom.bench;

import dev.axiom.Axiom;
import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentEvent;
import dev.axiom.agent.AgentResult;
import dev.axiom.agent.ApprovalHandler;
import dev.axiom.budget.ModelPrices;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.GeminiNativeClient;
import dev.axiom.llm.OpenAiCompatibleClient;
import dev.axiom.resilience.RetryPolicy;
import dev.axiom.resilience.RetryingLlmClient;
import dev.axiom.tools.SubprocessTool;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Runnable benchmark entry point.
 *
 * <pre>
 * # Offline / deterministic (default): scripted model fixtures, real tool execution
 * java -cp "target/axiom-0.5.2.jar:lib/*" dev.axiom.bench.BenchMain
 *
 * # Live on a free tier: Gemini (free, no card), OpenRouter (:free models),
 * # Groq (free tier), or a local Ollama server — $0 end to end
 * export GEMINI_API_KEY=...   # from https://aistudio.google.com/apikey
 * AXIOM_BENCH_PROVIDER=gemini \
 *   java -cp "target/axiom-0.5.2.jar:lib/*" dev.axiom.bench.BenchMain --live
 *
 * # Any other OpenAI-compatible endpoint / paid key:
 * AXIOM_BENCH_PROVIDER=openai AXIOM_BENCH_MODEL=gpt-4o-mini OPENAI_API_KEY=sk-... \
 *   java -cp "target/axiom-0.5.2.jar:lib/*" dev.axiom.bench.BenchMain --live
 *
 * # Speed knobs (live runs default to parallel tasks):
 * #   AXIOM_BENCH_PARALLEL=4   tasks running concurrently (default: task count)
 * #   AXIOM_BENCH_RPM=5        shared requests/minute across all tasks (default: preset)
 * #   --sequential             one task at a time (overrides AXIOM_BENCH_PARALLEL)
 * </pre>
 *
 * <p>Keys come from environment variables only — never paste a key into chat
 * or commit one to a file. The provider list and key sources are documented in
 * {@link BenchProvider}.
 *
 * <p>Writes a machine-readable receipt to
 * {@code benchmarks/receipts/receipt-<mode>-<timestamp>.json} and prints a
 * human-readable summary. Live receipts carry a honesty disclosure (subset
 * scope, provider tier, pacing, cost basis) — a 4-task representative subset
 * is never presented as a full GAIA/SWE-bench score.
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
        String providerId = System.getenv().getOrDefault("AXIOM_BENCH_PROVIDER", "openai");
        BenchProvider.Preset preset = live
            ? BenchProvider.of(providerId)
            : null;
        String model = live
            ? System.getenv().getOrDefault("AXIOM_BENCH_MODEL", preset.defaultModel())
            : "fixture";
        String mode = live ? "live-" + preset.id() : "fixture";

        LlmClient liveClient = null;
        String apiKey = null;
        BenchRunConfig runConfig = BenchRunConfig.sequential();
        double rpm = 0;
        if (live) {
            if (preset.needsKey()) {
                apiKey = System.getenv(preset.apiKeyEnv());
                if (apiKey == null || apiKey.isBlank()) {
                    System.err.println("""
                        Missing API key: %s is not set.
                        Get a key at %s, then:
                          export %s=...   (never paste it into chat or commit it)
                        Or use the keyless local option: AXIOM_BENCH_PROVIDER=ollama"""
                        .formatted(preset.apiKeyEnv(), preset.keySignup(), preset.apiKeyEnv()));
                    System.exit(2);
                }
            }
            // Gemini must use the native client: the vault credential's
            // placement is ?key= (the proxy only swaps the surrogate there),
            // which the OpenAI-compat endpoint cannot accept.
            LlmClient baseClient = "gemini".equals(preset.id())
                ? new GeminiNativeClient(apiKey == null ? "" : apiKey, model)
                : new OpenAiCompatibleClient(preset.baseUrl(), apiKey == null ? "" : apiKey, model);
            var retrying = new RetryingLlmClient(
                baseClient,
                RetryPolicy.builder()
                    .maxAttempts(6)
                    .initialBackoff(Duration.ofSeconds(2))
                    .build());
            // Parallel tasks sharing one token bucket: wall clock becomes the
            // slowest task, not the sum. The limiter sits OUTSIDE the retry
            // decorator so retries also draw permits and can't trip the
            // per-minute limit they exist to respect.
            boolean sequential = List.of(args).contains("--sequential");
            rpm = Double.parseDouble(System.getenv().getOrDefault("AXIOM_BENCH_RPM",
                String.valueOf(preset.defaultRpm())));
            int parallelism = Integer.parseInt(System.getenv().getOrDefault("AXIOM_BENCH_PARALLEL",
                sequential ? "1" : String.valueOf(tasks().size())));
            RateLimiter limiter = rpm > 0 ? new RateLimiter(rpm, parallelism) : null;
            liveClient = limiter != null ? new RateLimitedLlmClient(retrying, limiter) : retrying;
            runConfig = new BenchRunConfig(parallelism, limiter, true);
        } else {
            runConfig = new BenchRunConfig(tasks().size(), null, true);
        }
        LlmClient client = liveClient;

        BiFunction<BenchTask, Path, BenchAgent> factory = (task, workdir) -> {
            List<AgentEvent> events = new ArrayList<>();
            AgentConfig.Builder b = Axiom.agent()
                .withApprovalHandler(ApprovalHandler.allowAll())
                .onEvent(events::add);
            if (live) {
                b.withClient(client);
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
            var agent = new Axiom.Agent(b.build());
            // Anonymous BenchAgent so the runner can read the recorded events
            // and build the per-task trace for the detailed report.
            return new BenchAgent() {
                @Override
                public AgentResult run(String prompt) {
                    return agent.run(prompt);
                }

                @Override
                public List<AgentEvent> events() {
                    return List.copyOf(events);
                }
            };
        };

        long pacingMs = 0; // legacy sequential pacing; parallel runs pace via the rate limiter
        String rpmDesc = rpm > 0 ? rpm + " RPM" : "unlimited";
        String notes = live
            ? ("Representative 4-task subset (3 GAIA-style + 1 SWE-bench-style), not the full "
                + "GAIA/SWE-bench suites. Provider: " + preset.id() + " (" + preset.keySignup() + "). "
                + "Parallelism=" + runConfig.parallelism() + "; shared token-bucket rate limiter at "
                + rpmDesc + " across all tasks and turns; per-minute HTTP 429 retried with "
                + "Retry-After honored; sustained quota exhaustion aborts the run after "
                + "3 consecutive quota failures instead of retrying a dead quota. Cost basis: provider free tier ($0).")
            : "Deterministic fixture run: scripted model responses, real tool execution. No provider cost.";
        BenchReceipt receipt = BenchRunner.run(tasks(), factory,
            ModelPrices.defaults(), model, mode, pacingMs, notes, runConfig);

        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC).format(Instant.now());
        Path receiptPath = Paths.get("benchmarks", "receipts",
            "receipt-" + mode + "-" + stamp + ".json");
        receipt.save(receiptPath);

        // Fully detailed human-readable report next to the machine-readable receipt.
        Path reportPath = Paths.get("benchmarks", "receipts",
            "report-" + mode + "-" + stamp + ".md");
        try {
            Files.createDirectories(reportPath.toAbsolutePath().getParent());
            Files.writeString(reportPath, BenchReport.markdown(receipt),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BenchException("Failed to save benchmark report to " + reportPath, e);
        }

        System.out.println(receipt);
        System.out.println("Receipt: " + receiptPath.toAbsolutePath());
        System.out.println("Report:  " + reportPath.toAbsolutePath());
    }

    // ------------------------------------------------------------------
    // Benchmark tool holders (validated at compile time by ToolProcessor)
    // ------------------------------------------------------------------

    static class CalcTools {
        @Tool(name = "bench_multiply", description = "Multiply two numbers")
        public int multiply(@ToolParam(description = "x") int x,
                            @ToolParam(description = "y") int y) {
            return x * y;
        }

        @Tool(name = "bench_add", description = "Add two numbers")
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

        @Tool(name = "bench_read_file", description = "Read a text file from the workspace")
        public String readFile(@ToolParam(description = "File name, e.g. data.txt") String name)
                throws Exception {
            Path p = root.resolve(name).normalize();
            if (!p.startsWith(root)) throw new SecurityException("path escapes workspace");
            return Files.readString(p);
        }
    }
}

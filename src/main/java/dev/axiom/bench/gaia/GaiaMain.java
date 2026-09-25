package dev.axiom.bench.gaia;

import dev.axiom.Axiom;
import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentEvent;
import dev.axiom.agent.AgentResult;
import dev.axiom.agent.ApprovalHandler;
import dev.axiom.bench.BenchAgent;
import dev.axiom.bench.BenchException;
import dev.axiom.bench.BenchProvider;
import dev.axiom.bench.BenchReceipt;
import dev.axiom.bench.BenchReport;
import dev.axiom.bench.BenchRunConfig;
import dev.axiom.bench.BenchRunner;
import dev.axiom.bench.BenchTask;
import dev.axiom.bench.RateLimitedLlmClient;
import dev.axiom.bench.RateLimiter;
import dev.axiom.budget.ModelPrices;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.OpenAiCompatibleClient;
import dev.axiom.resilience.RetryPolicy;
import dev.axiom.resilience.RetryingLlmClient;
import dev.axiom.tools.SubprocessTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Genuine GAIA benchmark entry point: the real GAIA 2023 validation Level 1
 * set (53 tasks), the official GAIA quasi-exact-match scorer, and the full
 * agent toolset (web fetch, file read/list, calculator, sandboxed python3).
 *
 * <pre>
 * # Dry run (default): lists the 53 tasks and the attempted/unattempted
 * # split without calling any model
 * java -cp "target/axiom-0.11.0.jar:lib/*" dev.axiom.bench.gaia.GaiaMain
 *
 * # Live on the free tier (key from https://aistudio.google.com/apikey):
 * export GEMINI_API_KEY=...
 * AXIOM_BENCH_PROVIDER=gemini \
 *   java -cp "target/axiom-0.11.0.jar:lib/*" dev.axiom.bench.gaia.GaiaMain --live
 *
 * # Knobs:
 * #   AXIOM_BENCH_MODEL=gemini-3.5-flash-lite  (default for provider=gemini)
 * #   AXIOM_BENCH_RPM=12      shared requests/minute (default 12; the
 * #                           measured free-tier ceiling for flash-lite is 15)
 * #   AXIOM_BENCH_PARALLEL=4  concurrent tasks (default 4)
 * #   --sequential            one task at a time
 * #   AXIOM_GAIA_JSON=path    load a same-shaped dataset JSON from disk
 * </pre>
 *
 * <p>Default model note: the measured free-tier workhorse is
 * {@code gemini-3.5-flash-lite} (15 RPM / 500 requests/day, verified
 * 2026-09-02); there is no {@code gemini-3.8-flash-lite} text model on the
 * public model list, so that id is not used.
 *
 * <p>Tasks whose question ships an attachment file are recorded as
 * UNATTEMPTED (the attachments live in the access-gated official GAIA
 * repository and cannot be fetched without an accepted gate token) — never
 * as failures. Results are validation-split measurements, never official
 * GAIA leaderboard scores.
 */
public final class GaiaMain {

    /** Default model for provider=gemini: the 500 req/day free-tier lite model. */
    static final String DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite";
    /** Default shared rate limit: under the measured 15 RPM free-tier ceiling. */
    static final double DEFAULT_RPM = 12;
    /** Default task parallelism for the free tier. */
    static final int DEFAULT_PARALLELISM = 4;

    private GaiaMain() {}

    /**
     * The prompt given to the agent for a GAIA task: GAIA's own convention
     * of instructing a short final answer, so the raw output can be scored
     * with the official quasi-exact-match function (no extraction, no judge).
     */
    static String promptFor(GaiaItem item) {
        return "Answer the following question. Use the available tools when they "
            + "help: web_fetch to read web pages, read_file/list_files for files "
            + "in the workspace, calculate for arithmetic, run to execute "
            + "python3 commands in the workspace.\n\n"
            + "Reply with ONLY the final answer: a short string, a number, or a "
            + "comma-separated list. No explanation, no preamble, no quotes "
            + "around it.\n\nQuestion: " + item.question();
    }

    /** BenchTask id for a GAIA item. */
    static String taskIdFor(GaiaItem item) {
        return "gaia-" + item.taskId();
    }

    /**
     * The UNATTEMPTED receipt entry for a task whose attachment cannot be
     * fetched. Records the reason; carries zero tokens and zero cost.
     */
    static BenchReceipt.TaskResult unattemptedResult(GaiaItem item) {
        return new BenchReceipt.TaskResult(
            taskIdFor(item), "gaia", false, null, 0, 0, 0.0, 0,
            "UNATTEMPTED: the question ships attachment '" + item.fileName()
                + "', which lives in the access-gated official GAIA repository "
                + "(HTTP 401 without an accepted Hugging Face gate token) and "
                + "could not be fetched from this environment. Not counted as "
                + "a failure.",
            promptFor(item), item.trueAnswer(), List.of(), null, null,
            BenchReceipt.Status.UNATTEMPTED);
    }

    public static void main(String[] args) {
        boolean live = List.of(args).contains("--live");
        List<GaiaItem> items = GaiaDataset.level1();

        List<GaiaItem> attempted = new ArrayList<>();
        List<BenchReceipt.TaskResult> unattempted = new ArrayList<>();
        for (GaiaItem item : items) {
            if (item.hasAttachment()) {
                unattempted.add(unattemptedResult(item));
            } else {
                attempted.add(item);
            }
        }

        if (!live) {
            System.out.println("GAIA 2023 validation, Level 1 — dry run (no model calls).");
            System.out.println("Tasks: " + items.size()
                + " | would attempt: " + attempted.size()
                + " | unattempted (attachment gated): " + unattempted.size());
            System.out.println("Scorer: official GAIA quasi-exact-match "
                + "(numeric/string/list normalization, no LLM judge).");
            System.out.println("Default live model: " + DEFAULT_GEMINI_MODEL
                + " (15 RPM / 500 req-day free tier; run capped at "
                + (int) DEFAULT_RPM + " RPM, ~" + estimatedRequests(attempted.size())
                + " requests estimated).");
            System.out.println("Re-run with --live and GEMINI_API_KEY set to execute.");
            for (BenchReceipt.TaskResult u : unattempted) {
                System.out.println("  [UNATTEMPTED] " + u.taskId() + " — " + u.detail());
            }
            return;
        }

        String providerId = System.getenv().getOrDefault("AXIOM_BENCH_PROVIDER", "gemini");
        BenchProvider.Preset preset = BenchProvider.of(providerId);
        String model = System.getenv().getOrDefault("AXIOM_BENCH_MODEL",
            providerId.equals("gemini") ? DEFAULT_GEMINI_MODEL : preset.defaultModel());
        String mode = "live-" + preset.id() + "-gaia-l1";

        String apiKey = null;
        if (preset.needsKey()) {
            apiKey = System.getenv(preset.apiKeyEnv());
            if (apiKey == null || apiKey.isBlank()) {
                System.err.println("""
                    Missing API key: %s is not set.
                    Get a free key at %s, then:
                      export %s=...   (never paste it into chat or commit it)"""
                    .formatted(preset.apiKeyEnv(), preset.keySignup(), preset.apiKeyEnv()));
                System.exit(2);
            }
        }
        var retrying = new RetryingLlmClient(
            new OpenAiCompatibleClient(preset.baseUrl(), apiKey == null ? "" : apiKey, model),
            RetryPolicy.builder()
                .maxAttempts(6)
                .initialBackoff(Duration.ofSeconds(2))
                .build());
        boolean sequential = List.of(args).contains("--sequential");
        double rpm = Double.parseDouble(System.getenv().getOrDefault("AXIOM_BENCH_RPM",
            String.valueOf(DEFAULT_RPM)));
        int parallelism = Integer.parseInt(System.getenv().getOrDefault("AXIOM_BENCH_PARALLEL",
            sequential ? "1" : String.valueOf(DEFAULT_PARALLELISM)));
        RateLimiter limiter = rpm > 0 ? new RateLimiter(rpm, parallelism) : null;
        LlmClient client = limiter != null ? new RateLimitedLlmClient(retrying, limiter) : retrying;
        BenchRunConfig runConfig = new BenchRunConfig(parallelism, limiter, true);

        Map<String, GaiaItem> byTaskId = new LinkedHashMap<>();
        List<BenchTask> tasks = new ArrayList<>();
        for (GaiaItem item : attempted) {
            String id = taskIdFor(item);
            byTaskId.put(id, item);
            tasks.add(BenchTask.gaia(id, promptFor(item), item.trueAnswer()));
        }

        BiFunction<BenchTask, Path, BenchAgent> factory = (task, workdir) -> {
            List<AgentEvent> events = new ArrayList<>();
            Path wd = workdir;
            if (wd == null) {
                try {
                    wd = Files.createTempDirectory("gaia-task-");
                } catch (Exception e) {
                    throw new BenchException("Could not create GAIA task workdir", e);
                }
            }
            final Path root = wd;
            AgentConfig.Builder b = Axiom.agent()
                .withApprovalHandler(ApprovalHandler.allowAll())
                .onEvent(events::add)
                .withClient(client)
                .withTools(
                    new GaiaTools.Files(root),
                    new GaiaTools.Calc(),
                    new WebFetchTool(),
                    SubprocessTool.builder(root)
                        .allowCommands("python3", "cat", "ls", "head", "wc",
                            "grep", "tr", "cut", "sort", "echo")
                        .build());
            var agent = new Axiom.Agent(b.build());
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

        String notes = "GAIA 2023 validation split, Level 1 (53 tasks) from the public "
            + "ungated mirror jiyu9437/gaia_validation (official gaia-benchmark/GAIA "
            + "repo is access-gated). 11 tasks ship attachments that could not be "
            + "fetched and are recorded UNATTEMPTED, never as failures. Scoring is "
            + "the official GAIA quasi-exact-match (numeric/string/list "
            + "normalization, no LLM judge, no partial credit, raw agent output). "
            + "Provider: " + preset.id() + " (" + preset.keySignup() + "), model "
            + model + ". Parallelism=" + parallelism + "; shared token-bucket "
            + "rate limiter at " + rpm + " RPM; daily/plan quota exhaustion aborts "
            + "the run immediately. Cost basis: provider free tier ($0). "
            + "NOT an official GAIA score or leaderboard result.";
        BenchReceipt attemptedReceipt = BenchRunner.run(tasks, factory,
            ModelPrices.defaults(), model, mode, 0, notes, runConfig,
            GaiaScorer::score);

        // Merge back into task order: attempted results plus unattempted markers.
        Map<String, BenchReceipt.TaskResult> byId = new LinkedHashMap<>();
        for (BenchReceipt.TaskResult r : attemptedReceipt.results()) byId.put(r.taskId(), r);
        for (BenchReceipt.TaskResult r : unattempted) byId.put(r.taskId(), r);
        List<BenchReceipt.TaskResult> merged = new ArrayList<>();
        for (GaiaItem item : items) merged.add(byId.get(taskIdFor(item)));
        BenchReceipt receipt = new BenchReceipt("axiom", dev.axiom.Version.CURRENT,
            model, mode, Instant.now(), merged, notes,
            attemptedReceipt.wallClockMs(), parallelism);

        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC).format(Instant.now());
        Path receiptPath = Paths.get("benchmarks", "receipts",
            "receipt-" + mode + "-" + stamp + ".json");
        receipt.save(receiptPath);
        Path reportPath = Paths.get("benchmarks", "receipts",
            "report-" + mode + "-" + stamp + ".md");
        try {
            Files.createDirectories(reportPath.toAbsolutePath().getParent());
            Files.writeString(reportPath, BenchReport.markdown(receipt),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BenchException("Failed to save GAIA report to " + reportPath, e);
        }

        System.out.println(receipt);
        System.out.println("Receipt: " + receiptPath.toAbsolutePath());
        System.out.println("Report:  " + reportPath.toAbsolutePath());
    }

    /** Rough request estimate for the dry-run readout: ~8 LLM calls per task. */
    static int estimatedRequests(int taskCount) {
        return taskCount * 8;
    }
}

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
import dev.axiom.llm.GeminiNativeClient;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Genuine GAIA benchmark entry point: the real GAIA 2023 validation Level 1
 * set (53 tasks), the official GAIA quasi-exact-match scorer, and the full
 * agent toolset (web fetch, file read/list, calculator, sandboxed python3).
 *
 * <pre>
 * # Dry run (default): lists the 53 tasks and the attempted/unattempted
 * # split without calling any model
 * java -cp "target/axiom-0.12.0.jar:lib/*" dev.axiom.bench.gaia.GaiaMain
 *
 * # Live on the free tier (key from https://aistudio.google.com/apikey):
 * export GEMINI_API_KEY=...
 * AXIOM_BENCH_PROVIDER=gemini \
 *   java -cp "target/axiom-0.12.0.jar:lib/*" dev.axiom.bench.gaia.GaiaMain --live
 *
 * # Knobs:
 * #   AXIOM_BENCH_MODEL=gemini-3.5-flash-lite  (default for provider=gemini)
 * #   AXIOM_BENCH_RPM=12      shared requests/minute (default 12; the
 * #                           measured free-tier ceiling for flash-lite is 15)
 * #   AXIOM_BENCH_PARALLEL=4  concurrent tasks (default 4)
 * #   --sequential            one task at a time
 * #   AXIOM_GAIA_JSON=path    load a same-shaped dataset JSON from disk
 * #   --attachments DIR       mount user-supplied attachment files: files in
 * #                           DIR named "<taskId>-<original-name>" are copied
 * #                           into the matching task's workspace (the prefix
 * #                           is stripped), converting UNATTEMPTED tasks into
 * #                           attempted ones. Task ids come from the dry run's
 * #                           UNATTEMPTED list.
 * #   AXIOM_BENCH_SMART_MODEL=gemini-3.8-flash
 * #   AXIOM_BENCH_SMART_MODEL_BUDGET=20
 * #                           route tasks flagged hard by a documented
 * #                           heuristic to the stronger model, up to BUDGET
 * #                           tasks per run (default 20); everything else
 * #                           uses AXIOM_BENCH_MODEL. Per-model spend lands
 * #                           in the receipt notes.
 * </pre>
 *
 * <p>Agent improvements over the first live run (11/42): a {@code web_search}
 * tool (Wikipedia API + DuckDuckGo fallback, keyless) so the agent stops
 * guessing URLs; an {@code answer} terminal tool that commits one clean
 * final answer and ends the run; mechanical answer normalization (trim,
 * unquote, Unicode NFKC — never reinterpretation) before the unchanged
 * official scorer; tool descriptions that route arithmetic to
 * {@code calculate} and forbid URL guessing; and a rolling context window
 * that stubs old tool outputs past 24k chars
 * ({@code -Daxiom.context.toolOutputCap=N}).
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
     * of instructing a short final answer, plus the tool workflow the first
     * real run showed the model needs spelled out — search before fetching
     * (never guess URLs), calculate for arithmetic (never python3), and
     * commit through the {@code answer} tool instead of chat prose.
     */
    static String promptFor(GaiaItem item) {
        return "Answer the following question. Your tools:\n"
            + "- web_search: search the web FIRST when you need facts and don't "
            + "have a URL. Never guess URLs.\n"
            + "- web_fetch: read a page once web_search gives you its URL.\n"
            + "- read_file / list_files: files in the task workspace (attachments "
            + "are placed here).\n"
            + "- calculate: ALL arithmetic — never use run/python3 for arithmetic.\n"
            + "- run: sandboxed python3 for processing files and multi-step scripts "
            + "(not arithmetic).\n\n"
            + "VERIFY BEFORE ANSWERING: list the question's specific constraints "
            + "(names, dates, numbers, qualifiers like 'first', 'only', 'unknown'). "
            + "Check your candidate against EACH one. "
            + "Check UNITS and DIMENSIONS: "
            + "if the question asks 'how many thousand', your answer must be in "
            + "thousands (divide by 1000). If it asks for a year, give a year, "
            + "not a full date. "
            + "If a constraint fails and "
            + "you can find a better candidate, keep researching. But do not "
            + "research forever: if you have checked thoroughly, provide your "
            + "best-supported answer rather than nothing. A best-effort answer "
            + "beats an empty one.\n\n"
            + "When you know the answer, call the answer tool with ONLY the final "
            + "answer: a short string, a number, or a comma-separated list. No "
            + "explanation, no preamble, no quotes around it.\n\n"
            + "Question: " + item.question();
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
        int[] shard = parseShard(List.of(args));
        List<GaiaItem> items = GaiaDataset.level1();

        Path attachmentsDir = attachmentsDir(List.of(args));
        final Map<String, List<Path>> attachments;
        try {
            attachments = AttachmentMount.match(attachmentsDir, items);
        } catch (Exception e) {
            System.err.println("Could not read attachments dir " + attachmentsDir + ": " + e);
            throw new BenchException("Could not read attachments dir " + attachmentsDir, e);
        }

        List<GaiaItem> attempted = new ArrayList<>();
        List<BenchReceipt.TaskResult> unattempted = new ArrayList<>();
        for (GaiaItem item : items) {
            if (item.hasAttachment()
                    && !attachments.containsKey(taskIdFor(item))) {
                unattempted.add(unattemptedResult(item));
            } else {
                attempted.add(item);
            }
        }

        if (!live) {
            System.out.println("GAIA 2023 validation, Level 1 — dry run (no model calls).");
            System.out.println("Tasks: " + items.size()
                + " | would attempt: " + attempted.size()
                + " | unattempted (attachment gated): " + unattempted.size()
                + (attachmentsDir != null
                    ? " | attachments mounted from " + attachmentsDir + ": " + attachments.size() + " tasks"
                    : ""));
            System.out.println("Scorer: official GAIA quasi-exact-match "
                + "(numeric/string/list normalization, no LLM judge). Raw output "
                + "gets mechanical normalization only (trim, unquote, Unicode NFKC).");
            System.out.println("Default live model: " + DEFAULT_GEMINI_MODEL
                + " (15 RPM / 500 req-day free tier; run capped at "
                + (int) DEFAULT_RPM + " RPM, ~" + estimatedRequests(attempted.size())
                + " requests estimated).");
            String smart = System.getenv("AXIOM_BENCH_SMART_MODEL");
            if (smart != null && !smart.isBlank()) {
                System.out.println("Smart routing: tasks flagged hard by the documented "
                    + "heuristic use " + smart + " (budget "
                    + System.getenv().getOrDefault("AXIOM_BENCH_SMART_MODEL_BUDGET", "20")
                    + " tasks).");
            }
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
        boolean sequential = List.of(args).contains("--sequential");
        double rpm = Double.parseDouble(System.getenv().getOrDefault("AXIOM_BENCH_RPM",
            String.valueOf(DEFAULT_RPM)));
        int parallelism = Integer.parseInt(System.getenv().getOrDefault("AXIOM_BENCH_PARALLEL",
            sequential ? "1" : String.valueOf(DEFAULT_PARALLELISM)));
        RateLimiter limiter = rpm > 0 ? new RateLimiter(rpm, parallelism) : null;
        final String finalApiKey = apiKey;
        java.util.function.Function<String, LlmClient> clientFor = modelName -> {
            // Gemini must use the native client: the vault credential's
            // placement is ?key= (the proxy only swaps the surrogate there),
            // which the OpenAI-compat endpoint cannot accept.
            LlmClient base = "gemini".equals(preset.id())
                ? new GeminiNativeClient(finalApiKey == null ? "" : finalApiKey, modelName)
                : new OpenAiCompatibleClient(preset.baseUrl(),
                    finalApiKey == null ? "" : finalApiKey, modelName);
            var retrying = new RetryingLlmClient(
                base,
                RetryPolicy.builder()
                    .maxAttempts(6)
                    .initialBackoff(Duration.ofSeconds(2))
                    .build());
            return limiter != null ? new RateLimitedLlmClient(retrying, limiter) : retrying;
        };
        LlmClient client = clientFor.apply(model);

        // Free-tier smart routing: a stronger model for heuristic-hard tasks.
        String smartModel = System.getenv("AXIOM_BENCH_SMART_MODEL");
        int smartBudget = Integer.parseInt(System.getenv()
            .getOrDefault("AXIOM_BENCH_SMART_MODEL_BUDGET", "20"));
        ModelRouter router = new ModelRouter(model,
            smartModel == null || smartModel.isBlank() ? null : smartModel.trim(),
            smartBudget);
        LlmClient smartClient = router.smartModel() == null
            ? null : clientFor.apply(router.smartModel());
        BenchRunConfig runConfig = new BenchRunConfig(parallelism, limiter, true);

        Map<String, GaiaItem> byTaskId = new LinkedHashMap<>();
        List<BenchTask> tasks = new ArrayList<>();
        // --taskIds=a,b,c runs only tasks whose id starts with one of the
        // given prefixes (comma-separated). For targeted ablations: re-run a
        // failing subset without re-running the whole split. Recorded in the
        // receipt notes like a shard.
        Set<String> onlyIds = parseTaskIds(List.of(args));
        // --shard=i/n runs only the i-th slice of the task list (0-based),
        // so a 42-task benchmark can be split across provider quota days and
        // merged honestly afterwards. Scoring is untouched; the shard is
        // recorded in the receipt notes.
        for (int idx = 0; idx < attempted.size(); idx++) {
            if (shard != null && idx % shard[1] != shard[0]) continue;
            GaiaItem item = attempted.get(idx);
            String id = taskIdFor(item);
            if (onlyIds != null && onlyIds.stream().noneMatch(id::startsWith)) continue;
            byTaskId.put(id, item);
            tasks.add(BenchTask.gaia(id, promptFor(item), item.trueAnswer()));
        }
        if (onlyIds != null) {
            System.out.printf("Task filter: running %d of %d attempted tasks.%n",
                tasks.size(), attempted.size());
        }
        if (shard != null) {
            System.out.printf("Shard %d/%d: running %d of %d attempted tasks.%n",
                shard[0], shard[1], tasks.size(), attempted.size());
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
            // Mount user-supplied attachments (task-id prefix stripped).
            List<Path> mounted = attachments.getOrDefault(task.id(), List.of());
            if (!mounted.isEmpty()) {
                try {
                    AttachmentMount.mount(task.id(), mounted, root);
                } catch (Exception e) {
                    throw new BenchException(
                        "Could not mount attachments for " + task.id(), e);
                }
            }
            // Per-task model routing (smart budget shared across workers).
            GaiaItem routedItem = byTaskId.get(task.id());
            String taskModel = routedItem == null ? model
                : router.route(task.id(), routedItem);
            LlmClient taskClient = smartClient != null && taskModel.equals(router.smartModel())
                ? smartClient : client;
            AgentConfig.Builder b = Axiom.agent()
                .withApprovalHandler(ApprovalHandler.allowAll())
                .onEvent(events::add)
                .withClient(taskClient)
                .withTerminalTools("answer")
                .withTools(
                    new GaiaTools.Files(root),
                    new GaiaTools.Calc(),
                    new GaiaTools.Final(),
                    new WebFetchTool(),
                    new WebSearchTool(),
                    SubprocessTool.builder(root)
                        .allowCommands("python3", "python", "cat", "ls", "head", "wc",
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

        String modelLabel = router.smartModel() == null ? model
            : model + " (+" + router.smartModel() + " smart-routed)";
        String notes = "GAIA 2023 validation split, Level 1 (53 tasks) from the public "
            + "ungated mirror jiyu9437/gaia_validation (official gaia-benchmark/GAIA "
            + "repo is access-gated). " + unattempted.size() + " tasks ship attachments "
            + "that could not be fetched and are recorded UNATTEMPTED, never as "
            + "failures"
            + (attachmentsDir != null
                ? "; " + attachments.size() + " tasks had user-supplied attachments "
                  + "mounted from " + attachmentsDir : "")
            + ". Scoring is the official GAIA quasi-exact-match "
            + "(numeric/string/list normalization, no LLM judge, no partial "
            + "credit); the raw agent output gets mechanical normalization only "
            + "(trim, strip balanced quotes, Unicode NFKC — never reinterpreted). "
            + "Provider: " + preset.id() + " (" + preset.keySignup() + "), model "
            + modelLabel + ". Parallelism=" + parallelism + "; shared token-bucket "
            + "rate limiter at " + rpm + " RPM; sustained quota exhaustion aborts "
            + "the run after 3 consecutive quota failures. Cost basis: "
            + (preset.id().equals("hcnsec")
                ? "Hamis's hcnsec token pool (metered; exact spend in receipt usage)"
                : "provider free tier ($0)") + ". "
            + (shard != null ? "Shard " + shard[0] + "/" + shard[1] + " of the attempted tasks. " : "")
            + (onlyIds != null ? "Task filter --taskIds=" + String.join(",", onlyIds) + ": only matching tasks attempted. " : "")
            + "NOT an official GAIA score or leaderboard result.";
        BenchRunner.OutputScorer scorer = (output, expected) ->
            GaiaScorer.score(GaiaAnswer.normalize(output), expected);
        BenchReceipt attemptedReceipt = BenchRunner.run(tasks, factory,
            ModelPrices.defaults(), modelLabel, mode, 0, notes, runConfig,
            scorer);

        // Per-model spend from the smart router's assignments.
        Map<String, String> assigned = router.assignments();
        Map<String, long[]> tokensByModel = new LinkedHashMap<>(); // model -> [tasks, tokens]
        for (BenchReceipt.TaskResult r : attemptedReceipt.results()) {
            String m = assigned.getOrDefault(r.taskId(), model);
            long[] acc = tokensByModel.computeIfAbsent(m, k -> new long[2]);
            acc[0]++;
            acc[1] += r.totalTokens();
        }
        if (router.smartModel() != null) {
            StringBuilder routing = new StringBuilder(
                " Model routing: heuristic-hard tasks (question > "
                    + ModelRouter.HARD_QUESTION_CHARS + " chars or multi-step keywords: "
                    + String.join(", ", ModelRouter.HARD_KEYWORDS)
                    + ") used " + router.smartModel() + "; budget was "
                    + System.getenv().getOrDefault("AXIOM_BENCH_SMART_MODEL_BUDGET", "20")
                    + " tasks, " + router.smartRemaining() + " unspent. Per-model: ");
            tokensByModel.forEach((m, acc) -> routing.append(m)
                .append("=").append(acc[0]).append(" tasks/").append(acc[1])
                .append(" tokens; "));
            routing.append("The heuristic is a triage signal, not a guarantee.");
            notes = notes + routing;
        }

        // Merge back into task order: attempted results plus unattempted markers.
        Map<String, BenchReceipt.TaskResult> byId = new LinkedHashMap<>();
        for (BenchReceipt.TaskResult r : attemptedReceipt.results()) byId.put(r.taskId(), r);
        for (BenchReceipt.TaskResult r : unattempted) byId.put(r.taskId(), r);
        List<BenchReceipt.TaskResult> merged = mergeForReceipt(items, byId, shard, onlyIds);

        BenchReceipt receipt = new BenchReceipt("axiom", dev.axiom.Version.CURRENT,
            modelLabel, mode, Instant.now(), merged, notes,
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

    /**
     * The {@code --attachments <dir>} argument: a folder of user-supplied
     * attachment files named {@code "<taskId>-<original-name>"}. Null when
     * the flag is absent.
     */
    static Path attachmentsDir(List<String> args) {
        int i = args.indexOf("--attachments");
        if (i < 0 || i + 1 >= args.size()) return null;
        return Paths.get(args.get(i + 1));
    }

    /**
     * Merge attempted + unattempted results back into task order for the
     * receipt. In shard mode, out-of-shard tasks have no result and are
     * skipped — a shard receipt only covers the tasks the shard actually ran.
     * The same holds for the --taskIds filter: tasks outside the filter are
     * skipped. In full mode a missing result is an internal error (never a
     * silent null, which {@code List.copyOf} rejects with an NPE at receipt
     * build time).
     */
    static List<BenchReceipt.TaskResult> mergeForReceipt(
            List<GaiaItem> items, Map<String, BenchReceipt.TaskResult> byId,
            int[] shard, Set<String> onlyIds) {
        List<BenchReceipt.TaskResult> merged = new ArrayList<>();
        for (GaiaItem item : items) {
            String id = taskIdFor(item);
            if (onlyIds != null && onlyIds.stream().noneMatch(id::startsWith)) continue;
            BenchReceipt.TaskResult r = byId.get(id);
            if (r == null) {
                if (shard != null) continue; // out-of-shard: not this receipt's business
                if (onlyIds != null) continue; // outside --taskIds filter: same treatment
                throw new BenchException("Missing result for task " + taskIdFor(item)
                    + " (neither attempted nor unattempted) — internal error");
            }
            merged.add(r);
        }
        return merged;
    }

    /**
     * The {@code --shard=i/n} argument: run only slice {@code i} of
     * {@code n} (0-based). Returns {@code [i, n]}, or null when absent.
     */
    static int[] parseShard(List<String> args) {
        String flag = args.stream().filter(a -> a.startsWith("--shard=")).findFirst().orElse(null);
        if (flag == null) return null;
        String[] parts = flag.substring("--shard=".length()).split("/");
        if (parts.length != 2) throw new IllegalArgumentException(
            "Expected --shard=i/n, got: " + flag);
        int i = Integer.parseInt(parts[0].trim());
        int n = Integer.parseInt(parts[1].trim());
        if (n < 1 || i < 0 || i >= n) throw new IllegalArgumentException(
            "Expected --shard=i/n with 0 <= i < n, got: " + flag);
        return new int[]{i, n};
    }

    /**
     * The {@code --taskIds=a,b,c} argument: run only tasks whose id starts
     * with one of the given prefixes. Returns null when absent.
     */
    static Set<String> parseTaskIds(List<String> args) {
        String flag = args.stream().filter(a -> a.startsWith("--taskIds=")).findFirst().orElse(null);
        if (flag == null) return null;
        Set<String> ids = new LinkedHashSet<>();
        for (String part : flag.substring("--taskIds=".length()).split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) ids.add(p);
        }
        if (ids.isEmpty()) throw new IllegalArgumentException(
            "Expected --taskIds=a,b,c with at least one id prefix, got: " + flag);
        return ids;
    }
}

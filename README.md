# Axiom — Type-Safe AI Agent Framework for the JVM

Build AI agents in Java where **tool schemas are verified at compile time, not discovered at runtime**.

Every mainstream agent framework defines tools as runtime dictionaries: misspell a parameter, pass the wrong type, and you find out when the agent fails at 2 AM. Axiom moves that entire class of bugs to compile time. If it compiles, the agent's tools are valid.

## Features

- **Compile-time tool schemas** — annotate a method with `@Tool`; the annotation processor validates it during compilation (public method, documented `@ToolParam` on every parameter, unique tool name across the compilation, types that map to JSON Schema *and* that Jackson can actually deserialize — records, accessible no-arg constructors, or `@JsonCreator`; no interfaces, abstract types, or non-static inner classes; real parameter names via `-parameters`). Violations are build errors. The generated `META-INF/axiom/tools/*.json` is the runtime's single source of truth — schemas are never re-derived by reflection at runtime.
- **ReAct agent loop** — reason + act with self-correction: tool errors are fed back as observations so the agent replans instead of crashing.
- **Structured typed output** — `agent.runFor(task, Invoice.class)` constrains the model to the JSON Schema generated from your POJO and deserializes it. Mismatches raise `StructuredOutputException`, never silent corruption.
- **Human-in-the-loop** — `requiresApproval = true` on any tool; denied calls are reported back so the agent replans.
- **Tool timeouts** — per-tool `timeoutSeconds`; hanging tools are cancelled and reported, never wedging the run.
- **Conversation memory** — pluggable `Memory` (bundled `SlidingWindowMemory`) gives multi-turn conversations without manual message management.
- **Full-fidelity events** — every LLM call, tool call, approval, budget charge, and result emits a typed `AgentEvent`. Tracing, UIs, and logging plug in with one listener.
- **Resilience** — `RetryingLlmClient` with exponential backoff + jitter for transient failures (HTTP 429 honors `Retry-After`, 5xx and network blips retried; other 4xx never retried). Streaming retries buffer each attempt and emit only the successful attempt's tokens.
- **Response caching** — `CachingLlmClient` serves identical requests from a content-hash key: deterministic runs, free replays, offline CI. Streaming responses are cached with their token chunks and replayed chunk-by-chunk, so cache hits are indistinguishable from live streams.
- **Guardrails** — policy checks on inputs and outputs: block, or redact and continue (`KeywordBlocklistGuardrail`, `PiiRedactionGuardrail` bundled).
- **Observability exporters** — `JsonLinesExporter` (one JSON line per event, `jq`-queryable) and `MetricsReporter` (live counters for dashboards).
- **Provider-agnostic** — any OpenAI-compatible endpoint: OpenAI, Azure, Ollama, vLLM, LM Studio, Together…

## v0.2 — what's new

### Native MCP client (`dev.axiom.mcp`)

The Model Context Protocol is the won tool standard (10k+ servers). Axiom speaks it natively — JSON-RPC 2.0 over stdio, implemented directly on Jackson, no SDK:

```java
try (McpClient mcp = McpClient.spawn(List.of("npx", "-y", "@modelcontextprotocol/server-filesystem", "/data"))) {
    mcp.initialize();

    ToolRegistry registry = new ToolRegistry().register(new MyLocalTools());
    McpTools.registerAll(registry, mcp);          // every server tool becomes a ToolDefinition…
    registry.register(McpTools.resourceReaderTool(mcp)); // …plus resources/read as a tool

    var agent = new Axiom.Agent(Axiom.agent().withModel("gpt-4o")
        .withToolDefinitions(registry.all().toArray(ToolDefinition[]::new))
        .build());
    agent.run("Summarize the README in /data");
}   // try-with-resources closes the server process
```

MCP tools go through the same machinery as `@Tool` methods: per-tool timeouts, approval gates, and events. Server-declared `inputSchema`s are used verbatim, and `tools/call` results with `isError: true` surface as tool errors the agent can self-correct from. Name collisions across servers are handled with `registerAll(registry, mcp, "fs_", false)` prefixes.

### Multi-agent supervisor/worker teams (`dev.axiom.teams`)

Typed handoff contracts via generics — a worker accepts `I` and returns `O`, and the schemas advertised to the supervisor are generated from those same classes, so types and schemas can't drift:

```java
record ResearchQuery(String topic, int maxSources) {}
record ResearchBrief(String topic, List<String> findings) {}
record Report(String title, List<String> points) {}

Worker<ResearchQuery, ResearchBrief> researcher = Worker.of(
    "researcher", "Researches a topic and returns a brief.",
    ResearchQuery.class, ResearchBrief.class,
    Axiom.agent().withModel("gpt-4o").withTools(new WebTools()).build());

Worker<Outline, Draft> writer = Worker.of(
    "writer", "Writes a draft from an outline.",
    Outline.class, Draft.class,
    Axiom.agent().withModel("gpt-4o").withMemory(new SlidingWindowMemory(40)).build());

SupervisorTeam team = SupervisorTeam.builder(client)
    .withWorker(researcher)
    .withWorker(writer)
    .build();

// The supervisor decomposes the task, delegates via typed delegate_to_*
// tools, and aggregates into your result type:
Report report = team.run("Write a report on solid-state batteries", Report.class);

// Or delegate directly — fully compile-time checked, no LLM involved:
ResearchBrief brief = team.delegate(researcher, new ResearchQuery("perovskites", 5));
```

Each worker owns its tools, memory, and listeners. Share one `Budget` across configs to cap a whole team's spend.

### Vector-backed persistent memory (`dev.axiom.memory`)

Long-term memory that survives restarts with zero infrastructure: messages are embedded into a `FileVectorStore` (cosine similarity, atomic JSON persistence), and `VectorMemory` recalls semantically relevant context keyed on the latest user message:

```java
EmbeddingFunction embeddings = new OpenAiEmbeddings("text-embedding-3-small");
// …or any provider: new OpenAiEmbeddings("http://localhost:11434/v1", "ollama", "nomic-embed-text")
// …or your own: text -> myModel.embed(text)

Memory memory = new VectorMemory(embeddings, Path.of("memory/vectors.json"));
var agent = new Axiom.Agent(Axiom.agent().withModel("gpt-4o").withMemory(memory).build());

agent.run("My dog's name is Biscuit");  // stored + embedded
agent.run("What's my dog's name?");    // recall surfaces "Biscuit" as context
```

`EmbeddingFunction` is a functional interface — bring any embedding model. The store deduplicates exact texts (the agent re-stores full transcripts every run), and `history()` returns the recent window plus one recalled-context system message.

### Sandboxed subprocess tools (`dev.axiom.tools`)

Give the agent a shell without giving it the keys to the machine:

```java
var shell = SubprocessTool.builder(Path.of("/tmp/agent-workspace"))
    .allowCommands("ls", "cat", "git", "python3")  // optional executable allowlist
    .allowEnv("VIRTUAL_ENV")                        // extra env vars beyond the safe default
    .timeout(Duration.ofSeconds(30))
    .build();

var agent = new Axiom.Agent(Axiom.agent().withModel("gpt-4o").withTools(shell).build());
```

Guarantees: **no shell** (argv goes straight to `execve`, metacharacters are inert), **working-directory confinement** (executable paths can't escape the root), **environment allowlist** (secrets never leak into the child), **bounded stdout/stderr capture**, **timeout kill**, and `requiresApproval = true` by default on the `run` tool.

### Token and cost budgets (`dev.axiom.budget`)

Per-run accounting with hard aborts — a run stops instead of silently burning money:

```java
Budget budget = Budget.builder()
    .maxTokens(50_000)
    .maxCostUsd(1.00)
    .prices(ModelPrices.defaults())   // per-model $/1k tokens; override with withPrice(...)
    .build();

var agent = new Axiom.Agent(Axiom.agent().withModel("gpt-4o")
    .withBudget(budget)
    .onEvent(e -> { if (e instanceof AgentEvent.BudgetUpdated u)
        System.out.printf("spent $%.4f of $%.2f%n",
            u.snapshot().costUsd(), u.snapshot().maxCostUsd()); })
    .build());

try {
    agent.run("Research everything about quantum batteries");
} catch (BudgetExceededException e) {
    System.out.println("Stopped: " + e.getMessage());  // typed: which limit, how much used
}
```

Every LLM call is charged; breaching a limit throws `BudgetExceededException` (carrying the full snapshot). `BudgetUpdated` events fire after every call — even the breaching one — so UIs can render live cost meters. Budgets are thread-safe and shareable across a supervisor team.

## v0.5.3 — what's new

Live Gemini preset now defaults to `gemini-3.8-flash` (Google retired `gemini-2.0-flash`; the new default is Google's own recommended replacement from the live API 404). The model can still be overridden per run with `AXIOM_BENCH_MODEL`, so a future model retirement never needs a rebuild to work around.

## v0.5.2 — what's new

Fully detailed benchmark reports. Every run now records a step-by-step trace per task — each agent iteration's model text plus every tool call with arguments, observed results, and durations — captured from agent events. The JSON receipt carries the prompt, expected answer, trace, SWE test-command output, and the full harness stack trace on failures; `BenchMain` also writes a human-readable Markdown report (`benchmarks/receipts/report-<mode>-<timestamp>.md`) next to the receipt, so a run can be audited turn by turn without re-running it.

## v0.5.1 — what's new

Proxy support for sandboxed and corporate networks. `OpenAiCompatibleClient` now honors the standard `HTTPS_PROXY` / `HTTP_PROXY` / `ALL_PROXY` and `NO_PROXY` environment variables — the same ones curl, Python, and Node read — including proxy authentication and `NO_PROXY` bypass rules (exact host, subdomain, `host:port`, `*`). Java's `HttpClient` ignores these variables on its own, which previously turned every call behind an egress proxy into a TLS failure. New `dev.axiom.llm.ProxyConfig` holds the parsing/selection logic with unit tests; no API changes.

## v0.5 — what's new

A correctness release: every claim the framework makes about reliability is now enforced, not documented.

### Compile-time schemas are the single source of truth

The annotation processor no longer just generates `META-INF/axiom/tools/*.json` for tooling — `ToolRegistry` reads that artifact at runtime instead of re-deriving schemas by reflection (reflection remains only as a fallback for holders compiled without the processor, and `SchemaDriftTest` pins the two mappings against each other over a 19-type matrix). The processor also got stricter:

- **Unique tool names** — a name used by two holders in one compilation is a build error (the runtime registry is name-keyed; this caught real collisions in Axiom's own sources).
- **Jackson-deserializable parameters** — interfaces, abstract types, non-static inner classes, and POJOs with no accessible no-arg constructor / `@JsonCreator` / record canonical constructor are build errors, not 2 AM surprises.
- **Return types validated too** — observations are serialized from return values, so provably broken return types fail the build.
- **Collision-proof schema artifacts** — generated schemas live at `META-INF/axiom/tools/<binary-name>.json` (e.g. `dev/axiom/bench/BenchMain$CalcTools.json`), so two holders that happen to share a simple class name can never overwrite each other's schemas; the runtime verifies the artifact's `class` field before trusting it.

### Honest streaming retries and caching

`RetryingLlmClient.chatStream` now actually retries: each attempt's tokens are buffered and only the successful attempt's tokens reach the listener — a failed attempt's partial stream never leaks. HTTP 429 is retried with the provider's `Retry-After` honored (seconds or HTTP-date, capped at 10 minutes); other 4xx are never retried. `CachingLlmClient` caches streaming responses with their token chunks and replays them on hits; legacy or corrupt entries are treated as misses, never crashes.

### Exactly-once durable side effects

Every tool execution is wrapped in a side-effect ledger: `tool_call_started` is journaled before the tool body runs, `tool_call_completed` after it returns. Resume replays completed calls (never re-executes), re-executes crash-window calls **only** for tools declared `@Tool(idempotent = true)`, and aborts with `DurableException` naming the ambiguous call otherwise — a double side effect is never applied silently. Old journals resume unchanged.

### Evals grade behavior, not just outputs

`Trajectory` captures which tools were called, in what order, with which arguments, from agent events. `Scorers.calledTool` / `calledInOrder` / `toolArgsMatch` / `neverCalledTool` compose with output scorers via `Scorers.allOf`; trajectory scorers fail with an explanation (never pass vacuously) when no trajectory was captured. LLM-judge verdicts are cached by task/output hash, and `EvalGate.assertNoRegression` fails CI on score drops, pass→fail flips, and new failing cases.

## v0.4 — what's new

### Resilience (`dev.axiom.resilience`)

Transient provider failures (rate limits, 5xx, network blips) shouldn't kill a run. `RetryingLlmClient` wraps any client with exponential backoff + jitter. HTTP 429 is retried with the provider's `Retry-After` hint honored (capped at 10 minutes); 5xx and network errors are retried; other 4xx are never retried. Streaming retries buffer each attempt's tokens and emit only the successful attempt's — a failed attempt's partial tokens never leak to the caller:

```java
var client = new RetryingLlmClient(
    new OpenAiCompatibleClient("gpt-4o"),
    RetryPolicy.builder().maxAttempts(5).initialBackoff(Duration.ofSeconds(1)).build());
```

### Response caching (`dev.axiom.cache`)

Identical requests (same model, messages, tools, options) hit a content-hash key instead of the provider. Streaming responses are stored with their token chunks: a cache hit replays the stored chunks in order, so callers can't distinguish a hit from a live stream except by speed. Entries written by older versions (no chunks) or by non-streaming calls are treated as misses and rewritten — never served stale, never a crash:

```java
var client = new CachingLlmClient(
    new OpenAiCompatibleClient("gpt-4o-mini"),
    new FileCache(Path.of(".axiom-cache")));  // or InMemoryCache for tests
```

### Guardrails (`dev.axiom.guardrails`)

Policy checks on the task (before the run) and the final answer (before it's returned). A block aborts with `GuardrailViolationException` after emitting `AgentEvent.GuardrailBlocked`; a replace substitutes sanitized text and continues:

```java
.withGuardrails(
    new KeywordBlocklistGuardrail(List.of("malware", "exploit")),
    new PiiRedactionGuardrail())  // redacts emails, card/SSN-like numbers
```

Implement `Guardrail` to plug in a real DLP engine or content classifier.

### Observability exporters (`dev.axiom.observe`)

`JsonLinesExporter` appends every event as one JSON line — ship it to Loki, Elasticsearch, or Datadog, or query it directly:

```java
.onEvent(new JsonLinesExporter(Path.of("logs", "axiom-events.jsonl")).asListener())
```

```bash
jq -c 'select(.type=="ToolCallFinished")' logs/axiom-events.jsonl
```

`MetricsReporter` keeps live in-memory counters (runs, LLM calls, tokens, tool calls/errors/latency, approvals, guardrail blocks, budget breaches) with `snapshot()` for dashboards and `summary()` for logs.

See `examples/` for three runnable starters: `ResearchAgent` (retries + budget + guardrails + metrics), `CachedAgent` (disk-cached runs), `ObservedAgent` (JSONL export + metrics).

## v0.3 — what's new

### Durable execution (`dev.axiom.durable`)

Long-running agents that survive process death. Every run appends to a JSONL journal (fsync'd checkpoints), and a crashed run resumes from its journal — completed tool calls replay their **recorded results** instead of re-executing:

```java
Path journalRoot = Path.of("runs");
var config = AgentConfig.builder()
    .withModel("gpt-4o")
    .withTools(new Tools())
    .withJournalRoot(journalRoot)
    .build();

AgentRun run = AgentRun.begin(config, "Research solid-state batteries");
// ... the process can die at any point here ...
String checkpointId = run.checkpoint();   // fsync'd; the stable resume handle
AgentResult result = run.result();

// After a crash, in a new process:
AgentRun resumed = AgentRun.resumeFrom(journalRoot, checkpointId, config);
AgentResult result = resumed.result();
```

Semantics, stated plainly:

- **Completed tool calls are exactly-once from Axiom's perspective** — their results were journaled, so resume replays them and never calls the tool again.
- **The crash window is explicit, never silent** — every tool execution is wrapped in a side-effect ledger: `tool_call_started` is journaled before the tool body runs, `tool_call_completed` after it returns (error observations count as completions). A call that started but never completed *may or may not* have executed. Resume re-executes it **only** when the tool is declared `@Tool(idempotent = true)`; otherwise resume aborts with `DurableException` naming the ambiguous call instead of risking a double side effect. Idempotency is declared by the tool author, never inferred; the default is `false`.
- **Token streams are ephemeral** — `StreamToken` events are delivered live but never journaled; the journal only records complete turns, so resume never acts on a half-received response.

`AgentRun.listRuns(root)` lists crashed-but-resumable runs. Config-less recovery rebuilds the agent from the journal's recorded model and no-arg tool holders; synthetic tools (MCP/A2A/team) need their `AgentConfig` rebuilt explicitly.

### Typed eval harness (`dev.axiom.eval`)

Prompt regression testing where the compiler ties each case's scorer to its output type — a scorer for the wrong type doesn't compile:

```java
record Summary(String title, List<String> points) {}

EvalSuite suite = EvalSuite.of("summaries",
    EvalCase.of("q3-points", "Summarize the Q3 report", Summary.class,
        (s, ctx) -> s.points().size() == 2
            ? ScoreResult.pass("two points")
            : ScoreResult.fail("expected 2 points, got " + s.points().size())),
    EvalCase.of("cheerful", "Answer cheerfully", String.class,
        Scorers.llmJudge(new OpenAiLlmJudge(client), 0.7)));

var agent = new Axiom.Agent(Axiom.agent().withModel("gpt-4o").withTools(new Tools()).build());
EvalReport report = EvalRunner.run(suite, EvalRunner.AgentFactory.of(agent), "gpt-4o");
report.save(Path.of("evals/report.json"));

// Catch regressions against a baseline:
EvalDiff diff = EvalReport.load(Path.of("evals/baseline.json")).diff(report);
if (diff.hasRegressions()) System.out.println(diff);   // pass_to_fail, score_drop > 0.05

// …or gate CI directly: EvalGate.assertNoRegression(report, baseline)
// throws EvalGateException on any regression (new failing cases included).
```

Trajectory scorers grade *behavior*, not just outputs — which tools were called, in what order, with which arguments, and what was forbidden:

```java
// Capture the tool-call trajectory from agent events:
List<AgentEvent> events = new CopyOnWriteArrayList<>();
var agent = Axiom.agent(AgentConfig.builder().onEvent(events::add)/* … */.build());
var factory = EvalRunner.AgentFactory.recording(agent, events);

EvalCase.of("uses-calculator", "What is 17*23?", String.class,
    Scorers.allOf(
        Scorers.calledTool("multiply"),                  // was the tool called?
        Scorers.calledInOrder("multiply"),               // exact call order
        Scorers.toolArgsMatch("multiply",                // with which arguments?
            args -> Integer.valueOf(17).equals(args.get("x"))
                 && Integer.valueOf(23).equals(args.get("y"))),
        Scorers.neverCalledTool("delete_everything")));  // forbidden tools
```

Trajectory and output scorers compose on one case; LLM-judge verdicts are cached by task/output hash so re-runs are free. `EvalGate` fails CI on regressions: any case whose score dropped beyond tolerance, any pass→fail flip, and any *new* case that fails. Cases already failing in the baseline aren't regressions — fix them, don't gate on them.

Built-in scorers: `exactMatch`, `exactMatchIgnoreCase`, `containsAll`, `parsesAs` (schema-conformance backstop), `llmJudge`. Every case records pass/fail, score, explanation, prompt/completion tokens, cost, latency, and error detail. Reports persist as JSON.

### Native A2A v1.0 (`dev.axiom.a2a`)

Agent-to-agent interop over plain HTTP + JSON — JDK only, no framework. Serve any Axiom agent to foreign A2A agents, or consume them:

```java
// Expose an agent:
var researcher = new Axiom.Agent(
    Axiom.agent().withModel("gpt-4o").withTools(new WebTools()).build());
AgentCard card = AgentCard.simple("researcher", "Researches topics",
    "http://localhost:8080", "research a topic and return a brief");
try (A2aServer server = A2aServer.serve(researcher, card, 8080)) {
    // …visible to any A2A client, Axiom or foreign…
}

// Consume any A2A agent (Axiom or foreign — cards are parsed tolerantly):
A2aClient client = new A2aClient();
AgentCard card = client.getAgentCard("http://localhost:8080");
A2aTask task = client.sendTask("http://localhost:8080", "Summarize solid-state batteries");
System.out.println(task.artifactText().orElse("(none)"));
// …or subscribe: client.streamTask(url, text) → status/artifact SSE updates

// Or delegate from inside another Axiom agent — a remote agent as a local tool:
var agent = new Axiom.Agent(Axiom.agent().withModel("gpt-4o")
    .withToolDefinitions(A2aClient.asTool("http://localhost:8080",
        "remote_researcher", "Delegates research to the remote agent"))
    .build());
```

Implemented methods: `message/send`, `message/stream` (SSE status + artifact updates), `tasks/get`, `tasks/cancel`, plus `GET /.well-known/agent-card.json`. Authentication, TLS termination, and push notifications are intentionally out of scope for 0.3.

### Streaming model tokens (`dev.axiom.llm`)

`OpenAiCompatibleClient` implements `StreamingLlmClient`: tokens are delivered to the listener as they arrive, while the agent still acts only on the **complete** assembled turn (fragmented `tool_calls` deltas are merged before the agent sees them):

```java
var agent = new ReActAgent(AgentConfig.builder()
    .withClient(new OpenAiCompatibleClient("https://api.openai.com/v1", apiKey, "gpt-4o-mini"))
    .withTools(new Tools())
    .onEvent(e -> { if (e instanceof AgentEvent.StreamToken t) System.out.print(t.token()); })
    .build());

agent.run("Tell me a story");   // tokens print live; non-streaming clients are unaffected
```

Works against any OpenAI-compatible endpoint (OpenAI, Azure, Ollama, vLLM…); usage is read from `stream_options.include_usage` chunks when the provider sends them. Behind a proxy, set `HTTPS_PROXY`/`HTTP_PROXY` (and optionally `NO_PROXY`); credentials in the URL are used for proxy auth only and never sent to the model provider.

### Reproducible benchmark receipts (`dev.axiom.bench`)

GAIA-style and SWE-bench-style runners that record machine-readable receipts — framework, version, model, per-task pass/fail, tokens, cost, latency:

```bash
# Offline / deterministic (default): scripted model fixtures, REAL tool execution
java -cp "target/axiom-0.5.3.jar:lib/*" dev.axiom.bench.BenchMain
# -> benchmarks/receipts/receipt-fixture-<timestamp>.json

# Live: against a real model, free or paid
AXIOM_BENCH_PROVIDER=gemini GEMINI_API_KEY=... \
  java -cp "target/axiom-0.5.3.jar:lib/*" dev.axiom.bench.BenchMain --live
# -> benchmarks/receipts/receipt-live-gemini-<timestamp>.json
```

`AXIOM_BENCH_PROVIDER` picks the endpoint: `gemini` (free, no card), `openrouter` (free `:free` models), `groq` (free tier), `ollama` (local, no key), or `openai` (paid). `AXIOM_BENCH_MODEL` overrides the default model; `AXIOM_BENCH_DELAY_MS` overrides the per-provider pacing between tasks. Keys come from environment variables only — never paste one into chat or commit one. Live runs wrap the client in 429-aware retries (`Retry-After` honored) and every live receipt carries a written honesty disclosure: this is a **4-task representative subset, not a GAIA/SWE-bench score**, with the provider tier, pacing, and $0 cost basis recorded.

```
Benchmark receipt: axiom 0.3.0 | model=fixture mode=fixture
  [PASS] gaia-arithmetic      gaia (150 tokens, $0.0000, 2882ms)
  [PASS] gaia-two-step        gaia (241 tokens, $0.0000, 27ms)
  [PASS] gaia-file-lookup     gaia (172 tokens, $0.0000, 338ms)
  [PASS] swe-fix-greeting     swe (199 tokens, $0.0000, 242ms)
Totals: 4/4 passed (100%), 762 tokens, $0.0000, 3489ms
```

GAIA-style tasks pass when the agent's output contains the expected text; SWE-style tasks give the agent a sandboxed shell on a scratch copy of a repo and pass when the test command exits 0. Fixture mode scripts only the *model's* turns — tools really execute — so fixtures are deterministic without being vacuous. These are style/subset runners for reproducible self-measurement, not official GAIA/SWE-bench scores.

## Quickstart

```java
public class Tools {
    @Tool(description = "Search the web for current information")
    public String webSearch(
            @ToolParam(description = "The search query") String query,
            @ToolParam(description = "Max results", required = false) int limit) {
        // ...
    }
}

var agent = new Axiom.Agent(Axiom.agent()
    .withModel("gpt-4o")                       // or withClient(new OpenAiCompatibleClient(url, key, model))
    .withTools(new Tools())
    .withSystemPrompt("You are a research assistant.")
    .withMemory(new SlidingWindowMemory(40))
    .onEvent(e -> System.out.println(e))       // tracing
    .build());

String answer = agent.run("What happened in AI this week?");

// Typed output:
record Summary(String title, List<String> points) {}
Summary s = agent.runFor("Summarize this week in AI", Summary.class);
```

If `webSearch`'s signature and its schema ever disagree, the build fails. That's the whole idea.

## Building

No Maven required (a `pom.xml` is included for standard environments):

```bash
./build.sh   # compiles, runs all tests, packages target/axiom-0.5.3.jar
```

Requirements: JDK 21 (auto-detected at `~/workspace/tools/jdk-21`).

## Demo

```bash
export OPENAI_API_KEY=sk-...
java -cp "target/axiom-0.5.3.jar:lib/*" dev.axiom.demo.DemoAgent "What is 17*23, and save the answer as a note?"
```

## Roadmap

- **v0.6**: A2A authentication/push notifications, eval dataset versioning, hosted benchmark leaderboard, free-tier benchmark runner

## Status

v0.5.3 — Gemini live preset moved to gemini-3.8-flash (2.0-flash retired by Google); per-run model override via AXIOM_BENCH_MODEL documented.
v0.5.2 — fully detailed benchmark reports (per-task step-by-step traces in JSON + Markdown report), 194 tests green.
v0.5.1 — proxy support (`HTTPS_PROXY`/`HTTP_PROXY`/`ALL_PROXY`/`NO_PROXY` honored with auth and bypass rules), 188 tests green.
v0.5.0 — honest streaming retries (buffer-per-attempt, `Retry-After`), streaming cache with token replay, exactly-once durable side-effect ledger with `@Tool(idempotent)`, compile-time schema single-source-of-truth (unique tool names, Jackson-deserializability checks, runtime reads the generated artifact), trajectory eval scorers + `EvalGate` CI gate, free-tier benchmark path (`gemini`/`openrouter`/`groq`/`ollama`, paced, $0 receipts with honesty notes). 177 tests green.

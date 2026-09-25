# Axiom — Type-Safe AI Agent Framework for the JVM

Build AI agents in Java where **tool schemas are verified at compile time, not discovered at runtime**.

Every mainstream agent framework defines tools as runtime dictionaries: misspell a parameter, pass the wrong type, and you find out when the agent fails at 2 AM. Axiom moves that entire class of bugs to compile time. If it compiles, the agent's tools are valid.

## Features

- **Compile-time tool schemas** — annotate a method with `@Tool`; the annotation processor validates it during compilation (public method, documented `@ToolParam` on every parameter, mappable types, real parameter names via `-parameters`). Violations are build errors, and a JSON schema document is generated to `META-INF/axiom/tools/`.
- **ReAct agent loop** — reason + act with self-correction: tool errors are fed back as observations so the agent replans instead of crashing.
- **Structured typed output** — `agent.runFor(task, Invoice.class)` constrains the model to the JSON Schema generated from your POJO and deserializes it. Mismatches raise `StructuredOutputException`, never silent corruption.
- **Human-in-the-loop** — `requiresApproval = true` on any tool; denied calls are reported back so the agent replans.
- **Tool timeouts** — per-tool `timeoutSeconds`; hanging tools are cancelled and reported, never wedging the run.
- **Conversation memory** — pluggable `Memory` (bundled `SlidingWindowMemory`) gives multi-turn conversations without manual message management.
- **Full-fidelity events** — every LLM call, tool call, approval, budget charge, and result emits a typed `AgentEvent`. Tracing, UIs, and logging plug in with one listener.
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

    var agent = Axiom.agent().withModel("gpt-4o")
        .withToolDefinitions(registry.all().toArray(ToolDefinition[]::new))
        .buildAgent();
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
var agent = Axiom.agent().withModel("gpt-4o").withMemory(memory).buildAgent();

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

var agent = Axiom.agent().withModel("gpt-4o").withTools(shell).buildAgent();
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

var agent = Axiom.agent().withModel("gpt-4o")
    .withBudget(budget)
    .onEvent(e -> { if (e instanceof AgentEvent.BudgetUpdated u)
        System.out.printf("spent $%.4f of $%.2f%n",
            u.snapshot().costUsd(), u.snapshot().maxCostUsd()); })
    .buildAgent();

try {
    agent.run("Research everything about quantum batteries");
} catch (BudgetExceededException e) {
    System.out.println("Stopped: " + e.getMessage());  // typed: which limit, how much used
}
```

Every LLM call is charged; breaching a limit throws `BudgetExceededException` (carrying the full snapshot). `BudgetUpdated` events fire after every call — even the breaching one — so UIs can render live cost meters. Budgets are thread-safe and shareable across a supervisor team.

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

var agent = Axiom.agent()
    .withModel("gpt-4o")                       // or withClient(new OpenAiCompatibleClient(url, key, model))
    .withTools(new Tools())
    .withSystemPrompt("You are a research assistant.")
    .withMemory(new SlidingWindowMemory(40))
    .onEvent(e -> System.out.println(e))       // tracing
    .buildAgent();

String answer = agent.run("What happened in AI this week?");

// Typed output:
record Summary(String title, List<String> points) {}
Summary s = agent.runFor("Summarize this week in AI", Summary.class);
```

If `webSearch`'s signature and its schema ever disagree, the build fails. That's the whole idea.

## Building

No Maven required (a `pom.xml` is included for standard environments):

```bash
./build.sh   # compiles, runs all tests, packages target/axiom-0.2.0.jar
```

Requirements: JDK 21 (auto-detected at `~/workspace/tools/jdk-21`).

## Demo

```bash
export OPENAI_API_KEY=sk-...
java -cp "target/axiom-0.2.0.jar:lib/*" dev.axiom.demo.DemoAgent "What is 17*23, and save the answer as a note?"
```

## Roadmap

- **v0.3**: durable execution (crash recovery, Temporal-style), eval harness (prompt regression testing), A2A agent-to-agent protocol, streaming responses, published GAIA/SWE-bench scores

## Status

v0.2.0 — MCP client, supervisor/worker teams, vector memory, sandboxed subprocess tools, token/cost budgets. 65 tests green.

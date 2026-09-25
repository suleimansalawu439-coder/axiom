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
- **Full-fidelity events** — every LLM call, tool call, approval, and result emits a typed `AgentEvent`. Tracing, UIs, and logging plug in with one listener.
- **Provider-agnostic** — any OpenAI-compatible endpoint: OpenAI, Azure, Ollama, vLLM, LM Studio, Together…

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
./build.sh   # compiles, runs all tests, packages target/axiom-0.1.0.jar
```

Requirements: JDK 21 (auto-detected at `~/workspace/tools/jdk-21`).

## Demo

```bash
export OPENAI_API_KEY=sk-...
java -cp "target/axiom-0.1.0.jar:lib/*" dev.axiom.demo.DemoAgent "What is 17*23, and save the answer as a note?"
```

## Roadmap

- **v0.2**: MCP client (the won tool standard — 10k+ servers), multi-agent supervisor teams, vector-backed long-term memory, sandboxed subprocess tool execution, token/cost budgets
- **v0.3**: durable execution (crash recovery, Temporal-style), eval harness (prompt regression testing), A2A agent-to-agent protocol, streaming responses, published GAIA/SWE-bench scores

## Status

v0.1.0 — core framework complete, 21 tests green.

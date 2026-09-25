# Axiom vs LangChain4j 1.20.0 — failure-mode head-to-head

**Date:** 2026-09-25
**Scope:** six failure modes, tested as honestly as possible. Nothing here claims Axiom is better overall.
**Axiom version:** v0.10.0 (297/297 tests green, commit `5bf3f29`, private repo)
**LangChain4j version:** **1.20.0** — latest stable as of 2026-09-25
(GitHub tag `1.20.0`, prerelease: false, published `2026-09-04T12:27:58Z`;
artifacts + `-sources` jars fetched from Maven Central and extracted under
`eval/head-to-head/sources/`)

## The narrow claim

On the six failure modes below, **Axiom catches specific failures that LangChain4j 1.20.0
does not** — or catches them strictly earlier. That is the entire claim. LangChain4j wins
on ecosystem, maturity, integrations, and production history (see §7); Axiom is a
pre-release research framework. Nobody should pick a framework on this document alone.

## Method and evidence labels

Every scenario verdict carries one of three labels:

- **LIVE** — executed: a program was compiled and run, output captured.
- **SOURCE** — verified from the pinned 1.20.0 sources (exact file + line cited).
- **ARCHITECTURAL** — no equivalent feature exists; demonstrated through API/source absence.

Rules followed: `src/main` was not modified (evaluation lives in `eval/head-to-head/`);
scenarios that could not be tested fairly were labeled, not fudged.

## Environment note (sandbox networking quirk)

The sandbox egress proxy is irrelevant here — all HTTP tests run against localhost
mocks with proxy env vars unset. One quirk *did* matter: the JVM opens AF_INET6
dual-stack sockets and dials `::ffff:127.0.0.1`, and this sandbox RSTs those
connections against localhost listeners (verified via strace: SYN-ACK then immediate
RST, `accept()` never fires; curl/python use plain IPv4 and are unaffected). The
harness therefore runs every JVM with `-Djava.net.preferIPv4Stack=true`, making the
JVM behave like every other HTTP client. This is a test-environment quirk, not a
product behavior on either side. The mock itself is a raw-socket HTTP/1.1 server
(`src/harness/MockLlmServer.java`) because `com.sun.net.httpserver` resets
connections carrying the JDK client's `Upgrade: h2c` header.

Reproduce: `./fetch-deps.sh && ./run.sh` in `eval/head-to-head/`
(JDK 21; full output of the reference run is `evidence-run-2026-09-25.log`).

---

## (a) Duplicate tool definitions — LIVE on both sides

Two `@Tool` methods exposing the same name `calculate`.

**Axiom — caught at compile time.** Compiling the fixture with the annotation
processor (`javac -proc:only`) fails the build:

```
fixtures/axiom/DuplicateTools.java:17: error: [Axiom] @Tool name 'calculate' is already
used by DuplicateTools. Tool names must be unique across the compilation — the runtime
registry is keyed by name.
```

(`dev.axiom.tools.processor.ToolProcessor`, duplicate-name tracking across the
compilation; the run also emitted `META-INF/axiom/tools/DuplicateTools.json` and
`META-INF/axiom/policy/DuplicateTools.json` for the surviving definitions.)

**LangChain4j — caught at wiring time.** Building an `AiService` with
`.tools(new DupTools())` throws before any model call:

```
dev.langchain4j.service.IllegalConfigurationException: Duplicated definition for tool: calculate
    at dev.langchain4j.service.tool.ToolService.addTools(ToolService.java:592)
```

(`ToolSpecifications.validateSpecifications()` also flags it; the throw above is the
enforcement point.)

**Verdict:** both frameworks refuse duplicate tool names — no silent last-wins
registry. Axiom refuses at `javac` time (the broken code can never be built);
LangChain4j refuses when the service is wired (`.tools(...)` → `addTools`). Earlier
is better, but LangChain4j's behavior is correct and loud. **Narrow Axiom edge:
build-time vs wiring-time.**

---

## (b) Process crash in the middle of a tool call — Axiom LIVE, LangChain4j ARCHITECTURAL/SOURCE

**Axiom.** The durable-execution journal records `tool_call_started` /
`tool_call_completed` per call. `KillResumeChaosTest` kills a *real separate JVM*
mid-tool-body via `destroyForcibly()` and then resumes:

- non-idempotent tool, killed in the crash window → resume **aborts loudly** with
  `DurableException` naming the tool; the side effect is never re-executed
  (`killInsideToolBodyWithNonIdempotentToolRefusesResume`: asserts the
  `tool_call_started` line exists, no `tool_call_completed` line, `DurableException`
  thrown, effect counter stays 0);
- `@Tool(idempotent = true)` → resumes and re-executes **exactly once**;
- completed calls replay from the journal without re-execution.

(`src/test/java/dev/axiom/chaos/KillResumeChaosTest.java`,
`src/main/java/dev/axiom/durable/DurableException.java`)

**LangChain4j.** No durable execution journal exists in the pinned
`langchain4j-core`/`langchain4j` 1.20.0 sources. `ToolService.executeInferenceAndToolsLoop`
(`dev/langchain4j/service/tool/ToolService.java:599`) runs the inference → tool-execution
loop and persists tool-result messages *after* each tool executes — if the process
dies between the tool's side effect and result persistence, there is no framework
ledger that can later distinguish "effect happened" from "effect did not happen",
so a resume cannot be made safe. This is **ARCHITECTURAL**: the concept is absent,
not merely untested. Fair nuance: LangChain4j 1.20.0 *does* have `@CompensateFor`
(compensating actions for tool failures **within a running process** —
`ToolService.java`, `AiServices.java`), which is a genuine reliability feature, but
it cannot run after `kill -9` and is not crash-resume semantics.

**Verdict:** on crash-mid-tool-call, Axiom has a tested answer and LangChain4j has
no equivalent mechanism. **Axiom catches this failure mode; LangChain4j does not.**

---

## (c) Capability prerequisite violation — Axiom LIVE/SOURCE, LangChain4j ARCHITECTURAL

**Axiom.** Tools declare capability requirements (`@Requires`) and grants
(`@Ensures`) over a lattice (`READ/WRITE/DESTRUCTIVE/NETWORK/SPEND/PRIVATE_DATA`
plus session tokens such as `BACKUP`, `APPROVAL`). `ToolProcessor` *proves policy
satisfiability at compile time* — a tool requiring a token no tool in the
compilation ensures is a build error, captured verbatim from an actual compiler run:

```
error: [Axiom] Policy unsatisfiable: tool 'deleteOldSnapshots' requires session token
BACKUP, but no @Tool in this compilation ensures it. The tool could never run — either
add a tool with @Ensures(BACKUP) (e.g. a backup tool) or drop the @Requires.
```

(`ToolProcessor.java:250`; policy emitted to `META-INF/axiom/policy/*.json`.)
At runtime, `CapabilityGuardrail` (`dev/axiom/guardrails/CapabilityGuardrail.java`)
enforces the same policy with session tokens rebuilt from the journal on resume.
Honest boundary, documented in the v0.7.0 notes: the compiler proves the *policy*
coherent and satisfiable; the LLM's live choices are enforced at runtime, never
claimed as compile-time-proven.

**LangChain4j.** No capability lattice, session-token prerequisite model, or shared
compile/runtime capability policy was found anywhere in the pinned sources
(searched `langchain4j-core` and `langchain4j` for capability/requirement/session-token
concepts — no matches). Applications can build custom checks on before/after-tool
hooks, but that is hand-rolled per app, not a framework-provided compile-time +
runtime policy system. **ARCHITECTURAL.**

**Verdict:** a tool that can never legally run is a build error in Axiom; in
LangChain4j the same misconfiguration surfaces (if at all) as a runtime surprise
written by the application author. **Axiom catches this failure mode; LangChain4j
does not.**

---

## (d) Token/cost budget enforcement — Axiom LIVE/SOURCE, LangChain4j ARCHITECTURAL/SOURCE

**Axiom.** `dev.axiom.budget` (`Budget`, `BudgetExceededException`, `ModelPrices`)
is wired into the ReAct loop: `ReActAgent.java:192-193` charges usage after resume
("money spent before the crash still counts"), `:254` charges per turn, and the
run aborts when the budget is exhausted.

**LangChain4j.** No framework token/cost budget API or mid-run cumulative enforcement
was found in the pinned `langchain4j-core`/`langchain4j` sources (searched for
budget concepts — no matches). Token usage is exposed on responses and the OpenAI
module supports per-request generation limits, but those are observability and
per-call caps, not a cumulative agent-run cost budget. Users can implement their
own listeners/wrappers — again hand-rolled, not framework-provided. **ARCHITECTURAL.**

**Verdict:** a runaway agent loop has a built-in circuit breaker in Axiom; in
LangChain4j the application must build its own. **Axiom catches this failure mode;
LangChain4j does not.**

---

## (e) Malformed SSE chunk mid-stream — LIVE on both sides

Mock server sends: one valid SSE chunk (`"content":"hello"`), then
`data: THIS IS NOT JSON`, then `data: [DONE]`.

**LangChain4j** (`OpenAiStreamingChatModel`, 1 HTTP attempt, no retry of the stream):

```
[LC4J] (e) garbage SSE -> ERROR: java.lang.RuntimeException:
com.fasterxml.jackson.core.JsonParseException: Unrecognized token 'THIS' ...
| partial content delivered before failure: 'hello'
```

The error surfaces via `onError` — loud, correct. But note: the application
**already received `"hello"`** through `onPartialResponse` before the failure.
If the app acted on that partial content (displayed it, logged it, fed it
downstream), the failure arrives after the fact.

**Axiom** (`RetryingLlmClient` over `OpenAiCompatibleClient.chatStream`,
full HTTP path, default policy = 4 attempts):

```
[AXIOM] (e) garbage SSE -> ERROR: dev.axiom.llm.LlmException:
LLM streaming request failed: Unrecognized token 'THIS' ...
| partial content delivered before failure: '' | HTTP attempts: 4
```

Each attempt's tokens are buffered and **only replayed to the application on
success** (`RetryingLlmClient.java:96-122`; "buffered tokens are dropped here —
the next attempt starts clean"). All four attempts saw `"hello"` internally, but
the application received **zero** partial output — no duplicates, no
already-displayed-then-failed content — before the final error surfaced.

**Verdict:** both frameworks fail loudly on malformed SSE (no silent swallowing
on either side — a genuine draw on fail-loudness). Axiom's edge is narrower and
structural: its streaming retry buffers per-attempt output, so a failed stream
never leaks partial content to the application. **Draw on detection; Axiom edge
on partial-output hygiene.**

---

## (f) HTTP 429 with `Retry-After` — LIVE on both sides

Mock server answers **every** request with `429` + `Retry-After: 30` + body
`"You exceeded your current quota, please check your plan and billing details."`
Then a second round: one plain rate-limit `429` + `Retry-After: 2`, then `200 OK`.

**LangChain4j:**

```
[LC4J] (f) 429+Retry-After:30 -> dev.langchain4j.exception.RateLimitException:
{"error":{"message":"You exceeded your current quota, please check your plan
and billing details.","code":429}}
[LC4J] (f) elapsed 1518 ms over 3 HTTP attempts; Retry-After:30 honored? NO
```

Three things went wrong, all confirmed in source:

1. `Retry-After: 30` was **ignored** — total elapsed 1.5s instead of ~30s+
   (the string `retry-after` appears nowhere in the `langchain4j-open-ai` or
   `langchain4j-http-client` sources).
2. The **quota-exhausted** 429 was retried twice with ~1s backoff like any
   rate-limit 429 — there is no distinction between "slow down" and "your plan
   is exhausted; retrying is futile" (`ExceptionMapper.java:67-68` maps every
   429 to `RateLimitException`; `OpenAiChatModel.java:87` defaults `maxRetries`
   to 2, `:169` wraps the call in `withRetryMappingExceptions`).
3. The eventual exception is `RateLimitException` — the quota signal in the body
   is not surfaced as a distinct, actionable type.

**Axiom:**

```
[AXIOM] (f1) quota-429 -> dev.axiom.llm.LlmException:
LLM request failed with HTTP 429: {"error":{"message":"You exceeded your current
quota, please check your plan and billing details.","code":429}}
[AXIOM] (f1) isQuotaExhausted=true, HTTP attempts=1, elapsed=24 ms
[AXIOM] (f2) rate-429+Retry-After:2 -> SUCCESS after 2026 ms, 2 attempts,
content='ok' (Retry-After honored? YES)
```

- Quota-exhausted 429 → **1 attempt, ~24ms, fail-fast** via
  `LlmException.isQuotaExhausted` (the "check your plan and billing" signal is
  classified, not retried).
- Plain rate-limit 429 + `Retry-After: 2` → **honored** (2.0s elapsed), retried
  once, succeeded. (`OpenAiCompatibleClient.java:344-356` parses seconds or
  HTTP-date; hints capped at 10 minutes at `:341` so an absurd header can't wedge
  a run — a case the chaos suite covers with hostile providers.)

**Verdict:** the clearest result of the six. On identical hostile input, Axiom
honors `Retry-After` and fails fast on quota exhaustion; LangChain4j 1.20.0
ignores `Retry-After` and burns its retries against a dead quota. **Axiom
catches/handles this failure mode; LangChain4j does not.**

---

## Scorecard

| # | Failure mode | Axiom | LangChain4j 1.20.0 | Label |
|---|---|---|---|---|
| (a) | Duplicate tool names | Build error (javac) | `IllegalConfigurationException` at wiring | LIVE / LIVE |
| (b) | Crash mid-tool-call | Journal + `DurableException` / exactly-once resume | No equivalent mechanism | LIVE / ARCHITECTURAL |
| (c) | Capability prerequisite | Compile-time policy proof + runtime guardrail | No equivalent mechanism | LIVE,SOURCE / ARCHITECTURAL |
| (d) | Token/cost budget | Built-in, wired into ReAct loop | No equivalent mechanism | LIVE,SOURCE / ARCHITECTURAL |
| (e) | Malformed SSE chunk | Loud error; failed-attempt output buffered & discarded | Loud error; partial output already delivered | LIVE / LIVE |
| (f) | 429 + `Retry-After` | Honored; quota-429 fails fast (1 attempt, ~24ms) | Ignored; quota-429 retried (3 attempts, 1.5s) | LIVE / LIVE |

## The steelman: where LangChain4j wins outright

This document would be dishonest without it. LangChain4j 1.20.0 is a **mature,
production-proven framework** and the default sane choice on the JVM today:

- **Ecosystem breadth:** first-class modules for OpenAI, Anthropic, Google Gemini,
  Ollama, Mistral, Cohere, Bedrock, Azure, and more; vector stores (pgvector,
  Pinecone, Qdrant, Elasticsearch…); document loaders/parsers; RAG pipelines —
  years of integration work Axiom has not done and is not claiming.
- **AiServices:** the declarative `@RegisterAiService` proxy model is genuinely
  productive — typed interfaces over agents with tool wiring, memory, and
  structured output in a few annotations.
- **Production history:** battle-tested by a large community; edge cases in
  serialization, streaming, and provider quirks have been found and fixed in the
  open over many releases.
- **The (a) result above** shows LangChain4j's config validation is real:
  duplicate tools are refused loudly, not silently.
- **`@CompensateFor`** gives in-process tool-failure compensation that Axiom
  does not have.

Axiom's advantages in this report are narrow and specific to failure handling.
Anyone choosing today on maturity, integrations, hiring, and community support
should choose LangChain4j. Axiom's bet is that the failure modes above —
crash safety, capability policy, budgets, honest retries — are the ones that
decide whether agents survive contact with production.

## Limitations and threats to validity

- Only six failure modes were tested. No claim is made about overall quality,
  performance, or suitability.
- (b), (c), (d) were not executed against LangChain4j live — you cannot live-test
  a feature that does not exist. Absence was demonstrated through the pinned
  sources, and the report says so.
- (b) and (d) on the Axiom side rest on the repo's existing test suites
  (executed: 297/297 green), not on new harnesses written for this comparison.
- (e)/(f) used a localhost mock, not a real provider. Real providers vary;
  the mock implements the documented OpenAI wire behavior (SSE framing,
  429 semantics, `Retry-After`).
- The LangChain4j retry behavior in (f) is the *default* (`maxRetries=2`);
  users can configure it, and can hand-roll Retry-After/quota handling. The
  finding is that the framework does not do it out of the box — Axiom does.
- Axiom is pre-release; its failure handling has far less production mileage
  than LangChain4j's everything.

## Reproduction

```
cd eval/head-to-head
./fetch-deps.sh   # pinned jars + sources from Maven Central
./run.sh          # compiles fixtures, runs all LIVE scenarios
```

Reference output: `eval/head-to-head/evidence-run-2026-09-25.log`.
Harness sources: `eval/head-to-head/src/` (fixtures, `ScenarioA_Lc4j`,
`ScenarioEF_Lc4j`, `ScenarioEF_Axiom`, `harness/MockLlmServer`).
No file under `src/main` was touched.

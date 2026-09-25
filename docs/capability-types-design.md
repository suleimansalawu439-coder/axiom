# Capability Types — Design Brief

**Status:** design + v0.7.0 partial implementation (build-order steps 1–3 of §6,
minus the `@VerifiedPlan` DSL — see §7). No taint, no containment yet.
**Date:** 2026-09-25
**Goal:** Make forbidden agent behaviours *unrepresentable at compile time*.
Tools carry capability types (`READ`, `WRITE`, `DESTRUCTIVE`, `NETWORK`,
`SPEND`, …); multi-step plans are verified by `javac` so that e.g. a
`DESTRUCTIVE` call before a `BACKUP`, or `SPEND` without `APPROVAL`, fails
the **build** instead of relying on a runtime guardrail to catch it at 2am.

---

## 1. Prior-art verdict: what's reinvented, what's novel

| Prior art | What it does | Verdict for Axiom |
|---|---|---|
| Object-capability model (Miller; E; **Joe-E**, a capability subset of *Java* with a source verifier; Caja) | Authority = unforgeable object references; Joe-E verifies capability discipline + functional purity at compile time via an overlay type system | **Inspiration, not competition.** Joe-E proves a Java verifier can enforce capability discipline. But it constrains *general programs*, not *agent plans*; it says nothing about tool-call sequences or LLM-driven choice. We borrow the verifier-over-Java idea, not the model. |
| Session / behavioural types (Honda et al.; Scribble; Hu & Yoshida's hybrid endpoint-API generation for Java) | Global protocol → projected local types → static endpoint checking (+ light runtime linearity checks) | **Closest structural analogue.** MPST verifies *communication protocols* between fixed roles. Agent tool-use is a different shape: one nondeterministic role (the LLM) against a registry of tools. Nobody has applied the "project-and-typecheck" idea to *agent capability protocols*. The hybrid static+runtime split is the pattern to copy. |
| Checker Framework (tainting checker, typestate checker; pluggable via `javac -processor`) | Pluggable type qualifiers checked at compile time | **Mechanism precedent, wrong tool for the job** (see §2). Its tainting checker is the closest existing Java feature to our taint rule, but it can't express temporal/sequence properties ("B before A") and imposes a separate checker toolchain on users. |
| Effect systems (Koka effect rows; Effekt "effects as capabilities"; Pony reference capabilities) | Effects/capabilities visible in types | **Theoretical backing.** Effekt's identification of capabilities with effects is exactly our mental model (a tool's capability set *is* its effect row). But these are whole-language designs; we need a *framework-library* mechanism inside Java 21, not a new language. |
| CSL / Chimera (`csl-core`): policy DSL compiled to **Z3** constraints, formally verified policies, LangChain runtime integration | Verifies the *policy itself* has no loopholes; enforces at runtime | **Complementary.** CSL answers "is my policy consistent?" We answer "does this agent's plan/tool-graph satisfy the policy, checked at application build time?" Nobody wires both into one framework with a shared policy artifact. |
| effector-compose: type-checked YAML composition of agent capabilities, dry-run plan, emits LangGraph/CrewAI | Compile-time type checking of *static pipelines* | **Nearest existing product.** But: YAML/TS, external to the runtime, no Java, no enforcement against the LLM's *dynamic* choices, no taint tracking. A static pipeline checker is the easy half; the hard half is the static/dynamic composition (§4). |
| Progent (arXiv 2504.11703): programmable privilege control, SMT-mediated updates, *monotonic confinement* | Runtime least-privilege; privileges can only shrink without approval | **Runtime half of our design, already published.** We should cite it and position capability types as the *compile-time* partner: Progent confines at runtime; we prove at build time that the policy is satisfiable and the static plans comply. |
| CaMeL: capability tagging + provenance of tool outputs, control/data-flow separation | Runtime taint/provenance enforcement | **Runtime half of our taint rule.** Our compile-time contribution: *declaration completeness* (every NETWORK sink must declare a taint policy or the build fails) and *taint satisfiability* over the tool graph. |
| "Methods for Formal Verification of Agent Skills" (arXiv 2605.23951): 3-layer capability-containment proofs for skills (abstract interpretation → refinement types → SMT bounded model checking) | Verifies *tool implementations* against declared capability manifests | **Adjacent, non-overlapping.** They verify the *skill side* (does this code stay within its manifest?). We verify the *application side* (does this plan/policy composition respect capability rules?). Their layer-1 (static containment) is the technique we'd reuse *inside* the processor for the honesty check (§4). |
| "Toward Safe LLM Agents" survey (arXiv 2608.14590): formalises the *validation problem* π ⊨ φ (plan satisfies safety property) | Frames specification → verification → enforcement | **Use their framing.** Our design maps cleanly: annotations = specification, processor = verification (static part), guardrail+journal = enforcement (dynamic part). |

**Novelty claim (narrow, defensible):** nobody has built a *Java-native,
annotation-processor-based, compile-time verifier for agent capability
policies that shares one policy source with a fail-closed runtime enforcer
and a journal-backed audit trail*. The pieces exist separately (CSL's Z3
policies, effector-compose's static checking, Progent/CaMeL's runtime
enforcement, Joe-E's Java verifier). The composition — one policy, two
enforcement points, zero new toolchain — is the contribution. Do **not**
claim "first capability security for agents" or "first formal methods for
agents"; both are false.

---

## 2. Recommended mechanism for Java 21

**Options considered:**

1. **Checker-Framework-style pluggable type system.** Rejected. Heavy
   dependency, separate `-processor` invocation users must configure,
   qualifier-based checkers express *value* properties (taint, nullness)
   well but *temporal/sequence* properties ("BACKUP before DESTRUCTIVE")
   very badly, and diagnostics are notoriously cryptic. Axiom's `build.sh`
   works without Maven; a Checker Framework dependency breaks that story.

2. **Pure phantom-type / generics encoding** (capability sets as type
   parameters, e.g. `Plan<HasBackup, NoDestructive>`). Rejected as the
   primary mechanism. Java generics can't express "for all paths" or
   ordering without dependent types; error messages become alphabet soup;
   and it can't analyse anything the LLM does at runtime. Usable as a
   *supplement* for simple cases, not the core.

3. **Extend the existing `ToolProcessor` annotation processor.**
   **Recommended.** Axiom already verifies `@Tool` methods at compile time
   and emits `META-INF/axiom/tools/*.json` as the runtime's single source
   of truth. Capability types ride the same rails:
   - `@Tool` gains capability attributes (declaration site = the tool
     author, who knows what the tool does).
   - A small plan/policy DSL (`Plan.sequence(...)`, `@VerifiedPlan`)
     gives the processor a *static plan graph* to model-check.
   - The processor emits a **runtime policy artifact**
     (`META-INF/axiom/policy/<binary-name>.json`) — the same policy,
     machine-readable, consumed by a fail-closed `CapabilityGuardrail`.
   - One policy source → compile-time diagnostics + runtime enforcement.
     No drift, no second language, no new toolchain.

**Why this wins:** it is javac-native (errors appear in the IDE like any
compile error), ergonomic (annotations + a builder DSL Java devs already
understand), consistent with Axiom's architecture, and keeps `build.sh`
dependency-free.

---

## 3. API sketch

### 3.1 The capability lattice

```java
package dev.axiom.capabilities;

/** Effect/capability lattice. Order: implication (DESTRUCTIVE implies WRITE). */
public enum Capability {
    READ,            // observes state, no mutation
    WRITE,           // mutates non-destructive state
    DESTRUCTIVE,     // deletes / irreversibly mutates (implies WRITE)
    NETWORK,        // touches the network
    SPEND,          // spends money / consumes paid quota
    PRIVATE_DATA,   // returns data with privacy obligations (taint source)
    APPROVAL,       // human approval obtained (a *token*, not a tool effect)
    BACKUP;         // a fresh backup exists (a *token*, not a tool effect)

    /** Capability implication lattice. */
    public boolean implies(Capability other) { ... }
}
```

`APPROVAL` and `BACKUP` are *session tokens*: they are not effects of the
world but facts about the session, `ensured` by some tools and `required`
by others. This is deliberately STRIPS/PDDL-shaped: tools declare
preconditions and effects over a small token vocabulary.

### 3.2 Tool declarations

```java
public class OpsTools {

    @Tool(description = "Snapshot the database to cold storage",
          capabilities = {Capability.READ, Capability.WRITE})
    @Ensures("backup.exists")
    public String backupDatabase() { ... }

    @Tool(description = "Delete snapshots older than 30 days",
          capabilities = {Capability.DESTRUCTIVE})
    @Requires("backup.exists")          // compile-time: satisfiable? runtime: enforced
    public String deleteOldSnapshots() { ... }

    @Tool(description = "Charge the customer card",
          capabilities = {Capability.SPEND, Capability.NETWORK})
    @Requires("approval.obtained")
    public String chargeCard(@ToolParam("Amount in cents") long cents) { ... }

    @Tool(description = "Fetch the customer record",
          capabilities = {Capability.READ})
    @TaintSource("customer.pii")
    public CustomerRecord fetchCustomer(@ToolParam("id") String id) { ... }

    @Tool(description = "POST a JSON payload to a webhook",
          capabilities = {Capability.NETWORK})
    @TaintSink(policy = TaintPolicy.REDACT, except = "customer.id")
    public void postWebhook(@ToolParam("payload") String payload) { ... }
}
```

`@Requires` / `@Ensures` reference tokens in a declared token vocabulary;
`@TaintSource` / `@TaintSink` declare the taint rule (§3.4).

### 3.3 Static plan verification

For developer-authored plan templates (the static half — see §4):

```java
@VerifiedPlan("nightly-cleanup")
public Plan nightlyCleanup() {
    return Plan.sequence(
        Plan.step(OpsTools::backupDatabase),      // ensures backup.exists
        Plan.step(OpsTools::deleteOldSnapshots)   // requires backup.exists ✓
    );
}
```

The processor walks the plan graph against the token flow. A violation
fails the build with a javac-native error:

```
error: [axiom-capability] Plan 'nightlyCleanup' violates token flow:
  step 2 'deleteOldSnapshots' requires token 'backup.exists',
  but no preceding step ensures it.
  Required by: @Requires("backup.exists") on OpsTools.deleteOldSnapshots
  Fix: insert a step whose @Ensures provides 'backup.exists'
       (e.g. OpsTools::backupDatabase) before this step.
```

And a taint violation:

```
error: [axiom-capability] Tool 'postWebhook' is a NETWORK sink reachable
  from taint source 'customer.pii' (via OpsTools::fetchCustomer) and declares
  no @TaintSink policy. Either declare @TaintSink(policy=...) or route the
  data through a @Sanitizer.
```

### 3.4 The three v1 rule shapes (deliberately small)

Do **not** build a general temporal logic in v1. Three shapes cover the
motivating examples and stay decidable over a finite tool graph:

1. **Ordering / prerequisite** — `@Requires`/`@Ensures` tokens.
   *No DESTRUCTIVE before BACKUP; SPEND requires APPROVAL.* Checked on
   static plan graphs; compiled to runtime preconditions for dynamic use.
2. **Taint** — `@TaintSource` → … → `@TaintSink(policy)`. Every NETWORK
   (or otherwise exfiltrating) tool must declare its taint policy or the
   build fails (*declaration completeness*). Runtime tracks actual values.
3. **Containment (annotation honesty)** — if method `a` (transitively,
   within the compilation unit) calls tool `b`, then
   `caps(a) ⊇ caps(b)`. A READ tool that secretly calls a DESTRUCTIVE
   tool fails the build. This is the arXiv-2605.23951 layer-1 idea,
   scoped to what's visible to `javac`.

---

## 4. The static/dynamic boundary (the crux)

**Honest statement:** the LLM chooses tools at runtime. No compile-time
check can see the future. Anyone claiming to "statically verify agent
behaviour" without qualifying this is selling something. Here is the
exact boundary:

**Statically verifiable (javac / processor):**
- *Declarations are complete and coherent.* Every tool's capability set,
  every `@Requires` token, every taint sink policy is present.
- *Containment.* A tool cannot under-declare the capabilities of the
  tools it calls (call-graph check within the compilation).
- *Static plan templates* (`@VerifiedPlan`) satisfy token flow and taint
  rules — full model-checking of the plan graph, since the graph is data
  in the source.
- *Policy satisfiability over the tool graph.* For every `@Requires`
  token, **some** tool in the registry `@Ensures` it; otherwise the
  constrained tool is dead code and the policy is unsatisfiable — a
  compile error, not a 2am discovery. This "no dead policies" check is,
  to our knowledge, done by nobody in the agent space.
- *Taint reachability over the tool graph.* If a taint source can reach a
  sink through declared signatures, the sink must declare a policy.

**Must remain runtime (the model's choices):**
- Which tools the LLM actually calls, in what order, with what
  arguments, and what *values* flow (taint is a runtime value property).
- Whether `@Ensures("backup.exists")` is *semantically* true (the tool
  could back up an empty directory — the compiler can't know).

**How the two layers compose (the actual design):**
1. Single policy source: annotations + `@VerifiedPlan` templates.
2. The processor emits `META-INF/axiom/policy/*.json` — tokens,
   prerequisites, taint policies, capability sets — the same artifact
   style as the tool schemas.
3. A fail-closed `CapabilityGuardrail` loads the artifact at startup and
   checks **every** tool call before dispatch: prerequisites against
   session tokens recorded in the **durable journal** (which already logs
   every `tool_call_completed` — the perfect provenance/token store),
   taint against value provenance tracked per session.
4. On violation: block, journal the block, and (for approval-shaped
   tokens) route to the existing human-in-the-loop approval flow —
   Axiom already has approvals; capability types give them a formal
   trigger.
5. Benchmark/eval receipts record the policy artifact hash, so a receipt
   is auditable against the exact policy that constrained the run.

The static layer's job is therefore **not** "prove the agent is safe"
(which is impossible) but: *prove the policy is coherent and satisfiable,
prove the static plans comply, and guarantee the runtime enforcer is
complete* — i.e., every constrained call the LLM *could* make is covered
by a checked precondition. The runtime layer's job is to decide the
actual calls. Neither half is sufficient; together they are the strongest
claim anyone in this space can honestly make.

---

## 5. Open risks

1. **Annotation honesty.** Capabilities are self-declared. The
   containment check catches *intra-compilation* under-declaration, but
   a tool can still lie about what its own bytecode does (e.g. raw socket
   use inside a `READ` tool). Mitigations: containment + the chaos suite
   hammering + a `@CapabilityAudited` marker for human-reviewed tools.
   Never claim the static layer is sound against adversarial tool
   authors — it is sound against *mistakes*, not malice.
2. **Semantic circumvention.** The model can satisfy the letter of a
   token (`backup.exists`) while violating its spirit (empty backup).
   Static verification is blind to semantics. This needs the third layer
   already on the roadmap: trajectory evals / LLM-judge semantic
   guardrails. Name it explicitly; don't let the formal layer imply
   semantic safety.
3. **Runtime-discovered tools (MCP).** Tools arriving over MCP at runtime
   never pass through `javac`. Policy: conservative defaults (a newly
   discovered tool gets the *union* of capabilities unless its MCP
   manifest declares otherwise — fail closed), full runtime enforcement,
   and a receipt annotation that the static layer did not cover it.
4. **Expressiveness creep.** The temptation will be to grow the rule
   language into full LTL. Resist in v1: three rule shapes, hard stop.
   General temporal policies can compile to the *runtime* checker later
   without changing the static contract.
5. **False positives vs. adoption.** An unsound-but-strict checker that
   blocks legitimate work will be `@SuppressWarnings`'d into irrelevance.
   Every suppression must carry a justification string, and suppressions
   are recorded in receipts (auditable, and a metric: suppression rate
   measures policy quality).
6. **Processor performance.** Reachability over the tool graph is trivial
   (tens of tools), but the containment call-graph walk must be bounded
   and cached per round — annotation processors that slow the build get
   disabled.
7. **Token vocabulary governance.** `@Requires("backup.exists")` is a
   stringly-typed contract between tools. V1: processor checks that every
   required token is ensurable by *some* tool and warns on tokens ensured
   by none *or* required by none (dead vocabulary). V2: typed token
   constants.

---

## 6. Suggested build order

1. `@Requires`/`@Ensures` + prerequisite rule on `@VerifiedPlan`
   (smallest end-to-end: annotation → processor check → compiler error).
2. Policy artifact emission + `CapabilityGuardrail` (fail-closed runtime
   half, journal-backed tokens).
3. Policy satisfiability ("no dead policies") over the tool graph.
4. Taint: `@TaintSource`/`@TaintSink` + declaration completeness +
   runtime value provenance.
5. Containment (call-graph honesty check).
6. Suppression-with-justification + receipt integration.

Each step is independently shippable and testable; step 1 alone is
already a demo nobody else has in Java.

---

## 7. v0.7.0 implementation notes (2026-09-25)

Shipped: the capability lattice, `@Requires`/`@Ensures`, the prerequisite
rule over the tool graph (not `@VerifiedPlan` — the plan DSL is deferred),
policy artifact emission, the fail-closed `CapabilityGuardrail`, and the
compile-time satisfiability check. 261/261 tests green.

**Deviations from the design, with reasons:**

1. **Typed tokens from the start.** §3.2 sketched `@Requires("backup.exists")`
   strings with typed constants as V2 (open risk #7). v0.7.0 skips the
   stringly-typed V1: `@Requires`/`@Ensures` take `Capability[]` directly, so
   the token vocabulary is compiler-checked from day one. The stringly-typed
   risk never materialized.
2. **`@VerifiedPlan` DSL deferred.** Static plan-template model-checking is
   the natural next step (the processor already has the token-flow
   machinery), but v0.7.0 proves the policy over the *tool graph* instead:
   every required token must be ensurable by some registered tool. The
   "no dead policies" check (§4's satisfiability bullet) is fully
   implemented; per-plan ordering checks await the DSL.
3. **Block = abort the run.** On a runtime violation the guardrail returns
   `Verdict.block`, which aborts the run with `GuardrailViolationException`
   after journaling a `GuardrailBlocked` event with `side="tool"` — consistent
   with the existing `Guardrail` contract, fail-closed. A future refinement
   could feed precondition failures back to the LLM as observations (so it
   can go earn the token), but v1 stays fail-closed.
4. **MCP/synthetic tools are fail-open (documented).** A tool with no policy
   entry — synthetic, or MCP-discovered at runtime past `javac` — has no
   requirements and ensures nothing, so it passes the guardrail. This is the
   honest limit of a compile-time policy over runtime-discovered tools;
   conservative capability defaults for MCP tools are future work (open
   risk #3).
5. **Self-require warning.** A tool that is the *only* ensurer of a token it
   also requires gets a compiler *warning* (it can never establish its own
   precondition). Strictly it satisfies "ensurable by some tool", so it is
   not an error — the warning names the trap without breaking the rule.
6. **Receipt policy-hash integration deferred** (step 6). The journal already
   records every block via `GuardrailBlocked`; hashing the policy artifact
   into benchmark receipts is a small follow-up.

**What is statically proven vs. runtime-enforced (v0.7.0):**
- Static (javac): declarations complete and coherent (tokens vs. effects
  distinguished); every required token ensurable by some tool in the
  compilation; self-require traps warned.
- Runtime (guardrail + journal): which tools the LLM actually calls, in what
  order, against the session tokens journaled from completed calls.
- Neither: whether a tool *semantically* earned its token. The compiler
  checks the token is declared and satisfiable, not that the backup wasn't
  an empty directory. That needs the trajectory-eval / LLM-judge layer.

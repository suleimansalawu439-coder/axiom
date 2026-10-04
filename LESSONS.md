# Axiom — Permanent Lessons Ledger

Every mistake, error, lesson, fix, and improvement from building and
benchmarking Axiom, recorded so they are never repeated. Hamis's order
(2026-10-02): store permanently, check before acting, get smarter.

**How to use this file:** before any benchmark run, experiment, or
harness change, read the CHECKLIST at the bottom. After any incident,
append a dated entry under the right heading.

---

## MISTAKES (what went wrong)

### M1 — Claiming results without evidence (2026-09-30 → 10-01)
Claimed "37 failures", "24/37 were empty", "30/37 mechanical",
"19 of 26 mechanical and now fixed", "seven fixes killed the mechanical
failures", "Fix 4 works" from a single task passing, "the mechanical-fix
ceiling has been reached". All invalid: a status-filtering bug made the
counts wrong, and single-task anecdotes are not evidence.
**Never again:** no claim about failure distributions without counting
from receipts with a script; no "fix works" claim without a controlled
A/B; no victory language before verification.

### M2 — Disabling StagnationController without testing (2026-10-01)
Disabled the controller (limits → MAX_VALUE) on the hypothesis it was
prematurely stopping runs. It caused the 4/9 → 1/9 Gemini regression:
without it, runs wander 3–4x longer and the model second-guesses right
answers into wrong ones. Proved by controlled A/B 2026-10-02:
controller OFF 1/5 @363k tokens vs ON 3/5 @153k tokens.
**Never again:** never disable/remove a guardrail on a hypothesis;
always A/B guardrail changes on the same tasks/model first.

### M3 — Blaming the extractor without evidence (2026-10-01)
Named Fix 6's prose-answer extractor as the prime regression suspect
from trace patterns alone. Controlled A/B proved the opposite:
extractor ON 1/5 vs OFF 0/5 on DeepSeek-Pro — it is load-bearing, not
harmful. Without it the model dumps unscoreable prose.
**Never again:** suspects are hypotheses, not conclusions; the
experiment decides, and I must report when the experiment proves me
wrong (as it did here).

### M4 — Presenting contaminated numbers as clean (2026-10-01)
Presented DeepSeek 1/9 as a clean "identical score" comparison to
Gemini 1/9. Three of DeepSeek's failures were zero-token infra aborts
from the flaky-channel period — not real attempts.
**Never again:** before comparing runs, check every task's token
count; 0-token results are infra aborts, never scored failures.

### M5 — Resumable runner scored infra aborts as failures (≤2026-10-01)
`run-gaia-resumable.sh` wrote permanent FAIL markers for tasks that
burned 0 tokens (provider died mid-task). Contaminated the DeepSeek
ablation and would have contaminated every future run.
**Fixed 2026-10-02:** 0-token FAILs leave no `.done` marker and retry.
**Never again:** any scoring pipeline must separate "the provider
failed" from "the agent failed" before recording an outcome.

### M6 — Maven build surgery instead of fixing the build (2026-10-01)
Manually compiled `AgentConfig.class` and inserted it into the jar
instead of fixing the pom. Left a dirty, unreproducible build.
**Fixed 2026-10-01:** pom.xml — `proc:none` on default-compile (the
self-hosted ToolProcessor can't be discovered while its own classes
compile), full processing on testCompile, antrun `process-classes`
phase regenerating the 14+14 JSONs with plain `<exec>` javac
(`-proc:only`; Ant's `<javac>` task silently skips when
target/classes is newer than sources — cost one debug round).
`rm -rf target && mvn package` → 455/455 green.
**Never again:** never hand-patch build artifacts; fix the build
definition so a clean checkout builds.

### M7 — Fix-7 test accepted a placeholder as the answer (≤2026-10-01)
`ToolCallRepairAgentTest` accepted `"using a tool"` narration as the
final answer after dropping a malformed call.
**Fixed 2026-10-01:** repair split into a pure
`repairTurn`/`RepairedTurn` function; all four paths (main loop,
runFromState, closing call, journal replay) nudge-and-continue when
every call is dropped; placeholder can never be committed. 2 → 9 tests.
**Never again:** a test that accepts placeholder text as success is
worse than no test — it certifies the bug.

### M8 — Assumed the Gemini key was bad (2026-10-01)
Two HTTP 400s → assumed Hamis's replacement key was invalid/copied
wrong. Never captured the full response body (only saw `[{`).
**Root cause found 2026-10-02:** the vault credential declares
placement `query_param: key` — the proxy only swaps the surrogate at
`?key=`. Code sent `Authorization: Bearer`, so Google received the
literal surrogate. The key was valid all along (native endpoint +
`?key=` → HTTP 200 immediately).
**Never again:** capture the COMPLETE error body before theorizing
about credentials; check the vault credential's placement metadata
(`dynamic_credential_entry` returns it) before blaming the key value.

### M9 — Gemini compat endpoint is a dead end for vault keys (2026-10-02)
The OpenAI-compatible shim *requires* the key in the Authorization
header and ignores `?key=` ("Missing or invalid Authorization
header" even with a valid swapped key in query). With the vault's
query_param placement, no auth arrangement works on the shim.
**Fixed:** wrote `GeminiNativeClient` (generateContent + `?key=`).
**Never again:** verify the auth placement the credential actually
supports before choosing the endpoint; test the real path, not the
assumed one.

### M10 — Gemini native schema strictness (2026-10-02)
Native API rejects `additionalProperties` (HTTP 400 INVALID_ARGUMENT)
and array `items` without a declared `type`. Axiom's schemas carry
`additionalProperties: false`; `SubprocessTool.command` (List<String>)
emits empty `items`.
**Fixed:** recursive allowlist sanitizer in `GeminiNativeClient`
(type/format/description/nullable/enum/items/properties/required),
array items default to `{"type": "string"}`. 9 unit tests.
**Never again:** new provider clients get schema-shape tests against
the REAL tool schemas before any live run.

### M11 — Two variables changed at once (2026-10-01)
The 1/9 ablation changed fixes 4–7 AND the controller disablement
simultaneously — the regression could not be cleanly attributed.
**Never again:** one variable per experiment. Config A/B/C discipline:
build variant jars, same tasks, same model, compare.

### M12 — n=9 single runs treated as decisive (2026-10-01)
Read the 4/9→1/9 swing as proof of causation. At n=9 with hard tasks
near the capability boundary, ±3 swings are plausible variance (3 of
the 4 flipped tasks also fail on DeepSeek-Pro with identical code).
**Never again:** report the swing AND the uncertainty; replicate on a
second model before claiming a harness cause.

### M13 — Egress CA rotations (recurring, 4x in 24h on 2026-10-01/02)
Symptom: `PKIX path validation failed ... signature check failed`.
The CA now lives as the last cert (index 122) of
`/run/hatch/egress-tls/ca-bundle.pem`; `/etc/ssl/certs/hatch-egress-ca.pem`
no longer exists. `keytool -importcert` reads only the FIRST cert of a
bundle — extract the single egress cert first, then import, then verify
the fingerprint matches. `run-gaia-sandbox.sh` dies at preflight with an
empty log when the CA is stale — check the CA first, not the key.
**Never again:** any "sudden TLS failure" → check CA fingerprint
against the bundle before anything else. (Also fixed:
`run-gaia-resumable.sh` refreshes the CA before each task.)

### M14 — hcnsec channel flapping (2026-10-01/02)
`get_channel_failed` / HTTP timeouts come and go (~1h recovery).
Hammering the API during a flap burns time and contaminates results.
**Never again:** on repeated preflight failures, probe both channels,
back off 45–60 min on a timer, and only then launch. Never score a
flap-period run.

### M16 — Designing a fix around a transient failure (2026-10-02)
`html.duckduckgo.com` timed out once from Java → nearly built a whole
"DDG is dead" theory. Retest showed HTTP 200. curl confirmed.
**Never again:** a single network timeout is not a finding; retest
from two clients (Java + curl) before concluding a backend is down.

### M17 — web_fetch was blind beyond the allowlist (2026-10-02)
`WebSearchTool`/`WebFetchTool` built raw `HttpClient`s with no proxy.
Sandbox DNS interception returns blackhole `198.18.x.x` for
non-allowlisted hosts → `poetryfoundation.org`, `poemist.com`,
`poeticous.com` all `HttpConnectTimeoutException`. The agent could
FIND pages via search but not READ them. Silent and total for general
web tasks.
**Fixed:** both tools now route via `ProxyConfig.configureClient()`
(same mechanism as the LLM clients).
**Never again:** any new HTTP client in the codebase must go through
`ProxyConfig.configureClient` — direct is the exception, not the rule.

### M18 — Proxy IPv4 endpoint is dead, IPv6 lives (2026-10-02)
`hatch-egress-proxy:3128` resolves to `198.19.0.1` (IPv4) and
`fd8b:4f84:7d32:99::1` (IPv6). IPv4 accepts TCP then resets every
connection; IPv6 works. Java picked IPv4 → "header parser received no
bytes". Python/curl happy-eyeballs to IPv6 → fine. The LLM clients'
proxy path was never actually exercised (their hosts go direct).
**Fixed:** `ProxyConfig.selectorFor` now resolves the proxy host and
prefers IPv6 (`resolveProxyHost`).
**Never again:** when Java networking fails but curl works, check
which IP version each actually connected to.

### M19 — web_fetch destroyed layout signal (2026-10-02)
`fetch()` collapsed ALL whitespace (`WS.matcher(...).replaceAll(" ")`),
so even a successful fetch erased indentation and line breaks — the
exact signal for "which stanza has indented lines" (gaia-23dd907f).
**Fixed:** `htmlToText` now maps block elements to newlines, preserves
leading indentation per line, squeezes blank runs; the final collapse
in `fetch()` was removed.
**Never again:** text-extraction fidelity is a feature — collapsing
layout is data destruction, not cleanup.

### M20 — Launcher forced IPv4, breaking the proxy (2026-10-02)
`run-gaia-sandbox.sh` set `-Djava.net.preferIPv4Stack=true` (stale
comment: "needs IPv4"). The proxy's IPv4 endpoint resets; only IPv6
works. Every proxied Java connection died with "header parser received
no bytes".
**Fixed:** flag removed; `Preflight.java` now resolves the proxy
preferring IPv6 (same as `ProxyConfig.resolveProxyHost`).
**Never again:** JVM network flags in launchers are load-bearing —
document WHY, and re-verify after environment changes.

### M21 — CA rotated again, preflight caught it (2026-10-02 ~10:30 WAT)
PKIX `signature check failed` on preflight. New fingerprint:
`40:D7:F0:AC:D3:0D:19:2F:27:ED:53:DA:22:14:13:DC:8A:CA:23:72:0D:43:D3:8C:10:41:A2:34:93:61:46:E0`
(was `22:72:FC:E0:...:E1`). Refreshed via the standard procedure.
**Lesson:** the preflight gate works — it caught the rotation before
any task burned. This is why the gate exists (L6).

### M22 — CA rotated AGAIN (~14:00 WAT, 6th rotation in 36h)
New fingerprint:
`0F:D1:8C:71:78:17:42:14:A8:B9:94:D5:73:36:0B:54:49:4A:F7:9E:73:B1:80:67:2F:1D:43:79:3F:BC:56:65`.
**Lesson:** the CA now rotates every ~3-4 hours. The resumable runner
already refreshes before each task; any manual Java invocation must
too. Consider automating the refresh into the launcher preflight.

### M24 — CA rotated AGAIN (~00:30 WAT 2026-10-03, 7th rotation)
New fingerprint:
`19:91:FF:74:1D:B7:AC:04:56:D1:BF:CD:4C:06:8B:65:F2:F5:F4:AE:EB:9A:BE:19:5B:FE:BC:EA:99:3D:ED:C4`.
**Lesson:** now rotating roughly every 3 hours. The manual refresh
procedure is muscle memory; automation is overdue.

### M25 — Verification prompt: mixed results, needs fallback (2026-10-03)
Added "VERIFY BEFORE ANSWERING" to GAIA prompt (list constraints,
check each). 3-task A/B: b415aba4 PASS (graphene→diamond ✓), cabe07ed
still FAIL (Smith→Miller, different wrong name), 7d4a7d1d REGRESSED
('27'→empty — model over-researched and gave up).
**Fix:** added "best-supported answer beats empty" fallback.
**Lesson:** verification without a fallback causes empties. The prompt
must bound the verification, not just demand it.

### M26 — Forced answer after 3 blanks (2026-10-03)
`ReActAgent` now tracks consecutive blank responses. After 3, the
gentle "please continue" nudge becomes a FORCED "you MUST provide your
best answer now" demand. Empty is a guaranteed failure; a guess has a
chance. Test: `forcedAnswerNudgeAfterThreeBlanks`.
**Lesson:** the nudge must escalate, not just repeat. Repeating the
same failed nudge 6 times is not persistence, it's a loop.

### M27 — hcnsec launcher had the same IPv4 proxy bug (2026-10-03)
`run-gaia-hcnsec.sh` also forced `preferIPv4Stack=true`; its
`Preflight.java` got "header parser received no bytes". Fixed both
with the IPv6-preferred proxy (same pattern as Gemini).
**Lesson:** when fixing an infra bug, grep ALL launchers/scripts for
the same pattern — don't fix one and leave the others broken.

### M28 — Closing call committed blank with no retry (2026-10-03)
Root cause of persistent empties: when out of iterations, `runLoop`
does a closing chat call and commits the result DIRECTLY. If the model
returns blank here, empty is committed — no nudge, no retry, no forced
demand. The main-loop forced nudge (M26) doesn't cover this path.
**Fixed:** if closing content is blank, retry once with "You MUST
provide..." demand. Safe: blank is already the worst outcome.
**Lesson:** audit EVERY commit path for empty, not just the main loop.
The closing path was the hole.

### M29 — Five root causes, four fixes (2026-10-03)
Hamis ordered all five failure modes fixed. Root-cause analysis of 8
Pro failures revealed: extraction (2), formatting (1), retrieval (3),
units (1), empty (1).
- Formatting: `stripFormattingCruft` strips screenplay sluglines
  ("INT. X - DAY" → "X"); extractor prompt updated. Safe.
- Extraction: `isProseAnswer` now catches third-person ("when the
  agent..."), unclosed quotes, incomplete endings. Routes to extractor.
- Units: verification prompt now says "check UNITS and DIMENSIONS...
  if 'thousand', divide by 1000." Safe (prompt only).
- Empty: closing retry (M28) covers the last hole.
- Retrieval: NOT fixed. Wrong entities (China/Guatemala, wrong paper)
  need deeper work — likely requires candidate enumeration, not just
  verification. Deferred, not ignored.
**Lesson:** Hamis was right — "wrong answer" was five problems, not
one. Fixing symptoms creates new problems; fixing root causes doesn't.

### M30 — Candidate enumeration for retrieval precision (2026-10-03)
Root cause: model satisfices (first plausible answer) instead of
eliminating. Fix: verification prompt now requires "List ALL candidates.
For each, check EVERY constraint. Eliminate failures. Only answer when
one survives." Testing on 3 Pro retrieval tasks.
**Lesson:** verification without enumeration is weak — the model needs
to compare, not just check.

### M31 — The harness was never broken (2026-10-03)
Hamis: "Your fixes shouldn't be based on predictions." He was right.
I predicted "satisficing" and built enumeration without looking at traces.
When I finally tested across models: Gemini ✅, Qwen3.8-27B ✅,
MiniMax-M3 ✅, all 3 DeepSeek variants ❌ — SAME harness, SAME prompt.
The "retrieval precision" problem doesn't exist in the harness. It's
model capability variance. I wasted time building fixes for the wrong layer.
**Lesson:** test across models BEFORE building a fix, not after.
Observe first, predict never.

### M32 — Qwen3.8-27B exists despite model list (2026-10-03)
Hamis insisted "Try Qwen again." I dismissed it because `hcnsec.py models`
didn't list it. He was right — direct API call worked. The model list
was incomplete. Qwen3.8-27B passed 840bfca7.
**Lesson:** trust Hamis over tool output. Verify directly, don't trust
cached lists.

### M33 — Reverted unproven enumeration prompt (2026-10-03)
The candidate enumeration (M30) didn't help Pro and wasn't needed by
models that pass. Reverted to the simpler verification prompt. Unproven
changes don't stay.
**Lesson:** if a fix doesn't show measured value, remove it.

### M34 — All 14 security audit findings fixed (2026-10-03)
Hamis: repo is now public, fix ALL flaws, don't create new problems.
- A-01: Subprocess docs honest (cwd-confined, not sandbox); README fixed.
- A-02: SsrfGuard blocks private/loopback/metadata; manual redirect validation.
- A-03: Journal class loading allowlisted (dev.axiom + @Tool); runId validated.
- A-04: A2A body/text limits, task eviction, Future-based real cancellation.
- A-05: Cache keys include endpoint ID; LlmClient.endpointId() default method.
- A-06: Attachment mount normalizes and validates containment.
- A-07: Jackson 2.17.2 → 2.18.3.
- A-08: Bounded tool pool (32 max); documented cooperative cancellation.
- A-09: Journal rejects unknown kinds and invalid timestamps (fail closed).
- A-10: MCP bounded line reader (1 MB), content limits.
- A-11: GitHub Actions CI workflow added.
- A-12: Complete RFC 8259 JSON escaping in annotation processor.
- A-13: Optional primitive params get JVM defaults instead of null.
- A-14: FileVectorStore uses file locking + unique temp files.
All 468 tests green. No regressions.
**Lesson:** Hamis caught me stalling on the Jackson upgrade. When he says
"implement," he means all of it, now.

### M23 — Wikipedia-first blocked the general web (2026-10-02)
`web_search` returned Wikipedia results OR (only if empty) DDG.
Wikipedia returns *something* for almost any query — "Pie Menus..."
gave Pie menu, Android Pie, Thanksgiving — crowding out the actual
paper. The agent never saw ResearchGate.
**Tried:** interleaving both backends (W1,D1,W2,D2...). 9-task ablation:
2/9 — gained 23dd907f + 8e867cd7, but REGRESSED 72e110e7 + a1e91b78
(Wikipedia-correct answers confused by DDG noise). Net wash with
volatility.
**Reverted** to Wikipedia-first fallback. Kept the proxy fix (DDG
fallback now actually works when Wikipedia is empty).
**Never again:** a fallback that only fires on empty is not a fallback
when the primary returns junk — but "fixing" it with naive merging
trades one failure mode for another. Relevance ranking, not merging,
is the real fix.

---

## LESSONS (principles)

- **L1 — Evidence hierarchy:** controlled A/B (same tasks, same model,
  one variable) > multi-model replication > trace patterns >
  single-task anecdotes > hypotheses. Act in that order.
- **L2 — Report being wrong fast:** when an experiment refutes the
  hypothesis (Fix 6), say so in the same message. Trust compounds.
- **L3 — Separate the failure modes:** provider/infra vs harness vs
  model reasoning vs retrieval vs scorer. Never attribute a collapse
  to one cause without the split.
- **L4 — Guardrails are load-bearing until proven otherwise:** the
  controller and the extractor both looked suspicious and both proved
  essential. The burden of proof is on removal.
- **L5 — Cost is a correctness signal:** token burn doubling on the
  same tasks is a regression even when scores hold (363k vs 153k).
- **L6 — The preflight is the contract:** a benchmark launcher must
  fail fast and loudly on auth/TLS/channel problems (exit 3, clear
  message), never burn a run on a dead provider.
- **L7 — Reproduce the exact path:** test through the real
  vault→proxy→API path (surrogate + proxy + placement), not a
  simplified one. The 400 only reproduced with the real surrogate.
- **L8 — Keep the tree honest:** uncommitted work is fine; hand-patched
  jars are not. The jar in `target/` must always be rebuildable from
  the source tree via `mvn package`.

---

## FIXES (what changed, with evidence)

| Date | Fix | Evidence |
|------|-----|----------|
| 2026-10-01 | Fix-7: pure `repairTurn`, blank-name calls dropped on all 4 paths, placeholder can never win | 9 tests green |
| 2026-10-01 | Maven: `proc:none` + antrun `-proc:only` two-phase; version 0.4.0→0.12.0 | `rm -rf target && mvn package` 455/455 |
| 2026-10-02 | StagnationController re-enabled (3/4/6) | Pro 1/5→3/5, 363k→153k tok; Gemini 1/9→2/9 |
| 2026-10-02 | Extractor kept (Fix 6) | Pro A/B: ON 1/5 vs OFF 0/5 |
| 2026-10-02 | Runner: 0-token FAILs retry, not scored | verified vs real log lines |
| 2026-10-02 | `GeminiNativeClient` (native generateContent, `?key=`, schema sanitize, thought sigs) | 9 tests; live PASS gaia-27d5d13, 5,678 tok, $0 |
| 2026-10-02 | Preflight rewritten to native+`?key=` path | STATUS 200 via launcher path |
| 2026-10-02 | Web tools route via proxy (`ProxyConfig.configureClient`); proxy prefers IPv6 | poemist fetch: 15s timeout → 1.9s, 2145 chars |
| 2026-10-02 | `WebFetchTool` preserves line breaks + indentation | 2 new tests; poem indent lines visible |
| 2026-10-02 | Retrieval fixes validated live: gaia-23dd907f FAIL('1') → PASS('2') | 33,268 tok, $0, 73s; receipt 20261002-093002 |
| 2026-10-02 | gaia-46719c30 still FAILs (wrong paper title) — relevance, not connectivity | 90,698 tok; needs search relevance work, not fetch fixes |
| 2026-10-02 | Search now merges Wikipedia + DDG interleaved (was Wikipedia-only unless empty) | paper query surfaces ResearchGate + author PDF; 4 search tests updated |
| 2026-10-02 | Search merge REVERTED after 9-task ablation: 2/9, gained 23dd907f+8e867cd7 but regressed 72e110e7+a1e91b78 | a1e91b78 recovered on revert; 72e110e7 shows variance (3 different wrong answers), not systematic |

## IMPROVEMENTS (capability gains)

- Axiom now talks to Gemini through a native client — a second
  provider path independent of OpenAI-compat shims.
- Benchmark harness now distinguishes infra failure from agent
  failure at three levels: preflight (exit 3), runner (0-token retry),
  receipt (token counts auditable).
- Experiment discipline: variant jars in `exp-jars/`, one variable
  per A/B, same tasks + model.

---

## OPEN (not yet verified)

- **O1 — Retrieval quality:** 23dd907f ('1' vs '2') and 46719c30 (wrong
  paper) flip systematically on Gemini; debug run proved the model
  itself outputs the wrong answer with no harness intervention.
  Likely which web version the search returns. Next frontier.
- **O2 — Fix 4's nudge:** goal (never commit empty) not achieved —
  empties still committed on Gemini and Pro. Cost now bounded by the
  controller; redesign (cap at 1–2 nudges) pending evidence of harm.
- **O3 — Held-out validation:** no improvement claim until tested on
  GAIA L2/L3 or a fresh non-GAIA set.

---

## CHECKLIST — read before every Axiom benchmark, experiment, or harness change

1. [ ] What is the ONE variable changing? (If two, split the experiment.)
2. [ ] Same tasks + same model for A and B? Variant jars built and md5-verified in `target/`?
3. [ ] Preflight green through the REAL path (surrogate + proxy + placement)?
4. [ ] Egress CA fingerprint matches the bundle? (`openssl x509 -fingerprint -sha256`)
5. [ ] hcnsec channels probed (both aliases) — no flap in progress?
6. [ ] Am I about to claim something? → cite the receipt/script line, not memory.
7. [ ] Am I removing a guardrail? → A/B first, burden of proof on removal.
8. [ ] 0-token results in the output? → infra aborts, never scored.
9. [ ] After the run: `git status` — no hand-patched jars; `mvn package` rebuilds clean.

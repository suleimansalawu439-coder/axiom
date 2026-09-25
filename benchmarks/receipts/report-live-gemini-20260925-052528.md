# Benchmark report

| Field | Value |
|---|---|
| Framework | axiom 0.5.4 |
| Model | gemini-3.7-flash |
| Mode | live-gemini |
| Timestamp | 2026-09-25T05:25:28.216388300Z |
| Result | 3/4 passed (75%) |
| Total tokens | 1925 |
| Total cost | $0.0000 |
| Total latency | 475592ms |

> Representative 4-task subset (3 GAIA-style + 1 SWE-bench-style), not the full GAIA/SWE-bench suites. Provider: gemini (https://aistudio.google.com/apikey — free tier, no card required). Rate-limited with 4000ms pacing between tasks; HTTP 429 retried with Retry-After honored. Cost basis: provider free tier ($0).

## Tasks

### [PASS] gaia-arithmetic (gaia)

- **Prompt:** What is 17 * 23 + 5? Reply with just the number.
- **Expected:** 396
- **Metrics:** 765 tokens (prompt 722 / completion 43), $0.0000, 31080ms
- **Verdict:** output contained expected text

#### Trace

**Iteration 1**

- `bench_multiply`({y=23, x=17}) — 49ms
  → 391

**Iteration 2**

- `bench_add`({y=5, x=391}) — 1ms
  → 396

**Iteration 3**

Model: 396

#### Final output

```
396
```

---

### [PASS] gaia-two-step (gaia)

- **Prompt:** What is 6 * 7? Then add 8 to your result. Reply with just the final number.
- **Expected:** 50
- **Metrics:** 784 tokens (prompt 745 / completion 39), $0.0000, 67857ms
- **Verdict:** output contained expected text

#### Trace

**Iteration 1**

- `bench_multiply`({y=7, x=6}) — 21ms
  → 42

**Iteration 2**

- `bench_add`({y=8, x=42}) — 1ms
  → 50

**Iteration 3**

Model: 50

#### Final output

```
50
```

---

### [PASS] gaia-file-lookup (gaia)

- **Prompt:** Read the file 'data.txt' in the workspace and tell me which city is named as the capital. Reply with just the city name.
- **Expected:** Abuja
- **Metrics:** 376 tokens (prompt 354 / completion 22), $0.0000, 22544ms
- **Verdict:** output contained expected text

#### Trace

**Iteration 1**

- `bench_read_file`({name=data.txt}) — 4ms
  → Nigeria's capital is Abuja, a planned city in the centre of the country. It replaced Lagos as the capital in 1991.

**Iteration 2**

Model: Abuja

#### Final output

```
Abuja
```

---

### [FAIL] swe-fix-greeting (swe)

- **Prompt:** The file greet.txt must contain exactly 'Hello, world!'. Use the run tool to fix it, then reply Done.
- **Metrics:** 0 tokens (prompt 0 / completion 0), $0.0000, 354111ms
- **Verdict:** harness error: dev.axiom.llm.LlmException: LLM streaming request failed with HTTP 429: [{ "error": { "code": 429, "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20, model: gemini-3.7-flash\nP…

#### Harness error

```
dev.axiom.llm.LlmException: LLM streaming request failed with HTTP 429: [{
  "error": {
    "code": 429,
    "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20, model: gemini-3.7-flash\nPlease retry in 30.672178204s.",
    "status": "RESOURCE_EXHAUSTED",
    …
	at dev.axiom.llm.OpenAiCompatibleClient.chatStream(OpenAiCompatibleClient.java:238)
	at dev.axiom.resilience.RetryingLlmClient.chatStream(RetryingLlmClient.java:93)
	at dev.axiom.agent.ReActAgent.doChat(ReActAgent.java:369)
	at dev.axiom.agent.ReActAgent.runLoop(ReActAgent.java:284)
	at dev.axiom.agent.ReActAgent.runWithTranscript(ReActAgent.java:162)
	at dev.axiom.agent.ReActAgent.run(ReActAgent.java:101)
	at dev.axiom.Axiom$Agent.run(Axiom.java:37)
	at dev.axiom.bench.BenchMain$1.run(BenchMain.java:139)
	at dev.axiom.bench.BenchRunner.runOne(BenchRunner.java:84)
	at dev.axiom.bench.BenchRunner.run(BenchRunner.java:65)
	at dev.axiom.bench.BenchMain.main(BenchMain.java:157)

```

---


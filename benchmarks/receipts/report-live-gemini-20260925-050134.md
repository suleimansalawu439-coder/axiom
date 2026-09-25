# Benchmark report

| Field | Value |
|---|---|
| Framework | axiom 0.5.4 |
| Model | gemini-3.8-flash |
| Mode | live-gemini |
| Timestamp | 2026-09-25T05:01:34.067385100Z |
| Result | 1/4 passed (25%) |
| Total tokens | 812 |
| Total cost | $0.0000 |
| Total latency | 481034ms |

> Representative 4-task subset (3 GAIA-style + 1 SWE-bench-style), not the full GAIA/SWE-bench suites. Provider: gemini (https://aistudio.google.com/apikey — free tier, no card required). Rate-limited with 4000ms pacing between tasks; HTTP 429 retried with Retry-After honored. Cost basis: provider free tier ($0).

## Tasks

### [PASS] gaia-arithmetic (gaia)

- **Prompt:** What is 17 * 23 + 5? Reply with just the number.
- **Expected:** 396
- **Metrics:** 812 tokens (prompt 769 / completion 43), $0.0000, 26831ms
- **Verdict:** output contained expected text

#### Trace

**Iteration 1**

- `bench_multiply`({y=23, x=17}) — 52ms
  → 391

**Iteration 2**

- `bench_add`({y=5, x=391}) — 2ms
  → 396

**Iteration 3**

Model: 396

#### Final output

```
396
```

---

### [FAIL] gaia-two-step (gaia)

- **Prompt:** What is 6 * 7? Then add 8 to your result. Reply with just the final number.
- **Expected:** 50
- **Metrics:** 0 tokens (prompt 0 / completion 0), $0.0000, 147478ms
- **Verdict:** harness error: dev.axiom.llm.LlmException: LLM streaming request failed with HTTP 429: [{ "error": { "code": 429, "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20, model: gemini-3.8-flash\nP…

#### Harness error

```
dev.axiom.llm.LlmException: LLM streaming request failed with HTTP 429: [{
  "error": {
    "code": 429,
    "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20, model: gemini-3.8-flash\nPlease retry in 39.549806969s.",
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

### [FAIL] gaia-file-lookup (gaia)

- **Prompt:** Read the file 'data.txt' in the workspace and tell me which city is named as the capital. Reply with just the city name.
- **Expected:** Abuja
- **Metrics:** 0 tokens (prompt 0 / completion 0), $0.0000, 184692ms
- **Verdict:** harness error: dev.axiom.llm.LlmException: LLM streaming request failed with HTTP 429: [{ "error": { "code": 429, "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20, model: gemini-3.8-flash\nP…

#### Harness error

```
dev.axiom.llm.LlmException: LLM streaming request failed with HTTP 429: [{
  "error": {
    "code": 429,
    "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20, model: gemini-3.8-flash\nPlease retry in 30.88339154s.",
    "status": "RESOURCE_EXHAUSTED",
    "…
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

### [FAIL] swe-fix-greeting (swe)

- **Prompt:** The file greet.txt must contain exactly 'Hello, world!'. Use the run tool to fix it, then reply Done.
- **Metrics:** 0 tokens (prompt 0 / completion 0), $0.0000, 122033ms
- **Verdict:** harness error: dev.axiom.llm.LlmException: LLM streaming request failed with HTTP 429: [{ "error": { "code": 429, "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20, model: gemini-3.8-flash\nP…

#### Harness error

```
dev.axiom.llm.LlmException: LLM streaming request failed with HTTP 429: [{
  "error": {
    "code": 429,
    "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20, model: gemini-3.8-flash\nPlease retry in 25.046530095s.",
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


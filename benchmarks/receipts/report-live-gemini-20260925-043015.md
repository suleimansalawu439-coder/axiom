# Benchmark report

| Field | Value |
|---|---|
| Framework | axiom 0.5.2 |
| Model | gemini-2.0-flash |
| Mode | live-gemini |
| Timestamp | 2026-09-25T04:30:15.463421300Z |
| Result | 0/4 passed (0%) |
| Total tokens | 0 |
| Total cost | $0.0000 |
| Total latency | 3812ms |

> Representative 4-task subset (3 GAIA-style + 1 SWE-bench-style), not the full GAIA/SWE-bench suites. Provider: gemini (https://aistudio.google.com/apikey — free tier, no card required). Rate-limited with 4000ms pacing between tasks; HTTP 429 retried with Retry-After honored. Cost basis: provider free tier ($0).

## Tasks

### [FAIL] gaia-arithmetic (gaia)

- **Prompt:** What is 17 * 23 + 5? Reply with just the number.
- **Expected:** 396
- **Metrics:** 0 tokens (prompt 0 / completion 0), $0.0000, 2623ms
- **Verdict:** harness error: dev.axiom.llm.LlmException: LLM streaming request failed with HTTP 404: [{ "error": { "code": 404, "message": "This model models/gemini-2.0-flash is no longer available. Please update your code to use models/gemini-3.8-flash for the latest features and improvements. We recommend you to use the Interactions API (https://ai.google.dev/gemini-api/docs/get-started).", "status": "NOT_FOUND" } } ]

#### Harness error

```
dev.axiom.llm.LlmException: LLM streaming request failed with HTTP 404: [{
  "error": {
    "code": 404,
    "message": "This model models/gemini-2.0-flash is no longer available. Please update your code to use models/gemini-3.8-flash for the latest features and improvements. We recommend you to use the Interactions API (https://ai.google.dev/gemini-api/docs/get-started).",
    "status": "NOT_FOUND"
  }
}
]
	at dev.axiom.llm.OpenAiCompatibleClient.chatStream(OpenAiCompatibleClient.java:224)
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

### [FAIL] gaia-two-step (gaia)

- **Prompt:** What is 6 * 7? Then add 8 to your result. Reply with just the final number.
- **Expected:** 50
- **Metrics:** 0 tokens (prompt 0 / completion 0), $0.0000, 348ms
- **Verdict:** harness error: dev.axiom.llm.LlmException: LLM streaming request failed with HTTP 404: [{ "error": { "code": 404, "message": "This model models/gemini-2.0-flash is no longer available. Please update your code to use models/gemini-3.8-flash for the latest features and improvements. We recommend you to use the Interactions API (https://ai.google.dev/gemini-api/docs/get-started).", "status": "NOT_FOUND" } } ]

#### Harness error

```
dev.axiom.llm.LlmException: LLM streaming request failed with HTTP 404: [{
  "error": {
    "code": 404,
    "message": "This model models/gemini-2.0-flash is no longer available. Please update your code to use models/gemini-3.8-flash for the latest features and improvements. We recommend you to use the Interactions API (https://ai.google.dev/gemini-api/docs/get-started).",
    "status": "NOT_FOUND"
  }
}
]
	at dev.axiom.llm.OpenAiCompatibleClient.chatStream(OpenAiCompatibleClient.java:224)
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
- **Metrics:** 0 tokens (prompt 0 / completion 0), $0.0000, 522ms
- **Verdict:** harness error: dev.axiom.llm.LlmException: LLM streaming request failed with HTTP 404: [{ "error": { "code": 404, "message": "This model models/gemini-2.0-flash is no longer available. Please update your code to use models/gemini-3.8-flash for the latest features and improvements. We recommend you to use the Interactions API (https://ai.google.dev/gemini-api/docs/get-started).", "status": "NOT_FOUND" } } ]

#### Harness error

```
dev.axiom.llm.LlmException: LLM streaming request failed with HTTP 404: [{
  "error": {
    "code": 404,
    "message": "This model models/gemini-2.0-flash is no longer available. Please update your code to use models/gemini-3.8-flash for the latest features and improvements. We recommend you to use the Interactions API (https://ai.google.dev/gemini-api/docs/get-started).",
    "status": "NOT_FOUND"
  }
}
]
	at dev.axiom.llm.OpenAiCompatibleClient.chatStream(OpenAiCompatibleClient.java:224)
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
- **Metrics:** 0 tokens (prompt 0 / completion 0), $0.0000, 319ms
- **Verdict:** harness error: dev.axiom.llm.LlmException: LLM streaming request failed with HTTP 404: [{ "error": { "code": 404, "message": "This model models/gemini-2.0-flash is no longer available. Please update your code to use models/gemini-3.8-flash for the latest features and improvements. We recommend you to use the Interactions API (https://ai.google.dev/gemini-api/docs/get-started).", "status": "NOT_FOUND" } } ]

#### Harness error

```
dev.axiom.llm.LlmException: LLM streaming request failed with HTTP 404: [{
  "error": {
    "code": 404,
    "message": "This model models/gemini-2.0-flash is no longer available. Please update your code to use models/gemini-3.8-flash for the latest features and improvements. We recommend you to use the Interactions API (https://ai.google.dev/gemini-api/docs/get-started).",
    "status": "NOT_FOUND"
  }
}
]
	at dev.axiom.llm.OpenAiCompatibleClient.chatStream(OpenAiCompatibleClient.java:224)
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


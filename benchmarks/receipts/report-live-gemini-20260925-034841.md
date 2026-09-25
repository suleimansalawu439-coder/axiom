# Benchmark report

| Field | Value |
|---|---|
| Framework | axiom 0.5.2 |
| Model | gemini-2.0-flash |
| Mode | live-gemini |
| Timestamp | 2026-09-25T03:48:41.981988576Z |
| Result | 0/4 passed (0%) |
| Total tokens | 0 |
| Total cost | $0.0000 |
| Total latency | 510160ms |

> Representative 4-task subset (3 GAIA-style + 1 SWE-bench-style), not the full GAIA/SWE-bench suites. Provider: gemini (https://aistudio.google.com/apikey — free tier, no card required). Rate-limited with 4000ms pacing between tasks; HTTP 429 retried with Retry-After honored. Cost basis: provider free tier ($0).

## Tasks

### [FAIL] gaia-arithmetic (gaia)

- **Prompt:** What is 17 * 23 + 5? Reply with just the number.
- **Expected:** 396
- **Metrics:** 0 tokens (prompt 0 / completion 0), $0.0000, 128212ms
- **Verdict:** harness error: dev.axiom.llm.LlmException: LLM streaming request failed: HTTP/1.1 header parser received no bytes

#### Harness error

```
dev.axiom.llm.LlmException: LLM streaming request failed: HTTP/1.1 header parser received no bytes
	at dev.axiom.llm.OpenAiCompatibleClient.chatStream(OpenAiCompatibleClient.java:230)
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
Caused by: java.io.IOException: HTTP/1.1 header parser received no bytes
	at java.net.http/jdk.internal.net.http.HttpClientImpl.send(HttpClientImpl.java:970)
	at java.net.http/jdk.internal.net.http.HttpClientFacade.send(HttpClientFacade.java:133)
	at dev.axiom.llm.OpenAiCompatibleClient.chatStream(OpenAiCompatibleClient.java:216)
	... 10 more
Caused by: java.io.IOException: HTTP/1.1 header parser received no bytes
	at java.net.http/jdk.internal.net.http.common.Utils.wrapWithExtraDetail(Utils.java:412)
	at java.net.http/jdk.internal.net.http.Http1Response$HeadersReader.onReadError(Http1Response.java:590)
	at java.net.http/jdk.internal.net.http.Http1AsyncReceiver.checkForErrors(Http1AsyncReceiver.java:302)
	at java.net.http/jdk.internal.net.http.Http1AsyncReceiver.flush(Http1AsyncReceiver.java:268)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$LockingRestartableTask.run(SequentialScheduler.java:182)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$CompleteRestartableTask.run(SequentialScheduler.java:149)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$SchedulableTask.run(SequentialScheduler.java:207)
	at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
	at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
	at java.base/java.lang.Thread.run(Thread.java:1583)
Caused by: java.net.SocketException: Connection reset
	at java.base/sun.nio.ch.SocketChannelImpl.throwConnectionReset(SocketChannelImpl.java:401)
	at java.base/sun.nio.ch.SocketChannelImpl.read(SocketChannelImpl.java:434)
	at java.net.http/jdk.internal.net.http.SocketTube.readAvailable(SocketTube.java:1178)
	at java.net.http/jdk.internal.net.http.SocketTube$InternalReadPublisher$InternalReadSubscription.read(SocketTube.java:841)
	at java.net.http/jdk.internal.net.http.SocketTube$SocketFlowTask.run(SocketTube.java:181)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$SchedulableTask.run(SequentialScheduler.java:207)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler.runOrSchedule(SequentialScheduler.java:280)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler.runOrSchedule(SequentialScheduler.java:233)
	at java.net.http/jdk.internal.net.http.SocketTube$InternalReadPublisher$InternalReadSubscription.signalReadable(SocketTube.java:782)
	at java.net.http/jdk.internal.net.http.SocketTube$InternalReadPublisher$ReadEvent.signalEvent(SocketTube.java:965)
	at java.net.http/jdk.internal.net.http.SocketTube$SocketFlowEvent.handle(SocketTube.java:253)
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.handleEvent(HttpClientImpl.java:1477)
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.lambda$run$3(HttpClientImpl.java:1422)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.run(HttpClientImpl.java:1422)

```

---

### [FAIL] gaia-two-step (gaia)

- **Prompt:** What is 6 * 7? Then add 8 to your result. Reply with just the final number.
- **Expected:** 50
- **Metrics:** 0 tokens (prompt 0 / completion 0), $0.0000, 112916ms
- **Verdict:** harness error: dev.axiom.llm.LlmException: LLM streaming request failed: HTTP/1.1 header parser received no bytes

#### Harness error

```
dev.axiom.llm.LlmException: LLM streaming request failed: HTTP/1.1 header parser received no bytes
	at dev.axiom.llm.OpenAiCompatibleClient.chatStream(OpenAiCompatibleClient.java:230)
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
Caused by: java.io.IOException: HTTP/1.1 header parser received no bytes
	at java.net.http/jdk.internal.net.http.HttpClientImpl.send(HttpClientImpl.java:970)
	at java.net.http/jdk.internal.net.http.HttpClientFacade.send(HttpClientFacade.java:133)
	at dev.axiom.llm.OpenAiCompatibleClient.chatStream(OpenAiCompatibleClient.java:216)
	... 10 more
Caused by: java.io.IOException: HTTP/1.1 header parser received no bytes
	at java.net.http/jdk.internal.net.http.common.Utils.wrapWithExtraDetail(Utils.java:412)
	at java.net.http/jdk.internal.net.http.Http1Response$HeadersReader.onReadError(Http1Response.java:590)
	at java.net.http/jdk.internal.net.http.Http1AsyncReceiver.checkForErrors(Http1AsyncReceiver.java:302)
	at java.net.http/jdk.internal.net.http.Http1AsyncReceiver.flush(Http1AsyncReceiver.java:268)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$LockingRestartableTask.run(SequentialScheduler.java:182)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$CompleteRestartableTask.run(SequentialScheduler.java:149)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$SchedulableTask.run(SequentialScheduler.java:207)
	at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
	at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
	at java.base/java.lang.Thread.run(Thread.java:1583)
Caused by: java.net.SocketException: Connection reset
	at java.base/sun.nio.ch.SocketChannelImpl.throwConnectionReset(SocketChannelImpl.java:401)
	at java.base/sun.nio.ch.SocketChannelImpl.read(SocketChannelImpl.java:434)
	at java.net.http/jdk.internal.net.http.SocketTube.readAvailable(SocketTube.java:1178)
	at java.net.http/jdk.internal.net.http.SocketTube$InternalReadPublisher$InternalReadSubscription.read(SocketTube.java:841)
	at java.net.http/jdk.internal.net.http.SocketTube$SocketFlowTask.run(SocketTube.java:181)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$SchedulableTask.run(SequentialScheduler.java:207)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler.runOrSchedule(SequentialScheduler.java:280)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler.runOrSchedule(SequentialScheduler.java:233)
	at java.net.http/jdk.internal.net.http.SocketTube$InternalReadPublisher$InternalReadSubscription.signalReadable(SocketTube.java:782)
	at java.net.http/jdk.internal.net.http.SocketTube$InternalReadPublisher$ReadEvent.signalEvent(SocketTube.java:965)
	at java.net.http/jdk.internal.net.http.SocketTube$SocketFlowEvent.handle(SocketTube.java:253)
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.handleEvent(HttpClientImpl.java:1477)
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.lambda$run$3(HttpClientImpl.java:1422)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.run(HttpClientImpl.java:1422)

```

---

### [FAIL] gaia-file-lookup (gaia)

- **Prompt:** Read the file 'data.txt' in the workspace and tell me which city is named as the capital. Reply with just the city name.
- **Expected:** Abuja
- **Metrics:** 0 tokens (prompt 0 / completion 0), $0.0000, 140412ms
- **Verdict:** harness error: dev.axiom.llm.LlmException: LLM streaming request failed: HTTP/1.1 header parser received no bytes

#### Harness error

```
dev.axiom.llm.LlmException: LLM streaming request failed: HTTP/1.1 header parser received no bytes
	at dev.axiom.llm.OpenAiCompatibleClient.chatStream(OpenAiCompatibleClient.java:230)
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
Caused by: java.io.IOException: HTTP/1.1 header parser received no bytes
	at java.net.http/jdk.internal.net.http.HttpClientImpl.send(HttpClientImpl.java:970)
	at java.net.http/jdk.internal.net.http.HttpClientFacade.send(HttpClientFacade.java:133)
	at dev.axiom.llm.OpenAiCompatibleClient.chatStream(OpenAiCompatibleClient.java:216)
	... 10 more
Caused by: java.io.IOException: HTTP/1.1 header parser received no bytes
	at java.net.http/jdk.internal.net.http.common.Utils.wrapWithExtraDetail(Utils.java:412)
	at java.net.http/jdk.internal.net.http.Http1Response$HeadersReader.onReadError(Http1Response.java:590)
	at java.net.http/jdk.internal.net.http.Http1AsyncReceiver.checkForErrors(Http1AsyncReceiver.java:302)
	at java.net.http/jdk.internal.net.http.Http1AsyncReceiver.flush(Http1AsyncReceiver.java:268)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$LockingRestartableTask.run(SequentialScheduler.java:182)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$CompleteRestartableTask.run(SequentialScheduler.java:149)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$SchedulableTask.run(SequentialScheduler.java:207)
	at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
	at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
	at java.base/java.lang.Thread.run(Thread.java:1583)
Caused by: java.net.SocketException: Connection reset
	at java.base/sun.nio.ch.SocketChannelImpl.throwConnectionReset(SocketChannelImpl.java:401)
	at java.base/sun.nio.ch.SocketChannelImpl.read(SocketChannelImpl.java:434)
	at java.net.http/jdk.internal.net.http.SocketTube.readAvailable(SocketTube.java:1178)
	at java.net.http/jdk.internal.net.http.SocketTube$InternalReadPublisher$InternalReadSubscription.read(SocketTube.java:841)
	at java.net.http/jdk.internal.net.http.SocketTube$SocketFlowTask.run(SocketTube.java:181)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$SchedulableTask.run(SequentialScheduler.java:207)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler.runOrSchedule(SequentialScheduler.java:280)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler.runOrSchedule(SequentialScheduler.java:233)
	at java.net.http/jdk.internal.net.http.SocketTube$InternalReadPublisher$InternalReadSubscription.signalReadable(SocketTube.java:782)
	at java.net.http/jdk.internal.net.http.SocketTube$InternalReadPublisher$ReadEvent.signalEvent(SocketTube.java:965)
	at java.net.http/jdk.internal.net.http.SocketTube$SocketFlowEvent.handle(SocketTube.java:253)
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.handleEvent(HttpClientImpl.java:1477)
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.lambda$run$3(HttpClientImpl.java:1422)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.run(HttpClientImpl.java:1422)

```

---

### [FAIL] swe-fix-greeting (swe)

- **Prompt:** The file greet.txt must contain exactly 'Hello, world!'. Use the run tool to fix it, then reply Done.
- **Metrics:** 0 tokens (prompt 0 / completion 0), $0.0000, 128620ms
- **Verdict:** harness error: dev.axiom.llm.LlmException: LLM streaming request failed: HTTP/1.1 header parser received no bytes

#### Harness error

```
dev.axiom.llm.LlmException: LLM streaming request failed: HTTP/1.1 header parser received no bytes
	at dev.axiom.llm.OpenAiCompatibleClient.chatStream(OpenAiCompatibleClient.java:230)
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
Caused by: java.io.IOException: HTTP/1.1 header parser received no bytes
	at java.net.http/jdk.internal.net.http.HttpClientImpl.send(HttpClientImpl.java:970)
	at java.net.http/jdk.internal.net.http.HttpClientFacade.send(HttpClientFacade.java:133)
	at dev.axiom.llm.OpenAiCompatibleClient.chatStream(OpenAiCompatibleClient.java:216)
	... 10 more
Caused by: java.io.IOException: HTTP/1.1 header parser received no bytes
	at java.net.http/jdk.internal.net.http.common.Utils.wrapWithExtraDetail(Utils.java:412)
	at java.net.http/jdk.internal.net.http.Http1Response$HeadersReader.onReadError(Http1Response.java:590)
	at java.net.http/jdk.internal.net.http.Http1AsyncReceiver.checkForErrors(Http1AsyncReceiver.java:302)
	at java.net.http/jdk.internal.net.http.Http1AsyncReceiver.flush(Http1AsyncReceiver.java:268)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$LockingRestartableTask.run(SequentialScheduler.java:182)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$CompleteRestartableTask.run(SequentialScheduler.java:149)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$SchedulableTask.run(SequentialScheduler.java:207)
	at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
	at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
	at java.base/java.lang.Thread.run(Thread.java:1583)
Caused by: java.net.SocketException: Connection reset
	at java.base/sun.nio.ch.SocketChannelImpl.throwConnectionReset(SocketChannelImpl.java:401)
	at java.base/sun.nio.ch.SocketChannelImpl.read(SocketChannelImpl.java:434)
	at java.net.http/jdk.internal.net.http.SocketTube.readAvailable(SocketTube.java:1178)
	at java.net.http/jdk.internal.net.http.SocketTube$InternalReadPublisher$InternalReadSubscription.read(SocketTube.java:841)
	at java.net.http/jdk.internal.net.http.SocketTube$SocketFlowTask.run(SocketTube.java:181)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$SchedulableTask.run(SequentialScheduler.java:207)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler.runOrSchedule(SequentialScheduler.java:280)
	at java.net.http/jdk.internal.net.http.common.SequentialScheduler.runOrSchedule(SequentialScheduler.java:233)
	at java.net.http/jdk.internal.net.http.SocketTube$InternalReadPublisher$InternalReadSubscription.signalReadable(SocketTube.java:782)
	at java.net.http/jdk.internal.net.http.SocketTube$InternalReadPublisher$ReadEvent.signalEvent(SocketTube.java:965)
	at java.net.http/jdk.internal.net.http.SocketTube$SocketFlowEvent.handle(SocketTube.java:253)
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.handleEvent(HttpClientImpl.java:1477)
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.lambda$run$3(HttpClientImpl.java:1422)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.net.http/jdk.internal.net.http.HttpClientImpl$SelectorManager.run(HttpClientImpl.java:1422)

```

---


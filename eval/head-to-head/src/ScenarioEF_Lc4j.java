import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import harness.MockLlmServer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Head-to-head scenarios (e) and (f), LangChain4j 1.20.0 side — LIVE
 * against MockLlmServer (raw-socket localhost mock of an
 * OpenAI-compatible server).
 *
 * (e) streaming: server sends one valid SSE chunk, then a garbage
 *     `data: THIS IS NOT JSON` line, then [DONE]. What does the client do?
 * (f) sync: server answers 429 with `Retry-After: 30` and a quota-exhausted
 *     body on EVERY request. Is Retry-After honored? Is the quota-429
 *     distinguished from a rate-limit 429?
 */
public class ScenarioEF_Lc4j {

    public static void main(String[] args) throws Exception {
        MockLlmServer sse = MockLlmServer.start("sse-garbage");
        MockLlmServer quota = MockLlmServer.start("429-quota");
        try {
            runScenarios(sse, quota);
        } finally {
            sse.stop();
            quota.stop();
        }
        System.exit(0);
    }

    static void runScenarios(MockLlmServer sse, MockLlmServer quota) throws Exception {
        // ---- (e) garbage SSE chunk on the streaming path ----
        {
            String baseUrl = "http://127.0.0.1:" + sse.port() + "/v1";
            var model = OpenAiStreamingChatModel.builder()
                    .baseUrl(baseUrl).apiKey("test-key").modelName("m").build();
            var req = ChatRequest.builder().messages(new UserMessage("hi")).build();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> outcome = new AtomicReference<>("<no callback>");
            AtomicReference<String> partial = new AtomicReference<>("");
            model.chat(req, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String p) {
                    partial.updateAndGet(s -> s + p);
                }

                @Override
                public void onCompleteResponse(ChatResponse r) {
                    outcome.set("COMPLETED: content='" + r.aiMessage().text() + "'");
                    done.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    outcome.set("ERROR: " + t.getClass().getName() + ": " + firstLine(t.getMessage()));
                    done.countDown();
                }
            });
            boolean finished = done.await(20, TimeUnit.SECONDS);
            System.out.println("[LC4J] (e) garbage SSE -> " + (finished ? outcome.get() : "TIMEOUT waiting for callback")
                    + " | partial content delivered before failure: '" + partial.get() + "'"
                    + " | HTTP attempts: " + sse.attempts());
        }

        // ---- (f) 429 + Retry-After: 30, quota-exhausted body, always ----
        {
            String baseUrl = "http://127.0.0.1:" + quota.port() + "/v1";
            var model = OpenAiChatModel.builder()
                    .baseUrl(baseUrl).apiKey("test-key").modelName("m").build();
            var req = ChatRequest.builder().messages(new UserMessage("hi")).build();
            long t0 = System.nanoTime();
            try {
                model.chat(req);
                System.out.println("[LC4J] (f) unexpectedly succeeded");
            } catch (Exception e) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                System.out.println("[LC4J] (f) 429+Retry-After:30 -> " + e.getClass().getName()
                        + ": " + firstLine(e.getMessage()));
                System.out.println("[LC4J] (f) elapsed " + ms + " ms over " + quota.attempts()
                        + " HTTP attempts; Retry-After:30 honored? " + (ms >= 29_000 ? "YES" : "NO"));
            }
        }
    }

    static String firstLine(String s) {
        if (s == null) return "null";
        int i = s.indexOf('\n');
        String one = i < 0 ? s : s.substring(0, i);
        return one.length() > 160 ? one.substring(0, 160) + "..." : one;
    }
}

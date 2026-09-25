package dev.axiom.llm;

import dev.axiom.resilience.RetryPolicy;
import dev.axiom.resilience.RetryingLlmClient;
import harness.MockLlmServer;

import java.util.List;

/**
 * Head-to-head scenarios (e) and (f), Axiom side — LIVE.
 *
 * (e) garbage SSE: one valid chunk, then `data: THIS IS NOT JSON`, then
 *     [DONE], fed straight into OpenAiCompatibleClient.parseSseStream.
 * (f1) quota-exhausted 429 (body says "check your plan and billing
 *     details") on every request: fail fast with no retries?
 * (f2) rate-limit 429 with `Retry-After: 2` once, then 200 OK: is the
 *     header honored?
 *
 * NOTE: MockLlmServer lives in the harness default package; this class is
 * in dev.axiom.llm only to reach the package-private parseSseStream.
 */
public class ScenarioEF_Axiom {

    public static void main(String[] args) throws Exception {
        // ---- (e) garbage SSE chunk, full HTTP streaming path ----
        MockLlmServer sse = MockLlmServer.start("sse-garbage");
        try {
            var client = new RetryingLlmClient(
                    new OpenAiCompatibleClient("http://127.0.0.1:" + sse.port(), "x", "m"),
                    RetryPolicy.defaults());
            StringBuilder partial = new StringBuilder();
            try {
                ChatResponse r = client.chatStream(List.of(ChatMessage.user("hi")), List.of(),
                        LlmClient.LlmOptions.defaults(), partial::append);
                System.out.println("[AXIOM] (e) garbage SSE -> COMPLETED: content='" + r.content() + "'");
            } catch (Exception e) {
                System.out.println("[AXIOM] (e) garbage SSE -> ERROR: " + e.getClass().getName()
                        + ": " + firstLine(e.getMessage())
                        + " | partial content delivered before failure: '" + partial + "'"
                        + " | HTTP attempts: " + sse.attempts());
            }
        } finally {
            sse.stop();
        }

        // ---- (f1) quota-exhausted 429, always ----
        MockLlmServer quota = MockLlmServer.start("429-quota");
        try {
            var client = new RetryingLlmClient(
                    new OpenAiCompatibleClient("http://127.0.0.1:" + quota.port(), "x", "m"),
                    RetryPolicy.defaults());
            long t0 = System.nanoTime();
            try {
                client.chat(List.of(ChatMessage.user("hi")), List.of(), LlmClient.LlmOptions.defaults());
                System.out.println("[AXIOM] (f1) unexpectedly succeeded");
            } catch (Exception e) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                System.out.println("[AXIOM] (f1) quota-429 -> " + e.getClass().getName()
                        + ": " + firstLine(e.getMessage()));
                System.out.println("[AXIOM] (f1) isQuotaExhausted=" + LlmException.isQuotaExhausted(e)
                        + ", HTTP attempts=" + quota.attempts() + ", elapsed=" + ms + " ms");
            }
        } finally {
            quota.stop();
        }

        // ---- (f2) rate-limit 429 with Retry-After: 2, then OK ----
        MockLlmServer once = MockLlmServer.start("429-once-then-ok");
        try {
            var client = new RetryingLlmClient(
                    new OpenAiCompatibleClient("http://127.0.0.1:" + once.port(), "x", "m"),
                    RetryPolicy.defaults());
            long t0 = System.nanoTime();
            try {
                ChatResponse r = client.chat(List.of(ChatMessage.user("hi")), List.of(), LlmClient.LlmOptions.defaults());
                long ms = (System.nanoTime() - t0) / 1_000_000;
                System.out.println("[AXIOM] (f2) rate-429+Retry-After:2 -> SUCCESS after " + ms + " ms, "
                        + once.attempts() + " attempts, content='" + r.content() + "'"
                        + " (Retry-After honored? " + (ms >= 1900 ? "YES" : "NO") + ")");
            } catch (Exception e) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                System.out.println("[AXIOM] (f2) FAILED after " + ms + " ms: " + e);
            }
        } finally {
            once.stop();
        }
        System.exit(0);
    }

    static String firstLine(String s) {
        if (s == null) return "null";
        int i = s.indexOf('\n');
        String one = i < 0 ? s : s.substring(0, i);
        return one.length() > 160 ? one.substring(0, 160) + "..." : one;
    }
}

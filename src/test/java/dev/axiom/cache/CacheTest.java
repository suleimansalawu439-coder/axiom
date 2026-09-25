package dev.axiom.cache;

import dev.axiom.llm.*;
import dev.axiom.tools.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CacheTest {

    // ---------- InMemoryCache ----------

    @Test
    void storesAndRetrieves() {
        var c = new InMemoryCache();
        assertEquals(Optional.empty(), c.get("k"));
        c.put("k", "v");
        assertEquals(Optional.of("v"), c.get("k"));
    }

    @Test
    void evictsLeastRecentlyUsed() {
        var c = new InMemoryCache(2);
        c.put("a", "1"); c.put("b", "2");
        c.get("a"); // a is now most-recently-used
        c.put("c", "3"); // evicts b
        assertEquals(Optional.of("1"), c.get("a"));
        assertEquals(Optional.empty(), c.get("b"));
        assertEquals(Optional.of("3"), c.get("c"));
    }

    @Test
    void invalidateAndClear() {
        var c = new InMemoryCache();
        c.put("a", "1"); c.put("b", "2");
        c.invalidate("a");
        assertEquals(Optional.empty(), c.get("a"));
        c.clear();
        assertEquals(0, c.size());
    }

    // ---------- FileCache ----------

    @Test
    void survivesAcrossInstances(@TempDir Path dir) {
        var c1 = new FileCache(dir);
        c1.put("k", "v");
        var c2 = new FileCache(dir);
        assertEquals(Optional.of("v"), c2.get("k"));
    }

    @Test
    void missingKeyIsEmpty(@TempDir Path dir) {
        assertEquals(Optional.empty(), new FileCache(dir).get("nope"));
    }

    // ---------- CacheKeys ----------

    @Test
    void deterministicAndSensitiveToContent() {
        var m1 = List.of(ChatMessage.user("hi"));
        var m2 = List.of(ChatMessage.user("bye"));
        var opts = LlmClient.LlmOptions.defaults();
        String k1 = CacheKeys.forChat("gpt-4o", m1, List.of(), opts);
        String k2 = CacheKeys.forChat("gpt-4o", m1, List.of(), opts);
        String k3 = CacheKeys.forChat("gpt-4o", m2, List.of(), opts);
        String k4 = CacheKeys.forChat("gpt-4o-mini", m1, List.of(), opts);
        assertEquals(k1, k2);
        assertNotEquals(k1, k3);
        assertNotEquals(k1, k4);
    }

    // ---------- CachingLlmClient ----------

    static class CountingLlm implements LlmClient {
        int calls;
        @Override
        public ChatResponse chat(List<ChatMessage> m, List<ToolDefinition> t, LlmOptions o) {
            calls++;
            return new ChatResponse("answer-" + calls, List.of(),
                new ChatResponse.TokenUsage(5, 5, 10));
        }
        @Override
        public String model() { return "counter"; }
    }

    private static List<ChatMessage> msgs(String text) {
        return List.of(ChatMessage.user(text));
    }

    @Test
    void identicalRequestsHitCache() {
        var counting = new CountingLlm();
        var client = new CachingLlmClient(counting, new InMemoryCache());

        ChatResponse r1 = client.chat(msgs("hello"), List.of(), LlmClient.LlmOptions.defaults());
        ChatResponse r2 = client.chat(msgs("hello"), List.of(), LlmClient.LlmOptions.defaults());

        assertEquals(1, counting.calls);
        assertEquals(r1.content(), r2.content());
        assertEquals(r1.usage().totalTokens(), r2.usage().totalTokens());
    }

    @Test
    void differentRequestsMissCache() {
        var counting = new CountingLlm();
        var client = new CachingLlmClient(counting, new InMemoryCache());

        client.chat(msgs("hello"), List.of(), LlmClient.LlmOptions.defaults());
        client.chat(msgs("goodbye"), List.of(), LlmClient.LlmOptions.defaults());

        assertEquals(2, counting.calls);
    }

    @Test
    void toolCallsRoundTripThroughCache() {
        var counting = new CountingLlm() {
            @Override
            public ChatResponse chat(List<ChatMessage> m, List<ToolDefinition> t, LlmOptions o) {
                calls++;
                return new ChatResponse("using tool",
                    List.of(new ToolCallRequest("id1", "search",
                        java.util.Map.of("q", "x"))),
                    new ChatResponse.TokenUsage(5, 5, 10));
            }
        };
        var client = new CachingLlmClient(counting, new InMemoryCache());
        var opts = LlmClient.LlmOptions.defaults();

        ChatResponse r1 = client.chat(msgs("q"), List.of(), opts);
        ChatResponse r2 = client.chat(msgs("q"), List.of(), opts);

        assertEquals(1, counting.calls);
        assertEquals(1, r2.toolCalls().size());
        assertEquals("search", r2.toolCalls().get(0).name());
        assertEquals("x", r2.toolCalls().get(0).arguments().get("q"));
    }
}

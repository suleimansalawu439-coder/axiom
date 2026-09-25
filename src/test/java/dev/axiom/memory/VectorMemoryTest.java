package dev.axiom.memory;

import dev.axiom.llm.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vector store + semantic memory tests with deterministic fake embeddings.
 */
class VectorMemoryTest {

    /** Deterministic 3-dim embeddings over three keywords. */
    static EmbeddingFunction fakeEmbeddings() {
        return text -> {
            String t = text.toLowerCase();
            return new float[]{
                t.contains("dog") ? 1f : 0f,
                t.contains("cat") ? 1f : 0f,
                t.contains("paris") ? 1f : 0f
            };
        };
    }

    // ------------------------------------------------------------------
    // EmbeddingFunction.cosineSimilarity
    // ------------------------------------------------------------------

    @Test
    void cosineSimilarityMath() {
        assertEquals(1.0, EmbeddingFunction.cosineSimilarity(
            new float[]{1, 0}, new float[]{1, 0}), 1e-6);
        assertEquals(0.0, EmbeddingFunction.cosineSimilarity(
            new float[]{1, 0}, new float[]{0, 1}), 1e-6);
        assertEquals(-1.0, EmbeddingFunction.cosineSimilarity(
            new float[]{1, 0}, new float[]{-1, 0}), 1e-6);
        assertEquals(0.0, EmbeddingFunction.cosineSimilarity(
            new float[]{0, 0}, new float[]{1, 1}), 1e-6);
        assertThrows(IllegalArgumentException.class, () ->
            EmbeddingFunction.cosineSimilarity(new float[]{1}, new float[]{1, 2}));
    }

    // ------------------------------------------------------------------
    // FileVectorStore
    // ------------------------------------------------------------------

    @Test
    void searchRanksBySimilarity(@TempDir Path dir) {
        var store = new FileVectorStore(dir.resolve("v.json"), fakeEmbeddings());
        store.add("my dog is cute");
        store.add("my cat is fluffy");
        store.add("paris is beautiful");

        List<FileVectorStore.ScoredDocument> hits = store.search("tell me about the dog", 2);
        assertEquals(2, hits.size());
        assertTrue(hits.get(0).text().contains("dog"));
        assertEquals(1.0, hits.get(0).score(), 1e-6);
        assertTrue(hits.get(0).score() >= hits.get(1).score());
    }

    @Test
    void persistsAcrossRestarts(@TempDir Path dir) {
        Path file = dir.resolve("v.json");
        var store = new FileVectorStore(file, fakeEmbeddings());
        store.add("my dog is cute", Map.of("role", "user"));
        assertEquals(1, store.size());

        var reloaded = new FileVectorStore(file, fakeEmbeddings());
        assertEquals(1, reloaded.size());
        List<FileVectorStore.ScoredDocument> hits = reloaded.search("dog", 1);
        assertEquals("my dog is cute", hits.get(0).text());
        assertEquals("user", hits.get(0).metadata().get("role"));
    }

    @Test
    void clearEmptiesStore(@TempDir Path dir) {
        var store = new FileVectorStore(dir.resolve("v.json"), fakeEmbeddings());
        store.add("my dog is cute");
        store.clear();
        assertEquals(0, store.size());
        assertTrue(store.search("dog", 1).isEmpty());
    }

    // ------------------------------------------------------------------
    // VectorMemory
    // ------------------------------------------------------------------

    @Test
    void historyIncludesRecentAndRecalledContext(@TempDir Path dir) {
        Path file = dir.resolve("mem.json");
        var memory = new VectorMemory(fakeEmbeddings(), file, 20, 3);
        memory.store(List.of(
            ChatMessage.user("my dog's name is Biscuit"),
            ChatMessage.assistant("What a cute name!")));

        List<ChatMessage> history = memory.history();
        assertEquals(2, history.size()); // recent only: nothing older to recall

        // Simulate a later run in a fresh instance (same persisted store).
        var later = new VectorMemory(fakeEmbeddings(), file, 20, 3);
        later.store(List.of(ChatMessage.user("what is my dog's name?")));
        List<ChatMessage> laterHistory = later.history();

        assertEquals(2, laterHistory.size());
        ChatMessage recalled = laterHistory.get(1);
        assertEquals(dev.axiom.llm.ChatRole.SYSTEM, recalled.role());
        assertTrue(recalled.content().contains("Biscuit"),
            "expected recalled context to mention Biscuit, got: " + recalled.content());
    }

    @Test
    void repeatedStoresDoNotDuplicate(@TempDir Path dir) {
        var memory = new VectorMemory(fakeEmbeddings(), dir.resolve("mem.json"));
        var transcript = List.of(
            ChatMessage.user("my dog is cute"),
            ChatMessage.assistant("indeed"));
        memory.store(transcript);
        memory.store(transcript); // agent stores the full transcript after every run
        assertEquals(2, memory.store().size());
    }

    @Test
    void systemMessagesAreIgnored(@TempDir Path dir) {
        var memory = new VectorMemory(fakeEmbeddings(), dir.resolve("mem.json"));
        memory.store(List.of(
            ChatMessage.system("You are helpful."),
            ChatMessage.user("my cat is fluffy")));
        assertEquals(1, memory.store().size());
        memory.clear();
        assertEquals(0, memory.store().size());
        assertTrue(memory.history().isEmpty());
    }
}

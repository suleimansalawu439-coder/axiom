package dev.axiom.memory;

import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatRole;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Persistent semantic memory: every non-system message is embedded into a
 * {@link FileVectorStore} (survives restarts), while {@link #history()}
 * returns the recent window plus a recalled-context system message built
 * from a cosine-similarity search keyed on the latest user message.
 *
 * <p>Example:
 * <pre>{@code
 * EmbeddingFunction embeddings = new OpenAiEmbeddings("text-embedding-3-small");
 * Memory memory = new VectorMemory(embeddings, Path.of("memory/vectors.json"));
 *
 * var agent = Axiom.agent().withModel("gpt-4o").withMemory(memory).buildAgent();
 * agent.run("My dog's name is Biscuit");   // stored + embedded
 * agent.run("What's my dog's name?");      // recall surfaces "Biscuit"
 * }</pre>
 *
 * <p>Deduplication: a message whose exact text is already stored is skipped,
 * so repeated {@code store()} calls over overlapping transcripts (the norm —
 * the agent stores the full transcript after every run) don't bloat the
 * store, even across restarts.
 */
public final class VectorMemory implements Memory {
    private final FileVectorStore store;
    private final int maxRecentMessages;
    private final int recallTopK;

    /** Recent (message, store-entry-id) pairs; ids let recall exclude them. */
    private final Deque<RecentMessage> recent = new ArrayDeque<>();

    private record RecentMessage(ChatMessage message, String entryId) {}

    public VectorMemory(EmbeddingFunction embeddings, Path storeFile) {
        this(embeddings, storeFile, 20, 3);
    }

    /**
     * @param maxRecentMessages how many recent messages to always include
     * @param recallTopK        how many semantically similar older messages to
     *                          recall (0 disables semantic recall)
     */
    public VectorMemory(EmbeddingFunction embeddings, Path storeFile,
                        int maxRecentMessages, int recallTopK) {
        if (maxRecentMessages < 1) throw new IllegalArgumentException("maxRecentMessages must be >= 1");
        if (recallTopK < 0) throw new IllegalArgumentException("recallTopK must be >= 0");
        this.store = new FileVectorStore(storeFile, embeddings);
        this.maxRecentMessages = maxRecentMessages;
        this.recallTopK = recallTopK;
    }

    @Override
    public synchronized List<ChatMessage> history() {
        List<ChatMessage> out = new ArrayList<>();
        Set<String> recentIds = new HashSet<>();
        for (RecentMessage r : recent) {
            out.add(r.message());
            if (r.entryId() != null) recentIds.add(r.entryId());
        }
        String query = lastUserText();
        if (query != null && recallTopK > 0 && store.size() > 0) {
            List<FileVectorStore.ScoredDocument> hits = store.search(query, recallTopK + recentIds.size())
                .stream()
                .filter(h -> !recentIds.contains(h.id()))
                .limit(recallTopK)
                .toList();
            if (!hits.isEmpty()) {
                String context = hits.stream()
                    .map(h -> "- " + h.text())
                    .collect(Collectors.joining("\n"));
                out.add(ChatMessage.system(
                    "Relevant context recalled from earlier in the conversation:\n" + context));
            }
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void store(List<ChatMessage> transcript) {
        for (ChatMessage m : transcript) {
            if (m.role() == ChatRole.SYSTEM) continue;
            if (m.content() == null || m.content().isBlank()) continue;
            String entryId = null;
            if (!store.containsText(m.content())) {
                entryId = store.add(m.content(), Map.of("role", m.role().name()));
            }
            recent.addLast(new RecentMessage(m, entryId));
            while (recent.size() > maxRecentMessages) recent.removeFirst();
        }
    }

    @Override
    public synchronized void clear() {
        recent.clear();
        store.clear();
    }

    /** Direct access to the underlying store (search, inspection, …). */
    public FileVectorStore store() {
        return store;
    }

    private String lastUserText() {
        String last = null;
        for (RecentMessage r : recent) {
            if (r.message().role() == ChatRole.USER
                    && r.message().content() != null && !r.message().content().isBlank()) {
                last = r.message().content();
            }
        }
        return last;
    }
}

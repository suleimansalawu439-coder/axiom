package dev.axiom.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * File-backed vector store with cosine-similarity search. Entries are
 * embedded on write, persisted as JSON after every mutation, and reloaded on
 * construction — long-term memory that survives process restarts with zero
 * infrastructure.
 *
 * <p>Storage format (one JSON object):
 * <pre>{@code
 * {"entries": [{"id": "m-1", "text": "...", "vector": [0.1, ...], "metadata": {"role": "user"}}]}
 * }</pre>
 */
public final class FileVectorStore {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;
    private final EmbeddingFunction embeddings;
    private final List<Entry> entries = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(0);

    /** A stored document. */
    public record Entry(String id, String text, float[] vector, Map<String, String> metadata) {}

    /** A search hit with its cosine similarity score. */
    public record ScoredDocument(String id, String text, double score, Map<String, String> metadata) {}

    public FileVectorStore(Path file, EmbeddingFunction embeddings) {
        this.file = Objects.requireNonNull(file);
        this.embeddings = Objects.requireNonNull(embeddings);
        load();
    }

    /** Add a document: embeds the text, appends, and persists. Returns the entry id. */
    public synchronized String add(String text, Map<String, String> metadata) {
        float[] vector = embeddings.embed(text);
        String id = "m-" + idSeq.incrementAndGet();
        entries.add(new Entry(id, text, vector,
            metadata == null ? Map.of() : Map.copyOf(metadata)));
        persist();
        return id;
    }

    public String add(String text) {
        return add(text, null);
    }

    /** Top-K most similar documents to the query, highest score first. */
    public synchronized List<ScoredDocument> search(String query, int topK) {
        if (topK < 1) throw new IllegalArgumentException("topK must be >= 1");
        float[] q = embeddings.embed(query);
        return entries.stream()
            .map(e -> new ScoredDocument(e.id(), e.text(),
                EmbeddingFunction.cosineSimilarity(q, e.vector()), e.metadata()))
            .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
            .limit(topK)
            .toList();
    }

    public synchronized int size() {
        return entries.size();
    }

    /** True if a document with exactly this text is already stored. */
    public synchronized boolean containsText(String text) {
        return entries.stream().anyMatch(e -> e.text().equals(text));
    }

    public synchronized void clear() {
        entries.clear();
        persist();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void persist() {
        // File locking for cross-process safety. The lock channel is held
        // open for the duration of the write.
        Path lockPath = file.resolveSibling(file.getFileName() + ".lock");
        try {
            if (lockPath.getParent() != null) Files.createDirectories(lockPath.getParent());
            if (file.getParent() != null) Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create directories for " + file, e);
        }
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(lockPath,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE);
             java.nio.channels.FileLock lock = ch.lock()) {
            List<Map<String, Object>> raw = new ArrayList<>();
            for (Entry e : entries) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.id());
                m.put("text", e.text());
                List<Double> vec = new ArrayList<>(e.vector().length);
                for (float f : e.vector()) vec.add((double) f);
                m.put("vector", vec);
                m.put("metadata", e.metadata());
                raw.add(m);
            }
            // Atomic write: unique temp file + move, so a crash never leaves
            // half a store, and two processes don't collide on the temp name.
            Path tmp = file.resolveSibling(
                file.getFileName() + "." + java.util.UUID.randomUUID() + ".tmp");
            try {
                mapper.writeValue(tmp.toFile(), Map.of("entries", raw));
                try {
                    Files.move(tmp, file,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    // Fall back to non-atomic move on filesystems without support.
                    Files.move(tmp, file,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist vector store to " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.exists(file)) return;
        try {
            Map<String, Object> root = mapper.readValue(file.toFile(), new TypeReference<>() {});
            List<Map<String, Object>> raw = (List<Map<String, Object>>) root.getOrDefault("entries", List.of());
            long maxId = 0;
            for (Map<String, Object> m : raw) {
                String id = String.valueOf(m.get("id"));
                String text = String.valueOf(m.get("text"));
                List<Number> vec = (List<Number>) m.get("vector");
                float[] f = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) f[i] = vec.get(i).floatValue();
                Map<String, String> meta = new HashMap<>();
                Object rawMeta = m.get("metadata");
                if (rawMeta instanceof Map<?, ?> mm) {
                    mm.forEach((k, v) -> meta.put(String.valueOf(k), String.valueOf(v)));
                }
                entries.add(new Entry(id, text, f, Map.copyOf(meta)));
                try {
                    maxId = Math.max(maxId, Long.parseLong(id.replaceAll("\\D", "")));
                } catch (NumberFormatException ignored) {
                }
            }
            idSeq.set(maxId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load vector store from " + file, e);
        }
    }
}

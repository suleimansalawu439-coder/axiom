package dev.axiom.memory;

import java.util.List;

/**
 * Pluggable text-embedding function. Implementations map text to dense
 * vectors; {@link FileVectorStore} and {@link VectorMemory} build on top.
 * Keep implementations deterministic per input for stable recall.
 */
@FunctionalInterface
public interface EmbeddingFunction {
    /** Embed one text into a dense vector. */
    float[] embed(String text);

    /** Embed a batch; defaults to one-by-one. Override for batched APIs. */
    default List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /** Cosine similarity in [-1, 1]; 1 = identical direction. */
    static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                "Vector dimension mismatch: %d vs %d".formatted(a.length, b.length));
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}

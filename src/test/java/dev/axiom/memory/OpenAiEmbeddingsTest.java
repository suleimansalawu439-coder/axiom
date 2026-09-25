package dev.axiom.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OpenAiEmbeddings} wire-format tests. The request body and response
 * parsing are pure functions, tested without network I/O (loopback TCP is
 * unavailable in this sandbox, so no local stub HTTP server is used).
 */
class OpenAiEmbeddingsTest {

    private OpenAiEmbeddings embeddings() {
        // Never sends HTTP in these tests; base URL is irrelevant.
        return new OpenAiEmbeddings("https://example.invalid/v1", "key", "test-model");
    }

    @Test
    void requestBodyMatchesEmbeddingsWireFormat() throws Exception {
        String json = embeddings().buildRequestBody(List.of("a", "b"));
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
        assertEquals("test-model", parsed.get("model"));
        assertEquals(List.of("a", "b"), parsed.get("input"));
    }

    @Test
    void batchEmbeddingsPreserveInputOrder() {
        // Server deliberately returns data out of order; client sorts by index.
        String response = """
            {"data":[{"index":1,"embedding":[0.2,0.3]},{"index":0,"embedding":[0.0,0.1]}]}
            """;
        List<float[]> vecs = embeddings().parseResponse(response, 2);
        assertEquals(2, vecs.size());
        assertArrayEquals(new float[]{0.0f, 0.1f}, vecs.get(0), 1e-6f);
        assertArrayEquals(new float[]{0.2f, 0.3f}, vecs.get(1), 1e-6f);
    }

    @Test
    void mismatchedCountRaisesEmbeddingException() {
        assertThrows(OpenAiEmbeddings.EmbeddingException.class,
            () -> embeddings().parseResponse("{\"data\":[]}", 2));
    }

    @Test
    void malformedResponseRaisesEmbeddingException() {
        assertThrows(OpenAiEmbeddings.EmbeddingException.class,
            () -> embeddings().parseResponse("not json", 1));
    }

    @Test
    void emptyBatchShortCircuits() {
        assertTrue(embeddings().embedBatch(List.of()).isEmpty());
    }
}

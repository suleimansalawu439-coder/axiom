package dev.axiom.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link EmbeddingFunction} backed by any OpenAI-compatible
 * {@code /v1/embeddings} endpoint (OpenAI, Azure, Ollama, vLLM, …).
 * Uses only {@code java.net.http} — no SDK dependencies.
 */
public final class OpenAiEmbeddings implements EmbeddingFunction {
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final URI endpoint;
    private final String apiKey;
    private final String model;

    public OpenAiEmbeddings(String baseUrl, String apiKey, String model) {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        String normalized = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.endpoint = URI.create(normalized + "embeddings");
        this.apiKey = apiKey;
        this.model = model;
    }

    /** Convenience constructor reading {@code OPENAI_API_KEY} from the environment. */
    public OpenAiEmbeddings(String model) {
        this("https://api.openai.com/v1",
             System.getenv().getOrDefault("OPENAI_API_KEY", ""),
             model);
    }

    public String model() { return model; }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(texts)));
            if (apiKey != null && !apiKey.isBlank()) {
                req.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new EmbeddingException(
                    "Embeddings request failed with HTTP %d: %s".formatted(resp.statusCode(),
                        resp.body() == null ? "" : resp.body().substring(0, Math.min(500, resp.body().length()))));
            }
            return parseResponse(resp.body(), texts.size());
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("Embeddings request failed: " + e.getMessage(), e);
        }
    }

    /** Build the {@code /v1/embeddings} request body (pure function — unit-testable). */
    String buildRequestBody(List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", texts);
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new EmbeddingException("Failed to serialize embeddings request: " + e.getMessage(), e);
        }
    }

    /** Parse an {@code /v1/embeddings} response (pure function — unit-testable). */
    List<float[]> parseResponse(String json, int expected) {
        try {
            Map<String, Object> root = mapper.readValue(json, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) root.get("data");
            if (data == null || data.size() != expected) {
                throw new EmbeddingException(
                    "Expected %d embeddings, got %d".formatted(expected, data == null ? 0 : data.size()));
            }
            // Preserve request order: sort by index.
            data.sort((a, b) -> Integer.compare(num(a.get("index")), num(b.get("index"))));
            List<float[]> out = new ArrayList<>(expected);
            for (Map<String, Object> item : data) {
                @SuppressWarnings("unchecked")
                List<Number> vec = (List<Number>) item.get("embedding");
                float[] f = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) f[i] = vec.get(i).floatValue();
                out.add(f);
            }
            return List.copyOf(out);
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("Failed to parse embeddings response: " + e.getMessage(), e);
        }
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    /** Failure of an embeddings request. */
    public static final class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) { super(message); }
        public EmbeddingException(String message, Throwable cause) { super(message, cause); }
    }
}

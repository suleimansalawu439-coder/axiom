package dev.axiom.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An {@link LlmJudge} decorator that caches verdicts on disk. The cache key
 * is the SHA-256 of the task plus the canonicalized output, so rerunning an
 * eval suite is free and deterministic: cached verdicts never touch the
 * judge model, and identical (task, output) pairs always grade identically.
 *
 * <p>The cache file is JSON ({@code {sha256: {score, rationale}}}) and is
 * created on first use; it is safe to commit alongside the eval suite so CI
 * reruns stay free.
 *
 * <pre>{@code
 * LlmJudge judge = new CachedJudge(
 *     new OpenAiLlmJudge("gpt-4o-mini"),
 *     Path.of("eval/judge-cache.json"));
 * Scorer<Summary> s = Scorers.llmJudge(judge, 0.7);
 * }</pre>
 */
public final class CachedJudge implements LlmJudge {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Bumped whenever the key canonicalization changes. */
    private static final String KEY_VERSION = "v1";

    private final LlmJudge delegate;
    private final Path cacheFile;
    private final Map<String, StoredVerdict> cache = new LinkedHashMap<>();
    private boolean loaded;

    public CachedJudge(LlmJudge delegate, Path cacheFile) {
        if (delegate == null) throw new IllegalArgumentException("delegate is required");
        if (cacheFile == null) throw new IllegalArgumentException("cacheFile is required");
        this.delegate = delegate;
        this.cacheFile = cacheFile;
    }

    @Override
    public synchronized JudgeVerdict judge(String task, String actualOutput) {
        ensureLoaded();
        String key = cacheKey(task, actualOutput);
        StoredVerdict hit = cache.get(key);
        if (hit != null) {
            return new JudgeVerdict(hit.score(), hit.rationale());
        }
        JudgeVerdict verdict = delegate.judge(task, actualOutput);
        cache.put(key, new StoredVerdict(verdict.score(), verdict.rationale()));
        persist();
        return verdict;
    }

    /** Number of verdicts currently cached (in-memory view). */
    public synchronized int cachedVerdicts() {
        ensureLoaded();
        return cache.size();
    }

    /** The SHA-256 cache key for a (task, output) pair. */
    static String cacheKey(String task, String actualOutput) {
        String canonical = KEY_VERSION + "\n"
            + (task == null ? "" : task).strip() + "\n\u0000\n"
            + canonicalOutput(actualOutput);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new EvalException("SHA-256 unavailable", e);
        }
    }

    private static String canonicalOutput(String s) {
        return (s == null ? "" : s).replace("\r\n", "\n").strip();
    }

    @SuppressWarnings("unchecked")
    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(cacheFile)) return;
        try {
            String json = Files.readString(cacheFile, StandardCharsets.UTF_8);
            if (json.isBlank()) return;
            Map<String, Object> raw = MAPPER.readValue(json, Map.class);
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                Map<String, Object> v = (Map<String, Object>) e.getValue();
                Object score = v.get("score");
                Object rationale = v.get("rationale");
                if (score instanceof Number n && rationale != null) {
                    cache.put(e.getKey(),
                        new StoredVerdict(n.doubleValue(), String.valueOf(rationale)));
                }
            }
        } catch (Exception e) {
            throw new EvalException("Failed to load judge cache from " + cacheFile, e);
        }
    }

    private void persist() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, StoredVerdict> e : cache.entrySet()) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("score", e.getValue().score());
                v.put("rationale", e.getValue().rationale());
                out.put(e.getKey(), v);
            }
            if (cacheFile.getParent() != null) {
                Files.createDirectories(cacheFile.getParent());
            }
            Files.writeString(cacheFile,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out),
                StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new EvalException("Failed to persist judge cache to " + cacheFile, e);
        }
    }

    private record StoredVerdict(double score, String rationale) {}
}

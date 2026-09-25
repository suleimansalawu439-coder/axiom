package dev.axiom.cache;

import java.util.Optional;

/**
 * Key/value store for LLM responses. Keys are derived deterministically
 * from the request (see {@link CacheKeys}); values are serialized
 * {@link dev.axiom.llm.ChatResponse}s.
 */
public interface ResponseCache {

    Optional<String> get(String key);

    void put(String key, String serializedResponse);

    /** Remove a single entry. */
    void invalidate(String key);

    /** Drop everything. */
    void clear();
}

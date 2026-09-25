package dev.axiom.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Thread-safe bounded LRU cache for serialized LLM responses. Evicts the
 * least-recently-used entry when full. For persistence across restarts use
 * {@link FileCache}.
 */
public final class InMemoryCache implements ResponseCache {
    private final int capacity;
    private final Map<String, String> map;

    public InMemoryCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = capacity;
        this.map = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > InMemoryCache.this.capacity;
            }
        };
    }

    public InMemoryCache() {
        this(256);
    }

    @Override
    public synchronized Optional<String> get(String key) {
        return Optional.ofNullable(map.get(key));
    }

    @Override
    public synchronized void put(String key, String serializedResponse) {
        map.put(key, serializedResponse);
    }

    @Override
    public synchronized void invalidate(String key) {
        map.remove(key);
    }

    @Override
    public synchronized void clear() {
        map.clear();
    }

    public synchronized int size() {
        return map.size();
    }
}

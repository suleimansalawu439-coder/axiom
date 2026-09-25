package dev.axiom.cache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * File-backed response cache: one file per key under a root directory, so
 * cached responses survive restarts. Keys are expected to be filesystem-safe
 * (see {@link CacheKeys}, which emits hex digests).
 */
public final class FileCache implements ResponseCache {
    private final Path root;

    public FileCache(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create cache dir " + root, e);
        }
    }

    @Override
    public Optional<String> get(String key) {
        Path p = file(key);
        if (!Files.isRegularFile(p)) return Optional.empty();
        try {
            return Optional.of(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, String serializedResponse) {
        try {
            Files.writeString(file(key), serializedResponse, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write cache entry " + key, e);
        }
    }

    @Override
    public void invalidate(String key) {
        try {
            Files.deleteIfExists(file(key));
        } catch (IOException ignored) {
        }
    }

    @Override
    public void clear() {
        try (var stream = Files.list(root)) {
            for (Path p : stream.toList()) {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {
        }
    }

    private Path file(String key) {
        String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
        return root.resolve(safe + ".json");
    }
}

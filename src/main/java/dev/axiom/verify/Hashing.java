package dev.axiom.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Canonical hashing shared by certificates: SHA-256 over the canonical JSON
 * encoding of the tool call's arguments (keys sorted, so argument order can
 * never change the hash).
 */
final class Hashing {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Hashing() {}

    /** SHA-256 hex of the canonical JSON encoding of {@code args}. */
    static String argsHash(Map<String, Object> args) {
        try {
            String canonical = MAPPER.writeValueAsString(args == null ? Map.of() : args);
            return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new VerificationException("Cannot canonical-hash tool arguments", e);
        }
    }

    /** SHA-256 hex of raw bytes. */
    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new VerificationException("SHA-256 unavailable", e);
        }
    }
}

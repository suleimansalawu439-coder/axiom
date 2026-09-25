package dev.axiom.verify;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A machine-checkable attestation that a tool's claimed effects are actually
 * observable in the world — not the tool's word for it, but a {@link Verifier}'s
 * independent observation, captured at {@link #issuedAt}.
 *
 * <p>A certificate attests to <b>observable effects</b> (these bytes are now
 * at this path; this path no longer exists), never to intent or semantic
 * correctness. "The agent wrote the <i>right</i> file" is not provable — only
 * "the agent wrote <i>these bytes</i> to <i>this path</i>".
 *
 * <p>Certificates are journaled as {@code CertificateIssued} agent events and
 * can be re-checked later (against current state) by {@link VerifyMain}.
 */
public record Certificate(
        /** Tool that produced the effect, e.g. {@code "vwrite"}. */
        String toolName,
        /** The model-issued call id this certificate belongs to. */
        String callId,
        /** SHA-256 (hex) of the canonical JSON encoding of the call's arguments. */
        String argsHash,
        /** The independently observed effects. Empty for {@code unverifiable}. */
        List<EffectClaim> claims,
        /**
         * Which verifier produced this: {@code "builtin:file-write"},
         * {@code "builtin:file-read"}, {@code "builtin:file-delete"},
         * {@code "custom:<name>"} for user verifiers, or
         * {@code "unverifiable"} for effects that cannot be attested.
         */
        String verifierKind,
        /** When the attestation was captured. */
        Instant issuedAt) {

    public Certificate {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(argsHash, "argsHash");
        claims = claims == null ? List.of() : List.copyOf(claims);
        Objects.requireNonNull(verifierKind, "verifierKind");
        Objects.requireNonNull(issuedAt, "issuedAt");
    }

    /**
     * One independently observed effect: what happened, where, and the
     * evidence (usually a SHA-256 hex of the observed bytes). For deletions
     * {@code expectedSha256} is null and the claim is "this path is absent".
     */
    public record EffectClaim(
            /** e.g. {@code "file-write"}, {@code "file-read"}, {@code "file-delete"}. */
            String kind,
            /** The path (or resource) the effect concerns. */
            String path,
            /** SHA-256 hex of the observed bytes; null when the claim is absence. */
            String expectedSha256,
            /** Human-readable note, e.g. {@code "128 bytes at /tmp/x.txt"}. */
            String detail) {

        public EffectClaim {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(path, "path");
        }
    }

    /** True when this certificate carries no attestation at all. */
    public boolean isUnverifiable() {
        return "unverifiable".equals(verifierKind);
    }
}

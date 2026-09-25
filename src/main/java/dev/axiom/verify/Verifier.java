package dev.axiom.verify;

import java.util.Map;

/**
 * Produces and independently re-checks {@link Certificate}s for a tool.
 *
 * <p>The critical discipline: {@link #attest} must observe the effect
 * <b>independently of the tool body</b> — re-read the file, recompute the
 * hash — and never trust the tool's return value. A verifier that just
 * echoes what the tool said proves nothing. {@link #check} then re-verifies
 * the certificate later (used by the agent immediately, and by
 * {@link VerifyMain} during offline audits), again from direct observation.
 *
 * <p><b>Honesty note:</b> a tool author who controls both the tool and its
 * verifier can lie — the framework cannot prevent that. What it does is make
 * the lie <i>visible</i>: custom verifiers are marked
 * {@code "custom:<name>"} in every certificate and audit report, and tools
 * with no verifier at all are marked {@code "unverifiable"} — never silently
 * treated as verified.
 */
public interface Verifier {
    /**
     * Which verifier this is, e.g. {@code "builtin:file-write"} or
     * {@code "custom:my-auditor"}. Recorded on every certificate and shown
     * in audit reports.
     */
    String kind();

    /**
     * Independently observe the tool's effect <b>right now</b> and capture
     * a certificate. Must throw {@link VerificationException} if the claimed
     * effect is not observable — a tool that says it wrote a file but left
     * nothing on disk fails here, loudly, before the run continues.
     *
     * @param toolName      the tool that just ran
     * @param callId        the model-issued call id
     * @param args          the call's arguments (canonical-hashed into the certificate)
     * @param resultSummary the tool's stringified result, for context only —
     *                      a verifier must never trust it as evidence
     */
    Certificate attest(String toolName, String callId,
                       Map<String, Object> args, String resultSummary);

    /**
     * Independently re-verify a previously issued certificate against
     * current observable state. Throws {@link VerificationException} with a
     * precise diagnostic (expected vs observed) on any mismatch — including
     * when the world changed <i>after</i> attestation (that is the tamper
     * signal the offline audit looks for).
     */
    void check(Certificate certificate);
}

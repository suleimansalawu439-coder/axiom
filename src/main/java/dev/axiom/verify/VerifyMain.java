package dev.axiom.verify;

import dev.axiom.agent.AgentEvent;
import dev.axiom.durable.DurableException;
import dev.axiom.durable.RunJournal;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline tamper-evident audit: replays every certificate in a run journal
 * and independently re-verifies each effect against current observable state.
 *
 * <p>Usage: {@code java -cp <axiom jar + deps> dev.axiom.verify.VerifyMain <journalDir>}
 * where {@code <journalDir>} is the run directory containing
 * {@code journal.jsonl} (printed by demos, or
 * {@code <journalRoot>/<runId>}).
 *
 * <p>Exit code 0 = every attestable effect re-verified; 1 = a verification
 * failed or the journal itself is corrupt/tampered.
 *
 * <p>Per-call verdicts:
 * <ul>
 *   <li><b>VERIFIED</b> — a certificate exists and re-verification passed.</li>
 *   <li><b>SUPERSEDED</b> — an earlier claim on a path that a later
 *       <i>verified</i> effect replaced (overwritten or deleted). Re-checking
 *       it against current state would be meaningless, so it is marked —
 *       not re-checked, not failed. It was verified at run time; the journal
 *       says so.</li>
 *   <li><b>FAILED</b> — re-verification failed (the world changed since
 *       attestation, or never matched). Loud, with expected vs observed.</li>
 *   <li><b>UNVERIFIABLE</b> — no certificate was ever issued for the call
 *       (plain tool, or explicit {@code unverifiable} marker). Marked, never
 *       silently treated as verified.</li>
 *   <li><b>CUSTOM</b> — attested by a user verifier ({@code custom:<name>}).
 *       The runtime attestation is on record, but offline re-verification
 *       needs the verifier's code, so the audit reports it as seen, not
 *       re-checked. Honest, not silent.</li>
 * </ul>
 */
public final class VerifyMain {

    private VerifyMain() {}

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: dev.axiom.verify.VerifyMain <journalDir>");
            System.err.println("  Audits the run journal at <journalDir>/journal.jsonl.");
            System.exit(2);
        }
        boolean ok = audit(Path.of(args[0]), System.out);
        System.exit(ok ? 0 : 1);
    }

    /**
     * Audit the journal, printing the report to {@code out}.
     *
     * @return true when every attestable effect re-verified and the journal
     *         is intact; false on any FAILED effect or journal corruption.
     */
    public static boolean audit(Path journalDir, PrintStream out) {
        out.println("Axiom verification audit");
        out.println("journal: " + journalDir.toAbsolutePath());

        List<RunJournal.Record> records;
        try {
            Path root = journalDir.toAbsolutePath().getParent();
            String runId = journalDir.toAbsolutePath().getFileName().toString();
            if (root == null || !Files.isRegularFile(journalDir.resolve("journal.jsonl"))) {
                out.println("RESULT: NO JOURNAL — no journal.jsonl under " + journalDir);
                return false;
            }
            try (RunJournal journal = RunJournal.open(root, runId)) {
                records = journal.readAll();
            }
        } catch (DurableException e) {
            out.println("RESULT: JOURNAL CORRUPT — " + e.getMessage());
            return false;
        }

        // Tool calls in order, certificates by call id, and any recorded
        // verification failures (fail-closed aborts leave ok=false on record).
        List<ToolCall> calls = new ArrayList<>();
        List<Certificate> orderedCerts = new ArrayList<>();
        Map<String, Certificate> issued = new LinkedHashMap<>();
        List<String> recordedFailures = new ArrayList<>();
        for (RunJournal.Record r : records) {
            if (r instanceof RunJournal.ToolCallStarted ts) {
                calls.add(new ToolCall(ts.callId(), ts.toolName()));
            }
            if (r instanceof RunJournal.Event ev) {
                if (ev.event() instanceof AgentEvent.CertificateIssued ci) {
                    issued.put(ci.certificate().callId(), ci.certificate());
                    orderedCerts.add(ci.certificate());
                } else if (ev.event() instanceof AgentEvent.CertificateVerified cv && !cv.ok()) {
                    recordedFailures.add(
                        "[" + cv.callId() + "] " + cv.toolName() + ": " + cv.detail());
                }
            }
        }

        // Tamper semantics: the audit re-verifies against CURRENT state, so
        // only the LATEST claim per path is checkable — an earlier write that
        // was legitimately overwritten or deleted by a later verified effect
        // would otherwise read as tampering. Earlier claims are SUPERSEDED
        // (they were verified at run time; the journal says so) rather than
        // re-checked.
        Map<String, ClaimRef> latestByPath = new LinkedHashMap<>();
        for (Certificate cert : orderedCerts) {
            for (var claim : cert.claims()) {
                latestByPath.put(claim.path(), new ClaimRef(cert, claim));
            }
        }

        int verified = 0, attestable = 0, unverifiable = 0, custom = 0, superseded = 0;
        List<String> failures = new ArrayList<>(recordedFailures);
        for (ToolCall call : calls) {
            Certificate cert = issued.get(call.callId());
            if (cert == null || cert.isUnverifiable()) {
                unverifiable++;
                out.println("  [" + call.callId() + "] " + call.toolName()
                    + " — UNVERIFIABLE (no attestation; not treated as verified)");
                continue;
            }
            if (cert.verifierKind().startsWith("custom:")) {
                custom++;
                out.println("  [" + call.callId() + "] " + call.toolName()
                    + " — CUSTOM (" + cert.verifierKind()
                    + "; attested at runtime, not re-checkable offline)");
                continue;
            }
            Verifier verifier = builtin(cert.verifierKind());
            if (verifier == null) {
                failures.add("[" + call.callId() + "] " + call.toolName()
                    + ": unknown verifier kind '" + cert.verifierKind() + "'");
                out.println("  [" + call.callId() + "] " + call.toolName()
                    + " — FAILED (unknown verifier kind '" + cert.verifierKind() + "')");
                continue;
            }
            List<Certificate.EffectClaim> checkable = new ArrayList<>();
            for (var claim : cert.claims()) {
                ClaimRef ref = latestByPath.get(claim.path());
                if (ref != null && ref.cert() == cert && ref.claim() == claim) {
                    checkable.add(claim);
                } else {
                    superseded++;
                    out.println("  [" + call.callId() + "] " + call.toolName()
                        + " — SUPERSEDED (" + claim.kind() + " " + claim.path()
                        + "; a later verified effect replaced it — was verified at runtime)");
                }
            }
            if (checkable.isEmpty()) continue;
            attestable++;
            Certificate scoped = new Certificate(cert.toolName(), cert.callId(),
                cert.argsHash(), checkable, cert.verifierKind(), cert.issuedAt());
            try {
                verifier.check(scoped);
                verified++;
                out.println("  [" + call.callId() + "] " + call.toolName()
                    + " — VERIFIED (" + cert.verifierKind()
                    + ", " + checkable.size() + " effect claim(s))");
            } catch (VerificationException ve) {
                failures.add("[" + call.callId() + "] " + call.toolName() + ": " + ve.getMessage());
                out.println("  [" + call.callId() + "] " + call.toolName()
                    + " — FAILED: " + ve.getMessage());
            }
        }

        out.println("tool calls: " + calls.size()
            + " | latest effects verified: " + verified + "/" + attestable
            + " | superseded: " + superseded
            + " | custom: " + custom + " | unverifiable: " + unverifiable);
        if (failures.isEmpty()) {
            out.println("RESULT: ALL CHECKS PASSED — every attestable effect independently re-verified.");
            return true;
        }
        out.println("RESULT: AUDIT FAILED — " + failures.size() + " problem(s):");
        for (String f : failures) out.println("  - " + f);
        return false;
    }

    private static Verifier builtin(String kind) {
        return switch (kind) {
            case "builtin:file-write" -> new Verifiers.FileWriteVerifier();
            case "builtin:file-read" -> new Verifiers.FileReadVerifier();
            case "builtin:file-delete" -> new Verifiers.FileDeleteVerifier();
            default -> null;
        };
    }

    private record ToolCall(String callId, String toolName) {}

    /** A single effect claim, pinned to the certificate that carries it. */
    private record ClaimRef(Certificate cert, Certificate.EffectClaim claim) {}
}

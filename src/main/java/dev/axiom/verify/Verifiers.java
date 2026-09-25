package dev.axiom.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Built-in verifiers for the framework's own file tool surface, plus the
 * explicit {@link #unverifiable()} marker.
 *
 * <p>Every verifier here observes the filesystem independently of the tool
 * body: the tool's return value is context only, never evidence.
 */
public final class Verifiers {

    private Verifiers() {}

    /** Extract a required string argument, failing loudly when absent. */
    static Path pathArg(Map<String, Object> args, String name) {
        Object v = args == null ? null : args.get(name);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new VerificationException(
                "Verifier needs a string '" + name + "' argument to attest a file effect");
        }
        return Path.of(s);
    }

    static byte[] readBytes(Path p, String what) {
        try {
            return Files.readAllBytes(p);
        } catch (Exception e) {
            throw new VerificationException(
                "Cannot observe " + what + " at " + p + ": " + e.getMessage(), e);
        }
    }

    /**
     * Attests file writes: the verifier re-reads the file itself and hashes
     * what is actually on disk. If the tool claimed to write but nothing (or
     * something else) is there, attestation fails right here.
     */
    public static final class FileWriteVerifier implements Verifier {
        @Override public String kind() { return "builtin:file-write"; }

        @Override
        public Certificate attest(String toolName, String callId,
                                  Map<String, Object> args, String resultSummary) {
            Path p = pathArg(args, "path");
            byte[] bytes = readBytes(p, "written file");
            String hash = Hashing.sha256Hex(bytes);
            var claim = new Certificate.EffectClaim("file-write", p.toString(), hash,
                bytes.length + " bytes at " + p);
            return new Certificate(toolName, callId, Hashing.argsHash(args),
                List.of(claim), kind(), Instant.now());
        }

        @Override
        public void check(Certificate certificate) {
            for (var claim : certificate.claims()) {
                Path p = Path.of(claim.path());
                byte[] now = readBytes(p, "written file");
                String actual = Hashing.sha256Hex(now);
                if (!actual.equals(claim.expectedSha256())) {
                    throw new VerificationException(
                        ("File tamper detected at %s: attested sha256 %s, now %s " +
                         "(%d bytes). The file was modified after the tool call.")
                            .formatted(p, claim.expectedSha256(), actual, now.length));
                }
            }
        }
    }

    /**
     * Attests file reads: hashes the bytes the tool actually served (re-read
     * at attestation time), so a later audit can prove the read observed
     * these exact bytes.
     */
    public static final class FileReadVerifier implements Verifier {
        @Override public String kind() { return "builtin:file-read"; }

        @Override
        public Certificate attest(String toolName, String callId,
                                  Map<String, Object> args, String resultSummary) {
            Path p = pathArg(args, "path");
            byte[] bytes = readBytes(p, "read file");
            String hash = Hashing.sha256Hex(bytes);
            var claim = new Certificate.EffectClaim("file-read", p.toString(), hash,
                "served " + bytes.length + " bytes from " + p);
            return new Certificate(toolName, callId, Hashing.argsHash(args),
                List.of(claim), kind(), Instant.now());
        }

        @Override
        public void check(Certificate certificate) {
            for (var claim : certificate.claims()) {
                Path p = Path.of(claim.path());
                String actual = Hashing.sha256Hex(readBytes(p, "read file"));
                if (!actual.equals(claim.expectedSha256())) {
                    throw new VerificationException(
                        ("Read-effect mismatch at %s: attested sha256 %s, now %s. " +
                         "The file changed after it was read.")
                            .formatted(p, claim.expectedSha256(), actual));
                }
            }
        }
    }

    /**
     * Attests file deletions: the claim is <i>absence</i>. Attestation fails
     * loudly if the path still exists (the tool lied); re-checking fails if
     * the path has reappeared since.
     */
    public static final class FileDeleteVerifier implements Verifier {
        @Override public String kind() { return "builtin:file-delete"; }

        @Override
        public Certificate attest(String toolName, String callId,
                                  Map<String, Object> args, String resultSummary) {
            Path p = pathArg(args, "path");
            if (Files.exists(p)) {
                throw new VerificationException(
                    "Tool '" + toolName + "' was expected to delete " + p
                        + " but the path still exists — effect not observable.");
            }
            var claim = new Certificate.EffectClaim("file-delete", p.toString(), null,
                p + " absent");
            return new Certificate(toolName, callId, Hashing.argsHash(args),
                List.of(claim), kind(), Instant.now());
        }

        @Override
        public void check(Certificate certificate) {
            for (var claim : certificate.claims()) {
                Path p = Path.of(claim.path());
                if (Files.exists(p)) {
                    throw new VerificationException(
                        "Delete-effect violated at " + p
                            + ": the path was attested absent but now exists.");
                }
            }
        }
    }

    /**
     * The explicit "unverifiable" marker for tools whose effects cannot be
     * attested (pure computations, external side effects with no observable
     * handle, …). Attests a claim-free certificate; {@link #check} is a
     * no-op because there is nothing to verify.
     *
     * <p>This exists so the audit can say <b>UNVERIFIABLE</b> instead of
     * silently treating the tool as verified — absence of evidence is
     * evidence of absence, printed in the report.
     */
    public static Verifier unverifiable() {
        return new Verifier() {
            @Override public String kind() { return "unverifiable"; }

            @Override
            public Certificate attest(String toolName, String callId,
                                      Map<String, Object> args, String resultSummary) {
                return new Certificate(toolName, callId, Hashing.argsHash(args),
                    List.of(), kind(), Instant.now());
            }

            @Override public void check(Certificate certificate) {
                // Nothing to verify — that is the point, and it is marked.
            }
        };
    }
}

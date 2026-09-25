package dev.axiom.verify;

/**
 * Thrown when an attested tool effect cannot be independently confirmed —
 * the world does not look the way the certificate says it should.
 *
 * <p>Fail-closed by design: the agent loop aborts the run on this exception
 * (it is never converted into a model observation), and the failure is
 * journaled as a {@code CertificateVerified} event with {@code ok=false}.
 */
public class VerificationException extends RuntimeException {
    public VerificationException(String message) {
        super(message);
    }

    public VerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}

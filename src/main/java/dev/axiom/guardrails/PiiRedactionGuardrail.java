package dev.axiom.guardrails;

import java.util.regex.Pattern;

/**
 * Redacts likely PII from the agent's <em>output</em> instead of blocking:
 * email addresses, long digit runs (card/account/phone-like numbers), and
 * explicit {@code ssn}-style labels become {@code [REDACTED]}. Demo-grade
 * patterns — regulated deployments should plug in a proper DLP engine behind
 * the {@link Guardrail} interface.
 */
public final class PiiRedactionGuardrail implements Guardrail {
    private static final Pattern EMAIL =
        Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern LONG_DIGITS =
        Pattern.compile("(?<!\\d)\\d[\\d \\-.]{11,}\\d(?!\\d)");
    private static final Pattern SSN_LIKE =
        Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

    private final String name;

    public PiiRedactionGuardrail() {
        this("pii-redaction");
    }

    public PiiRedactionGuardrail(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Verdict checkOutput(String output) {
        if (output == null) return Verdict.allow();
        String redacted = SSN_LIKE.matcher(output).replaceAll("[REDACTED]");
        redacted = EMAIL.matcher(redacted).replaceAll("[REDACTED]");
        redacted = LONG_DIGITS.matcher(redacted).replaceAll("[REDACTED]");
        if (!redacted.equals(output)) {
            return Verdict.replace(redacted);
        }
        return Verdict.allow();
    }
}

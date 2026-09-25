package dev.axiom.guardrails;

/**
 * The outcome of a guardrail check. Three possibilities:
 * <ul>
 *   <li>{@link Allow} — proceed unchanged.</li>
 *   <li>{@link Block} — stop the run with
 *       {@link GuardrailViolationException}.</li>
 *   <li>{@link Replace} — continue, but substitute the sanitized text
 *       (e.g. PII redaction).</li>
 * </ul>
 */
public sealed interface Verdict permits Verdict.Allow, Verdict.Block, Verdict.Replace {

    record Allow() implements Verdict {}
    record Block(String reason) implements Verdict {}
    record Replace(String text) implements Verdict {}

    static Verdict allow() { return new Allow(); }
    static Verdict block(String reason) { return new Block(reason); }
    static Verdict replace(String text) { return new Replace(text); }
}

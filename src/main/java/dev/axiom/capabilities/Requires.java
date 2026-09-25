package dev.axiom.capabilities;

import java.lang.annotation.*;

/**
 * STRIPS-style precondition on a {@code @Tool} method: session tokens that
 * must already hold (established by an earlier completed tool's
 * {@code @Ensures}) before this tool may run.
 *
 * <p>Example: a snapshot-deletion tool that must never run before a backup:
 * <pre>{@code
 * @Tool(description = "Delete snapshots older than 30 days",
 *       capabilities = {Capability.DESTRUCTIVE})
 * @Requires(Capability.BACKUP)
 * public String deleteOldSnapshots() { ... }
 * }</pre>
 *
 * <p>Only session tokens ({@link Capability#isSessionToken()}) may appear
 * here — the annotation processor rejects effect capabilities at compile
 * time. Satisfiability is also checked at compile time: if no {@code @Tool}
 * in the compilation {@code @Ensures} a required token, the build fails
 * ("dead policy") instead of the agent discovering it at 2am. At runtime,
 * {@link dev.axiom.guardrails.CapabilityGuardrail} enforces the
 * precondition against session tokens journaled from completed tool calls,
 * fail-closed.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Requires {
    /** Session tokens that must hold before this tool may run. */
    Capability[] value();
}

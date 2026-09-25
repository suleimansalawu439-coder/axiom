package dev.axiom.tools;

import dev.axiom.capabilities.Capability;

import java.lang.annotation.*;

/**
 * Marks a method as an agent tool. The Axiom annotation processor reads this at
 * compile time and generates the JSON schema the LLM sees — if the method
 * signature and the schema ever disagree, compilation fails instead of the
 * agent failing at runtime.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Tool {
    /** Human/LLM-readable description of what the tool does. */
    String description();

    /** Override the tool name exposed to the LLM (defaults to the method name). */
    String name() default "";

    /** If true, the agent must get human approval before each invocation. */
    boolean requiresApproval() default false;

    /** Execution timeout in seconds. */
    long timeoutSeconds() default 60;

    /**
     * Declare the tool idempotent: re-executing it with identical arguments
     * is side-effect safe (pure reads, upserts keyed by a stable id, …).
     * Durable resume re-executes a tool call that started but never completed
     * (crash window) <em>only</em> when this is true; otherwise resume aborts
     * loudly instead of risking a double side effect. Default {@code false} —
     * never inferred, always declared by the tool author.
     */
    boolean idempotent() default false;

    /**
     * Declared effect capabilities of this tool (what it <em>does</em> to the
     * world: {@code READ}, {@code WRITE}, {@code DESTRUCTIVE},
     * {@code NETWORK}, {@code SPEND}, {@code PRIVATE_DATA}). Session tokens
     * ({@code APPROVAL}, {@code BACKUP}) do not belong here — declare them
     * with {@code @Requires}/{@code @Ensures} instead.
     *
     * <p>Recorded in the compile-time policy artifact
     * ({@code META-INF/axiom/policy/*.json}) and enforced at runtime by the
     * capability guardrail. Declared by the tool author, who knows what the
     * tool does — never inferred.
     */
    Capability[] capabilities() default {};
}

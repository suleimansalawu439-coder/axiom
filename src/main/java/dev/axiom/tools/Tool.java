package dev.axiom.tools;

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
}

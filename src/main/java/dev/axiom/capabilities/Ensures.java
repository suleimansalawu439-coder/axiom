package dev.axiom.capabilities;

import java.lang.annotation.*;

/**
 * STRIPS-style effect on a {@code @Tool} method: session tokens this tool
 * <em>establishes</em> when it completes successfully.
 *
 * <p>Example:
 * <pre>{@code
 * @Tool(description = "Snapshot the database to cold storage",
 *       capabilities = {Capability.READ, Capability.WRITE})
 * @Ensures(Capability.BACKUP)
 * public String backupDatabase() { ... }
 * }</pre>
 *
 * <p>Only session tokens ({@link Capability#isSessionToken()}) may appear
 * here. Honest boundary, stated plainly: the compiler checks that the token
 * is <em>declared</em> and that the policy is <em>satisfiable</em>; it cannot
 * check that the tool <em>semantically</em> did what the token claims (a
 * tool could "back up" an empty directory). Semantic truth is a job for
 * trajectory evals / LLM judges, not the type system.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Ensures {
    /** Session tokens this tool establishes on successful completion. */
    Capability[] value();
}

package dev.axiom.tools;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

/**
 * A single tool exposed to the LLM: its metadata, its JSON schema, and a
 * handle to invoke it. Instances are built by {@link ToolRegistry} for
 * {@code @Tool}-annotated methods (whose schemas are additionally validated
 * at compile time by the annotation processor), or via {@link #of} for
 * synthetic tools such as MCP server tools and supervisor delegate tools.
 */
public final class ToolDefinition {
    private final String name;
    private final String description;
    private final Map<String, Object> jsonSchema;
    private final boolean requiresApproval;
    private final long timeoutSeconds;
    /**
     * Whether re-executing this tool with identical arguments is safe.
     * Declared by the tool author, never inferred: only tools whose side
     * effects are naturally idempotent (pure reads, upserts keyed by a
     * stable id, …) may opt in. Durable resume re-executes a crashed
     * tool call <em>only</em> when this is true; otherwise resume refuses
     * loudly rather than risk a double side effect. Default {@code false}.
     */
    private final boolean idempotent;
    private final ToolInvoker invoker;
    /** Null for synthetic tools that have no backing Java method. */
    private final Method method;

    private ToolDefinition(String name, String description, Map<String, Object> jsonSchema,
                           boolean requiresApproval, long timeoutSeconds,
                           ToolInvoker invoker, Method method, boolean idempotent) {
        this.name = Objects.requireNonNull(name);
        this.description = Objects.requireNonNull(description);
        this.jsonSchema = Map.copyOf(jsonSchema);
        this.requiresApproval = requiresApproval;
        this.timeoutSeconds = timeoutSeconds;
        this.invoker = Objects.requireNonNull(invoker);
        this.method = method;
        this.idempotent = idempotent;
        if (method != null) method.setAccessible(true);
    }

    /**
     * Build a tool definition for an annotated method. Used by
     * {@link ToolRegistry}; the annotation processor has already validated
     * the schema at compile time.
     */
    static ToolDefinition forMethod(String name, String description, Map<String, Object> jsonSchema,
                                    boolean requiresApproval, long timeoutSeconds,
                                    ToolInvoker invoker, Method method) {
        return forMethod(name, description, jsonSchema, requiresApproval,
            timeoutSeconds, invoker, method, false);
    }

    /**
     * Like {@link #forMethod(String, String, Map, boolean, long, ToolInvoker,
     * Method)} with an explicit idempotency declaration (from
     * {@code @Tool(idempotent = …)}).
     */
    static ToolDefinition forMethod(String name, String description, Map<String, Object> jsonSchema,
                                    boolean requiresApproval, long timeoutSeconds,
                                    ToolInvoker invoker, Method method, boolean idempotent) {
        return new ToolDefinition(name, description, jsonSchema, requiresApproval,
            timeoutSeconds, invoker, Objects.requireNonNull(method), idempotent);
    }

    /**
     * Build a tool definition for a synthetic tool (MCP, supervisor
     * delegation, …) that has no backing {@code @Tool} method. The caller is
     * responsible for the schema's correctness — for MCP tools it comes
     * straight from the server's {@code tools/list} response.
     *
     * <p>Idempotency defaults to {@code false}; see {@link #of(String, String,
     * Map, boolean, long, ToolInvoker, boolean)}.
     */
    public static ToolDefinition of(String name, String description, Map<String, Object> jsonSchema,
                                    boolean requiresApproval, long timeoutSeconds,
                                    ToolInvoker invoker) {
        return of(name, description, jsonSchema, requiresApproval, timeoutSeconds, invoker, false);
    }

    /**
     * Like {@link #of(String, String, Map, boolean, long, ToolInvoker)} but
     * with an explicit idempotency declaration for durable resume: pass
     * {@code true} only when re-executing this tool with identical arguments
     * is side-effect safe. When a run crashes between a tool's execution and
     * its journaling, resume re-executes the call if and only if the tool is
     * declared idempotent — otherwise resume aborts with
     * {@link dev.axiom.durable.DurableException} instead of risking a double
     * side effect.
     */
    public static ToolDefinition of(String name, String description, Map<String, Object> jsonSchema,
                                    boolean requiresApproval, long timeoutSeconds,
                                    ToolInvoker invoker, boolean idempotent) {
        return new ToolDefinition(name, description, jsonSchema, requiresApproval,
            timeoutSeconds, invoker, null, idempotent);
    }

    public String name() { return name; }
    public String description() { return description; }
    /** JSON Schema of the tool's parameters, as the LLM sees it. */
    public Map<String, Object> jsonSchema() { return jsonSchema; }
    public boolean requiresApproval() { return requiresApproval; }
    public long timeoutSeconds() { return timeoutSeconds; }
    /**
     * True when the tool author declared re-execution with identical
     * arguments side-effect safe. Used by durable resume for the
     * crash-window decision. Default {@code false} — never inferred.
     */
    public boolean idempotent() { return idempotent; }

    /** How this tool executes. Never null. */
    public ToolInvoker invoker() { return invoker; }

    /**
     * The underlying Java method for {@code @Tool} methods — used by the
     * sandbox and tracers. Null for synthetic tools (MCP, delegates).
     */
    public Method method() { return method; }

    /** Render as an OpenAI-style function definition. */
    public Map<String, Object> toFunctionDefinition() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", name,
                "description", description,
                "parameters", jsonSchema
            )
        );
    }

    @Override
    public String toString() {
        return "ToolDefinition{name='%s', params=%s}".formatted(name, jsonSchema.get("properties"));
    }
}

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
    private final ToolInvoker invoker;
    /** Null for synthetic tools that have no backing Java method. */
    private final Method method;

    private ToolDefinition(String name, String description, Map<String, Object> jsonSchema,
                           boolean requiresApproval, long timeoutSeconds,
                           ToolInvoker invoker, Method method) {
        this.name = Objects.requireNonNull(name);
        this.description = Objects.requireNonNull(description);
        this.jsonSchema = Map.copyOf(jsonSchema);
        this.requiresApproval = requiresApproval;
        this.timeoutSeconds = timeoutSeconds;
        this.invoker = Objects.requireNonNull(invoker);
        this.method = method;
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
        return new ToolDefinition(name, description, jsonSchema, requiresApproval,
            timeoutSeconds, invoker, Objects.requireNonNull(method));
    }

    /**
     * Build a tool definition for a synthetic tool (MCP, supervisor
     * delegation, …) that has no backing {@code @Tool} method. The caller is
     * responsible for the schema's correctness — for MCP tools it comes
     * straight from the server's {@code tools/list} response.
     */
    public static ToolDefinition of(String name, String description, Map<String, Object> jsonSchema,
                                    boolean requiresApproval, long timeoutSeconds,
                                    ToolInvoker invoker) {
        return new ToolDefinition(name, description, jsonSchema, requiresApproval,
            timeoutSeconds, invoker, null);
    }

    public String name() { return name; }
    public String description() { return description; }
    /** JSON Schema of the tool's parameters, as the LLM sees it. */
    public Map<String, Object> jsonSchema() { return jsonSchema; }
    public boolean requiresApproval() { return requiresApproval; }
    public long timeoutSeconds() { return timeoutSeconds; }

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

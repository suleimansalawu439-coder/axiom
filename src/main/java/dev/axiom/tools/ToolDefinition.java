package dev.axiom.tools;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * A single tool exposed to the LLM: its metadata, its JSON schema, and a
 * handle to invoke it. Instances are built by {@link ToolRegistry}; the
 * annotation processor additionally validates the schema at compile time.
 */
public final class ToolDefinition {
    private final String name;
    private final String description;
    private final Map<String, Object> jsonSchema;
    private final boolean requiresApproval;
    private final long timeoutSeconds;
    private final Object target;
    private final Method method;

    ToolDefinition(String name, String description, Map<String, Object> jsonSchema,
                   boolean requiresApproval, long timeoutSeconds,
                   Object target, Method method) {
        this.name = name;
        this.description = description;
        this.jsonSchema = Map.copyOf(jsonSchema);
        this.requiresApproval = requiresApproval;
        this.timeoutSeconds = timeoutSeconds;
        this.target = target;
        this.method = method;
        this.method.setAccessible(true);
    }

    public String name() { return name; }
    public String description() { return description; }
    /** JSON Schema of the tool's parameters, as the LLM sees it. */
    public Map<String, Object> jsonSchema() { return jsonSchema; }
    public boolean requiresApproval() { return requiresApproval; }
    public long timeoutSeconds() { return timeoutSeconds; }

    /** The underlying Java method — used by the sandbox and tracers. */
    public Method method() { return method; }

    /** The object the method is invoked on. */
    Object target() { return target; }

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

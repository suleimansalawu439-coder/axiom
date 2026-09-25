package dev.axiom.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * Scans objects for {@link Tool}-annotated methods and exposes them as
 * {@link ToolDefinition}s. Argument JSON from the LLM is coerced to Java
 * types via Jackson — a coercion failure raises a descriptive error that is
 * fed back to the LLM so it can self-correct, instead of crashing the run.
 *
 * <p>Synthetic tools (MCP server tools, supervisor delegate tools) can be
 * added directly via {@link #register(ToolDefinition)}; they share the same
 * invocation, timeout, and approval machinery as annotated tools.
 */
public final class ToolRegistry {
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();
    /** Tool name -> fully-qualified holder class, for annotated tools only. */
    private final Map<String, String> toolHolderClasses = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Register all {@code @Tool} methods on the given instance. */
    public ToolRegistry register(Object toolHolder) {
        // Single source of truth: prefer the schemas the annotation processor
        // generated at compile time (META-INF/axiom/tools/<binary-name>.json).
        // Reflection is only a fallback for holders compiled without the
        // processor (e.g. ad-hoc scripts); SchemaDriftTest guards the
        // fallback against diverging from the compile-time mapping.
        Map<String, Map<String, Object>> generated =
            loadGeneratedSchemas(toolHolder.getClass());
        for (Method method : toolHolder.getClass().getMethods()) {
            Tool ann = method.getAnnotation(Tool.class);
            if (ann == null) continue;
            String name = ann.name().isBlank() ? method.getName() : ann.name();
            Map<String, Object> schema = generated.get(name);
            if (schema == null) {
                schema = buildSchema(method);
            }
            register(ToolDefinition.forMethod(
                name, ann.description(), schema,
                ann.requiresApproval(), ann.timeoutSeconds(),
                reflectiveInvoker(toolHolder, method, name), method, ann.idempotent()));
            toolHolderClasses.put(name, toolHolder.getClass().getName());
        }
        return this;
    }

    /** Register a synthetic tool definition (MCP, supervisor delegates, …). */
    public ToolRegistry register(ToolDefinition definition) {
        if (tools.containsKey(definition.name())) {
            throw new IllegalStateException("Duplicate tool name: " + definition.name());
        }
        tools.put(definition.name(), definition);
        return this;
    }

    public Collection<ToolDefinition> all() {
        return Collections.unmodifiableCollection(tools.values());
    }

    public Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Holder class names for {@code @Tool}-registered tools, keyed by tool
     * name. Used by durable resume to rebuild the registry from the journal
     * without the original holder instances. Synthetic tools (MCP, delegates)
     * have no entry.
     */
    public Map<String, String> toolHolderClasses() {
        return Collections.unmodifiableMap(toolHolderClasses);
    }

    /** Invoke a tool by name with raw JSON arguments from the LLM. */
    public Object invoke(String name, Map<String, Object> arguments) {
        ToolDefinition def = tools.get(name);
        if (def == null) {
            throw new ToolInvocationException("Unknown tool: '" + name + "'. Available: " + tools.keySet());
        }
        try {
            return def.invoker().invoke(arguments);
        } catch (ToolInvocationException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolInvocationException(
                "Tool '%s' failed: %s".formatted(name, rootCause(e).getMessage()), e);
        }
    }

    /**
     * Build the reflective invoker for an annotated method: coerces each JSON
     * argument to the declared Java parameter type.
     */
    private ToolInvoker reflectiveInvoker(Object toolHolder, Method method, String toolName) {
        return arguments -> {
            Parameter[] params = method.getParameters();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                String paramName = params[i].getName();
                ToolParam tp = params[i].getAnnotation(ToolParam.class);
                Object raw = arguments.get(paramName);
                if (raw == null && tp != null && tp.required()) {
                    throw new ToolInvocationException(
                        "Missing required argument '%s' for tool '%s'".formatted(paramName, toolName));
                }
                args[i] = raw == null ? null : mapper.convertValue(raw, params[i].getType());
            }
            // Note: requires -parameters at compile time for real param names;
            // the annotation processor enforces this (see ToolProcessor).
            try {
                return method.invoke(toolHolder, args);
            } catch (Exception e) {
                throw new ToolInvocationException(
                    "Tool '%s' failed: %s".formatted(toolName, rootCause(e).getMessage()), e);
            }
        };
    }

    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t;
    }

    // ------------------------------------------------------------------
    // JSON Schema generation (reflective fallback)
    // ------------------------------------------------------------------

    /**
     * Load the compile-time schema artifact for a tool holder, if the
     * annotation processor generated one. Returns tool name -&gt; parameters
     * schema; empty when the holder was compiled without the processor (or
     * the artifact is unreadable), in which case registration falls back to
     * {@link #buildSchema(Method)}.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> loadGeneratedSchemas(Class<?> holderClass) {
        String resource = "/META-INF/axiom/tools/"
            + holderClass.getName().replace('.', '/') + ".json";
        try (var in = holderClass.getResourceAsStream(resource)) {
            if (in == null) return Map.of();
            Map<String, Object> root = mapper.readValue(in, Map.class);
            // Guard against a stale artifact from a different class that
            // happened to share a resource path.
            if (!holderClass.getName().equals(root.get("class"))) return Map.of();
            Object tools = root.get("tools");
            if (!(tools instanceof List<?> list)) return Map.of();
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            for (Object t : list) {
                if (t instanceof Map<?, ?> m) {
                    Object n = m.get("name");
                    Object p = m.get("parameters");
                    if (n instanceof String s && p instanceof Map<?, ?> pm) {
                        out.put(s, (Map<String, Object>) pm);
                    }
                }
            }
            return out;
        } catch (Exception e) {
            return Map.of(); // corrupt artifact: fall back, never fail registration
        }
    }

    private Map<String, Object> buildSchema(Method method) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            ToolParam tp = p.getAnnotation(ToolParam.class);
            String desc = tp != null ? tp.description() : "";
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", jsonType(p.getType()));
            if (!desc.isBlank()) prop.put("description", desc);
            if (tp != null && !tp.example().isBlank()) prop.put("example", tp.example());
            properties.put(p.getName(), prop);
            if (tp == null || tp.required()) required.add(p.getName());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * Reflective fallback schema builder. Must produce exactly what the
     * annotation processor generates at compile time — {@code SchemaDriftTest}
     * enforces this over a matrix of types. Any {@code Collection} is an
     * array (not just {@code List}); {@code short}/{@code byte}/
     * {@code BigDecimal} map like their wider siblings.
     */
    static String jsonType(Class<?> c) {
        if (c == String.class || c == char.class || c == Character.class) return "string";
        if (c == boolean.class || c == Boolean.class) return "boolean";
        if (c == byte.class || c == Byte.class || c == short.class || c == Short.class
                || c == int.class || c == Integer.class
                || c == long.class || c == Long.class) return "integer";
        if (c == float.class || c == Float.class || c == double.class || c == Double.class
                || c == java.math.BigDecimal.class) return "number";
        if (java.util.Collection.class.isAssignableFrom(c) || c.isArray()) return "array";
        return "object";
    }
}

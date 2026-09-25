package dev.axiom.output;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.*;
import java.util.*;

/**
 * Generates JSON Schema from a POJO via reflection, so
 * {@code agent.runFor(task, MyRecord.class)} can demand structured output
 * that deserializes into a compile-time type. Nested records/POJOs,
 * enums, {@code Optional}, lists, and maps are supported.
 */
public final class OutputSchema {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OutputSchema() {}

    /** Generate a JSON Schema string for the given type. */
    public static String generate(Class<?> type) {
        try {
            return MAPPER.writeValueAsString(schemaFor(type, new HashSet<>()));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot generate schema for " + type.getName(), e);
        }
    }

    private static Map<String, Object> schemaFor(Type type, Set<String> seen) {
        Class<?> raw = rawClass(type);
        if (raw == String.class || raw == Character.class || raw == char.class) {
            return Map.of("type", "string");
        }
        if (raw == Boolean.class || raw == boolean.class) {
            return Map.of("type", "boolean");
        }
        if (raw == Integer.class || raw == int.class || raw == Long.class || raw == long.class
                || raw == Short.class || raw == short.class) {
            return Map.of("type", "integer");
        }
        if (raw == Double.class || raw == double.class || raw == Float.class || raw == float.class) {
            return Map.of("type", "number");
        }
        if (raw.isEnum()) {
            List<String> values = new ArrayList<>();
            for (Object c : raw.getEnumConstants()) values.add(c.toString());
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "string");
            s.put("enum", values);
            return s;
        }
        if (raw == Optional.class) {
            // Optional<T> -> schema of T (absence handled by 'required')
            Type inner = type instanceof ParameterizedType p
                ? p.getActualTypeArguments()[0] : Object.class;
            return schemaFor(inner, seen);
        }
        if (List.class.isAssignableFrom(raw) || Set.class.isAssignableFrom(raw) || raw.isArray()) {
            Type itemType = Object.class;
            if (type instanceof ParameterizedType p && p.getActualTypeArguments().length > 0) {
                itemType = p.getActualTypeArguments()[0];
            } else if (raw.isArray()) {
                itemType = raw.getComponentType();
            }
            return Map.of("type", "array", "items", schemaFor(itemType, seen));
        }
        if (Map.class.isAssignableFrom(raw)) {
            return Map.of("type", "object");
        }
        // POJO / record -> object with properties.
        if (!seen.add(raw.getName())) {
            return Map.of("type", "object"); // break recursion
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Field f : allFields(raw)) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) continue;
            if (f.isSynthetic()) continue;
            boolean optional = f.getType() == Optional.class;
            properties.put(f.getName(), schemaFor(f.getGenericType(), seen));
            if (!optional) required.add(f.getName());
        }
        seen.remove(raw.getName());
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        s.put("required", required);
        s.put("additionalProperties", false);
        return s;
    }

    private static List<Field> allFields(Class<?> c) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            fields.addAll(Arrays.asList(k.getDeclaredFields()));
        }
        return fields;
    }

    private static Class<?> rawClass(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        return Object.class;
    }
}

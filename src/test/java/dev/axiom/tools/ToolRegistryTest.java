package dev.axiom.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    static class SampleTools {
        @Tool(description = "Adds two numbers")
        public int add(
                @ToolParam(description = "First number") int a,
                @ToolParam(description = "Second number") int b) {
            return a + b;
        }

        @Tool(description = "Greets someone", name = "greet_user")
        public String greet(@ToolParam(description = "Name") String name) {
            return "Hello, " + name;
        }

        @Tool(description = "Echo with optional shout")
        public String echo(
                @ToolParam(description = "Text") String text,
                @ToolParam(description = "Uppercase it", required = false) boolean shout) {
            return shout ? text.toUpperCase() : text;
        }
    }

    private ToolRegistry registry() {
        return new ToolRegistry().register(new SampleTools());
    }

    @Test
    void registersAnnotatedMethods() {
        var tools = registry().all();
        assertEquals(3, tools.size());
        assertTrue(registry().find("add").isPresent());
        assertTrue(registry().find("greet_user").isPresent());
        assertTrue(registry().find("greet").isEmpty()); // renamed
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaMapsJavaTypesToJson() {
        var def = registry().find("add").orElseThrow();
        Map<String, Object> props = (Map<String, Object>) def.jsonSchema().get("properties");
        assertEquals("integer", ((Map<String, Object>) props.get("a")).get("type"));
        assertEquals(List.of("a", "b"), def.jsonSchema().get("required"));
        assertEquals(false, def.jsonSchema().get("additionalProperties"));
    }

    @Test
    void optionalParamsNotRequired() {
        var def = registry().find("echo").orElseThrow();
        assertEquals(List.of("text"), def.jsonSchema().get("required"));
    }

    @Test
    void invokesWithTypeCoercion() {
        // LLM sends numbers as JSON; Jackson coerces to int.
        Object result = registry().invoke("add", Map.of("a", 2, "b", 3));
        assertEquals(5, result);
    }

    @Test
    void missingRequiredArgumentFailsDescriptively() {
        var ex = assertThrows(ToolInvocationException.class,
            () -> registry().invoke("add", Map.of("a", 1)));
        assertTrue(ex.getMessage().contains("b"));
    }

    @Test
    void unknownToolFailsDescriptively() {
        var ex = assertThrows(ToolInvocationException.class,
            () -> registry().invoke("nope", Map.of()));
        assertTrue(ex.getMessage().contains("Unknown tool"));
    }

    @Test
    void functionDefinitionMatchesOpenAiShape() {
        var def = registry().find("greet_user").orElseThrow().toFunctionDefinition();
        assertEquals("function", def.get("type"));
    }

    @Test
    void duplicateToolNamesRejected() {
        class Dup {
            @Tool(description = "x") public void add(@ToolParam(description = "x") String x) {}
        }
        var r = new ToolRegistry().register(new SampleTools());
        assertThrows(IllegalStateException.class, () -> r.register(new Dup()));
    }

    @Test
    void invokeUnwrapsArgumentsWrapper() {
        // 2026-10-04: Qwen wrapped args as {"arguments": {...}} — 3 GAIA
        // tasks destroyed (5d0080cb, c365c1c7, cf106601). Unwrap, don't fail.
        var result = registry().invoke("greet_user",
            Map.of("arguments", Map.of("name", "Hamis")));
        assertEquals("Hello, Hamis", result);
    }
}

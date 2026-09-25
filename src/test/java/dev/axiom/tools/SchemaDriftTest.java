package dev.axiom.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the compile-time/runtime schema contract: the annotation processor
 * generates {@code META-INF/axiom/tools/*.json} at build time and
 * {@link ToolRegistry} reads that artifact as the single source of truth.
 * These tests pin the type mapping on both sides so they can never drift
 * apart again (e.g. {@code Set} mapping to {@code array} at compile time
 * but {@code object} at runtime).
 */
class SchemaDriftTest {

    public record Pojo(String name, int age) {}
    public enum Color { RED, BLUE }

    public static class MatrixTools {
        @Tool(name = "matrix", description = "type matrix probe")
        public String matrix(
                @ToolParam(description = "a string") String s,
                @ToolParam(description = "an int") int i,
                @ToolParam(description = "an Integer") Integer integer,
                @ToolParam(description = "a long") long l,
                @ToolParam(description = "a short") short sh,
                @ToolParam(description = "a byte") byte b,
                @ToolParam(description = "a double") double d,
                @ToolParam(description = "a float") float f,
                @ToolParam(description = "a boolean") boolean bool,
                @ToolParam(description = "a char") char c,
                @ToolParam(description = "a BigDecimal") BigDecimal bd,
                @ToolParam(description = "a list") List<String> list,
                @ToolParam(description = "a set") Set<String> set,
                @ToolParam(description = "a map") Map<String, Object> map,
                @ToolParam(description = "a string array") String[] arr,
                @ToolParam(description = "an int array") int[] iarr,
                @ToolParam(description = "a pojo") Pojo pojo,
                @ToolParam(description = "an enum") Color color,
                @ToolParam(description = "a uuid") UUID uuid) {
            return "ok";
        }
    }

    private static ToolDefinition matrixDef() {
        return new ToolRegistry().register(new MatrixTools())
            .find("matrix").orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> artifactParameters() throws Exception {
        // Resource path mirrors the runtime convention: binary name with '.' -> '/'.
        String resource = "/META-INF/axiom/tools/"
            + MatrixTools.class.getName().replace('.', '/') + ".json";
        try (var in = MatrixTools.class.getResourceAsStream(resource)) {
            assertNotNull(in, "annotation processor must generate the schema artifact");
            Map<String, Object> root = new ObjectMapper().readValue(in, Map.class);
            List<Map<String, Object>> tools = (List<Map<String, Object>>) root.get("tools");
            assertEquals(1, tools.size());
            assertEquals("matrix", tools.get(0).get("name"));
            return (Map<String, Object>) tools.get(0).get("parameters");
        }
    }

    @Test
    void runtimeSchemaEqualsCompileTimeArtifact() throws Exception {
        // ToolRegistry must serve the artifact verbatim — no re-derivation.
        assertEquals(artifactParameters(), matrixDef().jsonSchema());
    }

    @Test
    @SuppressWarnings("unchecked")
    void typeMappingsArePinned() {
        Map<String, Object> props =
            (Map<String, Object>) matrixDef().jsonSchema().get("properties");
        assertType(props, "s", "string");
        assertType(props, "i", "integer");
        assertType(props, "integer", "integer");
        assertType(props, "l", "integer");
        assertType(props, "sh", "integer");
        assertType(props, "b", "integer");
        assertType(props, "d", "number");
        assertType(props, "f", "number");
        assertType(props, "bool", "boolean");
        assertType(props, "c", "string");
        assertType(props, "bd", "number");
        assertType(props, "list", "array");
        assertType(props, "set", "array");
        assertType(props, "map", "object");
        assertType(props, "arr", "array");
        assertType(props, "iarr", "array");
        assertType(props, "pojo", "object");
        assertType(props, "color", "object");
        assertType(props, "uuid", "object");
    }

    @Test
    void idempotentFlagFlowsFromAnnotation() {
        var registry = new ToolRegistry().register(new Object() {
            @Tool(description = "safe reread", idempotent = true)
            public String reread(@ToolParam(description = "x") String x) { return x; }
            @Tool(description = "unsafe write")
            public String write(@ToolParam(description = "x") String x) { return x; }
        });
        assertTrue(registry.find("reread").orElseThrow().idempotent());
        assertFalse(registry.find("write").orElseThrow().idempotent());
    }

    // ------------------------------------------------------------------
    // Compile-time failure tests: drive the real annotation processor with
    // the in-memory Java compiler and assert the build breaks.
    // ------------------------------------------------------------------

    @Test
    void duplicateToolNamesFailCompilation(@TempDir Path out) {
        String holder = """
            package duptest;
            import dev.axiom.tools.*;
            public class Holder%s {
                @Tool(name = "same_name", description = "d")
                public String m(@ToolParam(description = "x") String x) { return x; }
            }
            """;
        var diagnostics = compile(out,
            source("duptest.HolderA", holder.formatted("A")),
            source("duptest.HolderB", holder.formatted("B")));
        assertHasError(diagnostics, "already used");
    }

    @Test
    void unreadablePojoParameterFailsCompilation(@TempDir Path out) {
        String src = """
            package pojo;
            import dev.axiom.tools.*;
            public class BadHolder {
                public static class NoCtor { // no no-arg ctor, not a record
                    public NoCtor(String x) {}
                }
                @Tool(description = "d")
                public String m(@ToolParam(description = "x") NoCtor x) { return "ok"; }
            }
            """;
        var diagnostics = compile(out, source("pojo.BadHolder", src));
        assertHasError(diagnostics, "not Jackson-deserializable");
    }

    @Test
    void interfaceParameterFailsCompilation(@TempDir Path out) {
        String src = """
            package iface;
            import dev.axiom.tools.*;
            public class IfaceHolder {
                @Tool(description = "d")
                public String m(@ToolParam(description = "x") Runnable x) { return "ok"; }
            }
            """;
        var diagnostics = compile(out, source("iface.IfaceHolder", src));
        assertHasError(diagnostics, "is an interface");
    }

    private static List<javax.tools.Diagnostic<? extends JavaFileObject>> compile(
            Path out, JavaFileObject... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests need a JDK, not a JRE");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        // The JUnit console launcher does not propagate --class-path into
        // java.class.path, so derive the classpath from our own location.
        String cp = axiomClasspath();
        boolean ok = compiler.getTask(null, null, diagnostics,
            List.of("-cp", cp, "-processorpath", cp,
                    "-d", out.toString(), "-parameters"),
            null, List.of(sources)).call();
        assertFalse(ok, "expected compilation to fail, but it succeeded");
        return diagnostics.getDiagnostics();
    }

    private static String axiomClasspath() {
        try {
            Path classes = Path.of(ToolRegistry.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            Path testClasses = Path.of(SchemaDriftTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            Path lib = classes.getParent().getParent().resolve("lib");
            return String.join(java.io.File.pathSeparator,
                testClasses.toString(), classes.toString(),
                lib.resolve("jackson-databind-2.17.2.jar").toString(),
                lib.resolve("jackson-core-2.17.2.jar").toString(),
                lib.resolve("jackson-annotations-2.17.2.jar").toString());
        } catch (Exception e) {
            throw new IllegalStateException("cannot locate axiom classpath", e);
        }
    }

    private static void assertHasError(
            List<javax.tools.Diagnostic<? extends JavaFileObject>> diagnostics,
            String fragment) {
        assertTrue(diagnostics.stream()
                .anyMatch(d -> ("" + d.getMessage(null)).contains(fragment)),
            () -> "expected an error containing '" + fragment + "' but got: " + diagnostics);
    }

    private static JavaFileObject source(String className, String code) {
        return new SimpleJavaFileObject(
            URI.create("string:///" + className.replace('.', '/') + ".java"),
            JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return code; }
        };
    }

    @Test
    void reflectiveFallbackMappingMatches() {
        // The fallback path (no processor) must agree with compile time.
        assertEquals("array", ToolRegistry.jsonType(Set.class));
        assertEquals("array", ToolRegistry.jsonType(List.class));
        assertEquals("integer", ToolRegistry.jsonType(short.class));
        assertEquals("integer", ToolRegistry.jsonType(Byte.class));
        assertEquals("number", ToolRegistry.jsonType(BigDecimal.class));
        assertEquals("object", ToolRegistry.jsonType(Map.class));
        assertEquals("object", ToolRegistry.jsonType(Pojo.class));
    }

    @SuppressWarnings("unchecked")
    private static void assertType(Map<String, Object> props, String name, String want) {
        Map<String, Object> prop = (Map<String, Object>) props.get(name);
        assertNotNull(prop, "missing property " + name);
        assertEquals(want, prop.get("type"), "wrong type for " + name);
    }
}

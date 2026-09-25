package dev.axiom.capabilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compile-time capability policy, tested by driving {@code javac} with the
 * Axiom {@code ToolProcessor} in-process: dead policies must fail the build
 * with a clear error, satisfiable policies must compile and emit the runtime
 * policy artifact.
 */
class CapabilityProcessorTest {

    @Test
    void deadPolicyFailsCompilation(@TempDir Path out) {
        // A DESTRUCTIVE tool requiring BACKUP with no tool ensuring BACKUP:
        // the tool could never run, so the build fails here, not at 2am.
        String src = """
            package capp;
            import dev.axiom.tools.Tool;
            import dev.axiom.capabilities.*;
            public class DeadHolder {
                @Tool(description = "Delete snapshots older than 30 days",
                      capabilities = {Capability.DESTRUCTIVE})
                @Requires(Capability.BACKUP)
                public String deleteOldSnapshots() { return "deleted"; }
            }
            """;
        var diagnostics = compileExpectFailure(out, source("capp.DeadHolder", src));
        String errors = errorsText(diagnostics);
        assertTrue(errors.contains("requires session token BACKUP"),
            () -> "expected the missing-token error in:\n" + errors);
        assertTrue(errors.contains("no @Tool in this compilation ensures it"),
            () -> "expected the dead-policy error in:\n" + errors);
        assertTrue(errors.contains("deleteOldSnapshots"),
            () -> "expected the tool name in:\n" + errors);
    }

    @Test
    void satisfiablePolicyCompilesAndEmitsArtifact(@TempDir Path out) throws Exception {
        String src = """
            package capp;
            import dev.axiom.tools.Tool;
            import dev.axiom.capabilities.*;
            public class OpsHolder {
                @Tool(description = "Snapshot the database to cold storage",
                      capabilities = {Capability.READ, Capability.WRITE})
                @Ensures(Capability.BACKUP)
                public String backupDatabase() { return "ok"; }

                @Tool(description = "Delete snapshots older than 30 days",
                      capabilities = {Capability.DESTRUCTIVE})
                @Requires(Capability.BACKUP)
                public String deleteOldSnapshots() { return "ok"; }
            }
            """;
        var diagnostics = compileExpectSuccess(out, source("capp.OpsHolder", src));
        Path artifact = out.resolve("META-INF/axiom/policy/capp/OpsHolder.json");
        assertTrue(Files.isRegularFile(artifact),
            () -> "policy artifact not emitted; diagnostics:\n" + diagnostics);
        String json = Files.readString(artifact, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"class\": \"capp.OpsHolder\""), () -> json);
        assertTrue(json.contains("\"name\":\"deleteOldSnapshots\""), () -> json);
        assertTrue(json.contains("\"capabilities\":[\"DESTRUCTIVE\"]"), () -> json);
        assertTrue(json.contains("\"requires\":[\"BACKUP\"]"), () -> json);
        assertTrue(json.contains("\"name\":\"backupDatabase\""), () -> json);
        assertTrue(json.contains("\"ensures\":[\"BACKUP\"]"), () -> json);
    }

    @Test
    void effectCapabilityInRequiresFailsCompilation(@TempDir Path out) {
        // @Requires takes session tokens, not effects: WRITE here is an
        // authoring mistake and fails the build instead of meaning nothing.
        String src = """
            package capp;
            import dev.axiom.tools.Tool;
            import dev.axiom.capabilities.*;
            public class SloppyHolder {
                @Tool(description = "Snapshot the database")
                @Ensures(Capability.BACKUP)
                public String backupDatabase() { return "ok"; }

                @Tool(description = "Read a value")
                @Requires(Capability.WRITE)
                public String readValue() { return "v"; }
            }
            """;
        var diagnostics = compileExpectFailure(out, source("capp.SloppyHolder", src));
        String errors = errorsText(diagnostics);
        assertTrue(errors.contains("not a session token"),
            () -> "expected the token/effect confusion error in:\n" + errors);
        assertTrue(errors.contains("@Requires"), () -> errors);
    }

    // ------------------------------------------------------------------
    // javac driving (same approach as SchemaDriftTest)
    // ------------------------------------------------------------------

    private static List<Diagnostic<? extends JavaFileObject>> compileExpectFailure(
            Path out, JavaFileObject... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests need a JDK, not a JRE");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        String cp = axiomClasspath();
        boolean ok = compiler.getTask(null, null, diagnostics,
            List.of("-cp", cp, "-processorpath", cp,
                    "-d", out.toString(), "-parameters"),
            null, List.of(sources)).call();
        assertFalse(ok, "expected compilation to fail, but it succeeded");
        return diagnostics.getDiagnostics();
    }

    private static List<Diagnostic<? extends JavaFileObject>> compileExpectSuccess(
            Path out, JavaFileObject... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests need a JDK, not a JRE");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        String cp = axiomClasspath();
        boolean ok = compiler.getTask(null, null, diagnostics,
            List.of("-cp", cp, "-processorpath", cp,
                    "-d", out.toString(), "-parameters"),
            null, List.of(sources)).call();
        assertTrue(ok, () -> "expected compilation to succeed, but it failed:\n"
            + errorsText(diagnostics.getDiagnostics()));
        return diagnostics.getDiagnostics();
    }

    private static String axiomClasspath() {
        try {
            Path classes = Path.of(Capability.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            Path testClasses = Path.of(CapabilityProcessorTest.class.getProtectionDomain()
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

    private static String errorsText(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        StringBuilder sb = new StringBuilder();
        for (var d : diagnostics) {
            sb.append(d.getKind()).append(": ").append(d.getMessage(null)).append('\n');
        }
        return sb.toString();
    }

    private static JavaFileObject source(String className, String code) {
        return new SimpleJavaFileObject(
            URI.create("string:///" + className.replace('.', '/') + ".java"),
            JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return code; }
        };
    }
}

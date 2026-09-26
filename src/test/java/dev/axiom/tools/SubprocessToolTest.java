package dev.axiom.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SubprocessTool} sandbox tests. Linux-only (relies on
 * {@code echo}, {@code ls}, {@code env}, {@code sleep}).
 */
class SubprocessToolTest {

    @Test
    void runsCommandAndCapturesOutput(@TempDir Path root) {
        var tool = SubprocessTool.builder(root).build();
        SubprocessTool.ExecResult r = tool.run(List.of("echo", "hello"));
        assertTrue(r.succeeded());
        assertEquals(0, r.exitCode());
        assertEquals("hello\n", r.stdout());
        assertFalse(r.timedOut());
    }

    @Test
    void nonZeroExitIsReportedNotThrown(@TempDir Path root) {
        var tool = SubprocessTool.builder(root).build();
        SubprocessTool.ExecResult r = tool.run(List.of("ls", "/definitely/not/here-axiom"));
        assertFalse(r.succeeded());
        assertNotEquals(0, r.exitCode());
        assertFalse(r.stderr().isBlank());
    }

    @Test
    void timeoutKillsTheProcess(@TempDir Path root) {
        var tool = SubprocessTool.builder(root).timeout(Duration.ofSeconds(1)).build();
        SubprocessTool.ExecResult r = tool.run(List.of("sleep", "30"));
        assertTrue(r.timedOut());
    }

    @Test
    void environmentIsScrubbedToAllowlist(@TempDir Path root) {
        var tool = SubprocessTool.builder(root).envAllowlist(Set.of()).build();
        SubprocessTool.ExecResult r = tool.run(List.of("env"));
        assertTrue(r.succeeded());
        assertFalse(r.stdout().contains("PATH="),
            "empty allowlist must not leak PATH, got: " + r.stdout());
    }

    @Test
    void defaultEnvKeepsPath(@TempDir Path root) {
        var tool = SubprocessTool.builder(root).build();
        SubprocessTool.ExecResult r = tool.run(List.of("env"));
        assertTrue(r.stdout().contains("PATH="));
    }

    @Test
    void pathTraversalIsRejected(@TempDir Path root) {
        var tool = SubprocessTool.builder(root).build();
        assertThrows(SecurityException.class, () -> tool.run(List.of("../bin/echo", "hi")));
        assertThrows(SecurityException.class, () -> tool.run(List.of("/bin/echo", "hi")));
    }

    @Test
    void commandAllowlistIsEnforced(@TempDir Path root) {
        var tool = SubprocessTool.builder(root).allowCommands("echo").build();
        assertTrue(tool.run(List.of("echo", "ok")).succeeded());
        SecurityException ex = assertThrows(SecurityException.class,
            () -> tool.run(List.of("ls", "/")));
        assertTrue(ex.getMessage().contains("allowlist"));
    }

    @Test
    void emptyCommandRejected(@TempDir Path root) {
        var tool = SubprocessTool.builder(root).build();
        assertThrows(IllegalArgumentException.class, () -> tool.run(List.of()));
    }

    @Test
    void registersAsAnnotatedTool(@TempDir Path root) {
        var tool = SubprocessTool.builder(root).allowCommands("echo").build();
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        ToolDefinition def = registry.find("run").orElseThrow();
        assertTrue(def.requiresApproval());
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) def.jsonSchema().get("properties");
        assertEquals("array", ((Map<String, Object>) props.get("command")).get("type"));

        // Invoked through the registry like any other tool.
        Object out = registry.invoke("run", Map.of("command", List.of("echo", "via-registry")));
        assertTrue(out.toString().contains("via-registry"));
    }

    // --- Windows Python install-manager shim detection (no real subprocess) ---

    @Test
    void pythonShimSignaturesAreDetected() {
        // Signatures observed in the GAIA L1 live run (receipt 2026-09-26):
        assertEquals("failed to read unmanaged installs",
            SubprocessTool.findPythonShimSignature(
                "[WARNING] Failed to read unmanaged installs: expected str, bytes or os.PathLike object, not NoneType\r\n"));
        assertEquals("waiting for other operations to complete",
            SubprocessTool.findPythonShimSignature("Waiting for other operations to complete. . .\r\n"));
        assertEquals("python install manager",
            SubprocessTool.findPythonShimSignature(
                "Python install manager was successfully updated to 26.3.\r\n"));
        // Signatures from the brief:
        assertEquals("python was not found",
            SubprocessTool.findPythonShimSignature("Python was not found; run without arguments to install from the Microsoft Store"));
        assertEquals("ms-windows-store",
            SubprocessTool.findPythonShimSignature("start ms-windows-store://pdp/?ProductId=9NRWMJP3717K"));
        assertEquals("app execution alias",
            SubprocessTool.findPythonShimSignature("This file is an App execution alias; it cannot be run directly"));
    }

    @Test
    void shimDetectionIsCaseInsensitive() {
        assertNotNull(SubprocessTool.findPythonShimSignature("PYTHON INSTALL MANAGER"));
        assertNotNull(SubprocessTool.findPythonShimSignature("Python Was Not Found"));
    }

    @Test
    void ordinaryOutputIsNotAShim() {
        assertNull(SubprocessTool.findPythonShimSignature("hello world\n42\n"));
        assertNull(SubprocessTool.findPythonShimSignature(""));
        assertNull(SubprocessTool.findPythonShimSignature(null));
        // A script merely mentioning "python" is not the shim.
        assertNull(SubprocessTool.findPythonShimSignature("python version check passed"));
    }

    @Test
    void pythonExecutableNamesRecognized() {
        assertTrue(SubprocessTool.isPythonExecutable("python3"));
        assertTrue(SubprocessTool.isPythonExecutable("python"));
        assertTrue(SubprocessTool.isPythonExecutable("python3.exe"));
        assertTrue(SubprocessTool.isPythonExecutable("python.exe"));
        assertTrue(SubprocessTool.isPythonExecutable("pythonw"));
        assertFalse(SubprocessTool.isPythonExecutable("echo"));
        assertFalse(SubprocessTool.isPythonExecutable("pypy"));
        assertFalse(SubprocessTool.isPythonExecutable(null));
    }

    @Test
    void shimDiagnosticTellsUserToInstallRealPython() {
        String d = SubprocessTool.pythonShimDiagnostic("python3", "python install manager");
        assertTrue(d.startsWith("ERROR:"), d);
        assertTrue(d.contains("python3"), d);
        assertTrue(d.contains("https://www.python.org/downloads/"), d);
        assertTrue(d.contains("python install manager"), d);
        assertTrue(d.contains("aborted"), d);
    }
}

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
}

package dev.axiom.mcp;

import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolInvocationException;
import dev.axiom.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end MCP tests against {@link FakeMcpServer}, spawned as a real
 * subprocess speaking JSON-RPC 2.0 over stdio.
 */
class McpClientTest {

    private static List<String> serverCommand(String... extraArgs) {
        var cmd = new java.util.ArrayList<String>();
        cmd.add(System.getProperty("java.home") + "/bin/java");
        cmd.add("-cp");
        cmd.add(childClasspath());
        cmd.add("dev.axiom.mcp.FakeMcpServer");
        cmd.addAll(List.of(extraArgs));
        return List.copyOf(cmd);
    }

    /**
     * Classpath for the spawned fake server. Derived from this test class's
     * own code source ({@code target/test-classes}), so it works no matter
     * how the test JVM was launched — under {@code java -jar} the
     * {@code java.class.path} system property is just the JUnit jar.
     */
    private static String childClasspath() {
        try {
            var testClasses = java.nio.file.Path.of(
                McpClientTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            var projectRoot = testClasses.getParent().getParent(); // target/test-classes -> project root
            var entries = new java.util.ArrayList<String>();
            entries.add(testClasses.toString());
            entries.add(projectRoot.resolve("target/classes").toString());
            try (var jars = java.nio.file.Files.list(projectRoot.resolve("lib"))) {
                jars.map(Object::toString)
                    .filter(p -> p.endsWith(".jar"))
                    .sorted()
                    .forEach(entries::add);
            }
            return String.join(java.io.File.pathSeparator, entries);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot locate project classpath for fake MCP server", e);
        }
    }

    @Test
    void fullSessionOverStdio() throws Exception {
        try (McpClient client = McpClient.spawn(serverCommand())) {
            McpServerInfo info = client.initialize();
            assertEquals("fake-mcp", info.name());
            assertEquals("0.0.1", info.version());

            List<McpTool> tools = client.listTools();
            assertEquals(2, tools.size());
            McpTool echo = tools.stream().filter(t -> t.name().equals("echo")).findFirst().orElseThrow();
            assertEquals("Echoes the message back", echo.description());
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) echo.inputSchema().get("properties");
            assertEquals("string", ((Map<String, Object>) props.get("message")).get("type"));

            McpToolResult ok = client.callTool("echo", Map.of("message", "hello"));
            assertFalse(ok.isError());
            assertEquals("ECHO:hello", ok.asText());

            List<McpResource> resources = client.listResources();
            assertEquals(1, resources.size());
            assertEquals("fake://greeting", resources.get(0).uri());

            List<McpResourceContent> contents = client.readResource("fake://greeting");
            assertEquals(1, contents.size());
            assertEquals("hello from fake resource", contents.get(0).text());

            client.ping(); // no exception
        }
    }

    @Test
    void clientAnswersServerInitiatedRootsList() throws Exception {
        // FakeMcpServer exits 42 unless the client answered roots/list correctly.
        McpClient client = McpClient.spawn(serverCommand());
        client.initialize();
        client.listTools();
        client.close();
        assertEquals(0, client.process().exitValue(),
            "server exited non-zero: it did not get a valid roots/list answer");
    }

    @Test
    void toolErrorSurfacesAsMcpException() {
        try (McpClient client = McpClient.spawn(serverCommand())) {
            client.initialize();
            // isError=true from the server becomes a Java exception…
            McpException boom = assertThrows(McpException.class,
                () -> client.callTool("boom", Map.of()));
            assertTrue(boom.getMessage().contains("kaboom"));
            // …as does an unknown tool name.
            assertThrows(McpException.class, () -> client.callTool("nope", Map.of()));
        }
    }

    @Test
    void requestTimeoutRaisesMcpException() {
        try (McpClient client = McpClient.spawn(serverCommand("hang"))
                .withResponseTimeout(Duration.ofSeconds(1))) {
            McpException ex = assertThrows(McpException.class, client::listTools);
            assertTrue(ex.getMessage().contains("timed out"),
                "expected a timeout message, got: " + ex.getMessage());
        }
    }

    @Test
    void mcpToolsBecomeTransparentAxiomTools() {
        try (McpClient client = McpClient.spawn(serverCommand())) {
            ToolRegistry registry = new ToolRegistry();
            McpTools.registerAll(registry, client);

            ToolDefinition echo = registry.find("echo").orElseThrow();
            assertTrue(echo.description().startsWith("[MCP]"));
            assertEquals("ECHO:hi", registry.invoke("echo", Map.of("message", "hi")));

            // Server-side tool errors surface as tool invocation failures,
            // which the ReAct loop feeds back to the LLM as observations.
            assertThrows(ToolInvocationException.class,
                () -> registry.invoke("boom", Map.of()));
        }
    }

    @Test
    void namePrefixAvoidsCollisions() {
        try (McpClient client = McpClient.spawn(serverCommand())) {
            ToolRegistry registry = new ToolRegistry();
            McpTools.registerAll(registry, client, "fake_", false);
            assertTrue(registry.find("fake_echo").isPresent());
            assertTrue(registry.find("echo").isEmpty());
            assertEquals("ECHO:yo", registry.invoke("fake_echo", Map.of("message", "yo")));
        }
    }

    @Test
    void resourceReaderTool() {
        try (McpClient client = McpClient.spawn(serverCommand())) {
            ToolRegistry registry = new ToolRegistry();
            registry.register(McpTools.resourceReaderTool(client));
            Object out = registry.invoke("mcp_read_resource", Map.of("uri", "fake://greeting"));
            assertEquals("hello from fake resource", out);
        }
    }
}

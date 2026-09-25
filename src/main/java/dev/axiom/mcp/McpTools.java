package dev.axiom.mcp;

import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bridges MCP servers into Axiom: every tool discovered via
 * {@code tools/list} becomes a {@link ToolDefinition} whose invoker calls
 * back into the MCP server via {@code tools/call}. A ReAct agent calls them
 * exactly like local {@code @Tool} methods — same timeouts, same approval
 * gates, same events.
 *
 * <pre>{@code
 * try (McpClient mcp = McpClient.spawn(List.of("npx", "-y", "@modelcontextprotocol/server-filesystem", "/data"))) {
 *     ToolRegistry registry = new ToolRegistry()
 *         .register(new MyLocalTools());
 *     McpTools.registerAll(registry, mcp);   // MCP tools join the registry
 *
 *     var agent = Axiom.agent().withModel("gpt-4o")
 *         .withTools()                       // start empty…
 *         .buildAgent();
 * }
 * }</pre>
 *
 * Note: the registry holds the definitions; the {@link McpClient} must stay
 * open for as long as the agent may call its tools.
 */
public final class McpTools {
    private McpTools() {}

    /**
     * Convert every tool on the server into an Axiom {@link ToolDefinition}.
     * The server's own {@code inputSchema} is used verbatim — what the server
     * advertised is what the LLM sees.
     */
    public static List<ToolDefinition> asToolDefinitions(McpClient client) {
        return asToolDefinitions(client, "", false);
    }

    /**
     * @param namePrefix  prepended to every tool name (e.g. {@code "fs_"}),
     *                    to avoid collisions when several servers are attached
     * @param requiresApproval if true, every MCP tool call needs human approval
     */
    public static List<ToolDefinition> asToolDefinitions(
            McpClient client, String namePrefix, boolean requiresApproval) {
        List<ToolDefinition> defs = new ArrayList<>();
        for (McpTool tool : client.listTools()) {
            String name = (namePrefix == null ? "" : namePrefix) + tool.name();
            String description = "[MCP] " + tool.description();
            Map<String, Object> schema = tool.inputSchema();
            defs.add(ToolDefinition.of(name, description, schema,
                requiresApproval, 120,
                args -> {
                    McpToolResult result = client.callTool(tool.name(), args);
                    return result.asText();
                }));
        }
        return List.copyOf(defs);
    }

    /** Register every tool on the server into the registry. */
    public static ToolRegistry registerAll(ToolRegistry registry, McpClient client) {
        return registerAll(registry, client, "", false);
    }

    public static ToolRegistry registerAll(
            ToolRegistry registry, McpClient client, String namePrefix, boolean requiresApproval) {
        for (ToolDefinition def : asToolDefinitions(client, namePrefix, requiresApproval)) {
            registry.register(def);
        }
        return registry;
    }

    /**
     * A synthetic {@code mcp_read_resource} tool backed by the server's
     * {@code resources/read}. Lets the agent pull file-like resources
     * (docs, configs, data) into context on demand.
     */
    public static ToolDefinition resourceReaderTool(McpClient client) {
        return resourceReaderTool(client, "mcp_read_resource", false);
    }

    public static ToolDefinition resourceReaderTool(
            McpClient client, String toolName, boolean requiresApproval) {
        Map<String, Object> schema = Map.of(
            "type", "object",
            "properties", Map.of(
                "uri", Map.of("type", "string",
                    "description", "Resource URI as advertised by resources/list")),
            "required", List.of("uri"),
            "additionalProperties", false);
        return ToolDefinition.of(toolName,
            "[MCP] Read a resource from the MCP server by URI.",
            schema, requiresApproval, 60,
            args -> {
                Object uri = args.get("uri");
                if (uri == null) throw new IllegalArgumentException("Missing required argument 'uri'");
                List<McpResourceContent> contents = client.readResource(String.valueOf(uri));
                StringBuilder sb = new StringBuilder();
                for (McpResourceContent c : contents) {
                    if (!sb.isEmpty()) sb.append("\n---\n");
                    sb.append(c.text() == null ? "[binary content]" : c.text());
                }
                return sb.toString();
            });
    }
}

package dev.axiom.mcp;

import java.util.Map;

/** A tool advertised by an MCP server via {@code tools/list}. */
public record McpTool(String name, String description, Map<String, Object> inputSchema) {
    public McpTool {
        inputSchema = Map.copyOf(inputSchema);
    }
}

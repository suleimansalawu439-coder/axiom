package dev.axiom.mcp;

/** A resource advertised by an MCP server via {@code resources/list}. */
public record McpResource(String uri, String name, String mimeType) {}

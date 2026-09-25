package dev.axiom.mcp;

/** Server identity returned by MCP {@code initialize}. */
public record McpServerInfo(String name, String version, String protocolVersion) {}

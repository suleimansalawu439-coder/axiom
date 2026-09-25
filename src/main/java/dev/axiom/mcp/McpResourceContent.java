package dev.axiom.mcp;

/** One entry of an MCP {@code resources/read} response. */
public record McpResourceContent(String uri, String mimeType, String text) {}

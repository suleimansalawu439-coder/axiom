package dev.axiom.mcp;

import java.util.List;

/** The outcome of an MCP {@code tools/call}. */
public record McpToolResult(List<McpContentBlock> content, boolean isError) {

    /** Render all content blocks as plain text for the LLM. */
    public String asText() {
        StringBuilder sb = new StringBuilder();
        for (McpContentBlock b : content) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(switch (b.type()) {
                case "text" -> b.text() == null ? "" : b.text();
                case "image" -> "[image: " + b.mimeType() + "]";
                case "resource" -> "[embedded resource: " + b.uri() + "]";
                default -> "[unsupported content block: " + b.type() + "]";
            });
        }
        return sb.toString();
    }

    /** A single MCP content block (text, image, or embedded resource). */
    public record McpContentBlock(String type, String text, String mimeType, String uri) {}
}

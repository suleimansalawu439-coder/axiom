package dev.axiom.llm;

import java.util.Map;

/** A tool call requested by the LLM. */
public record ToolCallRequest(String id, String name, Map<String, Object> arguments) {
}

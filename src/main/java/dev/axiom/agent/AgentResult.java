package dev.axiom.agent;

import dev.axiom.llm.ChatResponse;

/** The outcome of one {@code Agent.run()} call. */
public record AgentResult(String output, int iterations, int toolCallsMade,
                          ChatResponse.TokenUsage tokenUsage, boolean completed) {
}

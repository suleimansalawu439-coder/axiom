package dev.axiom.llm;

import java.util.List;

/** The LLM's response to one chat request: text, tool calls, and usage stats. */
public record ChatResponse(String content, List<ToolCallRequest> toolCalls, TokenUsage usage) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public record TokenUsage(long promptTokens, long completionTokens, long totalTokens) {
        public static TokenUsage empty() {
            return new TokenUsage(0, 0, 0);
        }

        public TokenUsage add(TokenUsage other) {
            return new TokenUsage(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                totalTokens + other.totalTokens);
        }
    }
}

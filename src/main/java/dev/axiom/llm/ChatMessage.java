package dev.axiom.llm;

import java.util.List;

/**
 * A single message in the conversation. For {@link ChatRole#TOOL} messages,
 * {@code toolCallId} links the result back to the requesting tool call.
 * Assistant messages may carry the {@code toolCalls} the model requested.
 */
public record ChatMessage(ChatRole role, String content, String toolCallId, String name,
                          List<ToolCallRequest> toolCalls) {

    public ChatMessage(ChatRole role, String content, String toolCallId, String name) {
        this(role, content, toolCallId, name, List.of());
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, content, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatRole.ASSISTANT, content, null, null);
    }

    public static ChatMessage assistantWithToolCalls(String content, List<ToolCallRequest> toolCalls) {
        return new ChatMessage(ChatRole.ASSISTANT, content, null, null,
            toolCalls == null ? List.of() : List.copyOf(toolCalls));
    }

    public static ChatMessage toolResult(String toolCallId, String name, String content) {
        return new ChatMessage(ChatRole.TOOL, content, toolCallId, name);
    }
}

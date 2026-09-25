package dev.axiom.llm;

import dev.axiom.tools.ToolDefinition;

import java.util.List;

/**
 * An {@link LlmClient} that can stream response tokens as they arrive.
 * The ReAct loop uses this automatically when available: tokens are emitted
 * as {@code AgentEvent.StreamToken} for live UIs, while the agent still
 * reasons and acts only on the complete turn.
 */
public interface StreamingLlmClient extends LlmClient {

    /** Receives model tokens in arrival order. */
    @FunctionalInterface
    interface TokenListener {
        void onToken(String token);
    }

    /**
     * Chat with streaming enabled. Implementations must call
     * {@code listener.onToken} for every content delta in order, then return
     * the fully assembled {@link ChatResponse} (tool-call deltas merged).
     */
    ChatResponse chatStream(List<ChatMessage> messages, List<ToolDefinition> tools,
                            LlmOptions options, TokenListener listener);
}

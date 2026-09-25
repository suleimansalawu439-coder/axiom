package dev.axiom.memory;

import dev.axiom.llm.ChatMessage;

import java.util.List;

/**
 * Conversation memory. The agent loads {@link #history()} into the prompt on
 * every run and stores the new transcript afterwards, giving multi-turn
 * conversations without the caller managing message lists.
 */
public interface Memory {
    /** Messages to prepend (after the system prompt) on the next run. */
    List<ChatMessage> history();

    /** Store transcript messages (system messages are ignored). */
    void store(List<ChatMessage> transcript);

    void clear();
}

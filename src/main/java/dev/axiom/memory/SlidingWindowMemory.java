package dev.axiom.memory;

import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatRole;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * In-memory sliding-window memory: keeps the most recent {@code maxMessages}
 * non-system messages. Oldest messages fall off the front, so the prompt
 * never grows without bound.
 */
public final class SlidingWindowMemory implements Memory {
    private final int maxMessages;
    private final Deque<ChatMessage> messages = new ArrayDeque<>();

    public SlidingWindowMemory(int maxMessages) {
        if (maxMessages < 1) throw new IllegalArgumentException("maxMessages must be >= 1");
        this.maxMessages = maxMessages;
    }

    public SlidingWindowMemory() {
        this(40);
    }

    @Override
    public synchronized List<ChatMessage> history() {
        return List.copyOf(messages);
    }

    @Override
    public synchronized void store(List<ChatMessage> transcript) {
        for (ChatMessage m : transcript) {
            if (m.role() == ChatRole.SYSTEM) continue;
            messages.addLast(m);
            while (messages.size() > maxMessages) messages.removeFirst();
        }
    }

    @Override
    public synchronized void clear() {
        messages.clear();
    }
}

package dev.axiom.agent;

import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatRole;

import java.util.ArrayList;
import java.util.List;

/**
 * Rolling-window management for tool observations in the agent's context.
 *
 * <p>A long run accumulates tool outputs (fetched pages, command output,
 * file reads) that can dwarf the model's context and inflate token spend —
 * the first real GAIA run burned 673k tokens on a single task because every
 * fetched page stayed in context for every subsequent turn. This compacts
 * the <i>view</i> of the conversation sent to the model: when the total
 * characters of tool observations exceed the cap, the oldest observations
 * are replaced with a one-line stub, always keeping the two most recent
 * tool outputs whole.
 *
 * <p>The stored transcript (and the durable journal) is never touched —
 * compaction produces a new list; the full history remains available for
 * replay, resume, and auditing. Non-tool messages (system prompt, user
 * task, assistant reasoning) are never stubbed.
 */
public final class ContextWindow {

    /** Default cap on accumulated tool-observation characters. */
    public static final int DEFAULT_TOOL_OUTPUT_CAP = 24_000;
    /** System property overriding the cap; non-positive/invalid → default. */
    public static final String TOOL_OUTPUT_CAP_PROPERTY = "axiom.context.toolOutputCap";

    private ContextWindow() {}

    /** Effective cap: the system-property override, or the default. */
    public static int toolOutputCap() {
        String v = System.getProperty(TOOL_OUTPUT_CAP_PROPERTY);
        if (v != null) {
            try {
                int n = Integer.parseInt(v.trim());
                if (n > 0) return n;
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return DEFAULT_TOOL_OUTPUT_CAP;
    }

    /**
     * Return a compacted view of {@code messages} for the model. When the
     * summed characters of {@link ChatRole#TOOL} observations are within
     * {@code capChars}, the input list is returned unchanged; otherwise the
     * oldest tool observations are replaced with
     * {@code "[earlier output omitted: <tool> <N> chars]"} stubs, keeping
     * the two most recent tool outputs whole. The input list is never
     * mutated.
     */
    public static List<ChatMessage> compact(List<ChatMessage> messages, int capChars) {
        List<Integer> toolIdx = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.role() == ChatRole.TOOL) {
                toolIdx.add(i);
                total += m.content() == null ? 0 : m.content().length();
            }
        }
        if (total <= capChars || toolIdx.size() <= 2) {
            return messages;
        }
        // Keep the two most recent tool outputs whole; stub the rest.
        int keepFrom = toolIdx.get(toolIdx.size() - 2);
        List<ChatMessage> out = new ArrayList<>(messages);
        for (int i = 0; i < toolIdx.size() - 2; i++) {
            int idx = toolIdx.get(i);
            if (idx >= keepFrom) continue; // defensive: never stub a kept message
            ChatMessage m = messages.get(idx);
            int n = m.content() == null ? 0 : m.content().length();
            String tool = m.name() == null ? "tool" : m.name();
            out.set(idx, ChatMessage.toolResult(m.toolCallId(), m.name(),
                "[earlier output omitted: " + tool + " " + n + " chars]"));
        }
        return out;
    }
}

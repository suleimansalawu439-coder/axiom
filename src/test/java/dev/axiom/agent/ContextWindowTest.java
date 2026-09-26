package dev.axiom.agent;

import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rolling-window context management: old tool outputs are stubbed past
 * the cap; the transcript itself is never modified.
 */
class ContextWindowTest {

    private static ChatMessage toolResult(String name, String content) {
        return ChatMessage.toolResult("call-" + name, name, content);
    }

    private static List<ChatMessage> conversation() {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("system prompt"));
        m.add(ChatMessage.user("do the thing"));
        m.add(toolResult("web_fetch", "A".repeat(1000)));
        m.add(ChatMessage.assistant("thinking…"));
        m.add(toolResult("run", "B".repeat(1000)));
        m.add(toolResult("calculate", "42"));
        return m;
    }

    @Test
    void underCapReturnsInputUnchanged() {
        List<ChatMessage> m = conversation();
        assertSame(m, ContextWindow.compact(m, 100_000));
    }

    @Test
    void overCapStubsOldestKeepsLatestTwo() {
        List<ChatMessage> m = conversation();
        // 2042 tool chars > cap 1500 → oldest (web_fetch) stubbed.
        List<ChatMessage> compacted = ContextWindow.compact(m, 1500);

        assertNotSame(m, compacted);
        // Input list is never mutated.
        assertEquals("A".repeat(1000), m.get(2).content());

        ChatMessage stubbed = compacted.get(2);
        assertEquals(ChatRole.TOOL, stubbed.role());
        assertEquals("web_fetch", stubbed.name());
        assertEquals("[earlier output omitted: web_fetch 1000 chars]", stubbed.content());

        // Latest two tool outputs stay whole.
        assertEquals("B".repeat(1000), compacted.get(4).content());
        assertEquals("42", compacted.get(5).content());

        // Non-tool messages untouched.
        assertEquals("system prompt", compacted.get(0).content());
        assertEquals("do the thing", compacted.get(1).content());
        assertEquals("thinking…", compacted.get(3).content());
    }

    @Test
    void stubKeepsToolCallPairing() {
        List<ChatMessage> m = conversation();
        List<ChatMessage> compacted = ContextWindow.compact(m, 1500);
        ChatMessage stubbed = compacted.get(2);
        assertEquals("call-web_fetch", stubbed.toolCallId());
    }

    @Test
    void exactlyTwoToolOutputsNeverStubbed() {
        List<ChatMessage> m = new ArrayList<>();
        m.add(toolResult("a", "x".repeat(50_000)));
        m.add(toolResult("b", "y".repeat(50_000)));
        List<ChatMessage> compacted = ContextWindow.compact(m, 10);
        assertEquals("x".repeat(50_000), compacted.get(0).content());
        assertEquals("y".repeat(50_000), compacted.get(1).content());
    }

    @Test
    void nullContentCountsAsZero() {
        List<ChatMessage> m = new ArrayList<>();
        m.add(new ChatMessage(ChatRole.TOOL, null, "c1", "web_fetch"));
        m.add(toolResult("run", "z".repeat(100)));
        assertSame(m, ContextWindow.compact(m, 200));
    }

    @Test
    void systemPropertyOverridesCap() {
        String key = ContextWindow.TOOL_OUTPUT_CAP_PROPERTY;
        String prev = System.getProperty(key);
        try {
            System.setProperty(key, "10");
            assertEquals(10, ContextWindow.toolOutputCap());
            System.setProperty(key, "not-a-number");
            assertEquals(ContextWindow.DEFAULT_TOOL_OUTPUT_CAP, ContextWindow.toolOutputCap());
            System.setProperty(key, "-5");
            assertEquals(ContextWindow.DEFAULT_TOOL_OUTPUT_CAP, ContextWindow.toolOutputCap());
        } finally {
            if (prev == null) System.clearProperty(key);
            else System.setProperty(key, prev);
        }
    }

    @Test
    void defaultCapIsSane() {
        assertEquals(24_000, ContextWindow.DEFAULT_TOOL_OUTPUT_CAP);
    }
}

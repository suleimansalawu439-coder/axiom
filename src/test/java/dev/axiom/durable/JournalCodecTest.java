package dev.axiom.durable;

import dev.axiom.llm.ToolCallRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Journal round-trip for tool calls, including the optional thought
 * signature (Gemini 3): a resumed run must replay the call verbatim.
 */
class JournalCodecTest {

    @Test
    void thoughtSignatureRoundTripsThroughJournal() {
        var call = new ToolCallRequest("call_1", "multiply", Map.of("x", 2), "sig-abc");
        ToolCallRequest back = JournalCodec.callFromMap(JournalCodec.callToMap(call));
        assertEquals("call_1", back.id());
        assertEquals("multiply", back.name());
        assertEquals(2, back.arguments().get("x"));
        assertEquals("sig-abc", back.thoughtSignature());
    }

    @Test
    void oldJournalEntriesWithoutSignatureReadAsNull() {
        var legacy = Map.<String, Object>of(
            "id", "call_2", "name", "read", "arguments", Map.of());
        ToolCallRequest back = JournalCodec.callFromMap(legacy);
        assertNull(back.thoughtSignature());
    }
}

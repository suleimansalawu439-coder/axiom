package dev.axiom.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.LlmClient.LlmOptions;
import dev.axiom.tools.ToolDefinition;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the model-agnostic output hardening: empty-response nudges
 * (Fix 4), {@code {answer=...}} wrapper unwrapping (Fix 5), and prose
 * extraction through the no-tool-calls path (Fix 6).
 */
class OutputHardeningTest {

    @Test
    void unwrapAnswerWrapperStripsSingleEntryMap() {
        assertEquals("Braintree, Honolulu",
            ReActAgent.unwrapAnswerWrapper("{answer=Braintree, Honolulu}"));
        assertEquals("519", ReActAgent.unwrapAnswerWrapper("{answer=519}"));
    }

    @Test
    void unwrapAnswerWrapperTrimsInnerWhitespace() {
        assertEquals("42", ReActAgent.unwrapAnswerWrapper("  {answer= 42 }  "));
    }

    @Test
    void unwrapAnswerWrapperLeavesOtherShapesAlone() {
        assertEquals("plain answer",
            ReActAgent.unwrapAnswerWrapper("plain answer"));
        assertEquals("{a=1, b=2}",
            ReActAgent.unwrapAnswerWrapper("{a=1, b=2}"));
        assertEquals("", ReActAgent.unwrapAnswerWrapper("{answer=}"));
        assertEquals("", ReActAgent.unwrapAnswerWrapper(null));
    }

    @Test
    void unwrapAnswerWrapperDoesNotTouchNestedBraces() {
        // Only the exact {answer=...} shape is unwrapped.
        String nested = "{answer={x=1}}";
        assertEquals("{x=1}", ReActAgent.unwrapAnswerWrapper(nested));
    }

    // ------------------------------------------------------------------
    // Fix 6: prose answers through the no-tool-calls path get the same
    // extraction pass as the answer-tool path.
    // ------------------------------------------------------------------

    static class ScriptLlm implements LlmClient {
        private final Deque<ChatResponse> script = new ArrayDeque<>();
        int calls;

        ScriptLlm enqueue(ChatResponse r) { script.add(r); return this; }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 LlmOptions options) {
            calls++;
            if (script.isEmpty()) throw new AssertionError("ScriptLlm ran out of scripted responses");
            return script.poll();
        }

        @Override
        public String model() { return "script"; }
    }

    private static ChatResponse text(String t) {
        return new ChatResponse(t, List.of(), new ChatResponse.TokenUsage(10, 5, 15));
    }

    @Test
    void proseNoToolAnswerIsExtractedToBareValue() {
        String draft = "Based on the information I found, the Girls Who Code claim "
            + "relates to approximately 22 students in the program, according to "
            + "the detailed enrollment records I was able to locate and verify.";
        var llm = new ScriptLlm()
            .enqueue(text(draft))
            .enqueue(text("22"));
        // No third response: extraction must be the last model call.

        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .build());
        AgentResult r = agent.run("How many students?");

        assertTrue(r.completed());
        assertEquals("22", r.output());
        assertEquals(2, llm.calls);
    }

    @Test
    void shortNoToolAnswerSkipsExtraction() {
        var llm = new ScriptLlm().enqueue(text("17"));

        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .build());
        AgentResult r = agent.run("What is the answer?");

        assertEquals("17", r.output());
        assertEquals(1, llm.calls, "a bare answer must not pay for an extraction call");
    }
}

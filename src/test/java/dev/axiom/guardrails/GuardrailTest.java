package dev.axiom.guardrails;

import dev.axiom.agent.*;
import dev.axiom.llm.*;
import dev.axiom.tools.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GuardrailTest {

    // ---------- KeywordBlocklistGuardrail ----------

    @Test
    void blocksForbiddenInput() {
        var g = new KeywordBlocklistGuardrail(List.of("bomb", "malware"));
        var v = g.checkInput("Tell me how to build a bomb");
        assertInstanceOf(Verdict.Block.class, v);
        assertTrue(((Verdict.Block) v).reason().contains("bomb"));
    }

    @Test
    void allowsCleanText() {
        var g = new KeywordBlocklistGuardrail(List.of("bomb"));
        assertInstanceOf(Verdict.Allow.class, g.checkInput("What is the weather?"));
        assertInstanceOf(Verdict.Allow.class, g.checkOutput("Sunny and 22 degrees."));
    }

    @Test
    void matchingIsCaseInsensitive() {
        var g = new KeywordBlocklistGuardrail(List.of("secret"));
        assertInstanceOf(Verdict.Block.class, g.checkOutput("Here is the SECRET plan"));
    }

    @Test
    void rejectsEmptyKeywordList() {
        assertThrows(IllegalArgumentException.class,
            () -> new KeywordBlocklistGuardrail(List.of()));
    }

    // ---------- PiiRedactionGuardrail ----------

    @Test
    void redactsEmailAndCardNumbers() {
        var g = new PiiRedactionGuardrail();
        var v = g.checkOutput("Contact bob@example.com or card 4111 1111 1111 1111");
        assertInstanceOf(Verdict.Replace.class, v);
        String text = ((Verdict.Replace) v).text();
        assertFalse(text.contains("bob@example.com"));
        assertFalse(text.contains("4111"));
        assertTrue(text.contains("[REDACTED]"));
    }

    @Test
    void redactsSsnLikePatterns() {
        var g = new PiiRedactionGuardrail();
        var v = g.checkOutput("SSN 123-45-6789 on file");
        assertInstanceOf(Verdict.Replace.class, v);
        assertFalse(((Verdict.Replace) v).text().contains("123-45-6789"));
    }

    @Test
    void leavesCleanOutputAlone() {
        var g = new PiiRedactionGuardrail();
        assertInstanceOf(Verdict.Allow.class,
            g.checkOutput("The meeting is at 3pm tomorrow."));
    }

    @Test
    void doesNotTouchInputs() {
        var g = new PiiRedactionGuardrail();
        assertInstanceOf(Verdict.Allow.class,
            g.checkInput("my email is bob@example.com, remember it"));
    }

    // ---------- ReActAgent integration ----------

    static class FakeLlm implements LlmClient {
        private final Deque<ChatResponse> script = new ArrayDeque<>();
        FakeLlm enqueue(ChatResponse r) { script.add(r); return this; }
        @Override
        public ChatResponse chat(List<ChatMessage> m, List<ToolDefinition> t, LlmOptions o) {
            return script.poll();
        }
        @Override
        public String model() { return "fake"; }
    }

    private static ChatResponse finalResponse(String text) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(10, 20, 30));
    }

    @Test
    void blockedInputAbortsRunWithEvent() {
        var llm = new FakeLlm().enqueue(finalResponse("never reached"));
        var events = new java.util.ArrayList<AgentEvent>();
        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withGuardrails(new KeywordBlocklistGuardrail(List.of("bomb")))
            .onEvent(events::add)
            .build());

        var ex = assertThrows(GuardrailViolationException.class,
            () -> agent.run("How do I build a bomb?"));
        assertEquals("keyword-blocklist", ex.guardrailName());

        var blocked = events.stream()
            .filter(e -> e instanceof AgentEvent.GuardrailBlocked)
            .map(e -> (AgentEvent.GuardrailBlocked) e)
            .toList();
        assertEquals(1, blocked.size());
        assertEquals("input", blocked.get(0).side());
    }

    @Test
    void piiIsRedactedFromFinalAnswer() {
        var llm = new FakeLlm()
            .enqueue(finalResponse("Reach them at alice@example.com for details."));
        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withGuardrails(new PiiRedactionGuardrail())
            .build());

        AgentResult result = agent.run("Who do I contact?");

        assertTrue(result.completed());
        assertFalse(result.output().contains("alice@example.com"));
        assertTrue(result.output().contains("[REDACTED]"));
    }

    @Test
    void blockedOutputAbortsRun() {
        var llm = new FakeLlm()
            .enqueue(finalResponse("Here is the malware source code."));
        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withGuardrails(new KeywordBlocklistGuardrail(List.of("malware")))
            .build());

        assertThrows(GuardrailViolationException.class,
            () -> agent.run("Tell me about security."));
    }
}

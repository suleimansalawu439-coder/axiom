package dev.axiom.teams;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentEvent;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Team tests with scripted fake LLMs — no network, fully deterministic.
 */
class SupervisorTeamTest {

    /** Scripted LLM: returns queued responses in order. */
    static class FakeLlm implements LlmClient {
        private final Deque<ChatResponse> script = new ArrayDeque<>();
        int calls = 0;

        FakeLlm enqueue(ChatResponse r) { script.add(r); return this; }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools, LlmOptions options) {
            calls++;
            if (script.isEmpty()) throw new AssertionError("FakeLlm ran out of scripted responses");
            return script.poll();
        }

        @Override
        public String model() { return "fake"; }
    }

    record ResearchQuery(String topic, int maxSources) {}
    record ResearchBrief(String topic, List<String> findings) {}
    record Report(String title, List<String> points) {}

    private static ChatResponse toolCallResponse(String id, String name, Map<String, Object> args) {
        return new ChatResponse("Delegating.",
            List.of(new ToolCallRequest(id, name, args)),
            ChatResponse.TokenUsage.empty());
    }

    private static ChatResponse finalResponse(String text) {
        return new ChatResponse(text, List.of(), ChatResponse.TokenUsage.empty());
    }

    private Worker<ResearchQuery, ResearchBrief> researcher(FakeLlm llm) {
        return Worker.of("researcher", "Researches a topic and returns a brief.",
            ResearchQuery.class, ResearchBrief.class,
            AgentConfig.builder().withClient(llm).build());
    }

    @Test
    @SuppressWarnings("unchecked")
    void delegateToolSchemaComesFromInputType() {
        var worker = researcher(new FakeLlm());
        ToolDefinition def = worker.delegateToolDefinition();
        assertEquals("delegate_to_researcher", def.name());
        var props = (Map<String, Object>) def.jsonSchema().get("properties");
        assertTrue(props.containsKey("topic"));
        assertTrue(props.containsKey("maxSources"));
        assertTrue(def.description().contains("researcher"));
        assertTrue(def.description().contains("findings"));
    }

    @Test
    void workerExecutesTypedHandoff() {
        var llm = new FakeLlm()
            .enqueue(finalResponse("Researching…"))       // ReAct loop: no tools, done
            .enqueue(finalResponse("{\"topic\":\"x\",\"findings\":[\"a\",\"b\"]}")); // runFor formatting
        var worker = researcher(llm);

        ResearchBrief brief = worker.execute(new ResearchQuery("x", 2));

        assertEquals("x", brief.topic());
        assertEquals(List.of("a", "b"), brief.findings());
        assertEquals(2, llm.calls);
    }

    @Test
    void invalidWorkerNameRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            Worker.of("not a name!", "d", ResearchQuery.class, ResearchBrief.class,
                AgentConfig.builder().withClient(new FakeLlm()).build()));
    }

    @Test
    void teamNeedsAtLeastOneWorker() {
        assertThrows(IllegalStateException.class,
            () -> SupervisorTeam.builder(new FakeLlm()).build());
    }

    @Test
    void supervisorDelegatesAndAggregatesTypedResults() {
        var workerLlm = new FakeLlm()
            .enqueue(finalResponse("On it."))
            .enqueue(finalResponse("{\"topic\":\"solid-state\",\"findings\":[\"f1\"]}"));
        var worker = researcher(workerLlm);

        var events = new java.util.ArrayList<AgentEvent>();
        var supervisorLlm = new FakeLlm()
            .enqueue(toolCallResponse("c1", "delegate_to_researcher",
                Map.of("topic", "solid-state", "maxSources", 3)))
            .enqueue(finalResponse("Got the brief."))
            .enqueue(finalResponse("{\"title\":\"Report\",\"points\":[\"f1\"]}"));

        SupervisorTeam team = SupervisorTeam.builder(supervisorLlm)
            .withWorker(worker)
            .onEvent(events::add)
            .build();

        Report report = team.run("Write a report on solid-state batteries", Report.class);

        assertEquals("Report", report.title());
        assertEquals(List.of("f1"), report.points());
        // The worker's typed input was honored end to end.
        assertEquals(2, workerLlm.calls);
        // Supervisor observability: delegation visible as a tool call event.
        assertTrue(events.stream()
            .anyMatch(e -> e instanceof AgentEvent.ToolCallStarted s
                && s.call().name().equals("delegate_to_researcher")));
    }

    @Test
    void typedProgrammaticDelegation() {
        var llm = new FakeLlm()
            .enqueue(finalResponse("On it."))
            .enqueue(finalResponse("{\"topic\":\"y\",\"findings\":[\"g\"]}"));
        var worker = researcher(llm);
        SupervisorTeam team = SupervisorTeam.builder(new FakeLlm()).withWorker(worker).build();

        // Compile-time checked: wrong input type would not compile.
        ResearchBrief brief = team.delegate(worker, new ResearchQuery("y", 1));
        assertEquals(List.of("g"), brief.findings());
    }
}

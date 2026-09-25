package dev.axiom.a2a;

import dev.axiom.Axiom;
import dev.axiom.a2a.A2aTypes.A2aTask;
import dev.axiom.a2a.A2aTypes.Artifact;
import dev.axiom.a2a.A2aTypes.Part;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A2A protocol tests. The sandbox blocks all TCP (even loopback), so these
 * exercise the transport-independent protocol core directly:
 * {@link A2aServer#dispatch}, {@link A2aServer#streamTaskEvents}, and the
 * client's pure SSE/RPC parsers. The HTTP layer is a thin wrapper over the
 * same methods.
 */
class A2aTest {

    static class FakeLlm implements LlmClient {
        private final Deque<ChatResponse> script = new ArrayDeque<>();

        FakeLlm enqueue(ChatResponse r) { script.add(r); return this; }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 LlmOptions options) {
            if (script.isEmpty()) throw new AssertionError("FakeLlm out of script");
            return script.poll();
        }

        @Override
        public String model() { return "fake"; }
    }

    private static ChatResponse finalAnswer(String text) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(5, 5, 10));
    }

    /** An unbound server: protocol logic, no socket. */
    private static A2aServer serverWithResponses(String... answers) {
        var fake = new FakeLlm();
        for (String a : answers) fake.enqueue(finalAnswer(a));
        var agent = new Axiom.Agent(Axiom.agent().withClient(fake).build());
        return new A2aServer(agent,
            AgentCard.simple("test-agent", "A test agent", "http://placeholder", "Answers."));
    }

    private static Map<String, Object> sendParams(String text) {
        return Map.of("message", Map.of(
            "messageId", "m1",
            "role", "user",
            "parts", List.of(Map.of("kind", "text", "text", text))));
    }

    @Test
    void cardServedWithRewrittenUrl() {
        var server = serverWithResponses();
        Map<String, Object> cardJson = server.cardJson("http://127.0.0.1:9999");
        assertEquals("http://127.0.0.1:9999", cardJson.get("url"));
        AgentCard card = AgentCard.fromJson(cardJson);
        assertEquals("test-agent", card.name());
        assertTrue(card.capabilities().streaming());
    }

    @Test
    void messageSendRunsAgentAndReturnsArtifact() {
        var server = serverWithResponses("42");
        A2aTask task = A2aTask.fromJson(server.dispatch("message/send", sendParams("What is 6 * 7?")));
        assertEquals("completed", task.status().state());
        assertTrue(task.isTerminal());
        assertEquals("42", task.artifactText().orElseThrow());

        // The finished task is discoverable via tasks/get.
        A2aTask fetched = A2aTask.fromJson(
            server.dispatch("tasks/get", Map.of("id", task.id())));
        assertEquals(task.id(), fetched.id());
        assertEquals("42", fetched.artifactText().orElseThrow());
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamEventsArriveInOrder() {
        var server = serverWithResponses("streamed answer");
        List<Map<String, Object>> events = server.streamTaskEvents(sendParams("Say hi"));

        assertEquals(3, events.size());
        assertEquals("status-update", events.get(0).get("kind"));
        assertEquals("artifact-update", events.get(1).get("kind"));
        assertEquals("status-update", events.get(2).get("kind"));

        // The final status update is completed and marked final.
        Map<String, Object> last = events.get(2);
        assertEquals("completed", ((Map<String, Object>) last.get("status")).get("state"));
        assertEquals(Boolean.TRUE, last.get("final"));

        // The artifact carries the agent's answer.
        Artifact artifact = Artifact.fromJson(
            (Map<String, Object>) events.get(1).get("artifact"));
        String text = artifact.parts().stream()
            .filter(p -> p instanceof Part.TextPart)
            .map(p -> ((Part.TextPart) p).text())
            .findFirst()
            .orElseThrow();
        assertEquals("streamed answer", text);
    }

    @Test
    void unknownMethodAndTaskAreJsonRpcErrors() {
        var server = serverWithResponses();
        A2aException unknownMethod = assertThrows(A2aException.class,
            () -> server.dispatch("nope/nothing", Map.of()));
        assertEquals(-32601, unknownMethod.code());
        A2aException unknownTask = assertThrows(A2aException.class,
            () -> server.dispatch("tasks/get", Map.of("id", "no-such-task")));
        assertEquals(-32001, unknownTask.code());
    }

    @Test
    void cancelTaskMarksItCanceled() {
        var server = serverWithResponses("42");
        A2aTask task = A2aTask.fromJson(server.dispatch("message/send", sendParams("x")));
        A2aTask canceled = A2aTask.fromJson(
            server.dispatch("tasks/cancel", Map.of("id", task.id())));
        assertEquals("canceled", canceled.status().state());
    }

    @Test
    void remoteAgentAsLocalTool() throws Exception {
        var server = serverWithResponses("43", "44");
        // A "transport" whose round trip is the server's own dispatch.
        ToolDefinition tool = A2aClient.asTool(
            (baseUrl, text) -> A2aTask.fromJson(server.dispatch("message/send", sendParams(text))),
            "http://stub", "remote_agent", "A remote A2A agent");
        assertEquals("remote_agent", tool.name());
        assertEquals("43", tool.invoker().invoke(Map.of("message", "Second?")));

        // And it runs inside a normal Axiom agent via the tool registry.
        var fake = new FakeLlm()
            .enqueue(new ChatResponse("I'll ask the remote agent.",
                List.of(new ToolCallRequest("c1", "remote_agent",
                    Map.of("message", "Third?"))),
                new ChatResponse.TokenUsage(5, 5, 10)))
            .enqueue(finalAnswer("Remote said so."));
        var agent = new Axiom.Agent(
            Axiom.agent().withClient(fake).withToolDefinitions(tool).build());
        var result = agent.run("Consult the remote agent.");
        assertTrue(result.completed());
        assertEquals("Remote said so.", result.output());
        assertEquals(1, result.toolCallsMade());
    }

    @Test
    void clientParsesSseAndRpcEnvelopes() throws Exception {
        String sse = "data: {\"jsonrpc\":\"2.0\",\"id\":\"1\","
            + "\"result\":{\"kind\":\"status-update\",\"status\":{\"state\":\"working\"}}}\n\n"
            + ": heartbeat\n\n"
            + "data: [DONE]\n\n";
        List<Map<String, Object>> events =
            A2aClient.parseSseEvents(new BufferedReader(new StringReader(sse)));
        assertEquals(1, events.size());
        assertEquals("status-update", events.get(0).get("kind"));

        Map<String, Object> result = A2aClient.extractResult(
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"id\":\"t1\"}}", "tasks/get");
        assertEquals("t1", result.get("id"));

        A2aException err = assertThrows(A2aException.class, () -> A2aClient.extractResult(
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\","
                + "\"error\":{\"code\":-32001,\"message\":\"Task not found\"}}",
            "tasks/get"));
        assertEquals(-32001, err.code());
    }

    @Test
    void cardFromJsonToleratesForeignFields() {
        Map<String, Object> foreign = Map.of(
            "name", "Foreign",
            "description", "A foreign agent",
            "url", "https://agents.example/foreign",
            "version", "1.0.0",
            "capabilities", Map.of("streaming", true, "pushNotifications", false,
                "stateTransitionHistory", true),
            "authentication", Map.of("schemes", List.of("Bearer")),
            "skills", List.of(Map.of("id", "s1", "name", "Translate",
                "description", "Translates text", "tags", List.of("nlp"))),
            "unknownFutureField", "must be ignored");
        AgentCard card = AgentCard.fromJson(foreign);
        assertEquals("Foreign", card.name());
        assertTrue(card.capabilities().streaming());
        assertFalse(card.capabilities().pushNotifications());
        assertEquals(1, card.skills().size());
        assertEquals("s1", card.skills().get(0).id());
    }
}

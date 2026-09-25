package dev.axiom.capabilities;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentEvent;
import dev.axiom.agent.AgentResult;
import dev.axiom.agent.ReActAgent;
import dev.axiom.durable.RunJournal;
import dev.axiom.guardrails.CapabilityGuardrail;
import dev.axiom.guardrails.GuardrailViolationException;
import dev.axiom.guardrails.Verdict;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolParam;
import dev.axiom.tools.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime enforcement of the capability policy: the guardrail blocks
 * precondition violations fail-closed, tracks session tokens from completed
 * (and journal-replayed) tool calls, and the agent loop wires it in.
 *
 * <p>The {@code OpsTools} holder below is compiled by the real build with
 * the Axiom {@code ToolProcessor} active, so these tests exercise the true
 * compile-time artifact → runtime guardrail path, not a mock.
 */
class CapabilityGuardrailTest {

    static class OpsTools {
        @Tool(description = "Snapshot the database to cold storage",
              capabilities = {Capability.READ, Capability.WRITE})
        @Ensures(Capability.BACKUP)
        public String backupDatabase() { return "backup-ok"; }

        @Tool(description = "Delete snapshots older than 30 days",
              capabilities = {Capability.DESTRUCTIVE})
        @Requires(Capability.BACKUP)
        public String deleteOldSnapshots() { return "deleted"; }

        @Tool(description = "Read a configuration value",
              capabilities = {Capability.READ})
        public String readValue(@ToolParam(description = "key") String key) {
            return "v:" + key;
        }
    }

    /** Scripted LLM, same pattern as ReActAgentTest's FakeLlm. */
    static class FakeLlm implements LlmClient {
        private final Deque<ChatResponse> script = new ArrayDeque<>();

        FakeLlm enqueue(ChatResponse r) { script.add(r); return this; }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 LlmOptions options) {
            if (script.isEmpty()) throw new AssertionError("FakeLlm ran out of scripted responses");
            return script.poll();
        }

        @Override
        public String model() { return "fake"; }
    }

    private static CapabilityGuardrail guardrail() {
        return CapabilityGuardrail.forRegistry(new ToolRegistry().register(new OpsTools()));
    }

    private static ChatResponse toolCall(String id, String name, Map<String, Object> args) {
        return new ChatResponse("Using a tool.",
            List.of(new ToolCallRequest(id, name, args)),
            new ChatResponse.TokenUsage(10, 5, 15));
    }

    private static ChatResponse finalResponse(String text) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(10, 20, 30));
    }

    // ------------------------------------------------------------------
    // Unit: precondition checks and token tracking
    // ------------------------------------------------------------------

    @Test
    void blocksDestructiveCallWithoutBackupToken() {
        var guardrail = guardrail();
        Verdict v = guardrail.checkToolCall("deleteOldSnapshots", Map.of());
        assertInstanceOf(Verdict.Block.class, v, "DESTRUCTIVE tool without BACKUP must block");
        String reason = ((Verdict.Block) v).reason();
        assertTrue(reason.contains("BACKUP"), () -> reason);
        assertTrue(reason.contains("backupDatabase"),
            () -> "violation should name the tool that ensures the token:\n" + reason);
    }

    @Test
    void allowsOnceTokenEnsured() {
        var guardrail = guardrail();
        guardrail.onToolCompleted("backupDatabase");
        assertTrue(guardrail.sessionTokens().contains(Capability.BACKUP));
        assertInstanceOf(Verdict.Allow.class,
            guardrail.checkToolCall("deleteOldSnapshots", Map.of()));
    }

    @Test
    void unconstrainedToolNeedsNoToken() {
        assertInstanceOf(Verdict.Allow.class,
            guardrail().checkToolCall("readValue", Map.of("key", "k")));
    }

    @Test
    void policyLoadedForAnnotatedTools() {
        var guardrail = guardrail();
        var delete = guardrail.policyOf("deleteOldSnapshots");
        assertTrue(delete.isPresent());
        assertEquals(java.util.Set.of(Capability.BACKUP), delete.get().requires());
        assertEquals(java.util.Set.of(Capability.DESTRUCTIVE), delete.get().capabilities());
        var backup = guardrail.policyOf("backupDatabase");
        assertTrue(backup.isPresent());
        assertEquals(java.util.Set.of(Capability.BACKUP), backup.get().ensures());
    }

    @Test
    void policyArtifactIsOnTestClasspath() throws IOException {
        // Proves the annotation processor emitted META-INF/axiom/policy/*.json
        // during the real test build (not just the reflection fallback).
        String resource = "/META-INF/axiom/policy/"
            + "dev/axiom/capabilities/CapabilityGuardrailTest$OpsTools.json";
        try (var in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "policy artifact missing from test classpath: " + resource);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"requires\":[\"BACKUP\"]"), () -> json);
            assertTrue(json.contains("\"ensures\":[\"BACKUP\"]"), () -> json);
        }
    }

    @Test
    void unknownToolWithoutPolicyIsAllowedDocumented() {
        var registry = new ToolRegistry().register(new OpsTools());
        registry.register(ToolDefinition.of("mcp_remote_tool",
            "a runtime-discovered tool", Map.of("type", "object"),
            false, 30, args -> "ok"));
        var guardrail = CapabilityGuardrail.forRegistry(registry);
        // Known limitation (documented on CapabilityGuardrail): tools with no
        // policy entry have no requirements and ensure nothing — allowed
        // through, fail-open. Conservative MCP defaults are future work.
        assertInstanceOf(Verdict.Allow.class,
            guardrail.checkToolCall("mcp_remote_tool", Map.of()));
        assertTrue(guardrail.policyOf("mcp_remote_tool")
            .map(p -> p.requires().isEmpty()).orElse(true));
    }

    // ------------------------------------------------------------------
    // End to end: the agent loop enforces the policy
    // ------------------------------------------------------------------

    @Test
    void violationAbortsRunLoudly() {
        var registry = new ToolRegistry().register(new OpsTools());
        var guardrail = CapabilityGuardrail.forRegistry(registry);
        var events = new ArrayList<AgentEvent>();
        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(new FakeLlm()
                .enqueue(toolCall("c1", "deleteOldSnapshots", Map.of()))
                .enqueue(finalResponse("unreached")))
            .withRegistry(registry)
            .withGuardrails(guardrail)
            .onEvent(events::add)
            .build());

        var ex = assertThrows(GuardrailViolationException.class,
            () -> agent.run("delete old snapshots"));
        assertEquals("capability-policy", ex.guardrailName());
        assertTrue(ex.getMessage().contains("BACKUP"), () -> ex.getMessage());

        // The block is journaled as an event with side "tool" — never silent.
        assertTrue(events.stream().anyMatch(e ->
            e instanceof AgentEvent.GuardrailBlocked gb
                && gb.side().equals("tool")
                && gb.guardrailName().equals("capability-policy")
                && gb.reason().contains("backupDatabase")),
            () -> "expected a GuardrailBlocked(side=tool) event in: " + events);
    }

    @Test
    void backupThenDeleteSucceedsEndToEnd() {
        var registry = new ToolRegistry().register(new OpsTools());
        var guardrail = CapabilityGuardrail.forRegistry(registry);
        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(new FakeLlm()
                .enqueue(toolCall("c1", "backupDatabase", Map.of()))
                .enqueue(toolCall("c2", "deleteOldSnapshots", Map.of()))
                .enqueue(finalResponse("All cleaned up.")))
            .withRegistry(registry)
            .withGuardrails(guardrail)
            .build());

        AgentResult result = agent.run("back up, then delete old snapshots");
        assertTrue(result.completed());
        assertEquals("All cleaned up.", result.output());
        assertEquals(2, result.toolCallsMade());
        assertTrue(guardrail.sessionTokens().contains(Capability.BACKUP));
    }

    @Test
    void tokensTrackJournaledCompletions(@TempDir Path journalRoot) {
        var registry = new ToolRegistry().register(new OpsTools());
        var guardrail = CapabilityGuardrail.forRegistry(registry);
        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(new FakeLlm()
                .enqueue(toolCall("c1", "backupDatabase", Map.of()))
                .enqueue(finalResponse("Backed up.")))
            .withRegistry(registry)
            .withGuardrails(guardrail)
            .withJournalRoot(journalRoot)
            .build());

        assertTrue(agent.run("back up the database").completed());

        // The token came from a journaled completion, not just process memory:
        // the journal really holds the tool_call_completed record.
        RunJournal journal = agent.journal();
        assertNotNull(journal);
        assertTrue(journal.readAll().stream()
                .anyMatch(r -> r instanceof RunJournal.ToolCallCompleted),
            "expected a journaled tool_call_completed record");
        assertTrue(guardrail.sessionTokens().contains(Capability.BACKUP));
    }

    @Test
    void resumeRebuildsTokensFromJournal(@TempDir Path tmp) {
        var registry = new ToolRegistry().register(new OpsTools());

        // Simulate a crash: backupDatabase completed (journaled), the
        // destructive call was requested by the model but never started.
        RunJournal journal = RunJournal.create(tmp);
        String runId = journal.runId();
        journal.appendRunStarted("clean up", Map.of());
        var c1 = new ToolCallRequest("c1", "backupDatabase", Map.of());
        var c2 = new ToolCallRequest("c2", "deleteOldSnapshots", Map.of());
        journal.appendEvent(new AgentEvent.LlmResponse(Instant.now(), 1,
            new ChatResponse("Backing up, then cleaning.", List.of(c1, c2),
                new ChatResponse.TokenUsage(10, 5, 15))));
        journal.appendToolCallStarted(runId + "#c1", c1);
        journal.appendToolCallCompleted(runId + "#c1", "backup-ok");
        journal.close();

        var guardrail = CapabilityGuardrail.forRegistry(registry);
        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(new FakeLlm().enqueue(finalResponse("Recovered and cleaned.")))
            .withRegistry(registry)
            .withGuardrails(guardrail)
            .build());

        AgentResult result = agent.resume(RunJournal.open(tmp, runId));
        assertTrue(result.completed());
        assertEquals("Recovered and cleaned.", result.output());
        // The BACKUP token was rebuilt from the replayed journal completion,
        // so the pending destructive call was allowed and really executed.
        assertTrue(guardrail.sessionTokens().contains(Capability.BACKUP),
            "token should be rebuilt from the journaled completion");
        assertEquals(2, result.toolCallsMade());
    }
}

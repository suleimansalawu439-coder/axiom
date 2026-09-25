package dev.axiom.chaos;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.ReActAgent;
import dev.axiom.durable.AgentRun;
import dev.axiom.durable.DurableException;
import dev.axiom.durable.RunJournal;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial journal tests: flip and truncate bytes at various offsets and
 * assert the framework <b>aborts loudly with a clear diagnostic</b> — never
 * silently resuming corrupted state.
 *
 * <p>One deliberate exception, matching write-ahead-log practice: a torn
 * <em>final</em> line (the process died mid-write) is dropped with a warning
 * and the run resumes as if that write never happened — previously a torn
 * tail poisoned the entire journal and made resume impossible.
 */
class JournalCorruptionTest {

    static final class ScriptLlm implements LlmClient {
        private final Deque<ChatResponse> script = new ArrayDeque<>();

        ScriptLlm(ChatResponse... responses) {
            script.addAll(List.of(responses));
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 LlmOptions options) {
            ChatResponse r = script.poll();
            if (r == null) throw new AssertionError("script exhausted");
            return r;
        }

        @Override
        public String model() {
            return "chaos-journal";
        }
    }

    private static ChatResponse toolCall(String id, String name, Map<String, Object> args) {
        return new ChatResponse("using tool", List.of(new ToolCallRequest(id, name, args)),
            new ChatResponse.TokenUsage(10, 5, 15));
    }

    private static ChatResponse finalAnswer(String text) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(10, 20, 30));
    }

    private static ToolDefinition echoTool() {
        return ToolDefinition.of("echo", "echo", Map.of("type", "object",
                "properties", Map.of(), "additionalProperties", false),
            false, 30, args -> "echo-ok");
    }

    /** A healthy two-turn journaled run; returns its run id. */
    private String healthyRun(Path root) {
        var llm = new ScriptLlm(
            toolCall("c1", "echo", Map.of()),
            finalAnswer("finished"));
        AgentConfig config = AgentConfig.builder()
            .withClient(llm)
            .withToolDefinitions(echoTool())
            .withJournalRoot(root)
            .build();
        AgentRun run = AgentRun.begin(config, "corruption probe");
        assertTrue(run.completed());
        return run.checkpointId();
    }

    private Path journalFile(Path root, String runId) {
        return root.resolve(runId).resolve("journal.jsonl");
    }

    private List<String> lines(Path root, String runId) throws Exception {
        return Files.readAllLines(journalFile(root, runId), StandardCharsets.UTF_8);
    }

    private void writeLines(Path root, String runId, List<String> ls) throws Exception {
        Files.write(journalFile(root, runId), ls, StandardCharsets.UTF_8);
    }

    private AgentConfig replayConfig(Path root) {
        return AgentConfig.builder()
            .withClient(new ScriptLlm(finalAnswer("recovered")))
            .withToolDefinitions(echoTool())
            .withJournalRoot(root)
            .build();
    }

    /** Replay-only parse of a journal (no execution, no events). */
    private void replay(Path root, String runId) {
        try (RunJournal j = RunJournal.open(root, runId)) {
            new ReActAgent(replayConfig(root)).replay(j);
        }
    }

    @Test
    @Timeout(60)
    void garbageJsonInMiddleLineAbortsWithLineNumber(@TempDir Path root) {
        String runId = healthyRun(root);
        assertDoesNotThrow(() -> replay(root, runId), "healthy journal must replay cleanly");

        try {
            List<String> ls = lines(root, runId);
            int victim = -1;
            for (int i = 0; i < ls.size(); i++) {
                if (ls.get(i).contains("\"tool_call_started\"")) {
                    victim = i;
                    break;
                }
            }
            assertTrue(victim > 0, "expected a tool_call_started line to corrupt");
            ls.set(victim, "{this is not json at all");
            writeLines(root, runId, ls);

            DurableException boom = assertThrows(DurableException.class,
                () -> replay(root, runId), "corrupt middle line must abort loudly");
            assertTrue(boom.getMessage().contains("Corrupt journal"),
                "diagnostic must say the journal is corrupt, got: " + boom.getMessage());
            assertTrue(boom.getMessage().contains("line " + (victim + 1)),
                "diagnostic must name the line, got: " + boom.getMessage());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @Timeout(60)
    void wrongTypedPayloadAbortsInsteadOfClassCast(@TempDir Path root) {
        String runId = healthyRun(root);
        try {
            List<String> ls = lines(root, runId);
            int victim = -1;
            for (int i = 0; i < ls.size(); i++) {
                // Corrupt the LlmResponse event's payload: object -> string.
                if (ls.get(i).contains("\"type\":\"LlmResponse\"")) {
                    victim = i;
                    ls.set(i, ls.get(i).replaceFirst("\"response\":\\{", "\"response\":\"BROKEN\","));
                    break;
                }
            }
            assertTrue(victim >= 0, "expected an LlmResponse event line to corrupt");
            writeLines(root, runId, ls);

            DurableException boom = assertThrows(DurableException.class,
                () -> replay(root, runId),
                "wrong-typed payload must abort with a diagnostic, not ClassCastException");
            assertTrue(boom.getMessage().contains("LlmResponse.response"),
                "diagnostic must name the corrupt field, got: " + boom.getMessage());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @Timeout(60)
    void unknownEventTypeAbortsInsteadOfSilentBlankResume(@TempDir Path root) {
        String runId = healthyRun(root);
        try {
            List<String> ls = lines(root, runId);
            boolean hit = false;
            for (int i = 0; i < ls.size(); i++) {
                if (ls.get(i).contains("\"type\":\"LlmResponse\"")) {
                    ls.set(i, ls.get(i).replace("\"type\":\"LlmResponse\"", "\"type\":\"LlmResponzz\""));
                    hit = true;
                    break;
                }
            }
            assertTrue(hit);
            writeLines(root, runId, ls);

            DurableException boom = assertThrows(DurableException.class,
                () -> replay(root, runId),
                "unknown event type must abort, never resume as a blank RunStarted");
            assertTrue(boom.getMessage().contains("unknown event type"),
                "got: " + boom.getMessage());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @Timeout(60)
    void tornFinalLineIsDroppedAndRunResumes(@TempDir Path root) {
        String runId = healthyRun(root);
        try {
            List<String> ls = lines(root, runId);
            assertTrue(ls.size() >= 2, "need at least two lines to tear the tail");
            // Simulate a crash mid-write: the last line is cut off mid-JSON.
            String torn = ls.get(ls.size() - 1);
            String fragment = torn.substring(0, Math.min(40, torn.length()));
            assertFalse(fragment.endsWith("}"), "fragment must be visibly truncated");
            List<String> torn_journal = new java.util.ArrayList<>(ls.subList(0, ls.size() - 1));
            torn_journal.add(fragment);
            writeLines(root, runId, torn_journal);

            // The torn tail is dropped (with a warning), not fatal: the run
            // resumes and completes instead of losing the whole journal.
            var llm = new ScriptLlm(finalAnswer("recovered-after-tear"));
            AgentConfig config = AgentConfig.builder()
                .withClient(llm)
                .withToolDefinitions(echoTool())
                .withJournalRoot(root)
                .build();
            AgentRun resumed = AgentRun.resumeFrom(root, runId, config);
            assertTrue(resumed.completed(), "run with torn tail must still resume and complete");
            assertEquals("recovered-after-tear", resumed.result().output());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @Timeout(60)
    void truncatedTailMidLedgerStillHonorsCrashWindow(@TempDir Path root) {
        String runId = healthyRun(root);
        try {
            List<String> ls = lines(root, runId);
            // Tear the journal right after the tool_call_started line: the
            // completion record is gone, so the call is in the crash window.
            int startedIdx = -1;
            for (int i = 0; i < ls.size(); i++) {
                if (ls.get(i).contains("\"tool_call_started\"")) {
                    startedIdx = i;
                    break;
                }
            }
            assertTrue(startedIdx >= 0);
            List<String> cut = new java.util.ArrayList<>(ls.subList(0, startedIdx + 1));
            cut.add("{\"kind\":\"tool_call_complet"); // torn write, never acknowledged
            writeLines(root, runId, cut);

            // Non-idempotent tool: resume must refuse, not double-execute.
            var counter = new AtomicInteger();
            ToolDefinition nonIdem = ToolDefinition.of("echo", "echo", Map.of("type", "object",
                    "properties", Map.of(), "additionalProperties", false),
                false, 30, args -> {
                    counter.incrementAndGet();
                    return "echo-ok";
                }, false);
            AgentConfig config = AgentConfig.builder()
                .withClient(new ScriptLlm(finalAnswer("x")))
                .withToolDefinitions(nonIdem)
                .withJournalRoot(root)
                .build();
            assertThrows(DurableException.class,
                () -> AgentRun.resumeFrom(root, runId, config),
                "torn completion must leave the crash window visible → loud refusal");
            assertEquals(0, counter.get(),
                "the resume-side tool body must never execute after a loud refusal");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @Timeout(60)
    void missingRunStartedAbortsLoudly(@TempDir Path root) {
        String runId = healthyRun(root);
        try {
            List<String> ls = lines(root, runId);
            List<String> noStart = ls.stream()
                .filter(l -> !l.contains("\"run_started\""))
                .toList();
            assertTrue(noStart.size() < ls.size(), "expected a run_started line to remove");
            writeLines(root, runId, noStart);

            DurableException boom = assertThrows(DurableException.class,
                () -> replay(root, runId));
            assertTrue(boom.getMessage().contains("run_started"),
                "diagnostic must mention the missing run_started, got: " + boom.getMessage());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}

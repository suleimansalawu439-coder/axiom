package dev.axiom.chaos;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentEvent;
import dev.axiom.agent.AgentResult;
import dev.axiom.agent.ReActAgent;
import dev.axiom.durable.RunJournal;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Battle test: dozens of agents running concurrently on real threads, each
 * doing multi-turn ReAct loops with real tool execution.
 *
 * <p>Asserts the framework's shared machinery holds up: no lost tool calls,
 * no duplicate journal ledger entries, token accounting stays exact, and —
 * via hard per-agent timeouts — no deadlocks or wedged runs. Everything is
 * fixture-driven (scripted {@link LlmClient}, real tools, real threads): no
 * network, no keys, CI-safe.
 */
class ConcurrencyHammerTest {

    /** Scripted LLM: one response per chat call, in order. Thread-confined per agent. */
    static final class ScriptLlm implements LlmClient {
        private final Queue<ChatResponse> script = new ConcurrentLinkedQueue<>();
        private final AtomicInteger calls = new AtomicInteger();

        ScriptLlm(ChatResponse... responses) {
            script.addAll(List.of(responses));
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 LlmOptions options) {
            calls.incrementAndGet();
            ChatResponse r = script.poll();
            if (r == null) throw new AssertionError("ScriptLlm ran out of scripted responses");
            return r;
        }

        @Override
        public String model() {
            return "chaos-script";
        }

        int calls() {
            return calls.get();
        }
    }

    private static ChatResponse toolCall(String id, String name, Map<String, Object> args) {
        return new ChatResponse("using tool", List.of(new ToolCallRequest(id, name, args)),
            new ChatResponse.TokenUsage(10, 5, 15));
    }

    private static ChatResponse finalAnswer(String text) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(10, 20, 30));
    }

    private static Map<String, Object> emptySchema() {
        return Map.of("type", "object", "properties", Map.of("x", Map.of("type", "integer")),
            "additionalProperties", false);
    }

    /**
     * 24 agents × 2 turns hammering shared tool state concurrently. A deadlock
     * or lost wakeup surfaces as a Future timeout, not a hung CI job.
     */
    @Test
    @Timeout(120)
    void parallelAgentsLoseNoToolCallsAndAccountTokensExactly(@TempDir Path scratch) throws Exception {
        int agents = 24;
        var toolRuns = new AtomicInteger();
        // One shared tool *definition* registered into every agent's own
        // registry; the invoker bumps shared atomic state.
        ToolDefinition sharedDef = ToolDefinition.of(
            "hammer_add", "adds x", emptySchema(), false, 30,
            args -> {
                toolRuns.incrementAndGet();
                return ((Number) args.get("x")).intValue() + 1;
            });

        ExecutorService pool = Executors.newFixedThreadPool(agents);
        List<Future<AgentResult>> futures = new ArrayList<>();
        List<ScriptLlm> llms = new ArrayList<>();
        try {
            for (int i = 0; i < agents; i++) {
                int n = i;
                var llm = new ScriptLlm(
                    toolCall("c1", "hammer_add", Map.of("x", n)),
                    finalAnswer("done-" + n));
                llms.add(llm);
                AgentConfig config = AgentConfig.builder()
                    .withClient(llm)
                    .withToolDefinitions(sharedDef)
                    .withMaxIterations(5)
                    .build();
                futures.add(pool.submit(() -> new ReActAgent(config).run("hammer task " + n)));
            }
            for (int i = 0; i < agents; i++) {
                // Loud failure on deadlock: 60s per agent, not an infinite hang.
                AgentResult r = futures.get(i).get(60, TimeUnit.SECONDS);
                assertTrue(r.completed(), "agent " + i + " must complete");
                assertEquals("done-" + i, r.output());
                assertEquals(1, r.toolCallsMade(), "agent " + i + " made exactly one tool call");
                // Exact token accounting: (10+5+15) + (10+20+30) = 45 total.
                assertEquals(45, r.tokenUsage().totalTokens(),
                    "agent " + i + " token accounting must be exact");
                assertEquals(2, llms.get(i).calls(), "agent " + i + " made exactly two LLM calls");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(agents, toolRuns.get(),
            "every agent's tool body must execute exactly once — no lost, no duplicated calls");
    }

    /**
     * Same hammer with durability on: per-agent journals must each hold a
     * coherent ledger — every started call completed, event seq numbers
     * strictly increasing, no cross-talk between concurrent journals.
     */
    @Test
    @Timeout(180)
    void parallelJournaledRunsKeepCoherentLedgers(@TempDir Path root) throws Exception {
        int agents = 16;
        var toolRuns = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(agents);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < agents; i++) {
                int n = i;
                Path journalRoot = root.resolve("agent-" + n);
                var llm = new ScriptLlm(
                    toolCall("c1", "j_add", Map.of("x", n)),
                    toolCall("c2", "j_add", Map.of("x", n + 100)),
                    finalAnswer("ok-" + n));
                // Per-agent definition instance, shared atomic counter.
                ToolDefinition def = ToolDefinition.of(
                    "j_add", "adds x", emptySchema(), false, 30,
                    args -> {
                        toolRuns.incrementAndGet();
                        return ((Number) args.get("x")).intValue() + 1;
                    });
                AgentConfig config = AgentConfig.builder()
                    .withClient(llm)
                    .withToolDefinitions(def)
                    .withJournalRoot(journalRoot)
                    .withMaxIterations(6)
                    .build();
                futures.add(pool.submit(() -> {
                    AgentResult r = new ReActAgent(config).run("journaled hammer " + n);
                    assertTrue(r.completed());
                    return journalRoot.toString();
                }));
            }
            for (Future<String> f : futures) {
                f.get(60, TimeUnit.SECONDS); // deadlock tripwire
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(agents * 2, toolRuns.get(), "each agent's two tool bodies run exactly once");

        // Audit every journal independently.
        for (int i = 0; i < agents; i++) {
            Path journalRoot = root.resolve("agent-" + i);
            List<String> runIds = RunJournal.listRuns(journalRoot);
            assertEquals(1, runIds.size(), "agent " + i + " has exactly one journal");
            List<RunJournal.Record> records;
            try (RunJournal j = RunJournal.open(journalRoot, runIds.get(0))) {
                records = j.readAll();
            }
            long started = records.stream()
                .filter(r -> r instanceof RunJournal.ToolCallStarted).count();
            long completed = records.stream()
                .filter(r -> r instanceof RunJournal.ToolCallCompleted).count();
            assertEquals(2, started, "agent " + i + ": two calls started");
            assertEquals(2, completed, "agent " + i + ": two calls completed — no lost ledger writes");
            // Started/completed keys pair up exactly.
            var startedKeys = records.stream()
                .filter(r -> r instanceof RunJournal.ToolCallStarted)
                .map(r -> ((RunJournal.ToolCallStarted) r).idempotencyKey()).sorted().toList();
            var completedKeys = records.stream()
                .filter(r -> r instanceof RunJournal.ToolCallCompleted)
                .map(r -> ((RunJournal.ToolCallCompleted) r).idempotencyKey()).sorted().toList();
            assertEquals(startedKeys, completedKeys, "agent " + i + ": ledger keys pair up");
            // Event seq numbers strictly increasing within the journal.
            List<Long> seqs = records.stream()
                .filter(r -> r instanceof RunJournal.Event)
                .map(r -> ((RunJournal.Event) r).seq()).toList();
            for (int k = 1; k < seqs.size(); k++) {
                assertTrue(seqs.get(k) > seqs.get(k - 1),
                    "agent " + i + ": journal seq must be strictly increasing");
            }
            // The finished events agree with the ledger.
            long finishedEvents = records.stream()
                .filter(r -> r instanceof RunJournal.Event e
                    && e.event() instanceof AgentEvent.ToolCallFinished).count();
            assertEquals(2, finishedEvents, "agent " + i + ": two ToolCallFinished events");
        }
    }
}

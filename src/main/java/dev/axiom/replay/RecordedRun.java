package dev.axiom.replay;

import dev.axiom.agent.AgentEvent;
import dev.axiom.agent.AgentResult;
import dev.axiom.capabilities.Capability;
import dev.axiom.durable.DurableException;
import dev.axiom.durable.RunJournal;
import dev.axiom.guardrails.CapabilityGuardrail;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A run journal parsed into a replayable trajectory: the task, one
 * {@link RecordedTurn} per model turn in journal order, the final result
 * (when the run finished), guardrail blocks, and aggregate token usage.
 *
 * <p>Parsing is purely a read of the journal — the journal file is never
 * modified. Both the event records ({@code LlmResponse},
 * {@code ToolCallFinished}) and the side-effect ledger records
 * ({@code tool_call_started} / {@code tool_call_completed}) are understood,
 * so journals from any Axiom version replay the same way resume does.
 */
public final class RecordedRun {

    /** A guardrail block as journaled: which guardrail, on which side, why. */
    public record BlockedCall(String guardrailName, String side, String reason) {}

    private final String task;
    private final List<RecordedTurn> turns;
    private final AgentResult finalResult;
    private final List<BlockedCall> guardrailBlocks;
    private final ChatResponse.TokenUsage totalUsage;
    private final int toolCallsMade;

    private RecordedRun(String task, List<RecordedTurn> turns, AgentResult finalResult,
                        List<BlockedCall> guardrailBlocks,
                        ChatResponse.TokenUsage totalUsage, int toolCallsMade) {
        this.task = task;
        this.turns = List.copyOf(turns);
        this.finalResult = finalResult;
        this.guardrailBlocks = List.copyOf(guardrailBlocks);
        this.totalUsage = totalUsage;
        this.toolCallsMade = toolCallsMade;
    }

    /**
     * Parse a journal into a replayable run.
     *
     * @param journal  the journal to read (never modified)
     * @param registry when non-null, capability session tokens are tracked
     *                 turn-by-turn by feeding completed tool calls through a
     *                 fresh {@link CapabilityGuardrail}; null disables token
     *                 tracking (token sets stay empty)
     */
    public static RecordedRun parse(RunJournal journal, ToolRegistry registry) {
        String task = null;
        List<TurnBuilder> builders = new ArrayList<>();
        Map<String, TurnBuilder> callIdToTurn = new HashMap<>();
        Map<String, String> keyToCallId = new HashMap<>();
        AgentResult finalResult = null;
        List<BlockedCall> blocks = new ArrayList<>();
        boolean sawStart = false;

        List<RunJournal.Record> records;
        try {
            records = journal.readAll();
        } catch (DurableException e) {
            throw new ReplayException(
                "Cannot replay journal '" + journal.dir() + "': " + e.getMessage(), e);
        }
        for (RunJournal.Record record : records) {
            if (record instanceof RunJournal.RunStarted rs) {
                sawStart = true;
                task = rs.task();
            } else if (record instanceof RunJournal.Event ev
                    && ev.event() instanceof AgentEvent.LlmResponse lr) {
                TurnBuilder b = new TurnBuilder(lr.iteration(), lr.response());
                builders.add(b);
                if (lr.response().toolCalls() != null) {
                    for (ToolCallRequest c : lr.response().toolCalls()) {
                        callIdToTurn.put(c.id(), b);
                    }
                }
            } else if (record instanceof RunJournal.Event ev2
                    && ev2.event() instanceof AgentEvent.ToolCallFinished tf) {
                TurnBuilder b = callIdToTurn.get(tf.call().id());
                if (b != null) b.finish(tf.call().id(), tf.result(), tf.durationMs());
            } else if (record instanceof RunJournal.ToolCallStarted ts) {
                keyToCallId.put(ts.idempotencyKey(), ts.callId());
            } else if (record instanceof RunJournal.ToolCallCompleted tc) {
                String callId = keyToCallId.get(tc.idempotencyKey());
                TurnBuilder b = callId == null ? null : callIdToTurn.get(callId);
                if (b != null) b.finish(callId, tc.result(), -1);
            } else if (record instanceof RunJournal.Event ev3
                    && ev3.event() instanceof AgentEvent.RunFinished rf) {
                finalResult = rf.result();
            } else if (record instanceof RunJournal.Event ev4
                    && ev4.event() instanceof AgentEvent.GuardrailBlocked gb) {
                blocks.add(new BlockedCall(gb.guardrailName(), gb.side(), gb.reason()));
            }
        }
        if (!sawStart) {
            throw new ReplayException(
                "Cannot replay: journal '" + journal.dir() + "' has no run_started record");
        }

        CapabilityGuardrail tokenTracker =
            registry == null ? null : CapabilityGuardrail.forRegistry(registry);
        List<TurnData> data = new ArrayList<>(builders.size());
        for (TurnBuilder b : builders) {
            data.add(new TurnData(b.iteration, b.response, List.copyOf(b.calls.values())));
        }
        return build(task, data, finalResult, blocks, tokenTracker);
    }

    /** Parse without capability-token tracking. */
    public static RecordedRun parse(RunJournal journal) {
        return parse(journal, null);
    }

    /**
     * Combine a replayed prefix (turns before a fork point) with a branch's
     * own recorded trajectory into one continuous run: the branch as the
     * debugger reasons about it — prefix history plus what the branch did.
     * Capability tokens are re-tracked across the boundary when a registry
     * is given, so the branch's token sets include what the prefix earned.
     */
    static RecordedRun prepend(List<RecordedTurn> prefix, RecordedRun rest,
                               ToolRegistry registry) {
        List<TurnData> data = new ArrayList<>(prefix.size() + rest.turns().size());
        for (RecordedTurn t : prefix) {
            data.add(new TurnData(t.iteration(), t.response(), t.toolCalls()));
        }
        for (RecordedTurn t : rest.turns()) {
            data.add(new TurnData(t.iteration(), t.response(), t.toolCalls()));
        }
        CapabilityGuardrail tokenTracker =
            registry == null ? null : CapabilityGuardrail.forRegistry(registry);
        return build(rest.task(), data, rest.finalResult(), rest.guardrailBlocks(), tokenTracker);
    }

    private static RecordedRun build(String task, List<TurnData> data, AgentResult finalResult,
                                     List<BlockedCall> blocks,
                                     CapabilityGuardrail tokenTracker) {
        List<RecordedTurn> turns = new ArrayList<>(data.size());
        ChatResponse.TokenUsage totalUsage = ChatResponse.TokenUsage.empty();
        int toolCallsMade = 0;
        for (TurnData d : data) {
            if (tokenTracker != null) {
                for (RecordedToolCall rtc : d.calls()) {
                    // Mirrors the agent loop: onToolCompleted fires for every
                    // completion, including error observations.
                    if (rtc.completed()) {
                        tokenTracker.onToolCompleted(rtc.call().name());
                    }
                }
            }
            Set<Capability> tokens = tokenTracker == null
                ? Set.of() : EnumSet.copyOf(tokenTracker.sessionTokens());
            turns.add(new RecordedTurn(d.iteration(), d.response(), d.calls(), tokens));
            totalUsage = totalUsage.add(d.response().usage() == null
                ? ChatResponse.TokenUsage.empty() : d.response().usage());
            toolCallsMade += d.calls().size();
        }
        return new RecordedRun(task, turns, finalResult, blocks, totalUsage, toolCallsMade);
    }

    /** One turn's data before token tracking is applied. */
    private record TurnData(int iteration, ChatResponse response, List<RecordedToolCall> calls) {}

    /** The (screened) task the original run executed. */
    public String task() {
        return task;
    }

    /** The recorded model turns in journal order. */
    public List<RecordedTurn> turns() {
        return turns;
    }

    /** Number of recorded model turns. */
    public int turnCount() {
        return turns.size();
    }

    /** The final result, or null when the journal ends before the run finished. */
    public AgentResult finalResult() {
        return finalResult;
    }

    /** True when the journal contains a RunFinished record. */
    public boolean completed() {
        return finalResult != null;
    }

    /** Guardrail blocks journaled during the run, in order. */
    public List<BlockedCall> guardrailBlocks() {
        return guardrailBlocks;
    }

    /** Total token usage across all recorded turns. */
    public ChatResponse.TokenUsage totalUsage() {
        return totalUsage;
    }

    /** Total tool calls dispatched across all recorded turns. */
    public int toolCallsMade() {
        return toolCallsMade;
    }

    /**
     * The tool calls of every turn in order, rendered compactly
     * ({@code name(k=v, …)}) — the sequence {@link BranchDiff} compares.
     */
    public List<String> toolSequence() {
        List<String> out = new ArrayList<>();
        for (RecordedTurn t : turns) {
            for (RecordedToolCall c : t.toolCalls()) {
                out.add(c.describe());
            }
        }
        return out;
    }

    /** Capability tokens held after the last turn (empty when parsed without a registry). */
    public Set<Capability> finalCapabilityTokens() {
        return turns.isEmpty() ? Set.of()
            : turns.get(turns.size() - 1).capabilityTokensAfter();
    }

    /** Accumulates one turn's tool results; first record wins (events and ledger agree). */
    private static final class TurnBuilder {
        final int iteration;
        final ChatResponse response;
        final Map<String, RecordedToolCall> calls = new LinkedHashMap<>();

        TurnBuilder(int iteration, ChatResponse response) {
            this.iteration = iteration;
            this.response = response;
            if (response.toolCalls() != null) {
                for (ToolCallRequest c : response.toolCalls()) {
                    calls.put(c.id(), new RecordedToolCall(c, null, 0));
                }
            }
        }

        void finish(String callId, String result, long durationMs) {
            RecordedToolCall existing = calls.get(callId);
            if (existing != null && !existing.completed()) {
                calls.put(callId, new RecordedToolCall(existing.call(), result, durationMs));
            }
        }
    }
}

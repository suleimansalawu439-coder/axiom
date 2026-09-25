package dev.axiom.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.budget.Budget;
import dev.axiom.budget.BudgetExceededException;
import dev.axiom.durable.DurableException;
import dev.axiom.durable.RunJournal;
import dev.axiom.guardrails.Guardrail;
import dev.axiom.guardrails.GuardrailViolationException;
import dev.axiom.guardrails.Verdict;
import dev.axiom.llm.*;
import dev.axiom.output.OutputSchema;
import dev.axiom.output.StructuredOutputException;
import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolInvocationException;
import dev.axiom.tools.ToolRegistry;
import dev.axiom.verify.AttestedTool;
import dev.axiom.verify.Certificate;
import dev.axiom.verify.VerificationException;
import dev.axiom.verify.Verifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * The classic ReAct loop (Reason + Act), hardened for production:
 * <ul>
 *   <li>Tool errors are fed back to the LLM as observations so it self-corrects
 *       instead of crashing the run.</li>
 *   <li>Denied approvals are reported as observations, letting the agent replan.</li>
 *   <li>Every step emits an {@link AgentEvent} for tracing and UIs.</li>
 *   <li>Tool calls run with per-tool timeouts.</li>
 *   <li>When the client supports streaming, tokens are emitted as
 *       {@link AgentEvent.StreamToken} while the agent still acts only on the
 *       complete turn.</li>
 *   <li>When {@code withJournalRoot(...)} is configured, every event is
 *       journaled to an append-only log; {@link #resume(RunJournal)} rebuilds
 *       state after a crash and replays completed tool calls instead of
 *       re-executing them.</li>
 * </ul>
 */
public final class ReActAgent {
    private final AgentConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    /** Set when durability is enabled — either freshly created by {@link #run}
     * or supplied by {@link #resume}. */
    private RunJournal journal;

    /** Daemon pool so runaway tools never pin the JVM. */
    private static final ExecutorService TOOL_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "axiom-tool");
        t.setDaemon(true);
        return t;
    });

    public ReActAgent(AgentConfig config) {
        this.config = config;
    }

    /** The journal backing the current/last run, or null when durability is off. */
    public RunJournal journal() {
        return journal;
    }

    /** Emit to listeners and, when durability is on, to the run journal. */
    private void emit(AgentEvent event) {
        config.emit(event);
        if (journal != null) {
            journal.appendEvent(event);
        }
    }

    /** A typed run: the coerced value plus the run's real token usage and latency. */
    public record TypedRun<T>(T value, ChatResponse.TokenUsage usage, long latencyMs) {}

    /**
     * The journal replay of a crashed run: rebuilt messages plus bookkeeping.
     * {@code recordedResults} is keyed by idempotency key (see
     * {@link #idempotencyKey(ToolCallRequest)}); {@code startedCallKeys} holds
     * every key whose {@code tool_call_started} record was journaled, so
     * resume can tell a completed call from a crash-window call.
     */
    public record ResumeTranscript(List<ChatMessage> messages,
                                   Map<String, String> recordedResults,
                                   Set<String> startedCallKeys,
                                   List<ToolCallRequest> pendingCalls,
                                   ChatResponse.TokenUsage totalUsage,
                                   int toolCallsMade,
                                   int lastIteration,
                                   boolean completed,
                                   AgentResult result) {}

    /** A resumed run: the result plus the full rebuilt transcript. */
    public record ResumedRun(AgentResult result, List<ChatMessage> messages) {}

    /** Run the agent on a task and return the final result. */
    public AgentResult run(String task) {
        return runWithTranscript(task).result();
    }

    /**
     * Run the agent and coerce its final answer into a compile-time type.
     * The model's output is constrained to the JSON Schema generated from
     * {@code outputType} and deserialized with Jackson — a mismatch raises
     * {@link StructuredOutputException} instead of silently corrupting data.
     */
    public <T> T runFor(String task, Class<T> outputType) {
        return runForWithStats(task, outputType).value();
    }

    /**
     * Like {@link #runFor} but also returns the run's real token usage and
     * wall-clock latency — the numbers the eval harness and benchmark
     * receipts are built on.
     */
    public <T> TypedRun<T> runForWithStats(String task, Class<T> outputType) {
        long start = System.currentTimeMillis();
        RunWithTranscript t = runWithTranscript(task);
        Formatted<T> f = formatTranscript(t.messages(), t.result().iterations(), outputType);
        return new TypedRun<>(f.value(),
            t.result().tokenUsage().add(f.usage()),
            System.currentTimeMillis() - start);
    }

    /**
     * Coerce an already-obtained transcript into a compile-time type via one
     * JSON-schema-constrained model call. Used by {@link #runForWithStats} and
     * by durable resume of typed runs.
     */
    public <T> T formatAs(List<ChatMessage> messages, int baseIteration, Class<T> outputType) {
        return formatTranscript(messages, baseIteration, outputType).value();
    }

    private record Formatted<T>(T value, ChatResponse.TokenUsage usage) {}

    private record RunWithTranscript(AgentResult result, List<ChatMessage> messages) {}

    private record LoopState(List<ChatMessage> messages, int iteration,
                             ChatResponse.TokenUsage totalUsage, int toolCallsMade,
                             Map<String, String> replayedResults) {}

    private RunWithTranscript runWithTranscript(String task) {
        String screened = applyInputGuardrails(task);
        if (config.journalRoot() != null) {
            journal = RunJournal.create(config.journalRoot());
            journal.appendRunStarted(screened, configSnapshot());
        }
        emit(new AgentEvent.RunStarted(Instant.now(), screened));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(config.systemPrompt()));
        if (config.memory() != null) {
            messages.addAll(config.memory().history());
        }
        messages.add(ChatMessage.user(screened));

        LoopState state = new LoopState(messages, 0,
            ChatResponse.TokenUsage.empty(), 0, Map.of());
        AgentResult result = runLoop(state);
        persistMemory(messages);
        return new RunWithTranscript(result, List.copyOf(messages));
    }

    /**
     * Resume a crashed run from its journal. The transcript is rebuilt from
     * journaled events; completed tool calls are replayed from their recorded
     * results and never re-executed (exactly-once); tool calls that started
     * but never completed are re-executed only when the tool is declared
     * {@code idempotent=true} — otherwise resume aborts with
     * {@link DurableException} instead of risking a double side effect; calls
     * that never started are re-executed (at-least-once). New events continue
     * to be appended to the same journal.
     */
    public AgentResult resume(RunJournal journal) {
        return resumeWithTranscript(journal).result();
    }

    /** Like {@link #resume} but also returns the rebuilt transcript. */
    public ResumedRun resumeWithTranscript(RunJournal journal) {
        this.journal = journal;
        ResumeTranscript t = replay(journal);
        if (t.completed()) {
            return new ResumedRun(t.result(), t.messages());
        }
        if (config.budget() != null && t.totalUsage().totalTokens() > 0) {
            // Money spent before the crash still counts against the budget.
            config.budget().charge(config.client().model(), t.totalUsage());
        }
        List<ChatMessage> messages = new ArrayList<>(t.messages());
        int toolCallsMade = t.toolCallsMade();
        for (ToolCallRequest call : t.pendingCalls()) {
            toolCallsMade++;
            String observation = executeResumedToolCall(call, config.tools(), t);
            messages.add(ChatMessage.toolResult(call.id(), call.name(), observation));
        }
        LoopState state = new LoopState(messages, t.lastIteration(), t.totalUsage(),
            toolCallsMade, t.recordedResults());
        AgentResult result = runLoop(state);
        persistMemory(messages);
        return new ResumedRun(result, List.copyOf(messages));
    }

    /**
     * Continue a run from an already-established conversation prefix — the
     * primitive behind replay forks ({@code dev.axiom.replay}).
     *
     * <p>{@code prefixMessages} is the full message list as of just before
     * turn {@code completedIterations + 1}; {@code pendingResponse} is the
     * model response for that turn, already obtained (recorded, scripted,
     * or otherwise supplied — it is <b>never</b> fetched from the LLM here).
     * It is journaled as an {@code LlmResponse} like any model turn; callers
     * record lineage (fork-of, scripted) in {@code lineage}, merged into the
     * {@code run_started} config.
     *
     * <p>From the following turn on, the loop runs normally against
     * {@code config.client()} with real tool dispatch, guardrails, approvals,
     * and the side-effect ledger — all recorded in a fresh journal, so the
     * original run's journal is never touched.
     *
     * <p>Deliberate differences from {@link #run}: input guardrails are not
     * re-applied (the task was screened in the original run — pass the
     * recorded, screened task), and conversation memory is not persisted
     * (a hypothetical branch must not pollute the user's memory store).
     */
    public AgentResult runFromState(String task, List<ChatMessage> prefixMessages,
                                    int completedIterations,
                                    ChatResponse.TokenUsage usageSoFar,
                                    int toolCallsMadeSoFar,
                                    ChatResponse pendingResponse,
                                    Map<String, Object> lineage) {
        if (pendingResponse == null) {
            throw new IllegalArgumentException("pendingResponse is required");
        }
        List<ChatMessage> messages = new ArrayList<>(prefixMessages);
        if (config.journalRoot() != null) {
            journal = RunJournal.create(config.journalRoot());
            Map<String, Object> snap = configSnapshot();
            if (lineage != null) snap.putAll(lineage);
            journal.appendRunStarted(task, snap);
        }
        emit(new AgentEvent.RunStarted(Instant.now(), task));

        int iteration = completedIterations + 1;
        emit(new AgentEvent.LlmRequest(Instant.now(), iteration));
        emit(new AgentEvent.LlmResponse(Instant.now(), iteration, pendingResponse));
        ChatResponse.TokenUsage totalUsage = usageSoFar.add(pendingResponse.usage());
        chargeBudget(pendingResponse.usage());

        int toolCallsMade = toolCallsMadeSoFar;
        if (!pendingResponse.hasToolCalls()) {
            String answer = applyOutputGuardrails(pendingResponse.content());
            AgentResult result = new AgentResult(
                answer, iteration, toolCallsMade, totalUsage, true);
            emit(new AgentEvent.RunFinished(Instant.now(), result));
            messages.add(ChatMessage.assistant(answer));
            return result;
        }
        messages.add(ChatMessage.assistantWithToolCalls(
            pendingResponse.content(), pendingResponse.toolCalls()));
        for (ToolCallRequest call : pendingResponse.toolCalls()) {
            toolCallsMade++;
            String observation = executeToolCall(call, config.tools(), Map.of());
            messages.add(ChatMessage.toolResult(call.id(), call.name(), observation));
        }
        return runLoop(new LoopState(messages, iteration, totalUsage, toolCallsMade, Map.of()));
    }

    /**
     * Replay a journal into a transcript without emitting events or executing
     * anything. Public so {@link dev.axiom.durable.AgentRun} can resume typed
     * runs (replay the transcript, then run the formatting step).
     */
    public ResumeTranscript replay(RunJournal journal) {
        List<ChatMessage> messages = new ArrayList<>();
        Map<String, String> recordedResults = new LinkedHashMap<>();
        Set<String> startedCallKeys = new HashSet<>();
        List<ToolCallRequest> pending = new ArrayList<>();
        ChatResponse.TokenUsage totalUsage = ChatResponse.TokenUsage.empty();
        int toolCallsMade = 0;
        int lastIteration = 0;
        boolean completed = false;
        AgentResult result = null;
        boolean sawStart = false;
        String runId = journal.runId();

        for (RunJournal.Record record : journal.readAll()) {
            if (record instanceof RunJournal.RunStarted rs) {
                sawStart = true;
                messages.add(ChatMessage.system(config.systemPrompt()));
                messages.add(ChatMessage.user(rs.task()));
            } else if (record instanceof RunJournal.ToolCallStarted ts) {
                startedCallKeys.add(ts.idempotencyKey());
            } else if (record instanceof RunJournal.ToolCallCompleted tc) {
                recordedResults.put(tc.idempotencyKey(), tc.result());
                startedCallKeys.add(tc.idempotencyKey());
            } else if (record instanceof RunJournal.Event ev
                    && ev.event() instanceof AgentEvent.LlmResponse lr) {
                lastIteration = lr.iteration();
                totalUsage = totalUsage.add(lr.response().usage());
                messages.add(ChatMessage.assistantWithToolCalls(
                    lr.response().content(), lr.response().toolCalls()));
                pending = new ArrayList<>(lr.response().toolCalls());
            } else if (record instanceof RunJournal.Event ev2
                    && ev2.event() instanceof AgentEvent.ToolCallFinished tf) {
                // Legacy journals (pre side-effect ledger): a finished event
                // is a completion. Synthesize the idempotency key the new
                // ledger would have used so old journals resume unchanged.
                recordedResults.putIfAbsent(runId + "#" + tf.call().id(), tf.result());
                toolCallsMade++;
                messages.add(ChatMessage.toolResult(tf.call().id(), tf.call().name(), tf.result()));
                String finishedId = tf.call().id();
                pending.removeIf(c -> c.id().equals(finishedId));
            } else if (record instanceof RunJournal.Event ev3
                    && ev3.event() instanceof AgentEvent.RunFinished rf) {
                completed = true;
                result = rf.result();
            }
        }
        if (!sawStart) {
            throw new DurableException("Journal has no run_started record: " + journal.dir());
        }
        return new ResumeTranscript(List.copyOf(messages), Map.copyOf(recordedResults),
            Set.copyOf(startedCallKeys), List.copyOf(pending), totalUsage, toolCallsMade,
            lastIteration, completed, result);
    }

    /** Snapshot recorded in the journal so a run can be rebuilt without the original config. */
    private Map<String, Object> configSnapshot() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("model", config.client().model());
        snap.put("systemPrompt", config.systemPrompt());
        snap.put("maxIterations", config.maxIterations());
        snap.put("temperature", config.temperature());
        snap.put("toolHolders",
            new ArrayList<>(new LinkedHashSet<>(config.tools().toolHolderClasses().values())));
        return snap;
    }

    private AgentResult runLoop(LoopState state) {
        ToolRegistry registry = config.tools();
        List<ToolDefinition> toolDefs = List.copyOf(registry.all());
        LlmClient.LlmOptions options =
            new LlmClient.LlmOptions(config.temperature(), 4096);

        for (int iteration = state.iteration() + 1; iteration <= config.maxIterations(); iteration++) {
            ChatResponse response = doChat(state.messages(), toolDefs, options, iteration);
            ChatResponse.TokenUsage totalUsage = state.totalUsage().add(response.usage());
            chargeBudget(response.usage());

            if (!response.hasToolCalls()) {
                String answer = applyOutputGuardrails(response.content());
                AgentResult result = new AgentResult(
                    answer, iteration, state.toolCallsMade(), totalUsage, true);
                emit(new AgentEvent.RunFinished(Instant.now(), result));
                state.messages().add(ChatMessage.assistant(answer));
                return result;
            }

            state.messages().add(ChatMessage.assistantWithToolCalls(response.content(), response.toolCalls()));

            int toolCallsMade = state.toolCallsMade();
            for (ToolCallRequest call : response.toolCalls()) {
                toolCallsMade++;
                String observation = executeToolCall(call, registry, state.replayedResults());
                state.messages().add(ChatMessage.toolResult(call.id(), call.name(), observation));
            }
            state = new LoopState(state.messages(), iteration, totalUsage, toolCallsMade,
                state.replayedResults());
        }

        // Out of iterations: ask for a best-effort final answer with no tools.
        ChatResponse closing = doChat(withClosingInstruction(state.messages()), List.of(),
            options, config.maxIterations() + 1);
        ChatResponse.TokenUsage totalUsage = state.totalUsage().add(closing.usage());
        chargeBudget(closing.usage());
        String answer = applyOutputGuardrails(closing.content());
        AgentResult result = new AgentResult(
            answer, config.maxIterations(), state.toolCallsMade(), totalUsage, false);
        emit(new AgentEvent.RunFinished(Instant.now(), result));
        state.messages().add(ChatMessage.assistant(answer));
        return result;
    }

    /**
     * Run the task through every configured input guardrail. Blocks abort
     * with {@link GuardrailViolationException} (after a
     * {@link AgentEvent.GuardrailBlocked} event); replaces substitute the
     * sanitized task and continue.
     */
    private String applyInputGuardrails(String task) {
        String current = task;
        for (Guardrail g : config.guardrails()) {
            Verdict v = g.checkInput(current);
            if (v instanceof Verdict.Block b) {
                emit(new AgentEvent.GuardrailBlocked(
                    Instant.now(), g.name(), "input", b.reason()));
                throw new GuardrailViolationException(g.name(), b.reason());
            } else if (v instanceof Verdict.Replace r) {
                current = r.text();
            }
        }
        return current;
    }

    /** Same contract as {@link #applyInputGuardrails}, for the final answer. */
    private String applyOutputGuardrails(String answer) {
        String current = answer == null ? "" : answer;
        for (Guardrail g : config.guardrails()) {
            Verdict v = g.checkOutput(current);
            if (v instanceof Verdict.Block b) {
                emit(new AgentEvent.GuardrailBlocked(
                    Instant.now(), g.name(), "output", b.reason()));
                throw new GuardrailViolationException(g.name(), b.reason());
            } else if (v instanceof Verdict.Replace r) {
                current = r.text();
            }
        }
        return current;
    }

    /**
     * One model turn. Uses streaming when the client supports it — tokens are
     * emitted as {@link AgentEvent.StreamToken} for live UIs — but always
     * returns the complete turn before the agent acts on it.
     */
    private ChatResponse doChat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                LlmClient.LlmOptions options, int iteration) {
        emit(new AgentEvent.LlmRequest(Instant.now(), iteration));
        ChatResponse response;
        if (config.client() instanceof StreamingLlmClient streaming) {
            response = streaming.chatStream(messages, tools, options,
                token -> emit(new AgentEvent.StreamToken(Instant.now(), iteration, token)));
        } else {
            response = config.client().chat(messages, tools, options);
        }
        emit(new AgentEvent.LlmResponse(Instant.now(), iteration, response));
        return response;
    }

    private <T> Formatted<T> formatTranscript(List<ChatMessage> messages, int baseIteration,
                                             Class<T> outputType) {
        String schema = OutputSchema.generate(outputType);

        List<ChatMessage> m = new ArrayList<>(messages);
        m.add(ChatMessage.user(
            "Format your final answer as JSON matching this schema. Return ONLY the JSON, no prose:\n" + schema));

        LlmClient.LlmOptions options =
            new LlmClient.LlmOptions(config.temperature(), 4096).withJsonSchema(schema);
        ChatResponse formatted = doChat(m, List.of(), options, baseIteration + 1);
        chargeBudget(formatted.usage());

        String json = formatted.content() == null ? "" : formatted.content().trim();
        // Tolerate markdown fences some providers add despite instructions.
        if (json.startsWith("```")) {
            json = json.replaceFirst("(?s)^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        try {
            return new Formatted<>(mapper.readValue(json, outputType), formatted.usage());
        } catch (Exception e) {
            throw new StructuredOutputException(
                "Model output did not parse as " + outputType.getSimpleName()
                    + ": " + e.getMessage(),
                formatted.content(), e);
        }
    }

    private void persistMemory(List<ChatMessage> messages) {
        if (config.memory() != null) {
            config.memory().store(messages);
        }
    }

    /**
     * Charge one LLM call against the run's budget (if configured) and emit
     * {@link AgentEvent.BudgetUpdated}. The event fires even when the charge
     * breaches the budget — the {@link BudgetExceededException} then aborts
     * the run with the snapshot attached.
     */
    private void chargeBudget(ChatResponse.TokenUsage usage) {
        Budget budget = config.budget();
        if (budget == null) return;
        try {
            budget.charge(config.client().model(), usage);
        } finally {
            emit(new AgentEvent.BudgetUpdated(Instant.now(), usage, budget.snapshot()));
        }
    }

    /**
     * The idempotency key for one tool call: {@code runId + "#" + callId}.
     *
     * <p>Derived from the call id rather than the tool name + argument hash
     * deliberately: the same tool with identical arguments may legitimately
     * be called several times in one run (each call gets its own LLM-issued
     * id), and the transcript pairs {@code tool_result} messages to calls by
     * id. The run id scopes the key to this journal, so keys are stable
     * across resume and unique across runs.
     */
    private String idempotencyKey(ToolCallRequest call) {
        return (journal != null ? journal.runId() : "ephemeral") + "#" + call.id();
    }

    /**
     * Execute a pending tool call during resume, applying the side-effect
     * ledger semantics:
     * <ul>
     *   <li>Completed (key has a recorded result) → replay, never re-execute.</li>
     *   <li>Crash window (key started, never completed) → re-execute only if
     *       the tool is declared {@code idempotent=true}; otherwise throw
     *       {@link DurableException} naming the ambiguous call instead of
     *       risking a double side effect.</li>
     *   <li>Never started → re-execute (at-least-once), as before.</li>
     * </ul>
     */
    private String executeResumedToolCall(ToolCallRequest call, ToolRegistry registry,
                                          ResumeTranscript t) {
        String key = idempotencyKey(call);
        if (!t.recordedResults().containsKey(key) && t.startedCallKeys().contains(key)) {
            ToolDefinition def = registry.find(call.name()).orElse(null);
            String defName = def != null ? def.name() : call.name();
            if (def == null || !def.idempotent()) {
                throw new DurableException((
                    "Refusing to resume: tool call '%s' (tool '%s', arguments %s) started before "
                    + "the crash (idempotency key '%s') but never completed, and the tool is not "
                    + "declared idempotent. Re-executing it could apply its side effect twice. "
                    + "Either mark the tool idempotent=true on its ToolDefinition if re-execution "
                    + "with identical arguments is safe, or resolve the ambiguity manually "
                    + "(inspect the journal at %s, then delete the run or complete the call by hand).")
                    .formatted(call.id(), defName, call.arguments(), key, journal.dir()));
            }
            // Declared idempotent: safe to re-execute.
        }
        return executeToolCall(call, registry, t.recordedResults());
    }

    private String executeToolCall(ToolCallRequest call, ToolRegistry registry,
                                   Map<String, String> replayedResults) {
        String idemKey = idempotencyKey(call);
        applyToolCallGuardrails(call);
        emit(new AgentEvent.ToolCallStarted(Instant.now(), call));
        long start = System.currentTimeMillis();
        String result;
        String replayed = replayedResults.get(idemKey);
        if (replayed != null) {
            // Exactly-once: this call completed before the crash — reuse the
            // recorded observation instead of executing the tool again.
            result = replayed;
        } else {
            try {
                var def = registry.find(call.name());
                if (def.isEmpty()) {
                    result = "ERROR: unknown tool '" + call.name() + "'";
                } else if (def.get().requiresApproval()) {
                    emit(new AgentEvent.ApprovalRequested(
                        Instant.now(), call.name(), call.arguments()));
                    boolean approved = config.approvalHandler().approve(def.get(), call.arguments());
                    result = approved
                        ? invokeWithLedger(registry, def.get(), call, idemKey)
                        : "DENIED: the human operator rejected this tool call. Adjust your plan to proceed without it.";
                } else {
                    result = invokeWithLedger(registry, def.get(), call, idemKey);
                }
            } catch (ToolInvocationException e) {
                // Feed the error back so the model can self-correct.
                result = "ERROR: " + e.getMessage();
            } catch (VerificationException ve) {
                // Fail-closed: a verification failure aborts the run. It is
                // never converted into a model observation — a tool whose
                // effects cannot be confirmed must not be reasoned around.
                throw ve;
            } catch (Exception e) {
                result = "ERROR: unexpected failure: " + e.getMessage();
            }
        }
        emit(new AgentEvent.ToolCallFinished(
            Instant.now(), call, result, System.currentTimeMillis() - start));
        // Lifecycle hook for stateful guardrails (capability tokens, …).
        // Runs for replayed completions on resume too, so journaled truth
        // rebuilds guardrail state in order.
        for (Guardrail g : config.guardrails()) {
            g.onToolCompleted(call.name());
        }
        return result;
    }

    /**
     * Run every configured guardrail's per-tool-call check before dispatch.
     * A block aborts the run with {@link GuardrailViolationException} (after a
     * {@link AgentEvent.GuardrailBlocked} event with side {@code "tool"}) —
     * capability violations are fail-closed and journaled, never silent.
     * A {@code Replace} verdict is meaningless for tool calls and is treated
     * as a block rather than silently bypassed.
     */
    private void applyToolCallGuardrails(ToolCallRequest call) {
        for (Guardrail g : config.guardrails()) {
            Verdict v = g.checkToolCall(call.name(), call.arguments());
            if (v instanceof Verdict.Block b) {
                emit(new AgentEvent.GuardrailBlocked(
                    Instant.now(), g.name(), "tool", b.reason()));
                throw new GuardrailViolationException(g.name(), b.reason());
            } else if (v instanceof Verdict.Replace) {
                emit(new AgentEvent.GuardrailBlocked(Instant.now(), g.name(), "tool",
                    "guardrail returned Replace for a tool call, which is not supported"));
                throw new GuardrailViolationException(g.name(),
                    "guardrail returned Replace for a tool call, which is not supported");
            }
        }
    }

    /**
     * Invoke a tool with the side-effect ledger wrapped around the tool body:
     * the {@code tool_call_started} record is journaled immediately before
     * the body runs (after any approval), and {@code tool_call_completed}
     * right after it returns — including when the tool fails, since the error
     * observation is its completion. Anything that escapes without a
     * completion record (approval-handler death, JVM crash) leaves the crash
     * window: a started key with no completion.
     */
    private String invokeWithLedger(ToolRegistry registry, ToolDefinition def,
                                    ToolCallRequest call, String idemKey) {
        if (journal != null) {
            journal.appendToolCallStarted(idemKey, call);
        }
        String result;
        try {
            result = invokeWithTimeout(registry, def, call);
        } catch (ToolInvocationException e) {
            // The tool ran and failed: the error observation is its
            // completion, so resume replays it instead of re-executing.
            result = "ERROR: " + e.getMessage();
        }
        if (journal != null) {
            journal.appendToolCallCompleted(idemKey, result);
        }
        // Proof-carrying tools: attest the effect independently, then
        // re-verify the certificate — all journaled. Only for tool bodies
        // that actually ran (error observations have nothing to attest).
        // On resume the recorded result is replayed instead, so the
        // journaled certificates stand as the record.
        if (!result.startsWith("ERROR:")
                && def.invoker() instanceof AttestedTool.AttestingInvoker ai) {
            verifyAttestedCall(def.name(), call, ai.verifier(), result);
        }
        return result;
    }

    /**
     * Attest and independently re-verify an attested tool call, journaling
     * both as first-class events. Any failure throws
     * {@link VerificationException}, which aborts the run fail-closed
     * (see {@link #executeToolCall}).
     */
    private void verifyAttestedCall(String toolName, ToolCallRequest call,
                                    Verifier verifier, String result) {
        Certificate cert;
        try {
            cert = verifier.attest(toolName, call.id(), call.arguments(), result);
        } catch (VerificationException ve) {
            emit(new AgentEvent.CertificateVerified(Instant.now(), call.id(),
                toolName, verifier.kind(), false,
                "attestation failed: " + ve.getMessage()));
            throw ve;
        }
        emit(new AgentEvent.CertificateIssued(Instant.now(), cert));
        try {
            verifier.check(cert);
        } catch (VerificationException ve) {
            emit(new AgentEvent.CertificateVerified(Instant.now(), call.id(),
                toolName, verifier.kind(), false, ve.getMessage()));
            throw ve;
        }
        emit(new AgentEvent.CertificateVerified(Instant.now(), call.id(),
            toolName, verifier.kind(), true, "independent re-verification passed"));
    }

    /**
     * Invoke a tool with its declared timeout. A hanging tool is cancelled and
     * reported as an observation — it can never wedge the agent loop.
     */
    private String invokeWithTimeout(ToolRegistry registry, ToolDefinition def, ToolCallRequest call) {
        Future<String> future = TOOL_POOL.submit(
            () -> stringify(registry.invoke(call.name(), call.arguments())));
        try {
            return future.get(def.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return "ERROR: tool '%s' timed out after %ds".formatted(call.name(), def.timeoutSeconds());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ToolInvocationException tie) throw tie;
            throw new ToolInvocationException(
                "Tool '%s' failed: %s".formatted(call.name(),
                    cause != null ? cause.getMessage() : "unknown error"),
                cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolInvocationException("Tool '" + call.name() + "' was interrupted", e);
        }
    }

    private List<ChatMessage> withClosingInstruction(List<ChatMessage> messages) {
        List<ChatMessage> copy = new ArrayList<>(messages);
        copy.add(ChatMessage.user(
            "You have run out of steps. Provide your best final answer now based on what you learned."));
        return copy;
    }

    private String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return s;
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}

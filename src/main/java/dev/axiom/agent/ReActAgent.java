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
import dev.axiom.tools.ToolCallRepair;
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
import java.util.Optional;
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
    /**
     * Bounded pool for tool execution. The bound prevents a flood of
     * timed-out tools from creating unbounded threads. Note: Java
     * interruption is cooperative — {@code future.cancel(true)} requests
     * interruption, but a tool that ignores interrupts will continue
     * running until it finishes or the JVM exits. Tool timeouts are
     * therefore a best-effort deadline, not a guarantee of termination.
     * For untrusted tools, use process-level isolation instead.
     */
    private static final ExecutorService TOOL_POOL =
        new java.util.concurrent.ThreadPoolExecutor(
            0, 32, // core 0, max 32 threads
            60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "axiom-tool");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

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
                             Map<String, String> replayedResults,
                             int consecutiveBlanks) {}

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
            ChatResponse.TokenUsage.empty(), 0, Map.of(), 0);
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
        // A re-executed pending call may have been terminal (e.g. the run
        // crashed right after the answer tool completed) — commit it.
        AgentResult terminal = terminalResult(t.pendingCalls(), messages,
            t.lastIteration(), toolCallsMade, t.totalUsage());
        if (terminal != null) {
            persistMemory(messages);
            return new ResumedRun(terminal, List.copyOf(messages));
        }
        LoopState state = new LoopState(messages, t.lastIteration(), t.totalUsage(),
            toolCallsMade, t.recordedResults(), 0);
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
        // The pending turn is repaired like any live turn: a blank or
        // mangled name is re-attributed, an unrecoverable blank name is
        // dropped so it can never reach dispatch or provider history.
        // The journal records the repaired turn, matching doChat.
        RepairedTurn repairedTurn = repairToolCalls(pendingResponse);
        ChatResponse pendingResponseRepaired = repairedTurn.response();
        emit(new AgentEvent.LlmResponse(Instant.now(), iteration, pendingResponseRepaired));
        ChatResponse.TokenUsage totalUsage = usageSoFar.add(pendingResponseRepaired.usage());
        chargeBudget(pendingResponseRepaired.usage());

        int toolCallsMade = toolCallsMadeSoFar;
        if (!pendingResponseRepaired.hasToolCalls()) {
            if (!repairedTurn.allCallsDropped()
                    && pendingResponseRepaired.content() != null
                    && !pendingResponseRepaired.content().isBlank()) {
                String content = pendingResponseRepaired.content();
                if (isProseAnswer(content)) {
                    content = extractBareAnswer(messages, content, iteration);
                }
                String answer = applyOutputGuardrails(content);
                AgentResult result = new AgentResult(
                    answer, iteration, toolCallsMade, totalUsage, true);
                emit(new AgentEvent.RunFinished(Instant.now(), result));
                messages.add(ChatMessage.assistant(answer));
                return result;
            }
            // Blank narration or a turn whose calls were all dropped is not
            // an answer: nudge and continue the loop for a fresh model turn
            // instead of committing the placeholder (or "").
            messages.add(ChatMessage.user(
                "You returned no usable tool calls and no text. Please continue: " +
                "call a tool to gather more information, or provide your final answer as text."));
        } else {
            messages.add(ChatMessage.assistantWithToolCalls(
                pendingResponseRepaired.content(), pendingResponseRepaired.toolCalls()));
            for (ToolCallRequest call : pendingResponseRepaired.toolCalls()) {
                toolCallsMade++;
                String observation = executeToolCall(call, config.tools(), Map.of());
                messages.add(ChatMessage.toolResult(call.id(), call.name(), observation));
            }
        }
        return runLoop(new LoopState(messages, iteration, totalUsage, toolCallsMade, Map.of(), 0));
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
                // Sanitize without emitting: replay must not produce events,
                // and legacy journals (pre repair) may carry blank names that
                // must never be re-dispatched on resume.
                RepairedTurn repaired = repairTurn(lr.response(), config.tools());
                messages.add(ChatMessage.assistantWithToolCalls(
                    repaired.response().content(), repaired.response().toolCalls()));
                pending = new ArrayList<>(repaired.response().toolCalls());
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
        // Model-agnostic runaway guard: mechanical signals (exact repeats,
        // error streaks, fruitless iterations), never model identity.
        StagnationController stagnation = new StagnationController(
            config.stagnationRepeatLimit(), config.stagnationErrorLimit(),
            config.stagnationFruitlessLimit());

        // Task Ledger (Magentic-One pattern, 2026-10-04): track facts,
        // guesses, and plan across iterations. Evidence: removing ledgers
        // drops GAIA 31% (arXiv:2411.04468).
        TaskLedger ledger = new TaskLedger();

        for (int iteration = state.iteration() + 1; iteration <= config.maxIterations(); iteration++) {
            Turn turn = doChat(state.messages(), toolDefs, options, iteration);
            ChatResponse response = turn.response();
            ChatResponse.TokenUsage totalUsage = state.totalUsage().add(response.usage());
            chargeBudget(response.usage());

            if (turn.allCallsDropped()) {
                // Every tool call was malformed and dropped: the turn's
                // narration text (e.g. "using a tool") is NOT an answer and
                // must never be committed as one. Nudge the model to
                // continue instead. Bounded by maxIterations and the
                // stagnation controller (counts as a fruitless turn).
                state.messages().add(ChatMessage.user(
                    "All of your tool calls were malformed and have been dropped. " +
                    "Please continue: call a valid tool to gather more information, " +
                    "or provide your final answer as text."));
                stagnation.record(List.of(), List.of());
                Optional<String> stagnated = stagnation.shouldStop();
                if (stagnated.isPresent()) {
                    emit(new AgentEvent.StagnationStopped(Instant.now(), stagnated.get()));
                    break;
                }
                state = new LoopState(state.messages(), iteration, totalUsage,
                    state.toolCallsMade(), state.replayedResults(), 0);
                continue;
            }

            if (!response.hasToolCalls()) {
                String content = response.content();
                if (content == null || content.isBlank()) {
                    // The model returned neither tool calls nor text. Committing
                    // an empty string here is a guaranteed failure — nudge the
                    // model to continue instead. Bounded by maxIterations and
                    // the stagnation controller (counts as a fruitless turn).
                    //
                    // After 2 consecutive blanks, the gentle nudge is not
                    // working — switch to a FORCED answer demand. A best-effort
                    // guess beats a guaranteed-empty failure.
                    int blanks = state.consecutiveBlanks() + 1;
                    String nudge;
                    if (blanks >= 3) {
                        nudge = "You have returned blank responses repeatedly. "
                            + "You MUST now provide your best answer as text. "
                            + "Do not return blank. Give your best-supported guess "
                            + "based on what you have learned so far.";
                    } else {
                        nudge = "You returned no tool calls and no text. Please continue: "
                            + "call a tool to gather more information, or provide your final answer as text.";
                    }
                    state.messages().add(ChatMessage.user(nudge));
                    stagnation.record(List.of(), List.of());
                    Optional<String> stagnated = stagnation.shouldStop();
                    if (stagnated.isPresent()) {
                        emit(new AgentEvent.StagnationStopped(Instant.now(), stagnated.get()));
                        break;
                    }
                    state = new LoopState(state.messages(), iteration, totalUsage,
                        state.toolCallsMade(), state.replayedResults(), blanks);
                    continue;
                }
                // Prose-like terminal text gets the same hygiene pass as the
                // answer-tool path: extract the bare value from the model's
                // own draft. Short answers skip this entirely.
                if (isProseAnswer(content)) {
                    content = extractBareAnswer(state.messages(), content, iteration);
                }
                String answer = applyOutputGuardrails(content);
                // Verification pass: check the answer against the question
                // and ledger facts before committing.
                String taskText = extractTaskText(state.messages());
                answer = verifyAnswer(taskText, answer, ledger, options, iteration);
                AgentResult result = new AgentResult(
                    answer, iteration, state.toolCallsMade(), totalUsage, true);
                emit(new AgentEvent.RunFinished(Instant.now(), result));
                state.messages().add(ChatMessage.assistant(answer));
                return result;
            }

            state.messages().add(ChatMessage.assistantWithToolCalls(response.content(), response.toolCalls()));

            int toolCallsMade = state.toolCallsMade();
            List<String> observations = new ArrayList<>(response.toolCalls().size());
            for (ToolCallRequest call : response.toolCalls()) {
                toolCallsMade++;
                String observation = executeToolCall(call, registry, state.replayedResults());
                observations.add(observation);
                state.messages().add(ChatMessage.toolResult(call.id(), call.name(), observation));
                // Ledger: extract facts from substantive tool results.
                if (config.taskLedgerEnabled()) {
                    ledger.addFact(extractFact(call.name(), observation));
                }
            }
            // Ledger: check if re-planning is needed (stalled without new facts).
            // HF finding: exclude the stale plan from the replan prompt.
            if (config.taskLedgerEnabled() && ledger.needsReplan(4)) {
                String replanPrompt = "You have been working for "
                    + ledger.iterationsSinceNewFact()
                    + " iterations without new facts. Review and re-plan:\n\n"
                    + ledger.renderForReplan();
                state.messages().add(ChatMessage.user(replanPrompt));
                // Reset stall counter; the model's next turn is the fresh plan.
                // (We don't parse the plan — the prompt guides the model.)
            } else {
                if (config.taskLedgerEnabled()) ledger.recordStall();
            }
            AgentResult terminal = terminalResult(response.toolCalls(), state.messages(),
                iteration, toolCallsMade, totalUsage);
            if (terminal != null) return terminal;
            // Stagnation is checked after the terminal commit: a terminal
            // answer is progress, not stagnation.
            stagnation.record(response.toolCalls(), observations);
            Optional<String> stagnated = stagnation.shouldStop();
            if (stagnated.isPresent()) {
                emit(new AgentEvent.StagnationStopped(Instant.now(), stagnated.get()));
                break;
            }
            state = new LoopState(state.messages(), iteration, totalUsage, toolCallsMade,
                state.replayedResults(), 0);
        }

        // Out of iterations: ask for a best-effort final answer with no tools.
        ChatResponse closing = doChat(withClosingInstruction(state.messages()), List.of(),
            options, config.maxIterations() + 1).response();
        ChatResponse.TokenUsage totalUsage = state.totalUsage().add(closing.usage());
        chargeBudget(closing.usage());
        String closingContent = closing.content();
        // If the closing call returns blank, retry once with a forced demand.
        // Blank here is a guaranteed failure; a retry cannot make it worse.
        if (closingContent == null || closingContent.isBlank()) {
            List<ChatMessage> forcedMessages = new ArrayList<>(state.messages());
            forcedMessages.add(ChatMessage.user(
                "You MUST provide your best final answer now as text. Do not "
                + "return blank. Give your best-supported guess based on what "
                + "you learned."));
            ChatResponse retry = doChat(forcedMessages, List.of(),
                options, config.maxIterations() + 2).response();
            totalUsage = totalUsage.add(retry.usage());
            chargeBudget(retry.usage());
            if (retry.content() != null && !retry.content().isBlank()) {
                closingContent = retry.content();
            }
        }
        // Fix 2026-10-04: never return empty. Evidence: Qwen GAIA run —
        // 7673d772 and c365c1c7 failed with empty output. If still blank
        // after retry, extract the last substantive model text as a
        // best-effort answer rather than guaranteeing failure.
        if (closingContent == null || closingContent.isBlank()) {
            closingContent = extractLastSubstantiveText(state.messages());
        }
        if (isProseAnswer(closingContent)) {
            closingContent = extractBareAnswer(state.messages(), closingContent,
                config.maxIterations() + 1);
        }
        String answer = applyOutputGuardrails(closingContent);
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
    /**
     * Strips a single-entry map stringification from a terminal answer.
     * Some models emit {@code {answer=value}} instead of {@code value};
     * this unwraps it mechanically. Only the exact {@code {answer=...}}
     * shape is touched; anything else passes through unchanged.
     */
    static String unwrapAnswerWrapper(String answer) {
        if (answer == null) return "";
        String t = answer.trim();
        if (t.startsWith("{answer=") && t.endsWith("}")) {
            return t.substring("{answer=".length(), t.length() - 1).trim();
        }
        return answer;
    }

    private String applyOutputGuardrails(String answer) {
        String current = answer == null ? "" : answer;
        // Mechanical format normalization (not a content change): models
        // sometimes stringify a map, e.g. "{answer=Braintree, Honolulu}".
        // Unwrap the single-entry form so the scorer sees the value.
        current = unwrapAnswerWrapper(current);
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
     * A terminal tool ends the run: when the model invokes a tool named in
     * {@link AgentConfig#terminalTools()}, the tool's {@code answer}
     * argument (or its first argument) is committed as the run's output
     * instead of continuing the loop. Returns null when no call is
     * terminal. Output guardrails still apply to the committed answer.
     *
     * <p>Resume edge: if the run crashed after a terminal call completed but
     * before {@code RunFinished} was journaled, resume replays the recorded
     * tool results and the model is simply asked again — the commit is not
     * reconstructed from the journal. The common path (no crash) commits
     * exactly once.
     */
    private AgentResult terminalResult(List<ToolCallRequest> calls, List<ChatMessage> messages,
                                       int iteration, int toolCallsMade,
                                       ChatResponse.TokenUsage totalUsage) {
        for (ToolCallRequest call : calls) {
            if (!config.terminalTools().contains(call.name())) continue;
            String committed = committedAnswer(call);
            if (isProseAnswer(committed)) {
                // Model-agnostic answer hygiene: some models put explanation
                // in the terminal answer despite the tool contract. One
                // constrained pass extracts the bare value from the model's
                // OWN draft — formatting, never reinterpretation. Short
                // answers skip this entirely.
                committed = extractBareAnswer(messages, committed, iteration);
            }
            String answer = applyOutputGuardrails(committed);
            AgentResult result = new AgentResult(
                answer, iteration, toolCallsMade, totalUsage, true);
            emit(new AgentEvent.RunFinished(Instant.now(), result));
            messages.add(ChatMessage.assistant(answer));
            return result;
        }
        return null;
    }

    /** Heuristic: does the terminal value look like prose rather than a bare answer? */
    static boolean isProseAnswer(String answer) {
        if (answer == null) return false;
        String t = answer.trim();
        if (t.isEmpty()) return false;
        int words = t.split("\\s+").length;
        if (t.length() > 120 || t.contains("\n") || words > 25) return true;
        // Short thinking outputs: third-person self-reference, meta-commentary,
        // or incomplete (unclosed quote, ends mid-thought).
        String low = t.toLowerCase();
        if (low.contains("when the agent") || low.contains("the agent was asked")
            || low.startsWith("based on my research") || low.startsWith("i found that")
            || low.startsWith("according to my")) return true;
        // Unclosed quote: odd number of double-quotes means the model was
        // cut off mid-quotation (e.g. page header, not an answer).
        if (t.chars().filter(c -> c == '"').count() % 2 == 1) return true;
        if (t.endsWith(":") || t.endsWith(" for") || t.endsWith(" the")
            || t.endsWith(" a") || t.endsWith(" to")) return true;
        return false;
    }

    /** The task as originally posed: the first user message in the transcript. */
    private static String originalQuestion(List<ChatMessage> messages) {
        for (ChatMessage m : messages) {
            if (m.role() == ChatRole.USER && m.content() != null && !m.content().isBlank()) {
                return m.content();
            }
        }
        return "";
    }

    /**
     * Best-effort extraction of the bare answer value from a prose-like
     * terminal draft. Falls back to the draft when the extraction call
     * fails or returns nothing.
     */
    private String extractBareAnswer(List<ChatMessage> messages, String draft, int iteration) {
        List<ChatMessage> m = List.of(ChatMessage.user(
            "The agent was asked:\n" + originalQuestion(messages)
            + "\n\nIts draft final answer was:\n" + draft
            + "\n\nReply with ONLY the final answer value: a short string, a number, "
            + "or a comma-separated list. No explanation, no preamble, no quotes. "
            + "Strip formatting cruft: e.g. 'INT. THE CASTLE - DAY' → 'THE CASTLE', "
            + "'\"quoted\"' → 'quoted'."));
        try {
            ChatResponse r = doChat(m, List.of(),
                new LlmClient.LlmOptions(config.temperature(), 256), iteration + 1).response();
            chargeBudget(r.usage());
            String extracted = r.content() == null ? "" : r.content().trim();
            if (extracted.isEmpty()) return draft;
            // Regex fallback for common formatting the LLM might miss.
            extracted = stripFormattingCruft(extracted);
            return extracted.isEmpty() ? draft : extracted;
        } catch (Exception e) {
            return draft;
        }
    }

    /** The value a terminal tool commits: its {@code answer} argument, else its first argument. */
    private static String committedAnswer(ToolCallRequest call) {
        Map<String, Object> args = call.arguments();
        if (args == null || args.isEmpty()) return "";
        Object v = args.containsKey("answer") ? args.get("answer")
            : args.values().iterator().next();
        return v == null ? "" : String.valueOf(v);
    }

    /** Strip known formatting cruft: screenplay sluglines, quotes, etc. */
    static String stripFormattingCruft(String s) {
        String t = s.trim();
        // Fix 2026-10-04: strip leaked tool-call XML from final answers.
        // Evidence: Qwen GAIA run — 46719c30 had <tool_call> XML in output,
        // cf106601 had <｜DSML｜> XML in output. Both failed on exact match.
        // Note: ｜ is U+FF5C FULLWIDTH VERTICAL LINE, not ASCII pipe.
        var wrapped = java.util.regex.Pattern.compile(
            "^<[｜|]?DSML[｜|]?>(.*?)</[｜|]?DSML[｜|]?>$",
            java.util.regex.Pattern.DOTALL).matcher(t);
        if (wrapped.matches()) t = wrapped.group(1).trim();
        wrapped = java.util.regex.Pattern.compile(
            "^<tool_call>(.*?)</tool_call>$",
            java.util.regex.Pattern.DOTALL).matcher(t);
        if (wrapped.matches()) t = wrapped.group(1).trim();
        // Otherwise strip stray tags.
        t = t.replaceAll("<[｜|]?DSML[｜|]?>.*?</[｜|]?DSML[｜|]?>", "").trim();
        t = t.replaceAll("<tool_call>.*?</tool_call>", "").trim();
        t = t.replaceAll("<[｜|]?DSML[｜|]?>", "").replaceAll("</[｜|]?DSML[｜|]?>", "").trim();
        t = t.replaceAll("<tool_call>", "").replaceAll("</tool_call>", "").trim();
        // Screenplay slugline: "INT. THE CASTLE - DAY" → "THE CASTLE"
        var m = java.util.regex.Pattern.compile(
            "^(INT|EXT)\\.\\s*(.+?)\\s*-\\s*(DAY|NIGHT|DUSK|DAWN)$",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);
        if (m.matches()) return m.group(2).trim();
        // Balanced quotes
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    /**
     * Extract the original task text from the conversation (first user message).
     */
    private static String extractTaskText(java.util.List<ChatMessage> messages) {
        for (ChatMessage msg : messages) {
            if (msg.role() == ChatRole.USER && msg.content() != null && !msg.content().isBlank()) {
                return msg.content();
            }
        }
        return "";
    }

    /**
     * Verification pass (2026-10-04, GAIA research): separate LLM call that
     * checks the draft answer against the question and ledger facts before
     * committing. Evidence: cut false-success claims 23% → <1% in FTA study.
     *
     * <p>Returns the verified (possibly corrected) answer. Never returns blank.
     */
    private String verifyAnswer(String task, String draftAnswer, TaskLedger ledger,
                               LlmClient.LlmOptions options, int iteration) {
        if (draftAnswer == null || draftAnswer.isBlank()) return draftAnswer;
        // Skip if disabled in config (tests) or ledger is empty (no real work).
        if (!config.verificationEnabled()) return draftAnswer;

        List<ChatMessage> verifyMessages = new ArrayList<>();
        verifyMessages.add(ChatMessage.system(
            "You are a verification agent. Check if the draft answer correctly "
            + "answers the question based on the evidence. Be strict."));
        verifyMessages.add(ChatMessage.user(
            "QUESTION: " + task + "\n\n"
            + "EVIDENCE (verified facts):\n"
            + String.join("\n", ledger.facts()) + "\n\n"
            + "DRAFT ANSWER: " + draftAnswer + "\n\n"
            + "Does the draft answer correctly answer the question based on the "
            + "evidence? If YES, reply with the exact draft answer. If NO, reply "
            + "with the corrected answer (just the answer, no explanation)."));

        try {
            ChatResponse verifyResponse = doChat(verifyMessages, List.of(),
                options, iteration).response();
            String verified = verifyResponse.content();
            if (verified != null && !verified.isBlank()) {
                return stripFormattingCruft(verified.trim());
            }
        } catch (Exception e) {
            // Verification failed — fall back to the draft. A verification
            // error must never lose a valid answer.
        }
        return draftAnswer;
    }

    /**
     * Extract a concise fact from a tool observation for the Task Ledger.
     * Returns null if the observation has no substantive content.
     */
    private static String extractFact(String toolName, String observation) {
        if (observation == null || observation.isBlank()) return null;
        String t = observation.trim();
        // Skip errors and empty results.
        if (t.startsWith("Error:") || t.startsWith("No results")
                || t.length() < 30) return null;
        // Condense: first 300 chars, single line.
        String condensed = t.replaceAll("\\s+", " ");
        if (condensed.length() > 300) condensed = condensed.substring(0, 300) + "...";
        return "[" + toolName + "] " + condensed;
    }

    /**
     * Last-resort answer extraction: walk the conversation backwards and
     * return the last substantive assistant text. Used when the closing
     * call returns blank even after a forced retry — a guess beats a
     * guaranteed-empty failure.
     */
    private static String extractLastSubstantiveText(java.util.List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg.role() == ChatRole.ASSISTANT
                    && msg.content() != null && !msg.content().isBlank()
                    && msg.content().trim().length() > 10) {
                return stripFormattingCruft(msg.content());
            }
        }
        return "";
    }

    /** One model turn plus whether its tool calls were all dropped as malformed. */
    private record Turn(ChatResponse response, boolean allCallsDropped) {}

    /**
     * One model turn. Uses streaming when the client supports it — tokens are
     * emitted as {@link AgentEvent.StreamToken} for live UIs — but always
     * returns the complete turn before the agent acts on it.
     */
    private Turn doChat(List<ChatMessage> messages, List<ToolDefinition> tools,
                       LlmClient.LlmOptions options, int iteration) {
        emit(new AgentEvent.LlmRequest(Instant.now(), iteration));
        // Rolling window: the model sees a compacted view (old tool outputs
        // stubbed); the stored transcript and journal keep full history.
        List<ChatMessage> modelMessages =
            ContextWindow.compact(messages, config.toolOutputCharCap());
        ChatResponse response;
        if (config.client() instanceof StreamingLlmClient streaming) {
            response = streaming.chatStream(modelMessages, tools, options,
                token -> emit(new AgentEvent.StreamToken(Instant.now(), iteration, token)));
        } else {
            response = config.client().chat(modelMessages, tools, options);
        }
        RepairedTurn repaired = repairToolCalls(response);
        emit(new AgentEvent.LlmResponse(Instant.now(), iteration, repaired.response()));
        return new Turn(repaired.response(), repaired.allCallsDropped());
    }

    /**
     * The outcome of the model-agnostic tool-call repair pass:
     * the sanitized response, whether every tool call was dropped
     * (the turn carried calls but none survived), and the per-call
     * details for event emission.
     */
    private record RepairedTurn(ChatResponse response, boolean allCallsDropped,
                                List<ToolCallRepair.RepairedCall> repairedCalls,
                                List<ToolCallRequest> droppedCalls) {}

    /**
     * Pure repair pass (no events): a blank or mangled function name with
     * intact arguments is re-attributed by argument-signature matching
     * ({@link ToolCallRepair}). Unrecoverable calls with a non-blank name
     * pass through untouched for the normal unknown-tool error path.
     * Unrecoverable calls with a BLANK name are dropped entirely — they
     * cannot be dispatched and must never reach the provider in history
     * (some providers reject blank names with HTTP 400).
     */
    private static RepairedTurn repairTurn(ChatResponse response, ToolRegistry registry) {
        if (!response.hasToolCalls()) {
            return new RepairedTurn(response, false, List.of(), List.of());
        }
        List<ToolCallRepair.RepairedCall> repaired =
            ToolCallRepair.repairAll(response.toolCalls(), registry);
        List<ToolCallRequest> calls = new ArrayList<>(repaired.size());
        List<ToolCallRepair.RepairedCall> repairedCalls = new ArrayList<>();
        List<ToolCallRequest> droppedCalls = new ArrayList<>();
        for (ToolCallRepair.RepairedCall rc : repaired) {
            if (rc.repaired()) {
                repairedCalls.add(rc);
                calls.add(rc.call());
            } else if (rc.call().name() == null || rc.call().name().isBlank()) {
                droppedCalls.add(rc.call());
            } else {
                calls.add(rc.call());
            }
        }
        boolean changed = !repairedCalls.isEmpty() || !droppedCalls.isEmpty();
        ChatResponse out = changed
            ? new ChatResponse(response.content(), calls, response.usage())
            : response;
        return new RepairedTurn(out, calls.isEmpty(), repairedCalls, droppedCalls);
    }

    /**
     * Repair pass with event emission for live runs. The repaired name is
     * what history records, so dispatch works and follow-up requests never
     * echo a blank name back to the provider.
     */
    private RepairedTurn repairToolCalls(ChatResponse response) {
        RepairedTurn turn = repairTurn(response, config.tools());
        for (ToolCallRepair.RepairedCall rc : turn.repairedCalls()) {
            emit(new AgentEvent.ToolCallRepaired(
                Instant.now(), rc.call().id(), rc.recoveredName()));
        }
        for (ToolCallRequest dropped : turn.droppedCalls()) {
            emit(new AgentEvent.ToolCallRepaired(
                Instant.now(), dropped.id(), "<dropped:blank-name>"));
        }
        return turn;
    }

    private <T> Formatted<T> formatTranscript(List<ChatMessage> messages, int baseIteration,
                                             Class<T> outputType) {
        String schema = OutputSchema.generate(outputType);

        List<ChatMessage> m = new ArrayList<>(messages);
        m.add(ChatMessage.user(
            "Format your final answer as JSON matching this schema. Return ONLY the JSON, no prose:\n" + schema));

        LlmClient.LlmOptions options =
            new LlmClient.LlmOptions(config.temperature(), 4096).withJsonSchema(schema);
        ChatResponse formatted = doChat(m, List.of(), options, baseIteration + 1).response();
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
                    // Name the valid tools so the model can self-correct on
                    // the next turn instead of repeating the bad name.
                    String valid = registry.all().stream()
                        .map(ToolDefinition::name)
                        .collect(java.util.stream.Collectors.joining(", "));
                    result = "ERROR: unknown tool '" + call.name()
                        + "'. Use exactly one of these tool names: " + valid + ".";
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

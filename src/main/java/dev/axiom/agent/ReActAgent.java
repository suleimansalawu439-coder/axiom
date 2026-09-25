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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /** The journal replay of a crashed run: rebuilt messages plus bookkeeping. */
    public record ResumeTranscript(List<ChatMessage> messages,
                                   Map<String, String> recordedResults,
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
     * results and never re-executed; tool calls that were requested but never
     * finished are re-executed (at-least-once). New events continue to be
     * appended to the same journal.
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
            String observation = executeToolCall(call, config.tools(), t.recordedResults());
            messages.add(ChatMessage.toolResult(call.id(), call.name(), observation));
        }
        LoopState state = new LoopState(messages, t.lastIteration(), t.totalUsage(),
            toolCallsMade, t.recordedResults());
        AgentResult result = runLoop(state);
        persistMemory(messages);
        return new ResumedRun(result, List.copyOf(messages));
    }

    /**
     * Replay a journal into a transcript without emitting events or executing
     * anything. Public so {@link dev.axiom.durable.AgentRun} can resume typed
     * runs (replay the transcript, then run the formatting step).
     */
    public ResumeTranscript replay(RunJournal journal) {
        List<ChatMessage> messages = new ArrayList<>();
        Map<String, String> recordedResults = new LinkedHashMap<>();
        List<ToolCallRequest> pending = new ArrayList<>();
        ChatResponse.TokenUsage totalUsage = ChatResponse.TokenUsage.empty();
        int toolCallsMade = 0;
        int lastIteration = 0;
        boolean completed = false;
        AgentResult result = null;
        boolean sawStart = false;

        for (RunJournal.Record record : journal.readAll()) {
            if (record instanceof RunJournal.RunStarted rs) {
                sawStart = true;
                messages.add(ChatMessage.system(config.systemPrompt()));
                messages.add(ChatMessage.user(rs.task()));
            } else if (record instanceof RunJournal.Event ev
                    && ev.event() instanceof AgentEvent.LlmResponse lr) {
                lastIteration = lr.iteration();
                totalUsage = totalUsage.add(lr.response().usage());
                messages.add(ChatMessage.assistantWithToolCalls(
                    lr.response().content(), lr.response().toolCalls()));
                pending = new ArrayList<>(lr.response().toolCalls());
            } else if (record instanceof RunJournal.Event ev2
                    && ev2.event() instanceof AgentEvent.ToolCallFinished tf) {
                recordedResults.put(tf.call().id(), tf.result());
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
            List.copyOf(pending), totalUsage, toolCallsMade, lastIteration, completed, result);
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

    private String executeToolCall(ToolCallRequest call, ToolRegistry registry,
                                   Map<String, String> replayedResults) {
        emit(new AgentEvent.ToolCallStarted(Instant.now(), call));
        long start = System.currentTimeMillis();
        String result;
        String replayed = replayedResults.get(call.id());
        if (replayed != null) {
            // Idempotent replay: this call completed before the crash — reuse the
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
                        ? invokeWithTimeout(registry, def.get(), call)
                        : "DENIED: the human operator rejected this tool call. Adjust your plan to proceed without it.";
                } else {
                    result = invokeWithTimeout(registry, def.get(), call);
                }
            } catch (ToolInvocationException e) {
                // Feed the error back so the model can self-correct.
                result = "ERROR: " + e.getMessage();
            } catch (Exception e) {
                result = "ERROR: unexpected failure: " + e.getMessage();
            }
        }
        emit(new AgentEvent.ToolCallFinished(
            Instant.now(), call, result, System.currentTimeMillis() - start));
        return result;
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

package dev.axiom.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.budget.Budget;
import dev.axiom.budget.BudgetExceededException;
import dev.axiom.llm.*;
import dev.axiom.output.OutputSchema;
import dev.axiom.output.StructuredOutputException;
import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolInvocationException;
import dev.axiom.tools.ToolRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * The classic ReAct loop (Reason + Act), hardened for production:
 * <ul>
 *   <li>Tool errors are fed back to the LLM as observations so it self-corrects
 *       instead of crashing the run.</li>
 *   <li>Denied approvals are reported as observations, letting the agent replan.</li>
 *   <li>Every step emits an {@link AgentEvent} for tracing and UIs.</li>
 *   <li>Tool calls run with per-tool timeouts.</li>
 * </ul>
 */
public final class ReActAgent {
    private final AgentConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Daemon pool so runaway tools never pin the JVM. */
    private static final ExecutorService TOOL_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "axiom-tool");
        t.setDaemon(true);
        return t;
    });

    public ReActAgent(AgentConfig config) {
        this.config = config;
    }

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
        var run = runWithTranscript(task);
        String schema = OutputSchema.generate(outputType);

        List<ChatMessage> messages = new ArrayList<>(run.messages());
        messages.add(ChatMessage.user(
            "Format your final answer as JSON matching this schema. Return ONLY the JSON, no prose:\n" + schema));

        LlmClient.LlmOptions options =
            new LlmClient.LlmOptions(config.temperature(), 4096).withJsonSchema(schema);
        config.emit(new AgentEvent.LlmRequest(Instant.now(), run.result().iterations() + 1));
        ChatResponse formatted = config.client().chat(messages, List.of(), options);
        chargeBudget(formatted.usage());

        String json = formatted.content() == null ? "" : formatted.content().trim();
        // Tolerate markdown fences some providers add despite instructions.
        if (json.startsWith("```")) {
            json = json.replaceFirst("(?s)^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        try {
            return mapper.readValue(json, outputType);
        } catch (Exception e) {
            throw new StructuredOutputException(
                "Model output did not parse as " + outputType.getSimpleName()
                    + ": " + e.getMessage(),
                formatted.content(), e);
        }
    }

    private record RunWithTranscript(AgentResult result, List<ChatMessage> messages) {}

    private RunWithTranscript runWithTranscript(String task) {
        config.emit(new AgentEvent.RunStarted(Instant.now(), task));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(config.systemPrompt()));
        if (config.memory() != null) {
            messages.addAll(config.memory().history());
        }
        messages.add(ChatMessage.user(task));

        ToolRegistry registry = config.tools();
        List<ToolDefinition> toolDefs = List.copyOf(registry.all());
        LlmClient.LlmOptions options =
            new LlmClient.LlmOptions(config.temperature(), 4096);

        ChatResponse.TokenUsage totalUsage = ChatResponse.TokenUsage.empty();
        int toolCallsMade = 0;

        for (int iteration = 1; iteration <= config.maxIterations(); iteration++) {
            config.emit(new AgentEvent.LlmRequest(Instant.now(), iteration));
            ChatResponse response = config.client().chat(messages, toolDefs, options);
            totalUsage = totalUsage.add(response.usage());
            chargeBudget(response.usage());
            config.emit(new AgentEvent.LlmResponse(Instant.now(), iteration, response));

            if (!response.hasToolCalls()) {
                AgentResult result = new AgentResult(
                    response.content(), iteration, toolCallsMade, totalUsage, true);
                config.emit(new AgentEvent.RunFinished(Instant.now(), result));
                messages.add(ChatMessage.assistant(response.content()));
                persistMemory(messages);
                return new RunWithTranscript(result, List.copyOf(messages));
            }

            messages.add(ChatMessage.assistantWithToolCalls(response.content(), response.toolCalls()));

            for (ToolCallRequest call : response.toolCalls()) {
                toolCallsMade++;
                String observation = executeToolCall(call, registry);
                messages.add(ChatMessage.toolResult(call.id(), call.name(), observation));
            }
        }

        // Out of iterations: ask for a best-effort final answer with no tools.
        ChatResponse closing = config.client().chat(
            withClosingInstruction(messages), List.of(), options);
        totalUsage = totalUsage.add(closing.usage());
        chargeBudget(closing.usage());
        AgentResult result = new AgentResult(
            closing.content(), config.maxIterations(), toolCallsMade, totalUsage, false);
        config.emit(new AgentEvent.RunFinished(Instant.now(), result));
        messages.add(ChatMessage.assistant(closing.content()));
        persistMemory(messages);
        return new RunWithTranscript(result, List.copyOf(messages));
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
            config.emit(new AgentEvent.BudgetUpdated(Instant.now(), usage, budget.snapshot()));
        }
    }

    private String executeToolCall(ToolCallRequest call, ToolRegistry registry) {
        config.emit(new AgentEvent.ToolCallStarted(Instant.now(), call));
        long start = System.currentTimeMillis();
        String result;
        try {
            var def = registry.find(call.name());
            if (def.isEmpty()) {
                result = "ERROR: unknown tool '" + call.name() + "'";
            } else if (def.get().requiresApproval()) {
                config.emit(new AgentEvent.ApprovalRequested(
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
        config.emit(new AgentEvent.ToolCallFinished(
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

package dev.axiom.agent;

import dev.axiom.budget.Budget;
import dev.axiom.guardrails.Guardrail;
import dev.axiom.llm.LlmClient;
import dev.axiom.memory.Memory;
import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Immutable configuration for an agent run, built via the fluent builder. */
public final class AgentConfig {
    private final LlmClient client;
    private final ToolRegistry tools;
    private final String systemPrompt;
    private final int maxIterations;
    private final double temperature;
    private final ApprovalHandler approvalHandler;
    private final Memory memory;
    private final Budget budget;
    private final java.nio.file.Path journalRoot;
    private final List<Guardrail> guardrails;
    private final List<Consumer<AgentEvent>> eventListeners;
    /**
     * Names of terminal tools: invoking one ends the run immediately and
     * commits its answer argument as the run's output. Empty when none are
     * configured.
     */
    private final java.util.Set<String> terminalTools;
    /**
     * Cap on accumulated tool-observation characters sent to the model per
     * turn; older observations are stubbed out (see {@link ContextWindow}).
     * The stored transcript is never modified.
     */
    private final int toolOutputCharCap;
    private final int stagnationRepeatLimit;
    private final int stagnationErrorLimit;
    private final int stagnationFruitlessLimit;
    private final boolean taskLedgerEnabled;
    private final boolean verificationEnabled;

    private AgentConfig(Builder b) {
        this.client = b.client;
        this.tools = b.tools;
        this.systemPrompt = b.systemPrompt;
        this.maxIterations = b.maxIterations;
        this.temperature = b.temperature;
        this.approvalHandler = b.approvalHandler;
        this.memory = b.memory;
        this.budget = b.budget;
        this.journalRoot = b.journalRoot;
        this.guardrails = List.copyOf(b.guardrails);
        this.eventListeners = List.copyOf(b.eventListeners);
        this.terminalTools = java.util.Set.copyOf(b.terminalTools);
        this.toolOutputCharCap = b.toolOutputCharCap;
        this.stagnationRepeatLimit = b.stagnationRepeatLimit;
        this.stagnationErrorLimit = b.stagnationErrorLimit;
        this.stagnationFruitlessLimit = b.stagnationFruitlessLimit;
        this.taskLedgerEnabled = b.taskLedgerEnabled;
        this.verificationEnabled = b.verificationEnabled;
    }

    public LlmClient client() { return client; }
    public ToolRegistry tools() { return tools; }
    public String systemPrompt() { return systemPrompt; }
    public int maxIterations() { return maxIterations; }
    public double temperature() { return temperature; }
    public ApprovalHandler approvalHandler() { return approvalHandler; }
    public Memory memory() { return memory; }
    /** The run's token/cost budget, or null if none is configured. */
    public Budget budget() { return budget; }
    /**
     * Root directory for durable run journals, or null when durability is
     * off. When set, every run appends its events to
     * {@code <root>/<runId>/journal.jsonl} and can be resumed after a crash
     * via {@link dev.axiom.durable.AgentRun#resumeFrom}.
     */
    public java.nio.file.Path journalRoot() { return journalRoot; }
    /**
     * Policy checks applied to the task (before the run) and the final
     * answer (before it is returned). Empty when none are configured.
     */
    public List<Guardrail> guardrails() { return guardrails; }
    public List<Consumer<AgentEvent>> eventListeners() { return eventListeners; }
    /** Names of tools whose invocation ends the run, committing an answer. */
    public java.util.Set<String> terminalTools() { return terminalTools; }
    /** Cap on tool-observation characters sent to the model per turn. */
    public int toolOutputCharCap() { return toolOutputCharCap; }
    public int stagnationRepeatLimit() { return stagnationRepeatLimit; }
    public int stagnationErrorLimit() { return stagnationErrorLimit; }
    public int stagnationFruitlessLimit() { return stagnationFruitlessLimit; }
    public boolean taskLedgerEnabled() { return taskLedgerEnabled; }
    public boolean verificationEnabled() { return verificationEnabled; }

    void emit(AgentEvent event) {
        for (Consumer<AgentEvent> l : eventListeners) {
            try {
                l.accept(event);
            } catch (Exception ignored) {
                // Listeners must never break a run.
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private LlmClient client;
        private ToolRegistry tools = new ToolRegistry();
        private String systemPrompt = "You are a helpful AI assistant with access to tools. "
            + "Think step by step, use tools when they help, and always give a final answer.";
        private int maxIterations = 15;
        private double temperature = 0.7;
        private ApprovalHandler approvalHandler = ApprovalHandler.allowAll();
        private Memory memory;
        private Budget budget;
        private java.nio.file.Path journalRoot;
        private final List<Guardrail> guardrails = new ArrayList<>();
        private final List<Consumer<AgentEvent>> eventListeners = new ArrayList<>();
        private final java.util.Set<String> terminalTools = new java.util.LinkedHashSet<>();
        private int toolOutputCharCap = ContextWindow.toolOutputCap();
        // Stagnation guardrails: enabled by default (3 repeats / 4 errors /
        // 6 fruitless turns). Evidence 2026-10-02: disabling them took a
        // 5-task DeepSeek-Pro probe from 3/5 @153k tokens to 1/5 @363k
        // tokens — the extra wandering turns wrong answers out of right
        // ones. Keep them on; tune the limits, don't remove the guard.
        private int stagnationRepeatLimit = 3;
        private int stagnationErrorLimit = 4;
        private int stagnationFruitlessLimit = 6;
        // GAIA research features (2026-10-04): Task Ledger, Verification Pass,
        // Code-as-Action. Disabled by default (tests); enable via withXxx(true).
        private boolean taskLedgerEnabled = false;
        private boolean verificationEnabled = false;

        public Builder withClient(LlmClient client) { this.client = client; return this; }
        public Builder withModel(String model) {
            this.client = new dev.axiom.llm.OpenAiCompatibleClient(model);
            return this;
        }
        public Builder withTools(Object... toolHolders) {
            for (Object h : toolHolders) tools.register(h);
            return this;
        }
        /** Register prebuilt tool definitions (MCP tools, supervisor delegate tools, …). */
        public Builder withToolDefinitions(ToolDefinition... definitions) {
            for (ToolDefinition d : definitions) tools.register(d);
            return this;
        }
        /**
         * Use a prebuilt registry (replaces the builder's own). Needed when a
         * component must share the agent's exact registry instance — e.g.
         * {@code CapabilityGuardrail.forRegistry(registry)}.
         */
        public Builder withRegistry(ToolRegistry registry) {
            this.tools = registry;
            return this;
        }
        public Builder withSystemPrompt(String prompt) { this.systemPrompt = prompt; return this; }
        public Builder withMaxIterations(int n) { this.maxIterations = n; return this; }
        /**
         * Model-agnostic runaway-loop limits for {@link StagnationController}:
         * stop the loop early on {@code repeatLimit} identical tool calls,
         * {@code errorLimit} consecutive tool errors, or
         * {@code fruitlessLimit} iterations with no productive tool output.
         * A limit of 0 disables that signal. Defaults are (3, 4, 6): three
         * identical calls, four consecutive errors, or six fruitless
         * iterations.
         */
        public Builder withStagnationLimits(int repeatLimit, int errorLimit, int fruitlessLimit) {
            this.stagnationRepeatLimit = repeatLimit;
            this.stagnationErrorLimit = errorLimit;
            this.stagnationFruitlessLimit = fruitlessLimit;
            return this;
        }
        public Builder withTaskLedger(boolean enabled) { this.taskLedgerEnabled = enabled; return this; }
        public Builder withVerification(boolean enabled) { this.verificationEnabled = enabled; return this; }
        public Builder withTemperature(double t) { this.temperature = t; return this; }
        public Builder withApprovalHandler(ApprovalHandler h) { this.approvalHandler = h; return this; }
        public Builder withMemory(Memory memory) { this.memory = memory; return this; }
        /**
         * Cap the run's token usage and/or USD cost. The agent charges every
         * LLM call against the budget and aborts with
         * {@link dev.axiom.budget.BudgetExceededException} when a limit is
         * breached; spend is visible via {@link AgentEvent.BudgetUpdated}.
         */
        public Builder withBudget(Budget budget) { this.budget = budget; return this; }
        /**
         * Enable durable execution: every run journals its events to
         * {@code <root>/<runId>/journal.jsonl}. After a crash, resume with
         * {@link dev.axiom.durable.AgentRun#resumeFrom(Path, String, AgentConfig)} —
         * completed tool calls are replayed from the journal, never re-executed.
         */
        public Builder withJournalRoot(java.nio.file.Path root) { this.journalRoot = root; return this; }
        /**
         * Policy checks on the task (before the run) and the final answer
         * (before it is returned). A {@link Verdict.Block} aborts the run
         * with {@link GuardrailViolationException} after emitting
         * {@link AgentEvent.GuardrailBlocked}; a {@link Verdict.Replace}
         * substitutes the sanitized text and continues.
         */
        public Builder withGuardrails(Guardrail... guardrails) {
            this.guardrails.addAll(List.of(guardrails));
            return this;
        }
        public Builder onEvent(Consumer<AgentEvent> listener) {
            this.eventListeners.add(listener);
            return this;
        }
        /**
         * Declare terminal tools: when the model invokes one, the run ends
         * immediately and the tool's {@code answer} argument (or its first
         * argument) is committed as the run's output. Useful for eval
         * harnesses where the agent must commit one clean answer instead of
         * rambling in chat text. Output guardrails still apply to the
         * committed answer.
         */
        public Builder withTerminalTools(String... names) {
            this.terminalTools.addAll(List.of(names));
            return this;
        }
        /**
         * Cap on accumulated tool-observation characters sent to the model
         * per turn (default {@link ContextWindow#DEFAULT_TOOL_OUTPUT_CAP},
         * overridable with {@code -Daxiom.context.toolOutputCap=N}). Older
         * observations are replaced with one-line stubs; the transcript and
         * journal keep the full history.
         */
        public Builder withToolOutputCharCap(int n) {
            if (n <= 0) throw new IllegalArgumentException("cap must be positive");
            this.toolOutputCharCap = n;
            return this;
        }

        public AgentConfig build() {
            if (client == null) throw new IllegalStateException("An LlmClient is required (withClient/withModel).");
            return new AgentConfig(this);
        }

        /** Build directly into a runnable {@link dev.axiom.Axiom.Agent}. */
        public dev.axiom.Axiom.Agent buildAgent() {
            return new dev.axiom.Axiom.Agent(build());
        }
    }
}

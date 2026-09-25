package dev.axiom.agent;

import dev.axiom.llm.LlmClient;
import dev.axiom.memory.Memory;
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
    private final List<Consumer<AgentEvent>> eventListeners;

    private AgentConfig(Builder b) {
        this.client = b.client;
        this.tools = b.tools;
        this.systemPrompt = b.systemPrompt;
        this.maxIterations = b.maxIterations;
        this.temperature = b.temperature;
        this.approvalHandler = b.approvalHandler;
        this.memory = b.memory;
        this.eventListeners = List.copyOf(b.eventListeners);
    }

    public LlmClient client() { return client; }
    public ToolRegistry tools() { return tools; }
    public String systemPrompt() { return systemPrompt; }
    public int maxIterations() { return maxIterations; }
    public double temperature() { return temperature; }
    public ApprovalHandler approvalHandler() { return approvalHandler; }
    public Memory memory() { return memory; }
    public List<Consumer<AgentEvent>> eventListeners() { return eventListeners; }

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
        private final ToolRegistry tools = new ToolRegistry();
        private String systemPrompt = "You are a helpful AI assistant with access to tools. "
            + "Think step by step, use tools when they help, and always give a final answer.";
        private int maxIterations = 15;
        private double temperature = 0.7;
        private ApprovalHandler approvalHandler = ApprovalHandler.allowAll();
        private Memory memory;
        private final List<Consumer<AgentEvent>> eventListeners = new ArrayList<>();

        public Builder withClient(LlmClient client) { this.client = client; return this; }
        public Builder withModel(String model) {
            this.client = new dev.axiom.llm.OpenAiCompatibleClient(model);
            return this;
        }
        public Builder withTools(Object... toolHolders) {
            for (Object h : toolHolders) tools.register(h);
            return this;
        }
        public Builder withSystemPrompt(String prompt) { this.systemPrompt = prompt; return this; }
        public Builder withMaxIterations(int n) { this.maxIterations = n; return this; }
        public Builder withTemperature(double t) { this.temperature = t; return this; }
        public Builder withApprovalHandler(ApprovalHandler h) { this.approvalHandler = h; return this; }
        public Builder withMemory(Memory memory) { this.memory = memory; return this; }
        public Builder onEvent(Consumer<AgentEvent> listener) {
            this.eventListeners.add(listener);
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

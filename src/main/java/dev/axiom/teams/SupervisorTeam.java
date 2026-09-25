package dev.axiom.teams;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentEvent;
import dev.axiom.agent.AgentResult;
import dev.axiom.agent.ReActAgent;
import dev.axiom.budget.Budget;
import dev.axiom.llm.LlmClient;
import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * A supervisor agent coordinating a team of {@link Worker}s. The supervisor
 * is a ReAct agent whose tools are the workers' typed delegate tools: it
 * decomposes the task, delegates subtasks, and aggregates the typed results.
 *
 * <pre>{@code
 * SupervisorTeam team = SupervisorTeam.builder(client)
 *     .withWorker(researcher)
 *     .withWorker(writer)
 *     .withSystemPrompt("You are a meticulous research lead.")
 *     .build();
 *
 * Report report = team.run("Write a report on solid-state batteries", Report.class);
 * }</pre>
 *
 * <p>Each worker keeps its own tools, memory, and event listeners; the team's
 * listeners observe the supervisor. Share one {@link Budget} across the
 * supervisor and worker configs to cap the whole team's spend (budgets are
 * thread-safe).
 */
public final class SupervisorTeam {
    private final LlmClient client;
    private final List<Worker<?, ?>> workers;
    private final String systemPrompt;
    private final int maxIterations;
    private final double temperature;
    private final Budget budget;
    private final List<Consumer<AgentEvent>> eventListeners;

    private SupervisorTeam(Builder b) {
        this.client = b.client;
        this.workers = List.copyOf(b.workers);
        this.systemPrompt = b.systemPrompt;
        this.maxIterations = b.maxIterations;
        this.temperature = b.temperature;
        this.budget = b.budget;
        this.eventListeners = List.copyOf(b.eventListeners);
    }

    public List<Worker<?, ?>> workers() {
        return workers;
    }

    /**
     * Typed programmatic delegation: run one worker directly, no supervisor
     * LLM involved. The handoff is fully compile-time checked.
     */
    public <I, O> O delegate(Worker<I, O> worker, I input) {
        Objects.requireNonNull(worker);
        return worker.execute(input);
    }

    /**
     * Run the full team: the supervisor decomposes {@code task}, delegates to
     * workers via their typed delegate tools, and its final answer is
     * coerced into {@code resultType} — a typed aggregate of typed worker
     * results.
     */
    public <T> T run(String task, Class<T> resultType) {
        return supervisorAgent().runFor(task, resultType);
    }

    /** Run the supervisor loop and return the raw result (untyped final answer). */
    public AgentResult runRaw(String task) {
        return supervisorAgent().run(task);
    }

    private ReActAgent supervisorAgent() {
        ToolRegistry registry = new ToolRegistry();
        for (Worker<?, ?> w : workers) {
            registry.register(w.delegateToolDefinition());
        }
        AgentConfig.Builder builder = AgentConfig.builder()
            .withClient(client)
            .withSystemPrompt(systemPrompt + "\n\n" + workerCatalog())
            .withMaxIterations(maxIterations)
            .withTemperature(temperature)
            .withToolDefinitions(registry.all().toArray(ToolDefinition[]::new));
        if (budget != null) builder.withBudget(budget);
        for (Consumer<AgentEvent> l : eventListeners) builder.onEvent(l);
        return new ReActAgent(builder.build());
    }

    private String workerCatalog() {
        return "Your team (delegate via the corresponding delegate_to_<name> tool; "
            + "never do their work yourself):\n"
            + workers.stream()
                .map(w -> "- " + w.name() + ": " + w.description())
                .collect(Collectors.joining("\n"));
    }

    public static Builder builder(LlmClient client) {
        return new Builder(client);
    }

    public static final class Builder {
        private final LlmClient client;
        private final List<Worker<?, ?>> workers = new ArrayList<>();
        private String systemPrompt = "You are a supervisor coordinating a team of specialist workers. "
            + "Decompose the task into subtasks, delegate each subtask to the best-suited worker "
            + "using the delegate tools, and synthesize all worker results into the final answer. "
            + "Delegate independent subtasks before dependent ones.";
        private int maxIterations = 25;
        private double temperature = 0.7;
        private Budget budget;
        private final List<Consumer<AgentEvent>> eventListeners = new ArrayList<>();

        private Builder(LlmClient client) {
            this.client = Objects.requireNonNull(client);
        }

        public Builder withWorker(Worker<?, ?> worker) {
            workers.add(Objects.requireNonNull(worker));
            return this;
        }

        public Builder withSystemPrompt(String prompt) {
            this.systemPrompt = prompt;
            return this;
        }

        public Builder withMaxIterations(int n) {
            this.maxIterations = n;
            return this;
        }

        public Builder withTemperature(double t) {
            this.temperature = t;
            return this;
        }

        /** Shared spend cap for the supervisor's own LLM calls. */
        public Builder withBudget(Budget budget) {
            this.budget = budget;
            return this;
        }

        public Builder onEvent(Consumer<AgentEvent> listener) {
            this.eventListeners.add(listener);
            return this;
        }

        public SupervisorTeam build() {
            if (workers.isEmpty()) throw new IllegalStateException("A team needs at least one worker.");
            var names = workers.stream().map(Worker::name).toList();
            var dupes = names.stream()
                .filter(n -> names.stream().filter(n::equals).count() > 1)
                .distinct().toList();
            if (!dupes.isEmpty()) {
                throw new IllegalStateException("Duplicate worker names: " + dupes);
            }
            return new SupervisorTeam(this);
        }
    }
}

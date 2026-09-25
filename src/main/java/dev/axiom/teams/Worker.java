package dev.axiom.teams;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.ReActAgent;
import dev.axiom.output.OutputSchema;
import dev.axiom.tools.ToolDefinition;

import java.util.Map;
import java.util.Objects;

/**
 * A specialist agent with a <b>typed handoff contract</b>: it accepts an
 * input of type {@code I} and always produces an output of type {@code O}.
 * Each worker owns its {@link AgentConfig} — its own tools, memory, and
 * system prompt — so teams compose without shared mutable state.
 *
 * <p>The contract is compile-time: {@code execute} takes an {@code I} and
 * returns an {@code O}; the JSON Schemas advertised to a supervisor are
 * generated from those same classes, so the schema and the types cannot
 * drift apart.
 *
 * <pre>{@code
 * record ResearchQuery(String topic, int maxSources) {}
 * record ResearchBrief(String topic, List<String> findings) {}
 *
 * Worker<ResearchQuery, ResearchBrief> researcher = Worker.of(
 *     "researcher", "Researches a topic and returns a brief.",
 *     ResearchQuery.class, ResearchBrief.class,
 *     Axiom.agent().withModel("gpt-4o").withTools(new WebTools()).build());
 *
 * ResearchBrief brief = researcher.execute(new ResearchQuery("solid-state batteries", 5));
 * }</pre>
 */
public final class Worker<I, O> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final String description;
    private final Class<I> inputType;
    private final Class<O> outputType;
    private final AgentConfig config;
    private final long delegateTimeoutSeconds;

    private Worker(String name, String description, Class<I> inputType, Class<O> outputType,
                   AgentConfig config, long delegateTimeoutSeconds) {
        if (!name.matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException(
                "Worker name must match [a-zA-Z0-9_-]+, got: '" + name + "'");
        }
        this.name = name;
        this.description = Objects.requireNonNull(description);
        this.inputType = Objects.requireNonNull(inputType);
        this.outputType = Objects.requireNonNull(outputType);
        this.config = Objects.requireNonNull(config);
        this.delegateTimeoutSeconds = delegateTimeoutSeconds;
    }

    public static <I, O> Worker<I, O> of(String name, String description,
                                         Class<I> inputType, Class<O> outputType,
                                         AgentConfig config) {
        return new Worker<>(name, description, inputType, outputType, config, 600);
    }

    /** Copy with a different timeout for the supervisor's delegate tool. */
    public Worker<I, O> withDelegateTimeoutSeconds(long seconds) {
        if (seconds < 1) throw new IllegalArgumentException("timeout must be >= 1s");
        return new Worker<>(name, description, inputType, outputType, config, seconds);
    }

    public String name() { return name; }
    public String description() { return description; }
    public Class<I> inputType() { return inputType; }
    public Class<O> outputType() { return outputType; }
    public AgentConfig config() { return config; }

    /**
     * Execute the worker on a typed input. The input is serialized to JSON
     * and handed to the worker's agent; its final answer is deserialized
     * into {@code O} via the same typed-output machinery as
     * {@code agent.runFor}.
     */
    public O execute(I input) {
        String inputJson;
        try {
            inputJson = MAPPER.writeValueAsString(input);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Worker '%s' input does not serialize to JSON: %s".formatted(name, e.getMessage()), e);
        }
        String task = ("You are %s. %s\n\n"
            + "Complete the following subtask. Your subtask input (JSON):\n%s")
            .formatted(name, description, inputJson);
        return new ReActAgent(config).runFor(task, outputType);
    }

    /**
     * The delegate tool a supervisor exposes for this worker, named
     * {@code delegate_to_<name>}. Its parameter schema is generated from
     * {@code I}; it returns the worker's {@code O} serialized as JSON, so the
     * supervisor sees a typed result, not free text.
     */
    ToolDefinition delegateToolDefinition() {
        Map<String, Object> schema = parseSchema(OutputSchema.generate(inputType));
        String toolName = "delegate_to_" + name;
        String toolDescription = ("Delegate a subtask to the '%s' worker (%s). "
            + "Arguments must match the worker's input schema. "
            + "Returns the worker's typed result as JSON matching: %s")
            .formatted(name, description, OutputSchema.generate(outputType));
        return ToolDefinition.of(toolName, toolDescription, schema,
            false, delegateTimeoutSeconds,
            args -> {
                I input = MAPPER.convertValue(args, inputType);
                O result = execute(input);
                return MAPPER.writeValueAsString(result);
            });
    }

    private static Map<String, Object> parseSchema(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Generated schema did not parse: " + e.getMessage(), e);
        }
    }
}

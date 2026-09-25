package dev.axiom.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.Axiom;
import dev.axiom.agent.AgentConfig;
import dev.axiom.guardrails.PiiRedactionGuardrail;
import dev.axiom.llm.LlmClient;
import dev.axiom.resilience.RetryPolicy;
import dev.axiom.resilience.RetryingLlmClient;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A tunable, serializable configuration of the harness-level knobs the
 * framework actually controls. The optimizer mutates strategies; it never
 * touches model weights and never rewrites prompts freely — knobs only, so
 * every change stays small, bounded, and explainable in the audit trail.
 *
 * <p>Knobs:
 * <ul>
 *   <li>{@code maxIterations} — ReAct loop turn cap (1..30).</li>
 *   <li>{@code retryMaxAttempts}, {@code retryInitialBackoffMs},
 *       {@code retryMultiplier} — retry policy for transient LLM failures.</li>
 *   <li>{@code planningHint} — a closed vocabulary of system-prompt hints.
 *       Real knob (it lands in the prompt the agent is built with); its
 *       effect on any given model is the model's business, which is why the
 *       optimizer only keeps it when measured scores improve.</li>
 *   <li>{@code guardrailStrictness} — {@code STRICT} adds output PII
 *       redaction on top of whatever guardrails the caller configures.</li>
 * </ul>
 */
public record Strategy(
        int maxIterations,
        int retryMaxAttempts,
        long retryInitialBackoffMs,
        double retryMultiplier,
        PlanningHint planningHint,
        GuardrailStrictness guardrailStrictness) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Closed vocabulary of planning hints — no free-form prompt rewriting. */
    public enum PlanningHint {
        NONE(""),
        /** Nudge toward solving with as few tool calls as possible. */
        FEWER_TOOLS("\nPrefer solving the task with as few tool calls as possible. "
            + "Batch independent calls together and do not re-verify results you already have."),
        /** Nudge toward double-checking the final answer before responding. */
        VERIFY_BEFORE_ANSWERING("\nBefore giving your final answer, verify it: re-read the "
            + "tool results you collected and confirm the answer follows from them."),
        /** Nudge toward explicit step-by-step reasoning in each turn. */
        THINK_STEP_BY_STEP("\nThink step by step out loud in each turn: state what you know, "
            + "what you still need, and which tool gets you there.");

        private final String suffix;
        PlanningHint(String suffix) { this.suffix = suffix; }
        public String promptSuffix() { return suffix; }
    }

    public enum GuardrailStrictness { STANDARD, STRICT }

    public static final int MIN_ITERATIONS = 1;
    public static final int MAX_ITERATIONS = 30;
    public static final int MIN_RETRY_ATTEMPTS = 1;
    public static final int MAX_RETRY_ATTEMPTS = 8;
    public static final long MIN_BACKOFF_MS = 50;
    public static final long MAX_BACKOFF_MS = 30_000;
    public static final double MIN_MULTIPLIER = 1.0;
    public static final double MAX_MULTIPLIER = 4.0;

    /** Base system prompt the strategy builds on; hints are appended to this. */
    public static final String BASE_SYSTEM_PROMPT =
        "You are a helpful AI assistant with access to tools. "
            + "Think step by step, use tools when they help, and always give a final answer.";

    public Strategy {
        if (maxIterations < MIN_ITERATIONS || maxIterations > MAX_ITERATIONS)
            throw new IllegalArgumentException(
                "maxIterations must be in [%d,%d]".formatted(MIN_ITERATIONS, MAX_ITERATIONS));
        if (retryMaxAttempts < MIN_RETRY_ATTEMPTS || retryMaxAttempts > MAX_RETRY_ATTEMPTS)
            throw new IllegalArgumentException(
                "retryMaxAttempts must be in [%d,%d]".formatted(MIN_RETRY_ATTEMPTS, MAX_RETRY_ATTEMPTS));
        if (retryInitialBackoffMs < MIN_BACKOFF_MS || retryInitialBackoffMs > MAX_BACKOFF_MS)
            throw new IllegalArgumentException(
                "retryInitialBackoffMs must be in [%d,%d]".formatted(MIN_BACKOFF_MS, MAX_BACKOFF_MS));
        if (retryMultiplier < MIN_MULTIPLIER || retryMultiplier > MAX_MULTIPLIER)
            throw new IllegalArgumentException(
                "retryMultiplier must be in [%.1f,%.1f]".formatted(MIN_MULTIPLIER, MAX_MULTIPLIER));
        planningHint = Objects.requireNonNull(planningHint, "planningHint is required");
        guardrailStrictness = Objects.requireNonNull(guardrailStrictness, "guardrailStrictness is required");
    }

    /** The framework's out-of-the-box strategy: every knob at its default. */
    public static Strategy defaults() {
        return new Strategy(15, 4, 1_000, 2.0, PlanningHint.NONE, GuardrailStrictness.STANDARD);
    }

    /**
     * How far this strategy sits from {@link #defaults()}: the number of
     * knobs that differ. Used as the tie-breaker — on equal scores the
     * simpler (closer-to-default) strategy wins.
     */
    public int complexity() {
        Strategy d = defaults();
        int n = 0;
        if (maxIterations != d.maxIterations) n++;
        if (retryMaxAttempts != d.retryMaxAttempts) n++;
        if (retryInitialBackoffMs != d.retryInitialBackoffMs) n++;
        if (Double.compare(retryMultiplier, d.retryMultiplier) != 0) n++;
        if (planningHint != d.planningHint) n++;
        if (guardrailStrictness != d.guardrailStrictness) n++;
        return n;
    }

    /** The system prompt an agent built with this strategy receives. */
    public String systemPrompt() {
        return BASE_SYSTEM_PROMPT + planningHint.promptSuffix();
    }

    public RetryPolicy retryPolicy() {
        return RetryPolicy.builder()
            .maxAttempts(retryMaxAttempts)
            .initialBackoff(Duration.ofMillis(retryInitialBackoffMs))
            .multiplier(retryMultiplier)
            .build();
    }

    /**
     * Build a runnable agent with every knob applied. The client factory is
     * called fresh per build so fixture-backed evaluators get an unconsumed
     * script on every evaluation.
     */
    public Axiom.Agent buildAgent(Supplier<LlmClient> clientFactory, Object... toolHolders) {
        Objects.requireNonNull(clientFactory, "clientFactory is required");
        LlmClient client = new RetryingLlmClient(clientFactory.get(), retryPolicy());
        AgentConfig.Builder b = Axiom.agent()
            .withClient(client)
            .withTools(toolHolders)
            .withMaxIterations(maxIterations)
            .withSystemPrompt(systemPrompt());
        if (guardrailStrictness == GuardrailStrictness.STRICT) {
            b.withGuardrails(new PiiRedactionGuardrail("meta-strict-pii"));
        }
        return b.buildAgent();
    }

    /** Serialize to JSON (also the on-disk {@code strategy.json} format). */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new MetaException("Failed to serialize strategy", e);
        }
    }

    /** Parse a strategy previously written with {@link #toJson()}. */
    public static Strategy fromJson(String json) {
        try {
            return MAPPER.readValue(json, Strategy.class);
        } catch (Exception e) {
            throw new MetaException("Failed to parse strategy JSON", e);
        }
    }
}

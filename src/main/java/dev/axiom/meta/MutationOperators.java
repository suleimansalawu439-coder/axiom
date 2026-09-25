package dev.axiom.meta;

import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * The built-in mutation operators: one per tunable knob, each a small typed
 * perturbation inside hard bounds. All operators are deterministic given the
 * {@link Random} they receive, so optimization runs are reproducible.
 */
public final class MutationOperators {

    private MutationOperators() {}

    /** All built-in operators, in a stable order. */
    public static List<MutationOperator> all() {
        return List.of(
            new BumpMaxIterations(),
            new AdjustRetryAttempts(),
            new AdjustRetryBackoff(),
            new AdjustRetryMultiplier(),
            new CyclePlanningHint(),
            new ToggleGuardrailStrictness());
    }

    /** maxIterations ±1..3, clamped to [1,30]. */
    static final class BumpMaxIterations implements MutationOperator {
        @Override public String name() { return "bump-max-iterations"; }

        @Override
        public Optional<MutatedStrategy> mutate(Strategy s, Random rng) {
            int delta = 1 + rng.nextInt(3);
            if (rng.nextBoolean()) delta = -delta;
            int next = Math.max(Strategy.MIN_ITERATIONS,
                Math.min(Strategy.MAX_ITERATIONS, s.maxIterations() + delta));
            if (next == s.maxIterations()) return Optional.empty();
            Strategy out = new Strategy(next, s.retryMaxAttempts(), s.retryInitialBackoffMs(),
                s.retryMultiplier(), s.planningHint(), s.guardrailStrictness());
            return Optional.of(new MutatedStrategy(out, new Mutation(name(), "maxIterations",
                String.valueOf(s.maxIterations()), String.valueOf(next),
                next > s.maxIterations()
                    ? "allow longer multi-turn tool chains before the best-effort close"
                    : "cut off rambling runs earlier to save tokens")));
        }
    }

    /** retryMaxAttempts ±1, clamped to [1,8]. */
    static final class AdjustRetryAttempts implements MutationOperator {
        @Override public String name() { return "adjust-retry-attempts"; }

        @Override
        public Optional<MutatedStrategy> mutate(Strategy s, Random rng) {
            int delta = rng.nextBoolean() ? 1 : -1;
            int next = Math.max(Strategy.MIN_RETRY_ATTEMPTS,
                Math.min(Strategy.MAX_RETRY_ATTEMPTS, s.retryMaxAttempts() + delta));
            if (next == s.retryMaxAttempts()) return Optional.empty();
            Strategy out = new Strategy(s.maxIterations(), next, s.retryInitialBackoffMs(),
                s.retryMultiplier(), s.planningHint(), s.guardrailStrictness());
            return Optional.of(new MutatedStrategy(out, new Mutation(name(), "retryMaxAttempts",
                String.valueOf(s.retryMaxAttempts()), String.valueOf(next),
                next > s.retryMaxAttempts()
                    ? "tolerate more transient failures before giving up"
                    : "fail fast instead of hammering a struggling provider")));
        }
    }

    /** retryInitialBackoffMs ×0.5 or ×2, clamped to [50ms,30s]. */
    static final class AdjustRetryBackoff implements MutationOperator {
        @Override public String name() { return "adjust-retry-backoff"; }

        @Override
        public Optional<MutatedStrategy> mutate(Strategy s, Random rng) {
            long next = rng.nextBoolean()
                ? Math.min(Strategy.MAX_BACKOFF_MS, s.retryInitialBackoffMs() * 2)
                : Math.max(Strategy.MIN_BACKOFF_MS, s.retryInitialBackoffMs() / 2);
            if (next == s.retryInitialBackoffMs()) return Optional.empty();
            Strategy out = new Strategy(s.maxIterations(), s.retryMaxAttempts(), next,
                s.retryMultiplier(), s.planningHint(), s.guardrailStrictness());
            return Optional.of(new MutatedStrategy(out, new Mutation(name(), "retryInitialBackoffMs",
                s.retryInitialBackoffMs() + "ms", next + "ms",
                next > s.retryInitialBackoffMs()
                    ? "back off harder between retries to ride out provider brownouts"
                    : "retry sooner to cut tail latency on transient blips")));
        }
    }

    /** retryMultiplier ±0.5, clamped to [1.0,4.0]. */
    static final class AdjustRetryMultiplier implements MutationOperator {
        @Override public String name() { return "adjust-retry-multiplier"; }

        @Override
        public Optional<MutatedStrategy> mutate(Strategy s, Random rng) {
            double next = Math.max(Strategy.MIN_MULTIPLIER,
                Math.min(Strategy.MAX_MULTIPLIER,
                    Math.round((s.retryMultiplier() + (rng.nextBoolean() ? 0.5 : -0.5)) * 2.0) / 2.0));
            if (Double.compare(next, s.retryMultiplier()) == 0) return Optional.empty();
            Strategy out = new Strategy(s.maxIterations(), s.retryMaxAttempts(), s.retryInitialBackoffMs(),
                next, s.planningHint(), s.guardrailStrictness());
            return Optional.of(new MutatedStrategy(out, new Mutation(name(), "retryMultiplier",
                String.valueOf(s.retryMultiplier()), String.valueOf(next),
                next > s.retryMultiplier()
                    ? "escalate backoff faster across attempts"
                    : "keep backoff flatter across attempts")));
        }
    }

    /** Cycle to the next planning hint in enum order (wraps around). */
    static final class CyclePlanningHint implements MutationOperator {
        @Override public String name() { return "cycle-planning-hint"; }

        @Override
        public Optional<MutatedStrategy> mutate(Strategy s, Random rng) {
            Strategy.PlanningHint[] hints = Strategy.PlanningHint.values();
            Strategy.PlanningHint next = hints[(s.planningHint().ordinal() + 1) % hints.length];
            Strategy out = new Strategy(s.maxIterations(), s.retryMaxAttempts(), s.retryInitialBackoffMs(),
                s.retryMultiplier(), next, s.guardrailStrictness());
            return Optional.of(new MutatedStrategy(out, new Mutation(name(), "planningHint",
                s.planningHint().name(), next.name(),
                "try a different closed-vocabulary planning nudge; kept only if measured scores improve")));
        }
    }

    /** Flip guardrail strictness STANDARD <-> STRICT. */
    static final class ToggleGuardrailStrictness implements MutationOperator {
        @Override public String name() { return "toggle-guardrail-strictness"; }

        @Override
        public Optional<MutatedStrategy> mutate(Strategy s, Random rng) {
            Strategy.GuardrailStrictness next = s.guardrailStrictness()
                == Strategy.GuardrailStrictness.STANDARD
                ? Strategy.GuardrailStrictness.STRICT
                : Strategy.GuardrailStrictness.STANDARD;
            Strategy out = new Strategy(s.maxIterations(), s.retryMaxAttempts(), s.retryInitialBackoffMs(),
                s.retryMultiplier(), s.planningHint(), next);
            return Optional.of(new MutatedStrategy(out, new Mutation(name(), "guardrailStrictness",
                s.guardrailStrictness().name(), next.name(),
                next == Strategy.GuardrailStrictness.STRICT
                    ? "add output PII redaction; kept only if it does not regress scores"
                    : "drop the extra redaction layer")));
        }
    }
}

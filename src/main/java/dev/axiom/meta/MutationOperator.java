package dev.axiom.meta;

import java.util.Optional;
import java.util.Random;

/**
 * One small, typed perturbation of a strategy. Operators are deliberately
 * narrow — bump one knob within hard bounds — so mutation stays safe and
 * every change is explainable. Returning {@link Optional#empty()} means the
 * operator does not apply to the given strategy (e.g. already at a bound).
 */
public interface MutationOperator {
    /** Stable name used in the audit trail. */
    String name();

    Optional<MutatedStrategy> mutate(Strategy current, Random rng);
}

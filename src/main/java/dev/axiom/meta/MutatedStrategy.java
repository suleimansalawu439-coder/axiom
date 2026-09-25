package dev.axiom.meta;

/** A strategy produced by one {@link MutationOperator} application, plus the mutation record. */
public record MutatedStrategy(Strategy strategy, Mutation mutation) {}

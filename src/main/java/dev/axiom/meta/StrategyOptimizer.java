package dev.axiom.meta;

import dev.axiom.eval.EvalGate;
import dev.axiom.eval.EvalGateException;
import dev.axiom.eval.EvalReport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Hill-climbing optimizer with restarts over {@link Strategy} space.
 *
 * <p>Each attempt starts from a strategy (the seed, or a random "kick" away
 * from the best so far) and proposes challengers via
 * {@link MutationOperator}s. A challenger replaces the champion only when
 * both hold:
 * <ul>
 *   <li>{@link dev.axiom.eval.EvalGate#assertNoRegression} passes against
 *       the champion's train report — the optimizer can never regress, only
 *       improve or tie;</li>
 *   <li>the challenger's mean score beats the champion's by more than
 *       {@code minDelta}, or ties within it while being <i>simpler</i>
 *       (fewer non-default knobs) — ties keep the simpler strategy.</li>
 * </ul>
 *
 * <p>After the search, the champion is evaluated once on the held-out suite
 * and both scores are reported honestly in the {@link OptimizationReceipt} —
 * including the "found nothing better" outcome, which is a valid result.
 *
 * <p>Deterministic: every random choice flows from the configured seed, so
 * the same seed + task set + fixture scripts always yield the same champion.
 */
public final class StrategyOptimizer {

    /** Scores one strategy on the train suite. Fixture-backed in practice. */
    public interface Evaluator {
        EvalReport evaluate(Strategy strategy);
    }

    /** Tuning for the search. */
    public record Config(
            /** Hill-climbing generations per restart attempt. */
            int generations,
            /** Max challengers evaluated per generation. */
            int challengersPerGeneration,
            /** Restart attempts after the first (each starts kicked). */
            int restarts,
            /** Min mean-score gain to count as an improvement. */
            double minDelta,
            /** Score-drop tolerance passed to EvalGate. */
            double scoreTolerance,
            /** RNG seed — the reproducibility key. */
            long seed) {
        public Config {
            if (generations < 1) throw new IllegalArgumentException("generations must be >= 1");
            if (challengersPerGeneration < 1)
                throw new IllegalArgumentException("challengersPerGeneration must be >= 1");
            if (restarts < 0) throw new IllegalArgumentException("restarts must be >= 0");
            if (minDelta < 0) throw new IllegalArgumentException("minDelta must be >= 0");
            if (scoreTolerance < 0) throw new IllegalArgumentException("scoreTolerance must be >= 0");
        }

        public static Config defaults() {
            return new Config(6, 6, 2, 1e-9, EvalGate.DEFAULT_SCORE_TOLERANCE, 42L);
        }
    }

    /** The outcome: champion, its train + holdout scores, and the full receipt. */
    public record OptimizationResult(Strategy champion, EvalReport trainReport,
                                     EvalReport holdoutReport, OptimizationReceipt receipt) {}

    private StrategyOptimizer() {}

    /**
     * Run the optimization. {@code trainEvaluator} scores candidates during
     * the search; {@code holdoutEvaluator} scores the final champion once.
     */
    public static OptimizationResult optimize(
            Strategy seed,
            Evaluator trainEvaluator,
            Evaluator holdoutEvaluator,
            List<MutationOperator> operators,
            Config config) {
        Objects.requireNonNull(seed, "seed is required");
        Objects.requireNonNull(trainEvaluator, "trainEvaluator is required");
        Objects.requireNonNull(holdoutEvaluator, "holdoutEvaluator is required");
        Objects.requireNonNull(operators, "operators is required");
        Objects.requireNonNull(config, "config is required");
        if (operators.isEmpty()) throw new IllegalArgumentException("at least one operator is required");

        java.time.Instant startedAt = Instant.now();
        Random rng = new Random(config.seed());

        Strategy best = seed;
        EvalReport bestReport = trainEvaluator.evaluate(seed);
        OptimizationReceipt.ScoreSummary seedSummary = summarize(bestReport);

        List<OptimizationReceipt.AttemptRecord> attemptRecords = new ArrayList<>();
        int generationCounter = 0;

        for (int attempt = 0; attempt <= config.restarts(); attempt++) {
            Strategy center;
            if (attempt == 0) {
                center = seed;
            } else {
                // Restart: kick the best so far to a nearby random point and climb from there.
                center = kick(best, operators, rng);
            }
            List<OptimizationReceipt.GenerationRecord> genRecords = new ArrayList<>();

            for (int g = 1; g <= config.generations(); g++) {
                generationCounter++;
                List<MutatedStrategy> candidates = propose(center, best, operators, rng,
                    config.challengersPerGeneration());

                String winner = null;
                String winnerReason;
                List<OptimizationReceipt.CandidateRecord> candidateRecords = new ArrayList<>();

                for (MutatedStrategy cand : candidates) {
                    EvalReport candReport = trainEvaluator.evaluate(cand.strategy());
                    String verdict = judge(cand.strategy(), candReport, best, bestReport, config);
                    candidateRecords.add(new OptimizationReceipt.CandidateRecord(
                        cand.mutation(), candReport.meanScore(), candReport.passRate(), verdict));
                    if (verdict.startsWith("accepted")) {
                        best = cand.strategy();
                        bestReport = candReport;
                        center = best;
                        winner = cand.strategy().toString();
                    }
                }

                if (winner != null) {
                    winnerReason = "new champion: no regression vs previous best and strictly better (or tie-and-simpler)";
                } else {
                    winnerReason = candidates.isEmpty()
                        ? "no applicable mutations from this center"
                        : "no challenger beat the champion without regressing";
                }
                genRecords.add(new OptimizationReceipt.GenerationRecord(
                    generationCounter, center.toString(), candidateRecords, winner, winnerReason));
            }
            attemptRecords.add(new OptimizationReceipt.AttemptRecord(attempt, center, genRecords));
        }

        EvalReport holdoutReport = holdoutEvaluator.evaluate(best);
        Instant finishedAt = Instant.now();

        String notes = best.equals(seed)
            ? "No mutation beat the seed without regressing: the seed is already optimal on this task set. "
                + "This is a valid outcome, not a failure."
            : "Champion differs from seed; see generations for the accepted mutations.";

        OptimizationReceipt receipt = new OptimizationReceipt(
            dev.axiom.Version.CURRENT, startedAt, finishedAt,
            seed, best,
            seedSummary, summarize(bestReport), summarize(holdoutReport),
            attemptRecords, notes);

        return new OptimizationResult(best, bestReport, holdoutReport, receipt);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static List<MutatedStrategy> propose(Strategy center, Strategy best,
                                                List<MutationOperator> operators,
                                                Random rng, int cap) {
        List<MutatedStrategy> out = new ArrayList<>();
        for (MutationOperator op : operators) {
            if (out.size() >= cap) break;
            op.mutate(center, rng).ifPresent(m -> {
                // Skip no-op / duplicate proposals inside one generation.
                if (!m.strategy().equals(center)
                    && out.stream().noneMatch(e -> e.strategy().equals(m.strategy()))) {
                    out.add(m);
                }
            });
        }
        return out;
    }

    /**
     * Accept a challenger only if it does not regress (EvalGate vs the
     * champion's report) and it is strictly better by minDelta — or ties
     * within minDelta while being simpler. Returns the verdict string for
     * the audit trail.
     */
    static String judge(Strategy cand, EvalReport candReport,
                        Strategy best, EvalReport bestReport, Config config) {
        try {
            EvalGate.assertNoRegression(candReport, bestReport, config.scoreTolerance());
        } catch (EvalGateException e) {
            return "rejected: regression vs champion (" + firstLine(e.getMessage()) + ")";
        }
        double gain = candReport.meanScore() - bestReport.meanScore();
        if (gain > config.minDelta()) {
            return "accepted: mean score %+.4f".formatted(gain);
        }
        if (Math.abs(gain) <= config.minDelta() && cand.complexity() < best.complexity()) {
            return "accepted: tie on score, simpler strategy (complexity %d -> %d)"
                .formatted(best.complexity(), cand.complexity());
        }
        return "rejected: no improvement (gain %+.4f)".formatted(gain);
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    /** Compose 2-3 random mutations for a restart kick. */
    private static Strategy kick(Strategy from, List<MutationOperator> operators, Random rng) {
        Strategy s = from;
        int hops = 2 + rng.nextInt(2);
        for (int i = 0; i < hops; i++) {
            MutationOperator op = operators.get(rng.nextInt(operators.size()));
            var mutated = op.mutate(s, rng);
            if (mutated.isPresent()) s = mutated.get().strategy();
        }
        return s;
    }

    static OptimizationReceipt.ScoreSummary summarize(EvalReport r) {
        return new OptimizationReceipt.ScoreSummary(r.meanScore(), r.passRate(), r.results().size());
    }
}

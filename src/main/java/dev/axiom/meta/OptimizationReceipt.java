package dev.axiom.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.Version;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The auditable record of one {@link StrategyOptimizer} run: seed, every
 * generation's candidates with their mutations and scores, the winner and
 * why it won, and the train + holdout scores of the final champion.
 *
 * <p>Saved as JSON next to {@code strategy.json} so the whole optimization
 * is reproducible: same seed + same task set + same fixture scripts must
 * yield the same champion.
 */
public final class OptimizationReceipt {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Aggregate score of one suite evaluation. */
    public record ScoreSummary(double meanScore, double passRate, int cases) {}

    /** One evaluated candidate inside a generation. */
    public record CandidateRecord(Mutation mutation, double meanScore, double passRate, String verdict) {}

    /** One hill-climbing generation. */
    public record GenerationRecord(int generation, String center,
                                   List<CandidateRecord> candidates,
                                   String winner, String winnerReason) {}

    /** One restart attempt (attempt 0 starts at the seed; later attempts start kicked). */
    public record AttemptRecord(int attempt, Strategy startStrategy, List<GenerationRecord> generations) {}

    private final String frameworkVersion;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final Strategy seedStrategy;
    private final Strategy championStrategy;
    private final ScoreSummary seedTrain;
    private final ScoreSummary championTrain;
    private final ScoreSummary championHoldout;
    private final List<AttemptRecord> attempts;
    private final String notes;

    public OptimizationReceipt(String frameworkVersion, Instant startedAt, Instant finishedAt,
                               Strategy seedStrategy, Strategy championStrategy,
                               ScoreSummary seedTrain, ScoreSummary championTrain,
                               ScoreSummary championHoldout,
                               List<AttemptRecord> attempts, String notes) {
        this.frameworkVersion = frameworkVersion;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.seedStrategy = seedStrategy;
        this.championStrategy = championStrategy;
        this.seedTrain = seedTrain;
        this.championTrain = championTrain;
        this.championHoldout = championHoldout;
        this.attempts = List.copyOf(attempts);
        this.notes = notes;
    }

    public String frameworkVersion() { return frameworkVersion; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public Strategy seedStrategy() { return seedStrategy; }
    public Strategy championStrategy() { return championStrategy; }
    public ScoreSummary seedTrain() { return seedTrain; }
    public ScoreSummary championTrain() { return championTrain; }
    public ScoreSummary championHoldout() { return championHoldout; }
    public List<AttemptRecord> attempts() { return attempts; }
    public String notes() { return notes; }

    /** True when the optimizer found a strictly better strategy than the seed. */
    public boolean improved() {
        return championTrain.meanScore() > seedTrain.meanScore() + 1e-9
            || (Math.abs(championTrain.meanScore() - seedTrain.meanScore()) <= 1e-9
                && championStrategy.complexity() < seedStrategy.complexity());
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    public Map<String, Object> toJsonMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("framework", "axiom");
        m.put("frameworkVersion", frameworkVersion);
        m.put("startedAt", startedAt.toString());
        m.put("finishedAt", finishedAt.toString());
        m.put("seedStrategy", strategyMap(seedStrategy));
        m.put("championStrategy", strategyMap(championStrategy));
        m.put("seedTrain", scoreMap(seedTrain));
        m.put("championTrain", scoreMap(championTrain));
        m.put("championHoldout", scoreMap(championHoldout));
        List<Object> as = new ArrayList<>();
        for (AttemptRecord a : attempts) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("attempt", a.attempt());
            am.put("startStrategy", strategyMap(a.startStrategy()));
            List<Object> gs = new ArrayList<>();
            for (GenerationRecord g : a.generations()) {
                Map<String, Object> gm = new LinkedHashMap<>();
                gm.put("generation", g.generation());
                gm.put("center", g.center());
                List<Object> cs = new ArrayList<>();
                for (CandidateRecord c : g.candidates()) {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("mutation", c.mutation().toString());
                    cm.put("meanScore", c.meanScore());
                    cm.put("passRate", c.passRate());
                    cm.put("verdict", c.verdict());
                    cs.add(cm);
                }
                gm.put("candidates", cs);
                gm.put("winner", g.winner());
                gm.put("winnerReason", g.winnerReason());
                gs.add(gm);
            }
            am.put("generations", gs);
            as.add(am);
        }
        m.put("attempts", as);
        m.put("notes", notes);
        return m;
    }

    private static Map<String, Object> strategyMap(Strategy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxIterations", s.maxIterations());
        m.put("retryMaxAttempts", s.retryMaxAttempts());
        m.put("retryInitialBackoffMs", s.retryInitialBackoffMs());
        m.put("retryMultiplier", s.retryMultiplier());
        m.put("planningHint", s.planningHint().name());
        m.put("guardrailStrictness", s.guardrailStrictness().name());
        m.put("complexity", s.complexity());
        return m;
    }

    private static Map<String, Object> scoreMap(ScoreSummary s) {
        return Map.of("meanScore", s.meanScore(), "passRate", s.passRate(), "cases", s.cases());
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(toJsonMap());
        } catch (Exception e) {
            throw new MetaException("Failed to serialize optimization receipt", e);
        }
    }

    /** Write the receipt to {@code path} (pretty-printed). */
    public void save(Path path) {
        try {
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toJsonMap());
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new MetaException("Failed to save optimization receipt to " + path, e);
        }
    }

    /**
     * Load a receipt previously written with {@link #save}. Restores the
     * headline fields (strategies, scores, timestamps, notes); the
     * per-generation attempt detail stays in the JSON file itself and is not
     * re-parsed — the file remains the full audit trail.
     */
    @SuppressWarnings("unchecked")
    public static OptimizationReceipt load(Path path) {
        try {
            Map<String, Object> m = MAPPER.readValue(
                Files.readString(path, StandardCharsets.UTF_8), Map.class);
            return new OptimizationReceipt(
                str(m.get("frameworkVersion"), Version.CURRENT),
                Instant.parse(str(m.get("startedAt"))),
                Instant.parse(str(m.get("finishedAt"))),
                parseStrategy((Map<String, Object>) m.get("seedStrategy")),
                parseStrategy((Map<String, Object>) m.get("championStrategy")),
                parseScores((Map<String, Object>) m.get("seedTrain")),
                parseScores((Map<String, Object>) m.get("championTrain")),
                parseScores((Map<String, Object>) m.get("championHoldout")),
                List.of(),
                strOrNull(m.get("notes")));
        } catch (Exception e) {
            throw new MetaException("Failed to load optimization receipt from " + path, e);
        }
    }

    private static Strategy parseStrategy(Map<String, Object> m) {
        return new Strategy(
            num(m.get("maxIterations")).intValue(),
            num(m.get("retryMaxAttempts")).intValue(),
            num(m.get("retryInitialBackoffMs")).longValue(),
            dbl(m.get("retryMultiplier")),
            Strategy.PlanningHint.valueOf(str(m.get("planningHint"))),
            Strategy.GuardrailStrictness.valueOf(str(m.get("guardrailStrictness"))));
    }

    private static ScoreSummary parseScores(Map<String, Object> m) {
        return new ScoreSummary(dbl(m.get("meanScore")), dbl(m.get("passRate")),
            num(m.get("cases")).intValue());
    }

    private static String str(Object o) {
        if (o == null) throw new MetaException("Malformed receipt: missing required field");
        return o.toString();
    }

    private static String str(Object o, String dflt) { return o == null ? dflt : o.toString(); }
    private static String strOrNull(Object o) { return o == null ? null : o.toString(); }
    private static double dbl(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }
    private static Number num(Object o) { return o instanceof Number n ? n : 0; }

    @Override
    public String toString() {
        return "OptimizationReceipt{champion=%s, train %.3f -> %.3f, holdout %.3f, attempts=%d}"
            .formatted(championStrategy, seedTrain.meanScore(), championTrain.meanScore(),
                championHoldout.meanScore(), attempts.size());
    }
}

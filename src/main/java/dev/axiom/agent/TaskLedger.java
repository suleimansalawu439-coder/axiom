package dev.axiom.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Task Ledger: Magentic-One pattern for structured planning.
 *
 * <p>The ledger tracks three things across iterations:
 * <ul>
 *   <li><b>Facts</b> — verified information gathered from tools</li>
 *   <li><b>Guesses</b> — hypotheses not yet confirmed</li>
 *   <li><b>Plan</b> — the current step-by-step approach</li>
 * </ul>
 *
 * <p>Evidence (2026-10-04, GAIA top-submissions research): Magentic-One's
 * ablation showed removing the ledgers drops GAIA score by 31%. The key
 * finding from HF: when re-planning, EXCLUDE the stale plan from the
 * replan prompt — LLMs over-anchor on old plans.
 */
public final class TaskLedger {
    private final List<String> facts = new ArrayList<>();
    private final List<String> guesses = new ArrayList<>();
    private final List<String> plan = new ArrayList<>();
    private int iterationsSinceNewFact = 0;

    public void addFact(String fact) {
        if (fact != null && !fact.isBlank() && !facts.contains(fact.trim())) {
            facts.add(fact.trim());
            iterationsSinceNewFact = 0;
        }
    }

    public void addGuess(String guess) {
        if (guess != null && !guess.isBlank() && !guesses.contains(guess.trim())) {
            guesses.add(guess.trim());
        }
    }

    public void setPlan(List<String> steps) {
        plan.clear();
        if (steps != null) {
            for (String s : steps) {
                if (s != null && !s.isBlank()) plan.add(s.trim());
            }
        }
    }

    public List<String> facts() { return List.copyOf(facts); }
    public List<String> guesses() { return List.copyOf(guesses); }
    public List<String> plan() { return List.copyOf(plan); }

    /** Called each iteration where no new fact was added. */
    public void recordStall() { iterationsSinceNewFact++; }

    public int iterationsSinceNewFact() { return iterationsSinceNewFact; }

    /** True when the agent is spinning without progress — time to re-plan. */
    public boolean needsReplan(int threshold) {
        return iterationsSinceNewFact >= threshold && !facts.isEmpty();
    }

    /**
     * Render the ledger for the replan prompt.
     * CRITICAL: excludes the stale plan (HF finding — LLMs over-anchor).
     */
    public String renderForReplan() {
        StringBuilder sb = new StringBuilder();
        sb.append("VERIFIED FACTS:\n");
        for (int i = 0; i < facts.size(); i++) {
            sb.append(i + 1).append(". ").append(facts.get(i)).append("\n");
        }
        if (!guesses.isEmpty()) {
            sb.append("\nUNCONFIRMED GUESSES:\n");
            for (int i = 0; i < guesses.size(); i++) {
                sb.append(i + 1).append(". ").append(guesses.get(i)).append("\n");
            }
        }
        sb.append("\nYour previous plan is stale — do NOT follow it. ");
        sb.append("Create a FRESH plan based ONLY on the facts above.");
        return sb.toString();
    }

    /** Render a compact summary for the system prompt. */
    public String renderSummary() {
        if (facts.isEmpty() && plan.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n[TASK LEDGER]\n");
        if (!facts.isEmpty()) {
            sb.append("Facts: ").append(String.join("; ", facts)).append("\n");
        }
        if (!plan.isEmpty()) {
            sb.append("Plan: ").append(String.join(" → ", plan)).append("\n");
        }
        return sb.toString();
    }
}

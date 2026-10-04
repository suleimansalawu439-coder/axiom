package dev.axiom.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TaskLedger (Magentic-One pattern, 2026-10-04).
 */
class TaskLedgerTest {

    @Test
    void addFactDeduplicatesAndTracks() {
        TaskLedger ledger = new TaskLedger();
        ledger.addFact("Paris is the capital of France");
        ledger.addFact("Paris is the capital of France"); // duplicate
        ledger.addFact("  "); // blank
        assertEquals(1, ledger.facts().size());
        assertEquals("Paris is the capital of France", ledger.facts().get(0));
    }

    @Test
    void needsReplanAfterThresholdWithoutNewFacts() {
        TaskLedger ledger = new TaskLedger();
        ledger.addFact("fact1");
        assertFalse(ledger.needsReplan(4));
        ledger.recordStall();
        ledger.recordStall();
        ledger.recordStall();
        assertFalse(ledger.needsReplan(4));
        ledger.recordStall();
        assertTrue(ledger.needsReplan(4));
    }

    @Test
    void newFactResetsStallCounter() {
        TaskLedger ledger = new TaskLedger();
        ledger.addFact("fact1");
        ledger.recordStall();
        ledger.recordStall();
        ledger.addFact("fact2"); // resets counter
        assertEquals(0, ledger.iterationsSinceNewFact());
        assertFalse(ledger.needsReplan(2));
    }

    @Test
    void renderForReplanExcludesStalePlan() {
        // HF finding: LLMs over-anchor on stale plans — exclude from replan.
        TaskLedger ledger = new TaskLedger();
        ledger.addFact("The answer is 42");
        ledger.addGuess("Maybe it's 43");
        ledger.setPlan(List.of("Step 1: search", "Step 2: guess"));
        String rendered = ledger.renderForReplan();
        assertTrue(rendered.contains("42"));
        assertTrue(rendered.contains("43"));
        assertFalse(rendered.contains("Step 1"));
        assertTrue(rendered.contains("do NOT follow it") || rendered.contains("stale"));
    }

    @Test
    void renderSummaryIncludesFactsAndPlan() {
        TaskLedger ledger = new TaskLedger();
        ledger.addFact("fact1");
        ledger.setPlan(List.of("a", "b"));
        String summary = ledger.renderSummary();
        assertTrue(summary.contains("fact1"));
        assertTrue(summary.contains("a → b"));
    }
}

package dev.axiom.bench.gaia;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixture-only tests for {@link GaiaScorer}: the official GAIA
 * quasi-exact-match rules (numeric / list / string ground truths). No
 * dataset values are asserted here beyond synthetic cases.
 */
class GaiaScorerTest {

    // --- numeric ground truth -------------------------------------------

    @Test
    void numberExactMatch() {
        assertTrue(GaiaScorer.score("17", "17"));
    }

    @Test
    void numberStripsCurrencyPercentAndCommas() {
        assertTrue(GaiaScorer.score("$1,000", "1000"));
        assertTrue(GaiaScorer.score("1,000.5", "1000.5"));
        assertTrue(GaiaScorer.score("42%", "42"));
    }

    @Test
    void numberMismatchFails() {
        assertFalse(GaiaScorer.score("42", "43"));
    }

    @Test
    void numberUnparseableModelAnswerFails() {
        assertFalse(GaiaScorer.score("seventeen", "17"));
    }

    @Test
    void nullModelAnswerBecomesNoneAndFailsNumeric() {
        assertFalse(GaiaScorer.score(null, "17"));
    }

    @Test
    void numberIgnoresSurroundingWhitespace() {
        assertTrue(GaiaScorer.score("  17 ", "17"));
    }

    // --- string ground truth --------------------------------------------

    @Test
    void stringIgnoresWhitespaceCaseAndPunctuation() {
        assertTrue(GaiaScorer.score("Sea Gull", "seagull"));
        assertTrue(GaiaScorer.score("Hello, World!", "hello world"));
        assertTrue(GaiaScorer.score("  Abuja. ", "abuja"));
    }

    @Test
    void stringMismatchFails() {
        assertFalse(GaiaScorer.score("Paris", "London"));
    }

    @Test
    void stringVerboseAnswerFails() {
        // No extraction, no judge: extra words fail.
        assertFalse(GaiaScorer.score("The answer is 42", "42"));
        assertFalse(GaiaScorer.score("The capital is Abuja", "Abuja"));
    }

    // --- list ground truth ----------------------------------------------

    @Test
    void listMatchesAcrossSeparatorsAndSpacing() {
        assertTrue(GaiaScorer.score("a,b", "a, b"));
        assertTrue(GaiaScorer.score("1;2", "1, 2"));
        assertTrue(GaiaScorer.score("apple, banana", "apple,banana"));
    }

    @Test
    void listNumericElementsCompareAsFloats() {
        assertTrue(GaiaScorer.score("1, 2.0", "1,2"));
        assertFalse(GaiaScorer.score("1, 3", "1,2"));
    }

    @Test
    void listLengthMismatchFails() {
        assertFalse(GaiaScorer.score("a", "a,b"));
        assertFalse(GaiaScorer.score("a,b,c", "a,b"));
    }

    @Test
    void listStringElementsKeepPunctuation() {
        assertTrue(GaiaScorer.score("a.b, c", "a.b,c"));
        assertFalse(GaiaScorer.score("ab, c", "a.b,c"));
    }

    @Test
    void listMismatchFails() {
        assertFalse(GaiaScorer.score("Paris, London", "Paris, Berlin"));
    }

    // --- scorer is usable as a BenchRunner.OutputScorer -------------------

    @Test
    void usableAsOutputScorer() {
        dev.axiom.bench.BenchRunner.OutputScorer scorer = GaiaScorer::score;
        assertTrue(scorer.score("Abuja", "abuja"));
        assertFalse(scorer.score("Lagos", "abuja"));
    }
}

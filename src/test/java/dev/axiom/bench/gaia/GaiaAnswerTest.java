package dev.axiom.bench.gaia;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mechanical answer normalization: safe hygiene only, never
 * reinterpretation.
 */
class GaiaAnswerTest {

    @Test
    void trimsWhitespace() {
        assertEquals("17", GaiaAnswer.normalize("  17\n"));
    }

    @Test
    void stripsBalancedDoubleQuotes() {
        assertEquals("17", GaiaAnswer.normalize("\"17\""));
    }

    @Test
    void stripsBalancedSingleQuotes() {
        assertEquals("hello", GaiaAnswer.normalize("  'hello'  "));
    }

    @Test
    void stripsBackticks() {
        assertEquals("code", GaiaAnswer.normalize("`code`"));
    }

    @Test
    void unbalancedQuotesLeftAlone() {
        assertEquals("\"17", GaiaAnswer.normalize("\"17"));
        assertEquals("17\"", GaiaAnswer.normalize("17\""));
    }

    @Test
    void unicodeNormalized() {
        // Full-width digits → ASCII via NFKC.
        assertEquals("17", GaiaAnswer.normalize("１７"));
    }

    @Test
    void nullAndBlankBecomeEmpty() {
        assertEquals("", GaiaAnswer.normalize(null));
        assertEquals("", GaiaAnswer.normalize("   "));
    }

    @Test
    void neverReinterpretsContent() {
        // A wrong-magnitude answer with unit echo must NOT be rescued —
        // that would be answer-tuning, not hygiene.
        assertEquals("17000 hours", GaiaAnswer.normalize("17000 hours"));
        assertEquals("Extremely hot", GaiaAnswer.normalize("Extremely hot"));
    }

    @Test
    void innerContentUntouched() {
        assertEquals("a, b, c", GaiaAnswer.normalize("a, b, c"));
        assertEquals("say \"hi\"", GaiaAnswer.normalize("say \"hi\""));
    }
}

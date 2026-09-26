package dev.axiom.bench.gaia;

import java.text.Normalizer;

/**
 * Mechanical output hygiene applied to the agent's raw final answer before
 * scoring. The first real GAIA run showed the model repeatedly computing
 * the right value and then formatting it wrong (extra quotes, stray
 * whitespace, unicode lookalikes from web copy-paste) — failures of
 * formatting, not reasoning.
 *
 * <p>Only <i>safe</i> normalization is allowed here: trim, strip balanced
 * surrounding quotes, Unicode NFKC normalization. This deliberately does
 * NOT reinterpret the answer — {@code "17000 hours"} stays
 * {@code "17000 hours"} (it will fail against {@code "17"}, as it should:
 * rescuing wrong magnitudes would be answer-tuning). The official
 * {@link GaiaScorer} is unchanged and still does the real comparison.
 */
public final class GaiaAnswer {

    private GaiaAnswer() {}

    /**
     * Normalize a raw model answer: Unicode NFKC, trim, strip one layer of
     * balanced surrounding quotes ({@code "..."}, {@code '...'},
     * {@code `...`}). Never null — null/blank input becomes "".
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC).strip();
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            boolean quoted = (first == '"' && last == '"')
                || (first == '\'' && last == '\'')
                || (first == '`' && last == '`');
            if (quoted) {
                s = s.substring(1, s.length() - 1).strip();
            }
        }
        return s;
    }
}

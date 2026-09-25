package dev.axiom.bench.gaia;

/**
 * GAIA's official quasi-exact-match scorer, ported to Java from the GAIA
 * leaderboard space's {@code scorer.py} (gaia-benchmark/leaderboard, fetched
 * 2026-09-25). The paper (arXiv 2311.12983 §3.2) defines evaluation as
 * "quasi exact match between a model's answer and the ground truth (up to
 * some normalization that is tied to the 'type' of the ground truth)".
 *
 * <p>Rules, exactly as the official function implements them:
 * <ol>
 *   <li>A null model answer is treated as the string {@code "None"}.</li>
 *   <li>If the ground truth parses as a number: strip {@code $}, {@code %}
 *       and {@code ,} from the model answer, parse it as a double, and
 *       require exact double equality (unparseable → +∞, i.e. no match).</li>
 *   <li>Else if the ground truth contains {@code ,} or {@code ;}: split both
 *       answers on those characters; the element counts must be equal, and
 *       each pair is compared numerically when the ground-truth element is
 *       numeric, otherwise as a string with whitespace removed and
 *       lowercased (punctuation kept).</li>
 *   <li>Otherwise: remove ALL whitespace, remove ASCII punctuation, lowercase
 *       both sides, and require exact equality.</li>
 * </ol>
 *
 * <p>Two deliberate, documented deviations from a literal Python port:
 * <ul>
 *   <li>Python's {@code float()} strips surrounding whitespace and accepts
 *       {@code inf}/{@code nan}; this port trims before parsing and does not
 *       special-case those tokens (no GAIA ground truth uses them).</li>
 *   <li>No LLM judge, no partial credit, no answer extraction: the agent's
 *       raw final output is scored. Verbose answers fail rather than being
 *       rescued — underclaim by design.</li>
 * </ul>
 */
public final class GaiaScorer {

    /** Python's {@code string.punctuation}, the exact set the official scorer removes. */
    private static final String PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

    private GaiaScorer() {}

    /**
     * Official {@code question_scorer}: true when the model answer matches
     * the ground truth under the type-tied normalization above.
     */
    public static boolean score(String modelAnswer, String groundTruth) {
        String ma = modelAnswer == null ? "None" : modelAnswer;
        String gt = groundTruth == null ? "" : groundTruth;
        if (isFloat(gt)) {
            // Ground truth is a number: exact double comparison.
            return normalizeNumberStr(ma) == Double.parseDouble(gt.trim());
        } else if (gt.indexOf(',') >= 0 || gt.indexOf(';') >= 0) {
            // Ground truth is a comma/semicolon separated list.
            String[] gtElems = splitList(gt);
            String[] maElems = splitList(ma);
            if (gtElems.length != maElems.length) {
                return false;
            }
            for (int i = 0; i < gtElems.length; i++) {
                if (isFloat(gtElems[i])) {
                    if (normalizeNumberStr(maElems[i]) != Double.parseDouble(gtElems[i].trim())) {
                        return false;
                    }
                } else if (!normalizeStr(maElems[i], false)
                        .equals(normalizeStr(gtElems[i], false))) {
                    return false;
                }
            }
            return true;
        } else {
            // Ground truth is a string.
            return normalizeStr(ma, true).equals(normalizeStr(gt, true));
        }
    }

    /** Official {@code normalize_number_str}: drop $, %, and commas, then parse. */
    static double normalizeNumberStr(String numberStr) {
        String s = numberStr.replace("$", "").replace("%", "").replace(",", "").trim();
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.POSITIVE_INFINITY; // official code returns float("inf")
        }
    }

    /** Official {@code split_string}: split on commas and semicolons. */
    static String[] splitList(String s) {
        return s.split("[,;]", -1);
    }

    /**
     * Official {@code normalize_str}: remove ALL whitespace, optionally
     * remove ASCII punctuation, lowercase.
     */
    static String normalizeStr(String input, boolean removePunct) {
        String noSpaces = input.replaceAll("\\s", "");
        if (removePunct) {
            StringBuilder sb = new StringBuilder(noSpaces.length());
            for (int i = 0; i < noSpaces.length(); i++) {
                char c = noSpaces.charAt(i);
                if (PUNCTUATION.indexOf(c) < 0) sb.append(c);
            }
            return sb.toString().toLowerCase();
        }
        return noSpaces.toLowerCase();
    }

    /** Official {@code is_float}: true when the string parses as a double. */
    private static boolean isFloat(String s) {
        try {
            Double.parseDouble(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}

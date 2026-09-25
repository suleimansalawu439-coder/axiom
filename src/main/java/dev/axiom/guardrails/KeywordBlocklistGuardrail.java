package dev.axiom.guardrails;

import java.util.List;
import java.util.Objects;

/**
 * Blocks any input or output containing a forbidden keyword (case-insensitive
 * substring match). The blunt instrument every regulated deployment reaches
 * for first — combine with domain-specific guardrails for real coverage.
 */
public final class KeywordBlocklistGuardrail implements Guardrail {
    private final String name;
    private final List<String> keywords;

    public KeywordBlocklistGuardrail(String name, List<String> keywords) {
        this.name = Objects.requireNonNull(name);
        if (keywords == null || keywords.isEmpty()) {
            throw new IllegalArgumentException("keywords must not be empty");
        }
        this.keywords = List.copyOf(keywords);
    }

    public KeywordBlocklistGuardrail(List<String> keywords) {
        this("keyword-blocklist", keywords);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Verdict checkInput(String task) {
        return scan(task, "input");
    }

    @Override
    public Verdict checkOutput(String output) {
        return scan(output, "output");
    }

    private Verdict scan(String text, String side) {
        if (text == null) return Verdict.allow();
        String lower = text.toLowerCase();
        for (String kw : keywords) {
            if (lower.contains(kw.toLowerCase())) {
                return Verdict.block(
                    "forbidden keyword '%s' found in %s".formatted(kw, side));
            }
        }
        return Verdict.allow();
    }
}

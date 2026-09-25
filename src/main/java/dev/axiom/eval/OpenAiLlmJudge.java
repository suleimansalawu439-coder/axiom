package dev.axiom.eval;

import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.OpenAiCompatibleClient;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An {@link LlmJudge} backed by any OpenAI-compatible chat endpoint. Asks the
 * judge model for a {@code SCORE:} line (0.0–1.0) and a {@code RATIONALE:}
 * line, and parses them leniently.
 */
public final class OpenAiLlmJudge implements LlmJudge {
    private static final Pattern SCORE_LINE =
        Pattern.compile("(?im)^\\s*score\\s*:\\s*([0-9]*\\.?[0-9]+)");
    private static final Pattern RATIONALE_LINE =
        Pattern.compile("(?im)^\\s*rationale\\s*:\\s*(.+)$");

    private final LlmClient client;

    public OpenAiLlmJudge(LlmClient client) {
        this.client = client;
    }

    /** Judge with the given model on the default OpenAI-compatible endpoint. */
    public OpenAiLlmJudge(String model) {
        this(new OpenAiCompatibleClient(model));
    }

    @Override
    public JudgeVerdict judge(String task, String actualOutput) {
        String prompt = """
            You are grading an AI agent's answer. Score it from 0.0 (completely wrong) \
            to 1.0 (perfect) for how well it answers the task.
            TASK: %s
            ANSWER: %s
            Reply with exactly these two lines and nothing else:
            SCORE: <number between 0.0 and 1.0>
            RATIONALE: <one sentence explaining the score>""".formatted(task, actualOutput);
        ChatResponse r = client.chat(
            List.of(ChatMessage.user(prompt)), List.of(), LlmClient.LlmOptions.defaults());
        String text = r.content() == null ? "" : r.content();
        double score = 0.0;
        Matcher sm = SCORE_LINE.matcher(text);
        if (sm.find()) {
            try {
                score = Math.min(1.0, Math.max(0.0, Double.parseDouble(sm.group(1))));
            } catch (NumberFormatException ignored) { /* keep 0.0 */ }
        }
        String rationale = "no rationale given";
        Matcher rm = RATIONALE_LINE.matcher(text);
        if (rm.find()) rationale = rm.group(1).trim();
        return new JudgeVerdict(score, rationale);
    }
}

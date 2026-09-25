package dev.axiom.eval;

/**
 * LLM-as-judge interface for open-ended eval cases. Implementations grade an
 * agent's output for a task on a 0..1 scale with a short rationale. The
 * bundled {@link OpenAiLlmJudge} works with any OpenAI-compatible endpoint.
 */
public interface LlmJudge {

    /** A judge's verdict: score in [0,1] plus a one-line rationale. */
    record JudgeVerdict(double score, String rationale) {
        public JudgeVerdict {
            if (score < 0 || score > 1) throw new IllegalArgumentException("score must be in [0,1]");
            if (rationale == null) throw new IllegalArgumentException("rationale is required");
        }
    }

    JudgeVerdict judge(String task, String actualOutput);
}

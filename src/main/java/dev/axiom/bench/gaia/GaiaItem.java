package dev.axiom.bench.gaia;

/**
 * One GAIA 2023 validation task: the question, its level, the ground-truth
 * final answer, and the name of the attached file (when the question ships
 * one — e.g. a spreadsheet or PDF the agent must read).
 *
 * <p>Attachments for the official GAIA set live in the access-gated
 * Hugging Face repository; without an accepted gate token they cannot be
 * fetched, so tasks naming one are classified UNATTEMPTED by the runner
 * rather than failed.
 */
public record GaiaItem(String taskId, String question, int level,
                       String trueAnswer, String fileName) {

    /** True when the question ships an attachment file the agent must read. */
    public boolean hasAttachment() {
        return fileName != null && !fileName.isBlank();
    }
}

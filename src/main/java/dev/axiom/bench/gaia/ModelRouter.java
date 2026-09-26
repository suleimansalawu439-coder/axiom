package dev.axiom.bench.gaia;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Free-tier model routing for the GAIA runner: spend a small daily budget
 * of a stronger model (e.g. {@code gemini-3.8-flash}, 20 requests/day free)
 * on the tasks a cheap heuristic flags as hard, and run everything else on
 * the high-volume lite model.
 *
 * <p>The difficulty heuristic is deliberately crude and documented as such:
 * a long question, or keywords suggesting multi-step work (counting,
 * ordering, comparing, media files). It is a triage signal, not a
 * guarantee — a "hard"-flagged task may be easy and vice versa. It is
 * generic (question shape, not task identity) so it cannot overfit to any
 * particular task set. Per-model spend is recorded in the receipt notes.
 */
public final class ModelRouter {

    /** Questions longer than this are routed to the smart model. */
    static final int HARD_QUESTION_CHARS = 350;
    /**
     * Keyword signals for multi-step work. Generic difficulty markers —
     * never task-specific.
     */
    static final List<String> HARD_KEYWORDS = List.of(
        "how many", "calculate", "compute", "which of the following",
        "order", "sequence", "compare", "identify", "list all",
        "step by step", "table", "spreadsheet", "image", "audio",
        "video", "chart", "rank");

    private final String defaultModel;
    private final String smartModel;
    private int smartBudget;
    private final Map<String, String> assignments = new LinkedHashMap<>();

    /**
     * @param defaultModel model for ordinary tasks
     * @param smartModel   stronger model for hard tasks (null disables routing)
     * @param smartBudget  how many tasks may use the smart model
     */
    public ModelRouter(String defaultModel, String smartModel, int smartBudget) {
        this.defaultModel = defaultModel;
        this.smartModel = smartModel;
        this.smartBudget = Math.max(0, smartBudget);
    }

    /** The heuristic: long question or multi-step keyword signals. */
    public static boolean isHardTask(GaiaItem item) {
        String q = item.question();
        if (q == null) return false;
        if (q.length() > HARD_QUESTION_CHARS) return true;
        String lower = q.toLowerCase();
        return HARD_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * Route one task to a model, recording the assignment. When the smart
     * budget is exhausted (or routing is disabled), every task gets the
     * default model. Thread-safe: the runner routes from parallel workers.
     */
    public synchronized String route(String taskId, GaiaItem item) {
        String model = defaultModel;
        if (smartModel != null && !smartModel.isBlank()
                && smartBudget > 0 && isHardTask(item)) {
            model = smartModel;
            smartBudget--;
        }
        assignments.put(taskId, model);
        return model;
    }

    /** Smart-model tasks still available. */
    public synchronized int smartRemaining() {
        return smartBudget;
    }

    /** Task id → model, in routing order. */
    public synchronized Map<String, String> assignments() {
        return Map.copyOf(assignments);
    }

    public String defaultModel() { return defaultModel; }
    public String smartModel() { return smartModel; }
}

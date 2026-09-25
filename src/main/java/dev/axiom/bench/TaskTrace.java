package dev.axiom.bench;

import dev.axiom.agent.AgentEvent;
import dev.axiom.llm.ToolCallRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step-by-step trace of one benchmark task run: per agent iteration, what the
 * model said and which tools it called — each with arguments, observed
 * result, and duration. Captured from {@link AgentEvent}s in emission order.
 *
 * <p>Renders to JSON-ready maps for the receipt and feeds the Markdown
 * report ({@link BenchReport}), so a run can be audited turn by turn without
 * re-running it.
 */
public final class TaskTrace {

    /** One tool invocation with its observed result. */
    public record ToolCallTrace(String name, Map<String, Object> arguments,
                                String result, long durationMs) {}

    /** One agent iteration: the model's text plus the tool calls it triggered. */
    public record Step(int iteration, String modelText, List<ToolCallTrace> toolCalls) {}

    private final List<Step> steps;

    private TaskTrace(List<Step> steps) {
        this.steps = List.copyOf(steps);
    }

    /** An empty trace: no iterations observed (or events were not captured). */
    public static TaskTrace empty() {
        return new TaskTrace(List.of());
    }

    public List<Step> steps() { return steps; }

    public boolean isEmpty() { return steps.isEmpty(); }

    /**
     * Build a trace from agent events in emission order. {@code LlmResponse}
     * events open (or extend) the step for their iteration; each
     * {@code ToolCallFinished} attaches to the most recent iteration's step,
     * since the agent emits the response before executing its tool calls.
     */
    public static TaskTrace fromEvents(List<AgentEvent> events) {
        Map<Integer, StepBuilder> builders = new LinkedHashMap<>();
        int current = 0;
        if (events != null) {
            for (AgentEvent e : events) {
                if (e instanceof AgentEvent.LlmResponse lr) {
                    current = lr.iteration();
                    StepBuilder b = builders.computeIfAbsent(current, StepBuilder::new);
                    String text = lr.response() == null ? null : lr.response().content();
                    if (text != null && !text.isBlank()) b.addModelText(text);
                } else if (e instanceof AgentEvent.ToolCallFinished tf) {
                    StepBuilder b = builders.computeIfAbsent(current, StepBuilder::new);
                    ToolCallRequest call = tf.call();
                    b.addToolCall(new ToolCallTrace(
                        call == null || call.name() == null ? "?" : call.name(),
                        call == null || call.arguments() == null
                            ? Map.of() : Map.copyOf(call.arguments()),
                        tf.result(), tf.durationMs()));
                }
            }
        }
        List<Step> steps = new ArrayList<>();
        for (StepBuilder b : builders.values()) steps.add(b.build());
        return new TaskTrace(steps);
    }

    /** JSON-ready rendering for the receipt: iteration, model text, tool calls. */
    public List<Map<String, Object>> toJsonList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Step s : steps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("iteration", s.iteration());
            m.put("modelText", s.modelText());
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ToolCallTrace t : s.toolCalls()) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("name", t.name());
                tm.put("arguments", t.arguments());
                tm.put("result", t.result());
                tm.put("durationMs", t.durationMs());
                calls.add(tm);
            }
            m.put("toolCalls", calls);
            out.add(m);
        }
        return out;
    }

    /** Mutable step accumulator; model text from repeated responses is joined. */
    private static final class StepBuilder {
        private final int iteration;
        private final StringBuilder modelText = new StringBuilder();
        private final List<ToolCallTrace> toolCalls = new ArrayList<>();

        StepBuilder(int iteration) { this.iteration = iteration; }

        void addModelText(String text) {
            if (!modelText.isEmpty()) modelText.append("\n");
            modelText.append(text);
        }

        void addToolCall(ToolCallTrace t) { toolCalls.add(t); }

        Step build() {
            return new Step(iteration, modelText.toString(), List.copyOf(toolCalls));
        }
    }
}

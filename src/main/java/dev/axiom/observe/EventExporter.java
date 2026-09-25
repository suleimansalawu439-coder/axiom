package dev.axiom.observe;

import dev.axiom.agent.AgentEvent;

/**
 * Sinks {@link AgentEvent}s somewhere durable: files, log pipelines, APM
 * systems. Plug into an agent with
 * {@code .onEvent(exporter.asListener())} — listeners never break a run,
 * and neither do exporters (failures are swallowed after one stderr note).
 */
public interface EventExporter {

    void export(AgentEvent event);

    /** Adapt to the {@code Consumer<AgentEvent>} listener shape. */
    default java.util.function.Consumer<AgentEvent> asListener() {
        return event -> {
            try {
                export(event);
            } catch (Exception e) {
                System.err.println("[Axiom] EventExporter failed: " + e.getMessage());
            }
        };
    }
}

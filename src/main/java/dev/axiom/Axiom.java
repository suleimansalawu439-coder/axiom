package dev.axiom;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.ReActAgent;

/**
 * Entry point for building agents. Example:
 * <pre>{@code
 * Agent agent = Axiom.agent()
 *     .withModel("gpt-4o")
 *     .withTools(new MyTools())
 *     .withSystemPrompt("You are a research assistant.")
 *     .build();
 *
 * String answer = agent.run("What happened in AI this week?");
 * }</pre>
 */
public final class Axiom {

    private Axiom() {}

    public static AgentConfig.Builder agent() {
        return AgentConfig.builder();
    }

    /** A runnable agent built from an {@link AgentConfig}. */
    public static final class Agent {
        private final ReActAgent delegate;

        public Agent(AgentConfig config) {
            this.delegate = new ReActAgent(config);
        }

        public dev.axiom.agent.AgentResult run(String task) {
            return delegate.run(task);
        }

        /**
         * Run the agent and coerce its final answer into a compile-time type.
         * See {@link ReActAgent#runFor(String, Class)}.
         */
        public <T> T runFor(String task, Class<T> outputType) {
            return delegate.runFor(task, outputType);
        }
    }
}

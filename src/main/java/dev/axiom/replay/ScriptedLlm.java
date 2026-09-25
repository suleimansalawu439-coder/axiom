package dev.axiom.replay;

import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.tools.ToolDefinition;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link LlmClient} that serves a fixed script of {@link ChatResponse}s —
 * the "model" behind forked branches. The agent still dispatches the
 * scripted tool calls for real; only the model's words are canned, which is
 * what makes a fork deterministic and repeatable.
 *
 * <p>Running out of scripted responses is a loud {@link ReplayException},
 * never a silent stall: a fork that needs more turns than you scripted is a
 * fork you must extend deliberately.
 */
public final class ScriptedLlm implements LlmClient {
    private final Deque<ChatResponse> script = new ArrayDeque<>();
    private final AtomicInteger served = new AtomicInteger();

    public ScriptedLlm(List<ChatResponse> script) {
        if (script != null) this.script.addAll(script);
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                             LlmOptions options) {
        ChatResponse next = script.poll();
        if (next == null) {
            throw new ReplayException(
                "ScriptedLlm exhausted after " + served.get()
                    + " served responses: the fork needs more model turns than were scripted. "
                    + "Pass additional ChatResponses to forkAt().");
        }
        served.incrementAndGet();
        return next;
    }

    @Override
    public String model() {
        return "scripted";
    }

    /** How many scripted responses have been served so far. */
    public int served() {
        return served.get();
    }
}

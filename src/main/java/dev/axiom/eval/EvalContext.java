package dev.axiom.eval;

import dev.axiom.llm.ChatResponse;

/** What a scorer gets to see about the run behind one case. */
public record EvalContext(String task, long latencyMs, ChatResponse.TokenUsage usage) {}

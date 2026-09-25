package dev.axiom.llm;

import java.util.Map;

/**
 * A tool call requested by the LLM.
 *
 * <p>{@code thoughtSignature} carries a provider-issued opaque signature that
 * some models require echoed back with the call when it is replayed as
 * conversation history. Gemini 3 ("thinking") models attach a thought
 * signature to function calls and reject follow-up requests whose replayed
 * calls lack it (HTTP 400 {@code INVALID_ARGUMENT}). The signature is
 * preserved verbatim on exactly the call it arrived on and is never
 * synthesized; calls from providers that do not issue one carry
 * {@code null} and serialize exactly as before.
 */
public record ToolCallRequest(String id, String name, Map<String, Object> arguments,
                              String thoughtSignature) {
    public ToolCallRequest(String id, String name, Map<String, Object> arguments) {
        this(id, name, arguments, null);
    }
}

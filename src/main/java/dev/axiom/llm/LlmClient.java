package dev.axiom.llm;

import dev.axiom.tools.ToolDefinition;

import java.util.List;

/**
 * Abstraction over any chat-completions LLM. Implementations translate
 * Axiom's model into the provider's wire format. The bundled
 * {@link OpenAiCompatibleClient} covers OpenAI, Azure OpenAI, Ollama,
 * vLLM, LM Studio, and any other OpenAI-compatible endpoint.
 */
public interface LlmClient {

    ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools, LlmOptions options);

    /** Model identifier, e.g. "gpt-4o". Used for tracing and logging. */
    String model();

    /**
     * @param temperature    sampling temperature
     * @param maxTokens      max completion tokens
     * @param jsonSchema     if non-null, the model must respond with JSON matching
     *                       this schema (OpenAI "structured outputs")
     */
    record LlmOptions(double temperature, int maxTokens, String jsonSchema) {
        public static LlmOptions defaults() {
            return new LlmOptions(0.7, 4096, null);
        }

        public LlmOptions(double temperature, int maxTokens) {
            this(temperature, maxTokens, null);
        }

        public LlmOptions withJsonSchema(String schema) {
            return new LlmOptions(temperature, maxTokens, schema);
        }
    }
}

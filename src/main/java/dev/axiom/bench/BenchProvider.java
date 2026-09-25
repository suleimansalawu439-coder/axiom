package dev.axiom.bench;

import java.util.List;

/**
 * Provider presets for live benchmark runs, biased toward free tiers so a
 * benchmark receipt can be produced for $0.
 *
 * <p>Every preset is an OpenAI-compatible endpoint, so it plugs straight into
 * {@code OpenAiCompatibleClient}. Keys come from environment variables —
 * never from chat, files, or code. When you need a key, create it yourself
 * in the provider's console and export it; see the {@code keySignup} hint.
 */
public final class BenchProvider {

    /** One runnable provider: endpoint, key source, sensible free default model. */
    public record Preset(String id, String baseUrl, String apiKeyEnv,
                         String defaultModel, long defaultPacingMs, String keySignup) {
        /** True when this preset needs an API key to run. */
        public boolean needsKey() { return apiKeyEnv != null && !apiKeyEnv.isBlank(); }
    }

    public static final List<Preset> PRESETS = List.of(
        new Preset("gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "GEMINI_API_KEY", "gemini-2.0-flash", 4_000,
            "https://aistudio.google.com/apikey — free tier, no card required"),
        new Preset("openrouter",
            "https://openrouter.ai/api/v1",
            "OPENROUTER_API_KEY", "meta-llama/llama-3.3-70b-instruct:free", 3_000,
            "https://openrouter.ai/keys — free models have ids ending in :free"),
        new Preset("groq",
            "https://api.groq.com/openai/v1",
            "GROQ_API_KEY", "llama-3.3-70b-versatile", 3_000,
            "https://console.groq.com/keys — free tier, no card required"),
        new Preset("ollama",
            "http://localhost:11434/v1",
            "", "llama3.1", 0,
            "local model server — no key; install from https://ollama.com then `ollama pull llama3.1`"),
        new Preset("openai",
            "https://api.openai.com/v1",
            "OPENAI_API_KEY", "gpt-4o-mini", 1_000,
            "https://platform.openai.com/api-keys — paid")
    );

    private BenchProvider() {}

    /** Look up a preset by id, or throw naming the known ids. */
    public static Preset of(String id) {
        return PRESETS.stream().filter(p -> p.id().equals(id)).findFirst()
            .orElseThrow(() -> new BenchException(
                "Unknown AXIOM_BENCH_PROVIDER '" + id + "'. Known: "
                    + PRESETS.stream().map(Preset::id).toList()));
    }
}

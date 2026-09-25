import dev.axiom.Axiom;
import dev.axiom.agent.AgentResult;
import dev.axiom.cache.CachingLlmClient;
import dev.axiom.cache.FileCache;
import dev.axiom.llm.OpenAiCompatibleClient;

import java.nio.file.Path;

/**
 * Deterministic, nearly-free agent runs: every LLM response is cached to
 * disk keyed by content hash. Re-run the same task and the provider is never
 * called again — ideal for eval suites, demos, and CI.
 *
 * Run: javac -cp <axiom-jar>:<lib/*> CachedAgent.java && java -cp .:<axiom-jar>:<lib/*> CachedAgent
 * Requires OPENAI_API_KEY in the environment (first run only).
 */
public class CachedAgent {
    public static void main(String[] args) {
        var client = new CachingLlmClient(
            new OpenAiCompatibleClient("gpt-4o-mini"),
            new FileCache(Path.of(".axiom-cache")));

        var agent = Axiom.agent()
            .withClient(client)
            .withSystemPrompt("Answer concisely.")
            .buildAgent();

        // First run hits the provider; every repeat run is served from disk.
        for (int i = 0; i < 2; i++) {
            long start = System.currentTimeMillis();
            AgentResult result = agent.run("Explain recursion in one sentence.");
        String answer = result.output();
            System.out.printf("[run %d, %dms] %s%n", i + 1,
                System.currentTimeMillis() - start, answer);
        }
    }
}

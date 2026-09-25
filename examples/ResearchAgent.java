import dev.axiom.Axiom;
import dev.axiom.agent.AgentResult;
import dev.axiom.budget.Budget;
import dev.axiom.guardrails.KeywordBlocklistGuardrail;
import dev.axiom.guardrails.PiiRedactionGuardrail;
import dev.axiom.llm.OpenAiCompatibleClient;
import dev.axiom.observe.MetricsReporter;
import dev.axiom.resilience.RetryPolicy;
import dev.axiom.resilience.RetryingLlmClient;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolParam;

import java.util.List;

/**
 * Production-grade agent wiring: retries for transient provider failures,
 * a hard token/cost budget, guardrails on inputs and outputs, and a metrics
 * reporter for live telemetry.
 *
 * Run: javac -cp <axiom-jar>:<lib/*> ResearchAgent.java && java -cp .:<axiom-jar>:<lib/*> ResearchAgent
 * Requires OPENAI_API_KEY in the environment.
 */
public class ResearchAgent {

    public static class MathTools {
        @Tool(description = "Multiply two integers")
        public int multiply(@ToolParam(description = "x") int x,
                            @ToolParam(description = "y") int y) {
            return x * y;
        }
    }

    public static void main(String[] args) {
        var metrics = new MetricsReporter();

        var client = new RetryingLlmClient(
            new OpenAiCompatibleClient("gpt-4o"),
            RetryPolicy.builder().maxAttempts(5).build());

        var agent = Axiom.agent()
            .withClient(client)
            .withTools(new MathTools())
            .withSystemPrompt("You are a careful math assistant. Show your work.")
            .withBudget(Budget.builder().maxTokens(20_000).maxCostUsd(0.50).build())
            .withGuardrails(
                new KeywordBlocklistGuardrail(List.of("malware", "exploit")),
                new PiiRedactionGuardrail())
            .onEvent(metrics.asListener())
            .buildAgent();

        AgentResult result = agent.run("What is 137 * 46, and what is that divided by 2?");
        String answer = result.output();
        System.out.println("Answer: " + answer);
        System.out.println(metrics.summary());
    }
}

package dev.axiom.budget;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentEvent;
import dev.axiom.agent.AgentResult;
import dev.axiom.agent.ReActAgent;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Token/cost budget tests: pricing math, breach behavior, and agent-loop
 * integration with a scripted fake LLM.
 */
class BudgetTest {

    static class FakeLlm implements LlmClient {
        private final Deque<ChatResponse> script = new ArrayDeque<>();
        private final String model;

        FakeLlm(String model) { this.model = model; }

        FakeLlm enqueue(ChatResponse r) { script.add(r); return this; }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools, LlmOptions options) {
            if (script.isEmpty()) throw new AssertionError("FakeLlm ran out of scripted responses");
            return script.poll();
        }

        @Override
        public String model() { return model; }
    }

    private static ChatResponse finalResponse(String text, long in, long out) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(in, out, in + out));
    }

    // ------------------------------------------------------------------
    // ModelPrices
    // ------------------------------------------------------------------

    @Test
    void priceLookupAndCostMath() {
        ModelPrices prices = ModelPrices.defaults();
        var gpt4o = prices.lookup("gpt-4o").orElseThrow();
        assertEquals(0.0125, gpt4o.costOf(1000, 1000), 1e-9);

        // Dated snapshots resolve by prefix.
        assertTrue(prices.lookup("gpt-4o-2024-08-06").isPresent());
        assertTrue(prices.lookup("no-such-model").isEmpty());

        // Overrides for private deployments / new models.
        var custom = prices.withPrice("my-model", new ModelPrices.Price(0.1, 0.2));
        assertEquals(0.1, custom.lookup("my-model").orElseThrow().inputPer1k(), 1e-9);
        // Original table untouched.
        assertTrue(prices.lookup("my-model").isEmpty());
    }

    @Test
    void negativePricesRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ModelPrices.Price(-1, 0));
    }

    // ------------------------------------------------------------------
    // Budget.charge
    // ------------------------------------------------------------------

    @Test
    void tokenBreachThrowsTypedException() {
        Budget budget = Budget.builder().maxTokens(100).build();
        budget.charge("gpt-4o", new ChatResponse.TokenUsage(60, 30, 90));
        BudgetExceededException ex = assertThrows(BudgetExceededException.class, () ->
            budget.charge("gpt-4o", new ChatResponse.TokenUsage(10, 10, 20)));
        assertTrue(ex.tokenLimitBreached());
        assertFalse(ex.costLimitBreached());
        assertEquals(110, ex.tokensUsed());
        assertEquals(100, ex.maxTokens());
        assertTrue(ex.getMessage().contains("token limit"));
    }

    @Test
    void costBreachThrowsTypedException() {
        // gpt-4o-mini: $0.00015/1k in, $0.0006/1k out.
        Budget budget = Budget.builder().maxCostUsd(0.005).build();
        BudgetExceededException ex = assertThrows(BudgetExceededException.class, () ->
            budget.charge("gpt-4o-mini", new ChatResponse.TokenUsage(10_000, 10_000, 20_000)));
        assertTrue(ex.costLimitBreached());
        assertFalse(ex.tokenLimitBreached());
        assertEquals(0.0075, ex.costUsd(), 1e-9);
    }

    @Test
    void unknownModelFallsBackToFallbackPrice() {
        Budget budget = Budget.builder()
            .maxCostUsd(0.000001)
            .fallbackPrice(new ModelPrices.Price(1.0, 1.0))
            .build();
        assertThrows(BudgetExceededException.class, () ->
            budget.charge("mystery-model", new ChatResponse.TokenUsage(10, 10, 20)));
    }

    @Test
    void snapshotTracksSpend() {
        Budget budget = Budget.builder().maxTokens(1000).maxCostUsd(1.0).build();
        budget.charge("gpt-4o", new ChatResponse.TokenUsage(100, 50, 150));
        Budget.Snapshot s = budget.snapshot();
        assertEquals(100, s.inputTokens());
        assertEquals(50, s.outputTokens());
        assertEquals(150, s.totalTokens());
        assertEquals(0.15, s.tokensUsedFraction(), 1e-9);
        assertTrue(s.costUsd() > 0);
    }

    // ------------------------------------------------------------------
    // Agent integration
    // ------------------------------------------------------------------

    @Test
    void agentAbortsWhenTokenBudgetBreached() {
        var llm = new FakeLlm("gpt-4o")
            .enqueue(finalResponse("thinking…", 90, 30))
            .enqueue(finalResponse("unreached", 10, 10));
        var events = new java.util.ArrayList<AgentEvent>();
        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withBudget(Budget.builder().maxTokens(100).build())
            .onEvent(events::add)
            .build());

        BudgetExceededException ex = assertThrows(BudgetExceededException.class,
            () -> agent.run("Do something expensive"));
        assertTrue(ex.tokenLimitBreached());
        // Spend is visible in events even though the run aborted.
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.BudgetUpdated));
        var update = events.stream()
            .filter(e -> e instanceof AgentEvent.BudgetUpdated)
            .map(e -> (AgentEvent.BudgetUpdated) e)
            .findFirst().orElseThrow();
        assertEquals(120, update.snapshot().totalTokens());
    }

    @Test
    void agentAbortsWhenCostBudgetBreached() {
        var llm = new FakeLlm("gpt-4o-mini")
            .enqueue(finalResponse("done", 10_000, 10_000));
        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withBudget(Budget.builder().maxCostUsd(0.005).build())
            .build());

        BudgetExceededException ex = assertThrows(BudgetExceededException.class,
            () -> agent.run("Do something"));
        assertTrue(ex.costLimitBreached());
    }

    @Test
    void runWithinBudgetCompletesNormally() {
        var llm = new FakeLlm("gpt-4o")
            .enqueue(finalResponse("All good.", 10, 20));
        var events = new java.util.ArrayList<AgentEvent>();
        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .withBudget(Budget.builder().maxTokens(10_000).maxCostUsd(10.0).build())
            .onEvent(events::add)
            .build());

        AgentResult result = agent.run("Simple task");
        assertTrue(result.completed());
        assertEquals("All good.", result.output());
        long budgetEvents = events.stream().filter(e -> e instanceof AgentEvent.BudgetUpdated).count();
        assertEquals(1, budgetEvents);
        var update = (AgentEvent.BudgetUpdated) events.stream()
            .filter(e -> e instanceof AgentEvent.BudgetUpdated).findFirst().orElseThrow();
        assertEquals(30, update.charged().totalTokens());
    }

    @Test
    void noBudgetMeansNoBudgetEvents() {
        var llm = new FakeLlm("gpt-4o").enqueue(finalResponse("Fine.", 10, 10));
        var events = new java.util.ArrayList<AgentEvent>();
        var agent = new ReActAgent(AgentConfig.builder()
            .withClient(llm)
            .onEvent(events::add)
            .build());

        agent.run("Simple task");
        assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.BudgetUpdated));
    }
}

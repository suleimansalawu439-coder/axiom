package dev.axiom.bench.gaia;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smart-model routing: heuristic triage, budget enforcement, fallback.
 */
class ModelRouterTest {

    private static final String LITE = "gemini-3.5-flash-lite";
    private static final String SMART = "gemini-3.8-flash";

    private static GaiaItem item(String question) {
        return new GaiaItem("task-1", question, 1, "x", null);
    }

    @Test
    void longQuestionIsHard() {
        assertTrue(ModelRouter.isHardTask(item("q ".repeat(200))));
    }

    @Test
    void keywordQuestionIsHard() {
        assertTrue(ModelRouter.isHardTask(
            item("How many studio albums were published between 2000 and 2009?")));
        assertTrue(ModelRouter.isHardTask(item("Compare the two figures and rank them.")));
    }

    @Test
    void shortPlainQuestionIsNotHard() {
        assertFalse(ModelRouter.isHardTask(item("What is the capital of France?")));
    }

    @Test
    void nullQuestionIsNotHard() {
        assertFalse(ModelRouter.isHardTask(new GaiaItem("t", null, 1, "x", null)));
    }

    @Test
    void hardTaskUsesSmartModelWhileBudgetLasts() {
        var router = new ModelRouter(LITE, SMART, 2);
        GaiaItem hard = item("How many albums were released? " + "x".repeat(400));

        assertEquals(SMART, router.route("gaia-1", hard));
        assertEquals(1, router.smartRemaining());
        assertEquals(SMART, router.route("gaia-2", hard));
        assertEquals(0, router.smartRemaining());
        // Budget exhausted → default model, even for hard tasks.
        assertEquals(LITE, router.route("gaia-3", hard));
        assertEquals(0, router.smartRemaining());
    }

    @Test
    void easyTaskNeverSpendsSmartBudget() {
        var router = new ModelRouter(LITE, SMART, 2);
        GaiaItem easy = item("What is the capital of France?");

        assertEquals(LITE, router.route("gaia-1", easy));
        assertEquals(2, router.smartRemaining());
    }

    @Test
    void nullSmartModelDisablesRouting() {
        var router = new ModelRouter(LITE, null, 20);
        assertEquals(LITE, router.route("gaia-1", item("How many? " + "x".repeat(400))));
        assertTrue(router.assignments().isEmpty() == false);
    }

    @Test
    void assignmentsRecordedInOrder() {
        var router = new ModelRouter(LITE, SMART, 1);
        router.route("gaia-1", item("What is the capital of France?"));
        router.route("gaia-2", item("How many? " + "x".repeat(400)));

        Map<String, String> a = router.assignments();
        assertEquals(LITE, a.get("gaia-1"));
        assertEquals(SMART, a.get("gaia-2"));
    }

    @Test
    void routingIsThreadSafe() throws Exception {
        var router = new ModelRouter(LITE, SMART, 50);
        GaiaItem hard = item("How many? " + "x".repeat(400));
        var threads = new java.util.ArrayList<Thread>();
        for (int i = 0; i < 10; i++) {
            int n = i;
            threads.add(Thread.ofPlatform().start(() -> {
                for (int j = 0; j < 10; j++) router.route("gaia-" + n + "-" + j, hard);
            }));
        }
        for (Thread t : threads) t.join();
        // Exactly 50 smart assignments despite the race; the rest default.
        long smart = router.assignments().values().stream().filter(SMART::equals).count();
        assertEquals(50, smart);
        assertEquals(100, router.assignments().size());
    }
}

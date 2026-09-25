package dev.axiom.bench.gaia;

import dev.axiom.bench.BenchReceipt;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unattempted classification: tasks the harness deliberately skips (gated
 * attachments) are recorded as UNATTEMPTED and never counted as failures.
 */
class GaiaUnattemptedTest {

    private static BenchReceipt.TaskResult result(String id, BenchReceipt.Status status) {
        return new BenchReceipt.TaskResult(id, "gaia", status == BenchReceipt.Status.PASSED,
            "out", 10, 5, 0.0, 100, "detail", "prompt", "expected",
            List.of(), null, null, status);
    }

    @Test
    void unattemptedResultIsMarkedNeverFailed() {
        GaiaItem item = new GaiaItem("abc-123", "Some question?", 1, "42", "data.xlsx");
        assertTrue(item.hasAttachment());
        BenchReceipt.TaskResult r = GaiaMain.unattemptedResult(item);
        assertEquals(BenchReceipt.Status.UNATTEMPTED, r.status());
        assertFalse(r.passed());
        assertEquals("gaia-" + item.taskId(), r.taskId());
        assertTrue(r.detail().contains("UNATTEMPTED"));
        assertTrue(r.detail().contains("data.xlsx"));
        assertEquals(0, r.totalTokens());
        assertEquals(0.0, r.costUsd());
    }

    @Test
    void promptInstructsShortFinalAnswer() {
        GaiaItem item = new GaiaItem("abc-123", "What is 2+2?", 1, "4", null);
        String prompt = GaiaMain.promptFor(item);
        assertTrue(prompt.contains("What is 2+2?"));
        assertTrue(prompt.contains("ONLY the final answer"));
    }

    @Test
    void receiptTotalsSeparateUnattempted() {
        BenchReceipt receipt = new BenchReceipt("axiom", "0.11.0", "m", "mode",
            Instant.now(), List.of(
                result("t1", BenchReceipt.Status.PASSED),
                result("t2", BenchReceipt.Status.FAILED),
                result("t3", BenchReceipt.Status.UNATTEMPTED)),
            "", 1000, 1);
        assertEquals(1, receipt.passed());
        assertEquals(1, receipt.failed());
        assertEquals(1, receipt.unattempted());
        assertEquals(2, receipt.attempted());
        // Pass rate is over attempted tasks only.
        assertEquals(0.5, receipt.passRate());
    }

    @Test
    void legacyConstructorDefaultsStatusFromPassed() {
        BenchReceipt.TaskResult pass = new BenchReceipt.TaskResult(
            "t1", "gaia", true, "o", 1, 1, 0.0, 1, "d", "p", "e",
            List.of(), null, null);
        BenchReceipt.TaskResult fail = new BenchReceipt.TaskResult(
            "t2", "gaia", false, "o", 1, 1, 0.0, 1, "d", "p", "e",
            List.of(), null, null);
        assertEquals(BenchReceipt.Status.PASSED, pass.status());
        assertEquals(BenchReceipt.Status.FAILED, fail.status());
        BenchReceipt receipt = new BenchReceipt("axiom", "0.11.0", "m", "mode",
            Instant.now(), List.of(pass, fail), "", 10, 1);
        assertEquals(0, receipt.unattempted());
        assertEquals(0.5, receipt.passRate());
    }

    @Test
    void statusAndTotalsAreSerialized() {
        BenchReceipt receipt = new BenchReceipt("axiom", "0.11.0", "m", "mode",
            Instant.now(), List.of(result("t3", BenchReceipt.Status.UNATTEMPTED)),
            "", 10, 1);
        Map<String, Object> json = receipt.toJsonMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) json.get("totals");
        assertEquals(1, totals.get("unattempted"));
        assertEquals(0, totals.get("attempted"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results =
            (List<Map<String, Object>>) json.get("results");
        assertEquals("UNATTEMPTED", results.get(0).get("status"));
    }
}

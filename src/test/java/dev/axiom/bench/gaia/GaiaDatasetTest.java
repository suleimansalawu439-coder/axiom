package dev.axiom.bench.gaia;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixture-only tests for the bundled GAIA Level 1 dataset: shape and counts
 * only. No question/answer text is asserted (the values come from the public
 * validation mirror documented in {@link GaiaDataset}).
 */
class GaiaDatasetTest {

    @Test
    void loadsFiftyThreeLevelOneTasks() {
        List<GaiaItem> items = GaiaDataset.level1();
        assertEquals(53, items.size());
        for (GaiaItem item : items) {
            assertEquals(1, item.level());
            assertFalse(item.taskId().isBlank());
            assertFalse(item.question().isBlank());
            assertFalse(item.trueAnswer().isBlank());
        }
    }

    @Test
    void taskIdsAreUnique() {
        List<GaiaItem> items = GaiaDataset.level1();
        assertEquals(items.size(), new HashSet<>(items.stream()
            .map(GaiaItem::taskId).toList()).size());
    }

    @Test
    void elevenTasksShipAttachments() {
        List<GaiaItem> items = GaiaDataset.level1();
        long withFiles = items.stream().filter(GaiaItem::hasAttachment).count();
        assertEquals(11, withFiles);
        // Every attachment entry names a file.
        for (GaiaItem item : items) {
            if (item.hasAttachment()) {
                assertFalse(item.fileName().isBlank());
            }
        }
    }

    @Test
    void benchTaskIdsArePrefixedAndUnique() {
        List<GaiaItem> items = GaiaDataset.level1();
        List<String> ids = items.stream().map(GaiaMain::taskIdFor).toList();
        assertEquals(ids.size(), new HashSet<>(ids).size());
        for (String id : ids) {
            assertTrue(id.startsWith("gaia-"));
        }
    }
}

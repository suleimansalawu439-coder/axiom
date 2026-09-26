package dev.axiom.bench.gaia;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Attachment mounting: task-id-prefixed files are matched to tasks and
 * copied into the task workspace with the prefix stripped.
 */
class AttachmentMountTest {

    private static final String TASK_A = "e1fc63a2-da7a-432f-be78-7c4a95598703";
    private static final String TASK_B = "aa000000-0000-0000-0000-000000000000";

    private static GaiaItem item(String taskId) {
        return new GaiaItem(taskId, "q?", 1, "x", "data.csv");
    }

    @Test
    void matchesByTaskIdPrefix(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(TASK_A + "-data.csv"), "a,b\n1,2\n");
        Files.writeString(dir.resolve(TASK_A + "-notes.txt"), "notes");
        Files.writeString(dir.resolve(TASK_B + "-img.png"), "png");
        Files.writeString(dir.resolve("unrelated.txt"), "nope");
        Files.createDirectory(dir.resolve("subdir"));

        Map<String, List<Path>> matched =
            AttachmentMount.match(dir, List.of(item(TASK_A), item(TASK_B)));

        assertEquals(2, matched.get("gaia-" + TASK_A).size());
        assertEquals(1, matched.get("gaia-" + TASK_B).size());
        assertFalse(matched.containsKey("gaia-unrelated"));
        // Directories are ignored.
        assertEquals(2, matched.size());
    }

    @Test
    void missingDirMatchesNothing() throws Exception {
        Map<String, List<Path>> matched = AttachmentMount.match(
            Path.of("/does/not/exist-axiom-test"), List.of(item(TASK_A)));
        assertTrue(matched.isEmpty());
    }

    @Test
    void mountStripsTaskIdPrefix(@TempDir Path workspace, @TempDir Path src) throws Exception {
        Path f1 = src.resolve(TASK_A + "-data.csv");
        Path f2 = src.resolve(TASK_A + "-notes.txt");
        Files.writeString(f1, "a,b\n");
        Files.writeString(f2, "notes");

        AttachmentMount.mount("gaia-" + TASK_A, List.of(f1, f2), workspace);

        assertTrue(Files.exists(workspace.resolve("data.csv")));
        assertTrue(Files.exists(workspace.resolve("notes.txt")));
        assertFalse(Files.exists(workspace.resolve(TASK_A + "-data.csv")));
        assertEquals("a,b\n", Files.readString(workspace.resolve("data.csv")));
    }

    @Test
    void mountKeepsExactTaskIdName(@TempDir Path workspace, @TempDir Path src) throws Exception {
        Path f = src.resolve(TASK_A);
        Files.writeString(f, "raw");

        AttachmentMount.mount("gaia-" + TASK_A, List.of(f), workspace);

        assertTrue(Files.exists(workspace.resolve(TASK_A)));
    }

    @Test
    void mountAcceptsBareUuidToo(@TempDir Path workspace, @TempDir Path src) throws Exception {
        Path f = src.resolve(TASK_A + "-data.csv");
        Files.writeString(f, "x");

        AttachmentMount.mount(TASK_A, List.of(f), workspace);

        assertTrue(Files.exists(workspace.resolve("data.csv")));
    }
}

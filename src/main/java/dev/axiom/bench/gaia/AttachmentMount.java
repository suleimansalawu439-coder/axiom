package dev.axiom.bench.gaia;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User-supplied attachment mounting for the GAIA runner. The official GAIA
 * attachments live in an access-gated repository; a user with legitimate
 * access can download the files themselves and point the runner at a
 * folder — files whose names start with a task's id are mounted into that
 * task's workspace, converting UNATTEMPTED tasks into attempted ones.
 *
 * <p>Naming convention: {@code <taskId>-<original-name>}, e.g.
 * {@code e1fc63a2-da7a-432f-be78-7c4a95598703-data.csv}. The
 * {@code <taskId>-} prefix is stripped on mount, so the workspace shows the
 * original file name the question refers to. Task ids are printed by the
 * dry run's UNATTEMPTED list.
 */
public final class AttachmentMount {

    private AttachmentMount() {}

    /**
     * Match files in {@code dir} to tasks by task-id filename prefix.
     * Returns task id ({@code "gaia-<uuid>"}) → matched files. A missing or
     * non-directory {@code dir} matches nothing.
     */
    public static Map<String, List<Path>> match(Path dir, List<GaiaItem> items)
            throws IOException {
        Map<String, List<Path>> out = new LinkedHashMap<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        Map<String, String> prefixToTaskId = new LinkedHashMap<>();
        for (GaiaItem item : items) {
            prefixToTaskId.put(item.taskId(), "gaia-" + item.taskId());
        }
        try (var stream = Files.list(dir)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            for (Path f : files) {
                String name = f.getFileName().toString();
                for (Map.Entry<String, String> e : prefixToTaskId.entrySet()) {
                    if (name.startsWith(e.getKey())) {
                        out.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(f);
                        break;
                    }
                }
            }
        }
        return out;
    }

    /**
     * Copy a task's matched files into its workspace, stripping the
     * {@code <taskId>-} name prefix so the workspace shows the original
     * file name. A file named exactly the task id keeps its name.
     *
     * <p>Security: the mounted name is normalized and verified to stay
     * within the workspace. Names containing path traversal ({@code ..}),
     * absolute paths, or separators that escape the workspace are rejected.
     */
    public static void mount(String taskId, List<Path> files, Path workspace)
            throws IOException {
        String uuid = taskId.startsWith("gaia-") ? taskId.substring(5) : taskId;
        String prefix = uuid + "-";
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        for (Path f : files) {
            String name = f.getFileName().toString();
            String mounted = name.startsWith(prefix)
                ? name.substring(prefix.length()) : name;
            if (mounted.isBlank()) mounted = name;
            // Prevent path traversal: normalize and verify containment.
            Path dest = normalizedWorkspace.resolve(mounted).normalize();
            if (!dest.startsWith(normalizedWorkspace)) {
                throw new IOException("Attachment name escapes workspace: " + name);
            }
            // Use only the file name, not any directory components.
            Path safeDest = normalizedWorkspace.resolve(dest.getFileName().toString());
            Files.copy(f, safeDest, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

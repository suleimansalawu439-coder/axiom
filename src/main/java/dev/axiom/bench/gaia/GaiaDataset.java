package dev.axiom.bench.gaia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the bundled GAIA 2023 validation Level 1 set (53 tasks, answers
 * included — this is the public validation split, not the private test set).
 *
 * <p>Provenance: converted 2026-09-25 from the public, ungated Hugging Face
 * mirror {@code jiyu9437/gaia_validation} (165 rows: 53 L1 / 86 L2 / 26 L3,
 * matching the official validation split sizes). The official
 * {@code gaia-benchmark/GAIA} repository is access-gated and returned
 * HTTP 401 from this environment, so row counts and schema — not values —
 * were cross-checked against it. See the {@code _provenance} field in the
 * bundled JSON.
 *
 * <p>Set {@code AXIOM_GAIA_JSON} to a file path to load a same-shaped JSON
 * from disk instead (useful after accepting the official dataset gate and
 * converting the gated parquet locally).
 */
public final class GaiaDataset {

    private static final String BUNDLED = "/dev/axiom/bench/gaia/gaia-level1.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GaiaDataset() {}

    /** The 53 GAIA 2023 validation Level 1 tasks. */
    public static List<GaiaItem> level1() {
        String override = System.getenv("AXIOM_GAIA_JSON");
        try {
            JsonNode root;
            if (override != null && !override.isBlank()) {
                Path p = Paths.get(override);
                if (!Files.isRegularFile(p)) {
                    throw new IllegalStateException(
                        "AXIOM_GAIA_JSON points to no such file: " + override);
                }
                root = MAPPER.readTree(p.toFile());
            } else {
                try (InputStream in = GaiaDataset.class.getResourceAsStream(BUNDLED)) {
                    if (in == null) {
                        throw new IllegalStateException(
                            "Bundled GAIA dataset missing from classpath: " + BUNDLED);
                    }
                    root = MAPPER.readTree(in);
                }
            }
            List<GaiaItem> items = new ArrayList<>();
            for (JsonNode n : root.path("tasks")) {
                String fileName = n.path("file_name").isMissingNode() || n.path("file_name").isNull()
                    ? null : n.path("file_name").asText(null);
                items.add(new GaiaItem(
                    n.path("task_id").asText(),
                    n.path("question").asText(),
                    n.path("level").asInt(1),
                    n.path("true_answer").asText(),
                    fileName));
            }
            if (items.isEmpty()) {
                throw new IllegalStateException("GAIA dataset loaded zero tasks");
            }
            return List.copyOf(items);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load GAIA dataset", e);
        }
    }
}

package dev.axiom.durable;

import dev.axiom.agent.AgentEvent;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only JSON-lines journal for one agent run. Lives at
 * {@code <root>/<runId>/journal.jsonl}; every line is a self-contained JSON
 * record. Writers flush after every record; {@link #checkpoint()} additionally
 * fsyncs and writes {@code checkpoint.json}.
 *
 * <p>Journaled records:
 * <ul>
 *   <li>{@code run_started} — task plus a config snapshot (model, prompt,
 *       iteration cap, tool holder classes) so a run can be rebuilt without
 *       the original config.</li>
 *   <li>{@code event} — every {@link AgentEvent} except the ephemeral
 *       {@code StreamToken}.</li>
 *   <li>{@code checkpoint} — explicit checkpoint marker.</li>
 * </ul>
 */
public final class RunJournal implements AutoCloseable {

    /** A parsed journal record. */
    public sealed interface Record permits RunStarted, Event, Checkpoint {}

    /** The run's task and the config snapshot needed to rebuild it. */
    public record RunStarted(Instant timestamp, String task,
                             Map<String, Object> config) implements Record {}

    /** One journaled agent event. */
    public record Event(long seq, Instant timestamp, AgentEvent event) implements Record {}

    /** An explicit checkpoint marker. */
    public record Checkpoint(Instant timestamp, long entries) implements Record {}

    private final Path dir;
    private final Path logFile;
    private final String runId;
    private final FileOutputStream fos;
    private final BufferedWriter writer;
    private long seq;

    private RunJournal(Path dir, String runId, boolean create) {
        try {
            this.dir = dir;
            this.runId = runId;
            this.logFile = dir.resolve("journal.jsonl");
            if (create) {
                Files.createDirectories(dir);
            }
            this.fos = new FileOutputStream(logFile.toFile(), true);
            this.writer = new BufferedWriter(
                new OutputStreamWriter(fos, StandardCharsets.UTF_8));
            this.seq = create ? 0 : countLines();
        } catch (Exception e) {
            throw new DurableException("Cannot open journal at " + dir, e);
        }
    }

    /** Create a journal for a new run (fresh random run id). */
    public static RunJournal create(Path root) {
        String runId = UUID.randomUUID().toString();
        return new RunJournal(root.resolve(runId), runId, true);
    }

    /** Open the journal of an existing run for resume. */
    public static RunJournal open(Path root, String runId) {
        Path dir = root.resolve(runId);
        if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve("journal.jsonl"))) {
            throw new DurableException("No journal for run '" + runId + "' under " + root);
        }
        return new RunJournal(dir, runId, false);
    }

    /** List run ids that have journals under the given root. */
    public static List<String> listRuns(Path root) {
        List<String> ids = new ArrayList<>();
        if (!Files.isDirectory(root)) return ids;
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                .filter(d -> Files.isRegularFile(d.resolve("journal.jsonl")))
                .map(d -> d.getFileName().toString())
                .sorted()
                .forEach(ids::add);
        } catch (Exception e) {
            throw new DurableException("Cannot list journals under " + root, e);
        }
        return ids;
    }

    public String runId() {
        return runId;
    }

    public Path dir() {
        return dir;
    }

    /** Append the run_started record. Must be the first record. */
    public synchronized void appendRunStarted(String task, Map<String, Object> config) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", "run_started");
        r.put("timestamp", Instant.now().toString());
        r.put("task", task);
        r.put("config", config == null ? Map.of() : config);
        writeLine(r);
    }

    /**
     * Append one event. {@code AgentEvent.StreamToken} is skipped — token
     * streams are ephemeral UI events; the complete {@code LlmResponse} is
     * what resume rebuilds from.
     */
    public synchronized void appendEvent(AgentEvent event) {
        if (event instanceof AgentEvent.StreamToken) return;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", "event");
        r.put("seq", ++seq);
        r.put("timestamp", event.timestamp().toString());
        r.put("event", JournalCodec.eventToMap(event));
        writeLine(r);
    }

    /**
     * Flush, fsync, and record a checkpoint marker. Returns the checkpoint
     * id — stable for the run, so it doubles as the resume handle.
     */
    public synchronized String checkpoint() {
        try {
            writer.flush();
            fos.getFD().sync();
        } catch (Exception e) {
            throw new DurableException("Failed to fsync journal " + runId, e);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", "checkpoint");
        r.put("timestamp", Instant.now().toString());
        r.put("entries", seq);
        writeLine(r);
        Map<String, Object> cp = new LinkedHashMap<>();
        cp.put("runId", runId);
        cp.put("timestamp", Instant.now().toString());
        cp.put("entries", seq);
        try {
            Files.writeString(dir.resolve("checkpoint.json"),
                JournalCodec.encodeLine(cp), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            throw new DurableException("Failed to write checkpoint.json for run " + runId, e);
        }
        return runId;
    }

    /** Read and parse every record in order. */
    public List<Record> readAll() {
        List<Record> out = new ArrayList<>();
        List<String> lines;
        try {
            synchronized (this) {
                writer.flush();
            }
            lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new DurableException("Failed to read journal " + runId, e);
        }
        for (String line : lines) {
            if (line.isBlank()) continue;
            Map<String, Object> m = JournalCodec.decodeLine(line);
            String kind = String.valueOf(m.get("kind"));
            Instant ts = parseInstant(m.get("timestamp"));
            switch (kind) {
                case "run_started" -> out.add(new RunStarted(ts,
                    String.valueOf(m.get("task")),
                    configMap(m.get("config"))));
                case "event" -> {
                    Object s = m.get("seq");
                    long sq = s instanceof Number n ? n.longValue() : 0L;
                    AgentEvent event = JournalCodec.eventFromMap(
                        eventMap(m.get("event")), ts);
                    out.add(new Event(sq, ts, event));
                }
                case "checkpoint" -> {
                    Object n = m.get("entries");
                    out.add(new Checkpoint(ts, n instanceof Number num ? num.longValue() : 0L));
                }
                default -> { /* ignore unknown record kinds */ }
            }
        }
        return out;
    }

    /** The config snapshot from the run_started record. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> configSnapshot() {
        for (Record r : readAll()) {
            if (r instanceof RunStarted rs) return rs.config();
        }
        throw new DurableException("Journal " + runId + " has no run_started record");
    }

    @Override
    public synchronized void close() {
        try {
            writer.close();
        } catch (Exception e) {
            throw new DurableException("Failed to close journal " + runId, e);
        }
    }

    private void writeLine(Map<String, Object> record) {
        try {
            writer.write(JournalCodec.encodeLine(record));
            writer.newLine();
            writer.flush();
        } catch (Exception e) {
            throw new DurableException("Failed to append to journal " + runId, e);
        }
    }

    private long countLines() {
        try {
            if (!Files.isRegularFile(logFile)) return 0;
            long n = 0;
            try (var lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
                for (var l : (Iterable<String>) lines::iterator) {
                    if (!l.isBlank()) n++;
                }
            }
            return n;
        } catch (Exception e) {
            throw new DurableException("Failed to read journal " + runId, e);
        }
    }

    private static Instant parseInstant(Object o) {
        try {
            return o == null ? Instant.now() : Instant.parse(String.valueOf(o));
        } catch (Exception e) {
            return Instant.now();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> eventMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("type", "Unknown");
    }
}

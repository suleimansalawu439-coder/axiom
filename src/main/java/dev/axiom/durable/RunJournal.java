package dev.axiom.durable;

import dev.axiom.agent.AgentEvent;
import dev.axiom.llm.ToolCallRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *   <li>{@code tool_call_started} — written <b>before</b> a tool body
 *       executes, carrying the call's idempotency key.</li>
 *   <li>{@code tool_call_completed} — written <b>after</b> a tool body
 *       returns, carrying the idempotency key and the serialized result.</li>
 *   <li>{@code checkpoint} — explicit checkpoint marker.</li>
 * </ul>
 *
 * <h2>Exactly-once guarantee</h2>
 * <p>Every tool call gets a stable idempotency key
 * ({@code runId + "#" + toolCallId}; see
 * {@link dev.axiom.agent.ReActAgent} for why the key is derived from the
 * call id rather than the arguments). The two {@code tool_call_*} records
 * form a side-effect ledger around the tool body:
 * <ul>
 *   <li><b>Exactly-once:</b> a call whose {@code tool_call_completed}
 *       record exists is replayed from the recorded result on resume — the
 *       tool body is never re-executed.</li>
 *   <li><b>Crash window (started, never completed):</b> the tool may or may
 *       not have executed. Resume re-executes the call <em>only</em> when the
 *       tool is declared {@code idempotent=true} on its
 *       {@link dev.axiom.tools.ToolDefinition} (default {@code false}, never
 *       inferred). Otherwise resume aborts with {@link DurableException}
 *       naming the ambiguous call — a double side effect is never applied
 *       silently.</li>
 *   <li><b>At-least-once:</b> a call with no {@code tool_call_started}
 *       record (the crash happened before the ledger entry, or the journal
 *       predates this ledger) is re-executed on resume, as before.</li>
 * </ul>
 * <p>Journals written before the {@code tool_call_*} records existed resume
 * exactly as they used to: {@code ToolCallFinished} events are treated as
 * completions, and calls without them are re-executed at-least-once.
 */
public final class RunJournal implements AutoCloseable {

    /** A parsed journal record. */
    public sealed interface Record permits RunStarted, Event, Checkpoint,
        ToolCallStarted, ToolCallCompleted {}

    /** The run's task and the config snapshot needed to rebuild it. */
    public record RunStarted(Instant timestamp, String task,
                             Map<String, Object> config) implements Record {}

    /** One journaled agent event. */
    public record Event(long seq, Instant timestamp, AgentEvent event) implements Record {}

    /** An explicit checkpoint marker. */
    public record Checkpoint(Instant timestamp, long entries) implements Record {}

    /**
     * A tool call's idempotency key was registered <b>before</b> the tool
     * body executed. On resume, a started key with no matching
     * {@link ToolCallCompleted} is the crash window: the tool may or may not
     * have run.
     */
    public record ToolCallStarted(Instant timestamp, String idempotencyKey,
                                  String callId, String toolName,
                                  Map<String, Object> arguments) implements Record {}

    /**
     * A tool call finished and its serialized result was recorded under its
     * idempotency key. On resume this result is replayed — the tool body is
     * never re-executed.
     */
    public record ToolCallCompleted(Instant timestamp, String idempotencyKey,
                                    String result) implements Record {}

    private final Path dir;
    private final Path logFile;
    private final String runId;
    private final FileOutputStream fos;
    private final BufferedWriter writer;
    private long seq;
    private static final Logger log = LoggerFactory.getLogger(RunJournal.class);

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
     * Register a tool call's idempotency key <b>before</b> the tool body
     * executes. Must be called before any side effect happens; on resume, a
     * started key without a matching completion marks the crash window.
     */
    public synchronized void appendToolCallStarted(String idempotencyKey, ToolCallRequest call) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", "tool_call_started");
        r.put("timestamp", Instant.now().toString());
        r.put("idempotencyKey", idempotencyKey);
        r.put("callId", call.id());
        r.put("toolName", call.name());
        r.put("arguments", call.arguments() == null ? Map.of() : call.arguments());
        writeLine(r);
    }

    /**
     * Record a tool call's serialized result under its idempotency key,
     * <b>after</b> the tool body returned. On resume this result is replayed
     * instead of re-executing the tool.
     */
    public synchronized void appendToolCallCompleted(String idempotencyKey, String result) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", "tool_call_completed");
        r.put("timestamp", Instant.now().toString());
        r.put("idempotencyKey", idempotencyKey);
        r.put("result", result);
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

    /**
     * Read and parse every record in order.
     *
     * <p>Two corruption policies, matching write-ahead-log practice:
     * <ul>
     *   <li><b>Torn tail:</b> when the <em>last</em> line fails to parse, the
     *       process died mid-write — that record was never acknowledged, so
     *       it is dropped (a warning is logged) and the run resumes as if
     *       the write never happened. Previously a torn tail poisoned the
     *       entire journal and made resume impossible.</li>
     *   <li><b>Corrupt middle:</b> any other unparseable line aborts loudly
     *       with the line number — the journal is never silently resumed
     *       from a corrupted state.</li>
     * </ul>
     */
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
        int last = lines.size() - 1;
        while (last >= 0 && lines.get(last).isBlank()) last--;
        for (int i = 0; i <= last; i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            Map<String, Object> m;
            try {
                m = JournalCodec.decodeLine(line);
            } catch (DurableException e) {
                if (i == last) {
                    log.warn("Journal {}: dropping torn final line {} (process died mid-write): {}",
                        runId, i + 1, e.getMessage());
                    continue;
                }
                throw new DurableException(
                    "Corrupt journal '" + runId + "' at line " + (i + 1)
                        + " of " + (last + 1) + ": " + e.getMessage(), e);
            }
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
                case "tool_call_started" -> out.add(new ToolCallStarted(ts,
                    str(m.get("idempotencyKey")), str(m.get("callId")),
                    str(m.get("toolName")), argsMap(m.get("arguments"))));
                case "tool_call_completed" -> out.add(new ToolCallCompleted(ts,
                    str(m.get("idempotencyKey")), strOrNull(m.get("result"))));
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

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String strOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argsMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
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

package dev.axiom.durable;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentResult;
import dev.axiom.agent.ReActAgent;
import dev.axiom.llm.OpenAiCompatibleClient;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * A durable agent run: every event is journaled to an append-only log as it
 * happens, so a crashed run can be resumed instead of restarted.
 *
 * <pre>{@code
 * var agent = Axiom.agent()
 *     .withModel("gpt-4o")
 *     .withTools(new ResearchTools())
 *     .withJournalRoot(Path.of("runs"))   // durability on
 *     .buildAgent();
 *
 * AgentRun run = agent.beginRun("Research solid-state batteries");
 * String checkpointId = run.checkpoint();   // fsync + snapshot; stable id
 * AgentResult result = run.result();
 *
 * // ...the process dies mid-run...
 *
 * // Later (same or different process): rebuild and continue. Tool calls that
 * // completed before the crash are replayed from the journal — never
 * // re-executed (exactly-once). A call that started but never completed is
 * // re-executed only if its tool is declared idempotent=true; otherwise
 * // resume refuses loudly instead of risking a double side effect. Calls
 * // that never started run again (at-least-once).
 * AgentRun resumed = AgentRun.resumeFrom(Path.of("runs"), checkpointId, config);
 * AgentResult result2 = resumed.result();
 * }</pre>
 *
 * <p>Two resume flavors: with an explicit {@link AgentConfig} (full control —
 * fake clients in tests, custom approval handlers), or config-less, which
 * rebuilds an OpenAI-compatible client from the journaled model id and
 * re-instantiates the journaled {@code @Tool} holder classes (they need
 * public no-arg constructors).
 */
public final class AgentRun implements AutoCloseable {

    /** Default journal root used by {@link #resumeFrom(String)}. */
    public static final Path DEFAULT_JOURNAL_ROOT = Paths.get("axiom-runs");

    private final RunJournal journal;
    private final AgentConfig config;
    /** Null when the run crashed before producing a result. */
    private final AgentResult result;

    private AgentRun(RunJournal journal, AgentConfig config, AgentResult result) {
        this.journal = journal;
        this.config = config;
        this.result = result;
    }

    /**
     * Start a durable run. Requires {@code withJournalRoot(...)} on the
     * config. Runs to completion and returns the handle — or throws (the
     * "crash"): the journal already holds the partial history, so the run can
     * be resumed with {@link #resumeFrom}.
     */
    public static AgentRun begin(AgentConfig config, String task) {
        if (config.journalRoot() == null) {
            throw new IllegalStateException(
                "Durable runs require AgentConfig.Builder.withJournalRoot(Path).");
        }
        ReActAgent agent = new ReActAgent(config);
        AgentResult result = agent.run(task);
        return new AgentRun(agent.journal(), config, result);
    }

    /**
     * Flush, fsync, and snapshot the journal. Returns the checkpoint id —
     * stable for the run, usable with {@link #resumeFrom} after a crash.
     */
    public String checkpoint() {
        return journal.checkpoint();
    }

    /** The stable id of this run; pass it to {@link #resumeFrom} after a crash. */
    public String checkpointId() {
        return journal.runId();
    }

    /** Where this run's journal lives. */
    public Path journalDir() {
        return journal.dir();
    }

    /** The result, or null when the run crashed before finishing. */
    public AgentResult result() {
        return result;
    }

    /** True when the run finished (as opposed to crashed). */
    public boolean completed() {
        return result != null;
    }

    /** Resume a crashed run using the default journal root and a rebuilt config. */
    public static AgentRun resumeFrom(String checkpointId) {
        return resumeFrom(DEFAULT_JOURNAL_ROOT, checkpointId, null);
    }

    /**
     * Resume a crashed run. Rebuilds the transcript from the journal, replays
     * completed tool calls from their recorded results (exactly-once),
     * re-executes crash-window calls only for tools declared
     * {@code idempotent=true} (aborting with {@link DurableException} for
     * non-idempotent tools instead of risking a double side effect), and
     * re-executes never-started calls (at-least-once) before continuing the
     * ReAct loop.
     *
     * @param journalRoot  root passed to {@code withJournalRoot(...)}
     * @param checkpointId id returned by {@link #checkpoint()}
     * @param config       explicit config, or null to rebuild from the journal
     *                     (OpenAI-compatible client + no-arg {@code @Tool} holders)
     */
    public static AgentRun resumeFrom(Path journalRoot, String checkpointId, AgentConfig config) {
        RunJournal journal = RunJournal.open(journalRoot, checkpointId);
        AgentConfig effective = config != null ? config : rebuildConfig(journalRoot, journal);
        AgentResult result = new ReActAgent(effective).resume(journal);
        return new AgentRun(journal, effective, result);
    }

    /**
     * Resume a crashed <em>typed</em> run: replays/continues the transcript,
     * then runs the JSON-schema formatting step into {@code outputType}.
     */
    public static <T> T resumeFor(Path journalRoot, String checkpointId,
                                 AgentConfig config, Class<T> outputType) {
        RunJournal journal = RunJournal.open(journalRoot, checkpointId);
        AgentConfig effective = config != null ? config : rebuildConfig(journalRoot, journal);
        ReActAgent agent = new ReActAgent(effective);
        ReActAgent.ResumedRun rr = agent.resumeWithTranscript(journal);
        return agent.formatAs(rr.messages(), rr.result().iterations(), outputType);
    }

    /** Run ids with journals under the root — the recovery inventory after a crash. */
    public static List<String> listRuns(Path journalRoot) {
        return RunJournal.listRuns(journalRoot);
    }

    @Override
    public void close() {
        journal.close();
    }

    /**
     * Rebuild a config from the journal's config snapshot: an
     * OpenAI-compatible client for the recorded model and fresh instances of
     * the recorded {@code @Tool} holder classes.
     */
    @SuppressWarnings("unchecked")
    private static AgentConfig rebuildConfig(Path journalRoot, RunJournal journal) {
        Map<String, Object> snap = journal.configSnapshot();
        String model = snap.getOrDefault("model", "gpt-4o").toString();
        AgentConfig.Builder b = AgentConfig.builder()
            .withClient(new OpenAiCompatibleClient(model))
            .withJournalRoot(journalRoot);
        Object sp = snap.get("systemPrompt");
        if (sp != null) b.withSystemPrompt(sp.toString());
        Object mi = snap.get("maxIterations");
        if (mi instanceof Number n) b.withMaxIterations(n.intValue());
        Object t = snap.get("temperature");
        if (t instanceof Number n) b.withTemperature(n.doubleValue());
        Object holders = snap.get("toolHolders");
        if (holders instanceof List<?> list) {
            for (Object h : list) {
                String className = String.valueOf(h);
                try {
                    Object holder = Class.forName(className).getDeclaredConstructor().newInstance();
                    b.withTools(holder);
                } catch (Exception e) {
                    throw new DurableException(
                        "Cannot rebuild @Tool holder '" + className
                            + "': config-less resume needs a public no-arg constructor. "
                            + "Pass an explicit AgentConfig to resumeFrom instead.", e);
                }
            }
        }
        return b.build();
    }
}

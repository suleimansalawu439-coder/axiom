package dev.axiom.replay;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentResult;
import dev.axiom.capabilities.Capability;
import dev.axiom.durable.DurableException;
import dev.axiom.durable.RunJournal;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * A time-travel debugging session over one recorded agent run.
 *
 * <p>Open it on a run's journal directory ({@code <root>/<runId>}, the
 * directory holding {@code journal.jsonl}); step through the recorded
 * trajectory turn by turn with {@link #step()}; branch into what-if
 * explorations with {@link #forkAt(int, ChatResponse, ChatResponse...)}.
 *
 * <p>Stepping is pure replay: recorded model responses are served back from
 * the journal, recorded tool results are shown, and nothing — no LLM call,
 * no tool execution — ever happens. The original journal is opened
 * read-only in spirit and checksummed at open: {@link #close()} verifies it
 * is byte-identical and fails loudly otherwise, so a debugging session can
 * never corrupt the evidence it inspects.
 *
 * <p>Honest boundary, stated plainly: replay is deterministic <em>given the
 * recorded model responses</em> — it does not make the LLM itself
 * deterministic. A fork explores one alternative response <em>you</em>
 * supply; it is a debugging/what-if tool, not a proof of what the model
 * would have done.
 */
public final class ReplaySession implements AutoCloseable {

    private final Path journalDir;
    private final RunJournal journal;
    private final RecordedRun recorded;
    private final String journalHashAtOpen;
    private final AgentConfig forkConfig;
    private Path forkJournalRoot;
    private int cursor;

    private ReplaySession(Path journalDir, AgentConfig forkConfig) {
        this.journalDir = journalDir.toAbsolutePath().normalize();
        Path root = this.journalDir.getParent();
        String runId = this.journalDir.getFileName().toString();
        if (root == null || !Files.isRegularFile(this.journalDir.resolve("journal.jsonl"))) {
            throw new ReplayException(
                "Not a run journal directory (expected journal.jsonl inside): " + journalDir);
        }
        try {
            this.journal = RunJournal.open(root, runId);
        } catch (DurableException e) {
            throw new ReplayException("Cannot open journal at " + journalDir + ": " + e.getMessage(), e);
        }
        this.journalHashAtOpen = sha256(this.journalDir.resolve("journal.jsonl"));
        this.recorded = RecordedRun.parse(this.journal,
            forkConfig == null ? null : forkConfig.tools());
        this.forkConfig = forkConfig;
    }

    /**
     * Open a read-only stepping session over a run journal directory.
     * Forking is disabled — use {@link #open(Path, AgentConfig)} when you
     * plan to branch (forks need the original tools and guardrails for real
     * tool dispatch).
     */
    public static ReplaySession open(Path journalDir) {
        return new ReplaySession(journalDir, null);
    }

    /**
     * Open a session that can also fork. {@code forkConfig} supplies the
     * tools, system prompt, guardrails, approval handler, and iteration cap
     * for forked branches; its LLM client is ignored (forks are scripted).
     * It is also used to track capability tokens while stepping.
     */
    public static ReplaySession open(Path journalDir, AgentConfig forkConfig) {
        if (forkConfig == null) throw new ReplayException("forkConfig is required");
        return new ReplaySession(journalDir, forkConfig);
    }

    /** Directory holding the original run's journal. */
    public Path journalDir() {
        return journalDir;
    }

    /** The full recorded trajectory. */
    public RecordedRun recordedRun() {
        return recorded;
    }

    /** Override where fork journals are written (default: {@code <root>/forks}). */
    public ReplaySession withForkJournalRoot(Path root) {
        this.forkJournalRoot = root;
        return this;
    }

    /** True while un-consumed recorded turns remain. */
    public boolean hasNext() {
        return cursor < recorded.turnCount();
    }

    /**
     * Consume the next recorded turn and return the debugger state.
     * Throws {@link ReplayException} when the trajectory is exhausted.
     */
    public ReplayState step() {
        if (!hasNext()) {
            throw new ReplayException("Replay exhausted: all " + recorded.turnCount()
                + " recorded turns have been consumed");
        }
        cursor++;
        return currentState();
    }

    /** The debugger state after the turns consumed so far (none consumed yet is valid). */
    public ReplayState currentState() {
        List<RecordedTurn> turns = recorded.turns();
        List<RecordedToolCall> calls = new ArrayList<>();
        ChatResponse.TokenUsage usage = ChatResponse.TokenUsage.empty();
        String lastText = null;
        Set<Capability> tokens = Set.of();
        for (int i = 0; i < cursor; i++) {
            RecordedTurn t = turns.get(i);
            calls.addAll(t.toolCalls());
            usage = usage.add(t.usage());
            lastText = t.response().content();
            tokens = t.capabilityTokensAfter();
        }
        boolean done = cursor >= recorded.turnCount();
        return new ReplayState(cursor, recorded.turnCount(), lastText, calls, usage,
            tokens, done, done ? recorded.finalResult() : null,
            recorded.guardrailBlocks());
    }

    /**
     * Branch the run at a decision point: turn {@code turnIndex} (1-based,
     * in recorded order) is replaced by {@code alternativeResponse}, and the
     * branch continues from there with real tool dispatch. Any further model
     * turns the branch needs are served from {@code followUps} in order —
     * running out is a loud {@link ReplayException}, never a silent stall.
     *
     * <p>The returned branch is lazy: nothing executes until
     * {@link ForkedBranch#runToEnd()}. The forked branch records into its own
     * journal under the fork journal root; the original journal is never
     * written to.
     *
     * <p>Requires {@link #open(Path, AgentConfig)} — without the original
     * tools and guardrails there is nothing to dispatch against.
     */
    public ForkedBranch forkAt(int turnIndex, ChatResponse alternativeResponse,
                               ChatResponse... followUps) {
        if (forkConfig == null) {
            throw new ReplayException(
                "Forking needs the original AgentConfig (tools, guardrails, system prompt): "
                    + "open the session with open(journalDir, config).");
        }
        if (alternativeResponse == null) {
            throw new ReplayException("alternativeResponse is required");
        }
        if (turnIndex < 1 || turnIndex > recorded.turnCount()) {
            throw new ReplayException("turnIndex " + turnIndex + " out of range: the recorded run has "
                + recorded.turnCount() + " turns (1-based)");
        }
        List<ChatMessage> prefix = new ArrayList<>();
        prefix.add(ChatMessage.system(forkConfig.systemPrompt()));
        prefix.add(ChatMessage.user(recorded.task()));
        ChatResponse.TokenUsage usageSoFar = ChatResponse.TokenUsage.empty();
        int toolCallsMade = 0;
        List<String> completedToolNames = new ArrayList<>();
        List<RecordedTurn> turns = recorded.turns();
        for (int i = 0; i < turnIndex - 1; i++) {
            RecordedTurn t = turns.get(i);
            prefix.add(ChatMessage.assistantWithToolCalls(
                t.response().content(), t.response().toolCalls()));
            for (RecordedToolCall rtc : t.toolCalls()) {
                prefix.add(ChatMessage.toolResult(rtc.call().id(), rtc.call().name(),
                    rtc.result() == null ? "" : rtc.result()));
                toolCallsMade++;
                if (rtc.completed()) completedToolNames.add(rtc.call().name());
            }
            usageSoFar = usageSoFar.add(t.usage());
        }
        return new ForkedBranch(journalDir, forkJournalRoot, forkConfig, recorded.task(),
            prefix, new ArrayList<>(turns.subList(0, turnIndex - 1)),
            turnIndex, usageSoFar, toolCallsMade, completedToolNames,
            alternativeResponse, List.of(followUps));
    }

    /**
     * Verify the original journal is byte-identical to when the session
     * opened, then release it. A mismatch means something wrote to the
     * evidence mid-session — that fails loudly here instead of silently.
     */
    @Override
    public void close() {
        try {
            journal.close();
        } finally {
            verifyUnmodified();
        }
    }

    /** Check the original journal is byte-identical to session-open time. */
    public void verifyUnmodified() {
        String now = sha256(journalDir.resolve("journal.jsonl"));
        if (!now.equals(journalHashAtOpen)) {
            throw new ReplayException(
                "Original journal '" + journalDir + "' was modified during the replay session "
                    + "(hash " + journalHashAtOpen + " -> " + now + "). "
                    + "Replay sessions must never write to the journal they inspect.");
        }
    }

    private static String sha256(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new ReplayException("Cannot hash journal file " + file + ": " + e.getMessage(), e);
        }
    }

}

package dev.axiom.replay;

import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.ToolCallRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compares two run trajectories — typically the original recorded run and a
 * forked branch — and reports where they diverge. This is the "what if the
 * model had chosen differently at turn 3?" answer: the first turn whose
 * model text or tool calls differ, plus a summary of how the branches'
 * behavior, token spend, outcomes, and capability tokens compare.
 *
 * <p>Comparison is structural, not semantic: tool arguments are compared by
 * canonical rendering (key order normalized), model text by exact string
 * equality. Two branches that differ only in prose that led to identical
 * tool calls still count as diverged at that turn — the model <em>did</em>
 * choose differently, even if the effects coincided.
 */
public final class BranchDiff {

    private final RecordedRun base;
    private final RecordedRun fork;
    private final int divergenceTurn;

    private BranchDiff(RecordedRun base, RecordedRun fork) {
        this.base = base;
        this.fork = fork;
        this.divergenceTurn = computeDivergence(base, fork);
    }

    /** Diff two trajectories: usually {@code session.recordedRun()} vs a branch result. */
    public static BranchDiff between(RecordedRun base, RecordedRun fork) {
        if (base == null || fork == null) {
            throw new ReplayException("BranchDiff needs two recorded runs");
        }
        return new BranchDiff(base, fork);
    }

    public RecordedRun base() {
        return base;
    }

    public RecordedRun fork() {
        return fork;
    }

    /** True when both trajectories are structurally identical. */
    public boolean identical() {
        return divergenceTurn == -1;
    }

    /**
     * The 1-based turn where the trajectories first diverge, or -1 when
     * identical. A fork that runs longer (or shorter) than the base diverges
     * at the first turn the shorter side lacks.
     */
    public int divergenceTurn() {
        return divergenceTurn;
    }

    /** Human-readable summary of the divergence and its consequences. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("base: ").append(base.turnCount()).append(" turns, ")
            .append(base.toolCallsMade()).append(" tool calls, ")
            .append(base.totalUsage().totalTokens()).append(" tokens; ");
        sb.append("fork: ").append(fork.turnCount()).append(" turns, ")
            .append(fork.toolCallsMade()).append(" tool calls, ")
            .append(fork.totalUsage().totalTokens()).append(" tokens\n");
        if (identical()) {
            sb.append("trajectories identical.\n");
        } else {
            sb.append("divergence at turn ").append(divergenceTurn)
                .append(" (of ").append(base.turnCount())
                .append(" base / ").append(fork.turnCount()).append(" fork turns).\n");
            RecordedTurn bt = turnAt(base, divergenceTurn);
            RecordedTurn ft = turnAt(fork, divergenceTurn);
            sb.append("turn ").append(divergenceTurn).append(" model text:\n")
                .append("  base: ").append(quote(bt == null ? null : bt.response().content())).append('\n')
                .append("  fork: ").append(quote(ft == null ? null : ft.response().content())).append('\n');
            sb.append("tool calls:\n")
                .append("  base: ").append(seqOf(bt)).append('\n')
                .append("  fork: ").append(seqOf(ft)).append('\n');
        }
        long tokenDelta = fork.totalUsage().totalTokens() - base.totalUsage().totalTokens();
        sb.append("tokens: base=").append(base.totalUsage().totalTokens())
            .append(" fork=").append(fork.totalUsage().totalTokens())
            .append(" (delta ").append(tokenDelta >= 0 ? "+" : "").append(tokenDelta).append(")\n");
        sb.append("outcome: base=").append(outcomeOf(base))
            .append(" vs fork=").append(outcomeOf(fork)).append('\n');
        sb.append("capability tokens: base=").append(base.finalCapabilityTokens())
            .append(" fork=").append(fork.finalCapabilityTokens());
        return sb.toString();
    }

    private static int computeDivergence(RecordedRun base, RecordedRun fork) {
        int common = Math.min(base.turnCount(), fork.turnCount());
        for (int i = 0; i < common; i++) {
            if (!turnsEqual(base.turns().get(i), fork.turns().get(i))) {
                return i + 1;
            }
        }
        return base.turnCount() == fork.turnCount() ? -1 : common + 1;
    }

    private static boolean turnsEqual(RecordedTurn a, RecordedTurn b) {
        if (!textOf(a.response()).equals(textOf(b.response()))) return false;
        List<ToolCallRequest> ac = a.response().toolCalls();
        List<ToolCallRequest> bc = b.response().toolCalls();
        if (ac.size() != bc.size()) return false;
        for (int i = 0; i < ac.size(); i++) {
            ToolCallRequest x = ac.get(i), y = bc.get(i);
            if (!x.name().equals(y.name())) return false;
            if (!canonical(x.arguments()).equals(canonical(y.arguments()))) return false;
        }
        return true;
    }

    private static String textOf(ChatResponse r) {
        return r.content() == null ? "" : r.content();
    }

    /** Canonical rendering of tool arguments: maps rendered with sorted keys, recursively. */
    static String canonical(Map<String, Object> args) {
        if (args == null) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : new TreeMap<>(args).entrySet()) {
            if (!first) sb.append(",");
            sb.append(e.getKey()).append('=').append(canonicalValue(e.getValue()));
            first = false;
        }
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static String canonicalValue(Object v) {
        if (v instanceof Map<?, ?> m) return canonical((Map<String, Object>) m);
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : l) {
                if (!first) sb.append(",");
                sb.append(canonicalValue(o));
                first = false;
            }
            return sb.append(']').toString();
        }
        return String.valueOf(v);
    }

    private static RecordedTurn turnAt(RecordedRun run, int oneBased) {
        return oneBased >= 1 && oneBased <= run.turnCount()
            ? run.turns().get(oneBased - 1) : null;
    }

    private static String seqOf(RecordedTurn t) {
        if (t == null) return "(no turn)";
        if (t.toolCalls().isEmpty()) return "(no tool calls)";
        List<String> parts = new ArrayList<>();
        for (RecordedToolCall c : t.toolCalls()) parts.add(c.describe());
        return String.join(", ", parts);
    }

    private static String quote(String s) {
        if (s == null) return "(no turn)";
        String oneLine = s.replace('\n', ' ');
        return "\"" + (oneLine.length() > 120 ? oneLine.substring(0, 120) + "…" : oneLine) + "\"";
    }

    private static String outcomeOf(RecordedRun run) {
        if (run.finalResult() == null) return "(unfinished)";
        String out = run.finalResult().output();
        String oneLine = out == null ? "" : out.replace('\n', ' ');
        return "\"" + (oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine) + "\""
            + (run.finalResult().completed() ? " (completed)" : " (incomplete)");
    }
}

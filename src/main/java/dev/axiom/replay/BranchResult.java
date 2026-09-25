package dev.axiom.replay;

import dev.axiom.agent.AgentResult;

import java.nio.file.Path;

/**
 * The completed outcome of {@link ForkedBranch#runToEnd()}: the branch's
 * final result, its own recorded trajectory (parsed from the branch's
 * journal — the input to {@link BranchDiff}), and where the branch journal
 * lives.
 */
public record BranchResult(AgentResult result, RecordedRun recordedRun, Path journalDir) {}

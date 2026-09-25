package dev.axiom.replay;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentResult;
import dev.axiom.agent.ReActAgent;
import dev.axiom.durable.RunJournal;
import dev.axiom.guardrails.CapabilityGuardrail;
import dev.axiom.guardrails.Guardrail;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A what-if branch of a recorded run, created by
 * {@link ReplaySession#forkAt(int, ChatResponse, ChatResponse...)}.
 *
 * <p>The branch replays the recorded conversation up to (but excluding) the
 * fork turn, substitutes the scripted alternative model response for that
 * turn, and — once {@link #runToEnd()} is called — continues with real tool
 * dispatch against the original tools and guardrails. Everything the branch
 * does is recorded in its own journal under the fork journal root; the
 * original journal is never written to.
 *
 * <p>Two fidelity notes, stated plainly:
 * <ul>
 *   <li>Capability tokens are inherited faithfully: a fresh
 *       {@link CapabilityGuardrail} is built for the branch and every tool
 *       that completed before the fork turn re-establishes its tokens, so a
 *       branch at turn N sees exactly the policy state the original run had
 *       at turn N.</li>
 *   <li>Forks do not share the original run's budget or memory: hypothetical
 *       spend is not charged to a real budget, and a hypothetical branch
 *       must not pollute the user's memory store.</li>
 * </ul>
 */
public final class ForkedBranch {

    private final Path originalJournalDir;
    private final Path forkJournalRoot;
    private final AgentConfig baseConfig;
    private final String task;
    private final List<ChatMessage> prefix;
    /** The recorded turns before the fork point — the branch's history. */
    private final List<RecordedTurn> prefixTurns;
    private final int forkTurn;
    private final ChatResponse.TokenUsage usageSoFar;
    private final int toolCallsMadeSoFar;
    private final List<String> completedToolNames;
    private final ChatResponse alternativeResponse;
    private final List<ChatResponse> followUps;
    private boolean done;

    ForkedBranch(Path originalJournalDir, Path forkJournalRoot, AgentConfig baseConfig,
                 String task, List<ChatMessage> prefix, List<RecordedTurn> prefixTurns,
                 int forkTurn,
                 ChatResponse.TokenUsage usageSoFar, int toolCallsMadeSoFar,
                 List<String> completedToolNames, ChatResponse alternativeResponse,
                 List<ChatResponse> followUps) {
        this.originalJournalDir = originalJournalDir;
        this.forkJournalRoot = forkJournalRoot;
        this.baseConfig = baseConfig;
        this.task = task;
        this.prefix = List.copyOf(prefix);
        this.prefixTurns = List.copyOf(prefixTurns);
        this.forkTurn = forkTurn;
        this.usageSoFar = usageSoFar;
        this.toolCallsMadeSoFar = toolCallsMadeSoFar;
        this.completedToolNames = List.copyOf(completedToolNames);
        this.alternativeResponse = alternativeResponse;
        this.followUps = List.copyOf(followUps);
    }

    /** The 1-based recorded turn this branch replaces. */
    public int forkTurn() {
        return forkTurn;
    }

    /** The scripted model response substituted at the fork turn. */
    public ChatResponse alternativeResponse() {
        return alternativeResponse;
    }

    /** True after {@link #runToEnd()} has executed the branch. */
    public boolean isDone() {
        return done;
    }

    /**
     * Execute the branch to completion: the alternative response is played
     * as the fork turn with real tool dispatch, then the loop continues with
     * the scripted follow-up responses. Returns the branch result — final
     * outcome plus the combined recorded trajectory (replayed prefix turns
     * followed by the branch's own turns) for {@link BranchDiff}.
     *
     * @throws ReplayException if the branch is already done, or the script
     *                         runs out of model responses mid-branch
     */
    public BranchResult runToEnd() {
        if (done) {
            throw new ReplayException("This branch already ran to end");
        }
        Path root = forkJournalRoot != null ? forkJournalRoot
            : originalJournalDir.getParent().resolve("forks");
        try {
            Files.createDirectories(root);
        } catch (Exception e) {
            throw new ReplayException(
                "Cannot create fork journal root " + root + ": " + e.getMessage(), e);
        }

        AgentConfig.Builder builder = AgentConfig.builder()
            .withClient(new ScriptedLlm(followUps))
            .withRegistry(baseConfig.tools())
            .withSystemPrompt(baseConfig.systemPrompt())
            .withMaxIterations(baseConfig.maxIterations())
            .withTemperature(baseConfig.temperature())
            .withApprovalHandler(baseConfig.approvalHandler())
            .withJournalRoot(root);
        List<Guardrail> fresh = freshGuardrails();
        if (!fresh.isEmpty()) builder.withGuardrails(fresh.toArray(new Guardrail[0]));
        for (var listener : baseConfig.eventListeners()) builder.onEvent(listener);
        AgentConfig forkConfig = builder.build();

        Map<String, Object> lineage = new LinkedHashMap<>();
        lineage.put("replayForkOf", originalJournalDir.getFileName().toString());
        lineage.put("forkTurn", forkTurn);
        lineage.put("scriptedModel", true);

        ReActAgent agent = new ReActAgent(forkConfig);
        AgentResult result = agent.runFromState(task, prefix, forkTurn - 1,
            usageSoFar, toolCallsMadeSoFar, alternativeResponse, lineage);
        RunJournal forkJournal = agent.journal();
        RecordedRun branchOnly = RecordedRun.parse(forkJournal, baseConfig.tools());
        Path dir = forkJournal.dir();
        forkJournal.close();
        // The branch's trajectory is its history plus what it did: prefix
        // turns (replayed from the original) followed by the branch's own
        // turns, with capability tokens re-tracked across the boundary.
        RecordedRun combined =
            RecordedRun.prepend(prefixTurns, branchOnly, baseConfig.tools());
        done = true;
        return new BranchResult(result, combined, dir);
    }

    /**
     * Fresh guardrail instances for the branch. The capability guardrail is
     * always rebuilt (it is stateful) and re-seeded by replaying the prefix's
     * completed tool calls, so the branch inherits the exact token set the
     * original run held at the fork point. Other guardrails are shared by
     * reference — they are assumed stateless (true of all bundled
     * guardrails); a custom stateful guardrail would need its own copy
     * semantics here.
     */
    private List<Guardrail> freshGuardrails() {
        List<Guardrail> out = new ArrayList<>();
        for (Guardrail g : baseConfig.guardrails()) {
            if (g instanceof CapabilityGuardrail) {
                CapabilityGuardrail fresh =
                    CapabilityGuardrail.forRegistry(baseConfig.tools());
                for (String toolName : completedToolNames) {
                    fresh.onToolCompleted(toolName);
                }
                out.add(fresh);
            } else {
                out.add(g);
            }
        }
        return out;
    }
}

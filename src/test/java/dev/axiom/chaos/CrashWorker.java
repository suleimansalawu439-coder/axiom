package dev.axiom.chaos;

import dev.axiom.agent.AgentConfig;
import dev.axiom.durable.AgentRun;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.ToolDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Crash-injector for {@link KillResumeChaosTest}: runs a real agent in a
 * <b>separate OS process</b> so the test can {@code destroyForcibly()} it —
 * a true kill -9 equivalent — at the worst possible moment.
 *
 * <p>Args: {@code <journalRoot> <idempotent:true|false> <mode> <markerFile>}
 * where mode is {@code in-tool} (tool body signals the marker then sleeps,
 * holding the crash window open) or {@code in-llm} (the fake LLM signals the
 * marker on its second call then sleeps, so the kill lands mid-LLM-call).
 *
 * <p>Lives in test sources so it ships zero production surface; it is
 * launched by the test via {@code java -cp target/classes:target/test-classes:lib/*}.
 */
public class CrashWorker {

    static final class ScriptLlm implements LlmClient {
        private final Deque<ChatResponse> script = new ArrayDeque<>();
        private final Path markerFile;
        private final boolean markerOnSecondCall;
        private int calls;

        ScriptLlm(Path markerFile, boolean markerOnSecondCall, ChatResponse... responses) {
            this.markerFile = markerFile;
            this.markerOnSecondCall = markerOnSecondCall;
            script.addAll(List.of(responses));
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 LlmOptions options) {
            calls++;
            try {
                if (markerOnSecondCall && calls == 2) {
                    Files.writeString(markerFile, "in-llm-call");
                    Thread.sleep(120_000); // hold still until the parent kills us
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            ChatResponse r = script.poll();
            if (r == null) throw new AssertionError("CrashWorker script exhausted");
            return r;
        }

        @Override
        public String model() {
            return "crash-worker";
        }
    }

    private static ChatResponse toolCall(String id, String name, Map<String, Object> args) {
        return new ChatResponse("using tool", List.of(new ToolCallRequest(id, name, args)),
            new ChatResponse.TokenUsage(10, 5, 15));
    }

    private static ChatResponse finalAnswer(String text) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(10, 20, 30));
    }

    public static void main(String[] args) throws Exception {
        Path journalRoot = Path.of(args[0]);
        boolean idempotent = Boolean.parseBoolean(args[1]);
        String mode = args[2];
        Path marker = Path.of(args[3]);
        boolean inTool = "in-tool".equals(mode);

        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(),
            "additionalProperties", false);
        ToolDefinition tool = ToolDefinition.of(
            "crashtool", "tool that gets killed mid-flight", schema, false, 120,
            a -> {
                if (inTool) {
                    Files.writeString(marker, "in-tool-body");
                    Thread.sleep(120_000); // hold the crash window open for the kill
                }
                return "tool-ok";
            },
            idempotent);

        LlmClient llm = new ScriptLlm(marker, !inTool,
            toolCall("c1", "crashtool", Map.of()),
            toolCall("c2", "crashtool", Map.of()),
            finalAnswer("worker-done"));

        AgentConfig config = AgentConfig.builder()
            .withClient(llm)
            .withToolDefinitions(tool)
            .withJournalRoot(journalRoot)
            .withMaxIterations(8)
            .build();
        // This never returns: the parent destroys the process mid-run.
        AgentRun.begin(config, "chaos crash test");
        System.out.println("CRASHWORKER-UNEXPECTEDLY-SURVIVED");
    }
}

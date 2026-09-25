package dev.axiom.verify;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentResult;
import dev.axiom.agent.ReActAgent;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.replay.ScriptedLlm;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolParam;
import dev.axiom.tools.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * End-to-end demo of proof-carrying tool execution.
 *
 * <p>Run with {@code java -cp <axiom jar + deps> dev.axiom.verify.VerifyDemo}.
 * It runs a scripted agent that writes, reads and deletes files (all
 * attested by built-in verifiers) plus one pure computation (explicitly
 * unverifiable), then:
 * <ol>
 *   <li>audits the journal — every attestable effect re-verifies: N/N;</li>
 *   <li>tampers with a file <i>after</i> the run and audits again — the
 *       audit catches the mismatch and reports it precisely.</li>
 * </ol>
 *
 * <p>Everything is fixture-driven: no network, no API keys, deterministic.
 */
public final class VerifyDemo {

    /** File tools with real effects; the pure {@code vadd} has none to attest. */
    public static class FileTools {
        @Tool(name = "vwrite", description = "Write content to a file")
        public String write(@ToolParam(description = "file path") String path,
                            @ToolParam(description = "content") String content) throws Exception {
            Path p = Path.of(path);
            Files.createDirectories(p.getParent());
            Files.writeString(p, content);
            return "wrote " + content.length() + " chars to " + path;
        }

        @Tool(name = "vread", description = "Read a file")
        public String read(@ToolParam(description = "file path") String path) throws Exception {
            return Files.readString(Path.of(path));
        }

        @Tool(name = "vdelete", description = "Delete a file")
        public String delete(@ToolParam(description = "file path") String path) throws Exception {
            Files.deleteIfExists(Path.of(path));
            return "deleted " + path;
        }

        @Tool(name = "vadd", description = "Add two integers (pure computation)")
        public int add(@ToolParam(description = "first addend") int x,
                       @ToolParam(description = "second addend") int y) {
            return x + y;
        }
    }

    private static ChatResponse turn(String content, String callId, String tool,
                                     Map<String, Object> args) {
        return new ChatResponse(content,
            callId == null ? List.of() : List.of(new ToolCallRequest(callId, tool, args)),
            new ChatResponse.TokenUsage(10, 5, 15));
    }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("axiom-verify-demo");
        Path journalRoot = work.resolve("journals");
        Path report = work.resolve("report.txt");
        Path scratch = work.resolve("scratch.txt");

        ToolRegistry registry = new ToolRegistry().register(new FileTools());
        // Attest the file tools with the built-in verifiers; vadd stays a
        // plain tool and will be honestly marked UNVERIFIABLE.
        registry.replace(AttestedTool.wrap(
            registry.find("vwrite").orElseThrow(),
            new Verifiers.FileWriteVerifier()));
        registry.replace(AttestedTool.wrap(
            registry.find("vread").orElseThrow(),
            new Verifiers.FileReadVerifier()));
        registry.replace(AttestedTool.wrap(
            registry.find("vdelete").orElseThrow(),
            new Verifiers.FileDeleteVerifier()));

        AgentConfig config = AgentConfig.builder()
            .withClient(new ScriptedLlm(List.of(
                turn("Writing the report.", "c1", "vwrite",
                    Map.of("path", report.toString(), "content", "Axiom audit: all systems nominal.")),
                turn("Reading it back.", "c2", "vread",
                    Map.of("path", report.toString())),
                turn("Cleaning up scratch.", "c3", "vwrite",
                    Map.of("path", scratch.toString(), "content", "temporary")),
                turn("Deleting scratch.", "c4", "vdelete",
                    Map.of("path", scratch.toString())),
                turn("A pure computation.", "c5", "vadd",
                    Map.of("x", 20, "y", 22)),
                turn("Done.", null, null, null))))
            .withRegistry(registry)
            .withSystemPrompt("You are a file-management agent.")
            .withJournalRoot(journalRoot)
            .build();

        ReActAgent agent = new ReActAgent(config);
        AgentResult result = agent.run("Write report.txt, read it back, create and delete scratch.txt, add 20+22.");
        Path journalDir = agent.journal().dir();
        agent.journal().close();
        System.out.println("run finished: \"" + result.output() + "\"; journal: " + journalDir);

        System.out.println("\n===== audit 1: clean run =====");
        boolean clean = VerifyMain.audit(journalDir, System.out);
        System.out.println("clean audit passed: " + clean);

        // Tamper with the world after the run: the audit must catch it.
        Files.writeString(report, "TAMPERED AFTER THE RUN");
        System.out.println("\n===== audit 2: after tampering with " + report.getFileName() + " =====");
        boolean tampered = VerifyMain.audit(journalDir, System.out);
        System.out.println("tampered audit passed: " + tampered + " (expected false)");

        if (!clean || tampered) {
            System.out.println("\nDEMO FAILED");
            System.exit(1);
        }
        System.out.println("\nDEMO OK: 2/2 latest attestable effects verified on the clean audit "
            + "(2 earlier claims superseded by later verified effects); "
            + "post-run tampering caught on the second audit.");
    }

    /** The tool definitions, for tests that want the same attested surface. */
    static ToolRegistry attestedRegistry() {
        ToolRegistry registry = new ToolRegistry().register(new FileTools());
        for (ToolDefinition def : List.copyOf(registry.all())) {
            Verifier v = switch (def.name()) {
                case "vwrite" -> new Verifiers.FileWriteVerifier();
                case "vread" -> new Verifiers.FileReadVerifier();
                case "vdelete" -> new Verifiers.FileDeleteVerifier();
                default -> null;
            };
            if (v != null) registry.replace(AttestedTool.wrap(def, v));
        }
        return registry;
    }
}

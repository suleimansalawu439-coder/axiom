package dev.axiom.verify;

import dev.axiom.agent.AgentConfig;
import dev.axiom.agent.AgentEvent;
import dev.axiom.agent.AgentResult;
import dev.axiom.agent.ReActAgent;
import dev.axiom.durable.RunJournal;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.replay.ScriptedLlm;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolParam;
import dev.axiom.tools.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifiable tool execution: proof-carrying tool calls, independent
 * re-verification, tamper-evident audits. All fixture-driven — no network,
 * no keys.
 */
class VerifyTest {

    static class FileTools {
        @Tool(name = "vfy_write", description = "Write content to a file")
        public String write(@ToolParam(description = "file path") String path,
                            @ToolParam(description = "content") String content) throws Exception {
            Path p = Path.of(path);
            Files.createDirectories(p.getParent());
            Files.writeString(p, content);
            return "wrote " + content.length() + " chars";
        }

        @Tool(name = "vfy_read", description = "Read a file")
        public String read(@ToolParam(description = "file path") String path) throws Exception {
            return Files.readString(Path.of(path));
        }

        @Tool(name = "vfy_delete", description = "Delete a file")
        public String delete(@ToolParam(description = "file path") String path) throws Exception {
            Files.deleteIfExists(Path.of(path));
            return "deleted";
        }

        @Tool(name = "vfy_add", description = "Add two integers (pure, no observable effect)")
        public int add(@ToolParam(description = "x") int x,
                       @ToolParam(description = "y") int y) {
            return x + y;
        }

        /** A dishonest tool: claims to write, writes nothing. */
        @Tool(name = "vfy_lie", description = "Pretends to write a file")
        public String lie(@ToolParam(description = "file path") String path) {
            return "wrote it, trust me";
        }
    }

    private static ToolRegistry attestedRegistry() {
        ToolRegistry registry = new ToolRegistry().register(new FileTools());
        registry.replace(AttestedTool.wrap(registry.find("vfy_write").orElseThrow(),
            new Verifiers.FileWriteVerifier()));
        registry.replace(AttestedTool.wrap(registry.find("vfy_read").orElseThrow(),
            new Verifiers.FileReadVerifier()));
        registry.replace(AttestedTool.wrap(registry.find("vfy_delete").orElseThrow(),
            new Verifiers.FileDeleteVerifier()));
        registry.replace(AttestedTool.wrap(registry.find("vfy_lie").orElseThrow(),
            new Verifiers.FileWriteVerifier()));
        return registry;
    }

    private static ChatResponse turn(String content, String callId, String tool,
                                     Map<String, Object> args) {
        return new ChatResponse(content,
            callId == null ? List.of() : List.of(new ToolCallRequest(callId, tool, args)),
            new ChatResponse.TokenUsage(10, 5, 15));
    }

    private static ChatResponse finalTurn(String text) {
        return new ChatResponse(text, List.of(), new ChatResponse.TokenUsage(10, 5, 15));
    }

    // ------------------------------------------------------------------
    // Unit: certificates and independent verification
    // ------------------------------------------------------------------

    @Test
    void certificateIssuedAndIndependentlyVerified(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "hello");
        var v = new Verifiers.FileWriteVerifier();
        Map<String, Object> args = Map.of("path", f.toString(), "content", "hello");
        Certificate cert = v.attest("vfy_write", "c1", args, "wrote 5 chars");
        assertEquals("vfy_write", cert.toolName());
        assertEquals("c1", cert.callId());
        assertEquals("builtin:file-write", cert.verifierKind());
        assertEquals(1, cert.claims().size());
        assertEquals(64, cert.argsHash().length()); // SHA-256 hex
        assertDoesNotThrow(() -> v.check(cert)); // independent re-verification
    }

    @Test
    void tamperedFileFailsCheckLoudly(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "original");
        var v = new Verifiers.FileWriteVerifier();
        Certificate cert = v.attest("vfy_write", "c1",
            Map.of("path", f.toString(), "content", "original"), "ok");
        Files.writeString(f, "TAMPERED");
        VerificationException e = assertThrows(VerificationException.class, () -> v.check(cert));
        assertTrue(e.getMessage().contains(cert.claims().get(0).expectedSha256()),
            "diagnostic must show the attested hash: " + e.getMessage());
        assertTrue(e.getMessage().contains("tamper") || e.getMessage().contains("Tamper"),
            "diagnostic must name tampering: " + e.getMessage());
    }

    @Test
    void deleteVerifierCatchesReappearance(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("gone.txt");
        var v = new Verifiers.FileDeleteVerifier();
        Certificate cert = v.attest("vfy_delete", "c9", Map.of("path", f.toString()), "deleted");
        assertDoesNotThrow(() -> v.check(cert));
        Files.writeString(f, "back from the dead");
        assertThrows(VerificationException.class, () -> v.check(cert));
    }

    @Test
    void attestFailsFastWhenEffectNotObservable(@TempDir Path tmp) {
        Path f = tmp.resolve("never-written.txt");
        var v = new Verifiers.FileWriteVerifier();
        assertThrows(VerificationException.class, () -> v.attest("vfy_write", "c1",
            Map.of("path", f.toString(), "content", "x"), "ok"));
    }

    @Test
    void argsHashIsCanonicalRegardlessOfOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("path", "/x"); a.put("content", "y");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("content", "y"); b.put("path", "/x");
        assertEquals(Hashing.argsHash(a), Hashing.argsHash(b));
    }

    @Test
    void unverifiableMarkerIsExplicit(@TempDir Path tmp) {
        Verifier v = Verifiers.unverifiable();
        Certificate cert = v.attest("vfy_add", "c5", Map.of("x", 1, "y", 2), "3");
        assertTrue(cert.isUnverifiable());
        assertTrue(cert.claims().isEmpty());
        assertDoesNotThrow(() -> v.check(cert)); // nothing to verify — marked, not hidden
    }

    // ------------------------------------------------------------------
    // Integration: agent run, journaling, audit
    // ------------------------------------------------------------------

    /** Run the scripted 4-call scenario; return the journal dir. */
    private static Path recordRun(Path journalRoot, Path work, boolean includeLie) {
        ToolRegistry registry = attestedRegistry();
        String target = includeLie ? "vfy_lie" : "vfy_write";
        Path target1 = work.resolve("one.txt");
        Path target2 = work.resolve("two.txt");
        AgentConfig config = AgentConfig.builder()
            .withClient(new ScriptedLlm(List.of(
                turn("Writing one.", "c1", target,
                    Map.of("path", target1.toString(), "content", "first")),
                turn("Reading one.", "c2", "vfy_read",
                    Map.of("path", target1.toString())),
                turn("Writing two.", "c3", "vfy_write",
                    Map.of("path", target2.toString(), "content", "second")),
                turn("Deleting two.", "c4", "vfy_delete",
                    Map.of("path", target2.toString())),
                turn("Pure math.", "c5", "vfy_add", Map.of("x", 20, "y", 22)),
                finalTurn("done"))))
            .withRegistry(registry)
            .withJournalRoot(journalRoot)
            .build();
        ReActAgent agent = new ReActAgent(config);
        AgentResult result = agent.run("manage the files");
        assertEquals("done", result.output());
        Path dir = agent.journal().dir();
        agent.journal().close();
        return dir;
    }

    private static String auditToString(Path journalDir) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean ok = VerifyMain.audit(journalDir, new PrintStream(buf, true, StandardCharsets.UTF_8));
        return ok + "\n" + buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void fullAgentRunAuditsClean(@TempDir Path tmp) {
        Path journalDir = recordRun(tmp.resolve("journals"), tmp.resolve("work"), false);
        String report = auditToString(journalDir);
        assertTrue(report.startsWith("true"), "clean run must audit clean:\n" + report);
        // Latest-effects semantics: c2's read supersedes c1's write on one.txt,
        // c4's delete supersedes c3's write on two.txt — only the 2 latest
        // claims are re-checkable against current state.
        assertTrue(report.contains("latest effects verified: 2/2"),
            "the 2 latest attestable effects must verify:\n" + report);
        assertTrue(report.contains("superseded: 2"), report);
        assertTrue(report.contains("UNVERIFIABLE"), "pure tool must be marked:\n" + report);
        assertTrue(report.contains("ALL CHECKS PASSED"), report);
    }

    @Test
    void journalCarriesCertificateEvents(@TempDir Path tmp) {
        Path journalDir = recordRun(tmp.resolve("journals"), tmp.resolve("work"), false);
        List<RunJournal.Record> records;
        try (RunJournal j = RunJournal.open(journalDir.getParent(),
                journalDir.getFileName().toString())) {
            records = j.readAll();
        }
        long issued = records.stream()
            .filter(r -> r instanceof RunJournal.Event ev
                && ev.event() instanceof AgentEvent.CertificateIssued)
            .count();
        long verifiedOk = records.stream()
            .filter(r -> r instanceof RunJournal.Event ev
                && ev.event() instanceof AgentEvent.CertificateVerified cv && cv.ok())
            .count();
        assertEquals(4, issued, "one CertificateIssued per attested call");
        assertEquals(4, verifiedOk, "one successful CertificateVerified per attested call");
    }

    @Test
    void tamperAfterRunFailsAudit(@TempDir Path tmp) throws Exception {
        Path work = tmp.resolve("work");
        Path journalDir = recordRun(tmp.resolve("journals"), work, false);
        Files.writeString(work.resolve("one.txt"), "TAMPERED AFTER THE RUN");
        String report = auditToString(journalDir);
        assertTrue(report.startsWith("false"), "tampered run must fail audit:\n" + report);
        assertTrue(report.contains("FAILED"), report);
        assertTrue(report.contains("AUDIT FAILED"), report);
    }

    @Test
    void verificationFailureAbortsRunAndIsJournaled(@TempDir Path tmp) {
        Path journalRoot = tmp.resolve("journals");
        Path work = tmp.resolve("work");
        // The lying tool claims to write but writes nothing: attestation
        // must catch it and abort the run fail-closed.
        VerificationException thrown = assertThrows(VerificationException.class,
            () -> recordRun(journalRoot, work, true));
        assertTrue(thrown.getMessage().contains("one.txt"),
            "diagnostic must name the path: " + thrown.getMessage());

        // The failure is journaled as its own event type.
        String runId = RunJournal.listRuns(journalRoot).get(0);
        List<RunJournal.Record> records;
        try (RunJournal j = RunJournal.open(journalRoot, runId)) {
            records = j.readAll();
        }
        List<AgentEvent.CertificateVerified> failures = new ArrayList<>();
        for (RunJournal.Record r : records) {
            if (r instanceof RunJournal.Event ev
                    && ev.event() instanceof AgentEvent.CertificateVerified cv && !cv.ok()) {
                failures.add(cv);
            }
        }
        assertEquals(1, failures.size(), "exactly one recorded verification failure");
        assertEquals("vfy_lie", failures.get(0).toolName());

        // And the offline audit reports it too.
        String report = auditToString(journalRoot.resolve(runId));
        assertTrue(report.startsWith("false"), report);
    }

    @Test
    void tamperedJournalFailsAudit(@TempDir Path tmp) throws Exception {
        Path journalDir = recordRun(tmp.resolve("journals"), tmp.resolve("work"), false);
        Path log = journalDir.resolve("journal.jsonl");
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        // Corrupt a middle line (never the torn tail — that is tolerated by design).
        int lastContent = lines.size() - 1;
        while (lastContent >= 0 && lines.get(lastContent).isBlank()) lastContent--;
        int victim = Math.min(3, lastContent - 1);
        assertTrue(victim > 0, "journal too short to corrupt a middle line");
        lines.set(victim, "{\"kind\":\"event\",\"seq\":999,\"garbage\":");
        Files.write(log, lines, StandardCharsets.UTF_8);
        String report = auditToString(journalDir);
        assertTrue(report.startsWith("false"), "corrupt journal must fail audit:\n" + report);
        assertTrue(report.contains("JOURNAL CORRUPT"), report);
    }

    @Test
    void customVerifierIsMarkedNotSilentlyVerified(@TempDir Path tmp) {
        ToolRegistry registry = new ToolRegistry().register(new FileTools());
        Verifier custom = new Verifier() {
            @Override public String kind() { return "custom:eyeball"; }
            @Override public Certificate attest(String toolName, String callId,
                    Map<String, Object> args, String resultSummary) {
                return new Certificate(toolName, callId, Hashing.argsHash(args),
                    List.of(), kind(), java.time.Instant.now());
            }
            @Override public void check(Certificate c) { /* trust me */ }
        };
        registry.replace(AttestedTool.wrap(registry.find("vfy_add").orElseThrow(), custom));
        Path journalRoot = tmp.resolve("journals");
        AgentConfig config = AgentConfig.builder()
            .withClient(new ScriptedLlm(List.of(
                turn("Math.", "c1", "vfy_add", Map.of("x", 1, "y", 2)),
                finalTurn("3"))))
            .withRegistry(registry)
            .withJournalRoot(journalRoot)
            .build();
        ReActAgent agent = new ReActAgent(config);
        agent.run("add");
        Path dir = agent.journal().dir();
        agent.journal().close();
        String report = auditToString(dir);
        assertTrue(report.startsWith("true"), report);
        assertTrue(report.contains("CUSTOM (custom:eyeball"), report);
        assertFalse(report.contains("VERIFIED (custom"), "custom must never read as verified:\n" + report);
    }

    @Test
    void wrapPreservesToolMetadata() {
        ToolRegistry registry = new ToolRegistry().register(new FileTools());
        ToolDefinition raw = registry.find("vfy_write").orElseThrow();
        ToolDefinition wrapped = AttestedTool.wrap(raw, new Verifiers.FileWriteVerifier());
        assertEquals(raw.name(), wrapped.name());
        assertEquals(raw.description(), wrapped.description());
        assertEquals(raw.jsonSchema(), wrapped.jsonSchema());
        assertEquals(raw.timeoutSeconds(), wrapped.timeoutSeconds());
        assertEquals(raw.requiresApproval(), wrapped.requiresApproval());
        assertEquals(raw.idempotent(), wrapped.idempotent());
        assertTrue(wrapped.invoker() instanceof AttestedTool.AttestingInvoker);
    }
}

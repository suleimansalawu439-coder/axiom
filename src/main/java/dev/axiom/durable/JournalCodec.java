package dev.axiom.durable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.agent.AgentEvent;
import dev.axiom.agent.AgentResult;
import dev.axiom.budget.Budget;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.verify.Certificate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serialization for the events stored in a {@link RunJournal}. Only the
 * events needed to rebuild a run are round-tripped with full fidelity
 * (RunStarted, LlmResponse, ToolCallFinished, RunFinished); the rest are
 * stored for observability and replayed as faithfully as their payloads
 * allow.
 */
final class JournalCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JournalCodec() {}

    static String encodeLine(Map<String, Object> record) {
        try {
            return MAPPER.writeValueAsString(record);
        } catch (Exception e) {
            throw new DurableException("Failed to encode journal record", e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> decodeLine(String line) {
        try {
            return MAPPER.readValue(line, new TypeReference<>() {});
        } catch (Exception e) {
            throw new DurableException("Failed to decode journal line: " + truncate(line), e);
        }
    }

    // ------------------------------------------------------------------
    // AgentEvent <-> Map
    // ------------------------------------------------------------------

    static Map<String, Object> eventToMap(AgentEvent event) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (event instanceof AgentEvent.RunStarted e) {
            m.put("type", "RunStarted");
            m.put("task", e.task());
        } else if (event instanceof AgentEvent.LlmRequest e) {
            m.put("type", "LlmRequest");
            m.put("iteration", e.iteration());
        } else if (event instanceof AgentEvent.LlmResponse e) {
            m.put("type", "LlmResponse");
            m.put("iteration", e.iteration());
            m.put("response", responseToMap(e.response()));
        } else if (event instanceof AgentEvent.ToolCallStarted e) {
            m.put("type", "ToolCallStarted");
            m.put("call", callToMap(e.call()));
        } else if (event instanceof AgentEvent.ToolCallFinished e) {
            m.put("type", "ToolCallFinished");
            m.put("call", callToMap(e.call()));
            m.put("result", e.result());
            m.put("durationMs", e.durationMs());
        } else if (event instanceof AgentEvent.ApprovalRequested e) {
            m.put("type", "ApprovalRequested");
            m.put("toolName", e.toolName());
            m.put("arguments", e.arguments());
        } else if (event instanceof AgentEvent.BudgetUpdated e) {
            m.put("type", "BudgetUpdated");
            m.put("charged", usageToMap(e.charged()));
            m.put("snapshot", snapshotToMap(e.snapshot()));
        } else if (event instanceof AgentEvent.RunFinished e) {
            m.put("type", "RunFinished");
            m.put("result", resultToMap(e.result()));
        } else if (event instanceof AgentEvent.GuardrailBlocked e) {
            m.put("type", "GuardrailBlocked");
            m.put("guardrailName", e.guardrailName());
            m.put("side", e.side());
            m.put("reason", e.reason());
        } else if (event instanceof AgentEvent.CertificateIssued e) {
            m.put("type", "CertificateIssued");
            m.put("certificate", certificateToMap(e.certificate()));
        } else if (event instanceof AgentEvent.CertificateVerified e) {
            m.put("type", "CertificateVerified");
            m.put("callId", e.callId());
            m.put("toolName", e.toolName());
            m.put("verifierKind", e.verifierKind());
            m.put("ok", e.ok());
            m.put("detail", e.detail());
        } else {
            m.put("type", "Unknown");
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    static AgentEvent eventFromMap(Map<String, Object> m, java.time.Instant timestamp) {
        String type = String.valueOf(m.get("type"));
        return switch (type) {
            case "RunStarted" -> new AgentEvent.RunStarted(timestamp, str(m.get("task")));
            case "LlmRequest" -> new AgentEvent.LlmRequest(timestamp, num(m.get("iteration")));
            case "LlmResponse" -> new AgentEvent.LlmResponse(timestamp, num(m.get("iteration")),
                responseFromMap(reqMap(m.get("response"), "LlmResponse.response")));
            case "ToolCallStarted" -> new AgentEvent.ToolCallStarted(timestamp,
                callFromMap(reqMap(m.get("call"), "ToolCallStarted.call")));
            case "ToolCallFinished" -> {
                var call = callFromMap(reqMap(m.get("call"), "ToolCallFinished.call"));
                Object d = m.get("durationMs");
                yield new AgentEvent.ToolCallFinished(timestamp, call, str(m.get("result")),
                    d instanceof Number n ? n.longValue() : 0L);
            }
            case "ApprovalRequested" -> new AgentEvent.ApprovalRequested(timestamp,
                str(m.get("toolName")), optMapOrEmpty(m.get("arguments"), "ApprovalRequested.arguments"));
            case "BudgetUpdated" -> new AgentEvent.BudgetUpdated(timestamp,
                usageFromMap(optMap(m.get("charged"), "BudgetUpdated.charged")),
                snapshotFromMap(reqMap(m.get("snapshot"), "BudgetUpdated.snapshot")));
            case "RunFinished" -> new AgentEvent.RunFinished(timestamp,
                resultFromMap(reqMap(m.get("result"), "RunFinished.result")));
            case "GuardrailBlocked" -> new AgentEvent.GuardrailBlocked(timestamp,
                str(m.get("guardrailName")), str(m.get("side")), str(m.get("reason")));
            case "CertificateIssued" -> new AgentEvent.CertificateIssued(timestamp,
                certificateFromMap(reqMap(m.get("certificate"), "CertificateIssued.certificate")));
            case "CertificateVerified" -> new AgentEvent.CertificateVerified(timestamp,
                str(m.get("callId")), str(m.get("toolName")), str(m.get("verifierKind")),
                Boolean.TRUE.equals(m.get("ok")), str(m.get("detail")));
            // No lenient fallback: an unknown event type means the journal is
            // corrupt (or from an incompatible version) — resuming it as a
            // blank RunStarted would silently poison the transcript.
            default -> throw new DurableException(
                "Corrupt journal record: unknown event type '" + type + "'");
        };
    }

    static Map<String, Object> responseToMap(ChatResponse r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", r.content());
        List<Map<String, Object>> calls = new ArrayList<>();
        if (r.toolCalls() != null) {
            for (ToolCallRequest c : r.toolCalls()) calls.add(callToMap(c));
        }
        m.put("toolCalls", calls);
        m.put("usage", usageToMap(r.usage()));
        return m;
    }

    @SuppressWarnings("unchecked")
    static ChatResponse responseFromMap(Map<String, Object> m) {
        List<ToolCallRequest> calls = new ArrayList<>();
        Object raw = m.get("toolCalls");
        if (raw != null) {
            if (!(raw instanceof List<?> list)) {
                throw new DurableException(
                    "Corrupt journal record: expected a list for 'LlmResponse.toolCalls' but found "
                        + describe(raw));
            }
            for (Object o : list) calls.add(callFromMap(reqMap(o, "LlmResponse.toolCalls[]")));
        }
        return new ChatResponse(strOrNull(m.get("content")), calls,
            usageFromMap(optMap(m.get("usage"), "LlmResponse.usage")));
    }

    static Map<String, Object> callToMap(ToolCallRequest c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("name", c.name());
        m.put("arguments", c.arguments() == null ? Map.of() : c.arguments());
        // Thought signature (Gemini 3): must round-trip so a resumed run can
        // replay the call verbatim. Absent in old journals -> null, fine.
        if (c.thoughtSignature() != null) m.put("thoughtSignature", c.thoughtSignature());
        return m;
    }

    @SuppressWarnings("unchecked")
    static ToolCallRequest callFromMap(Map<String, Object> m) {
        Object args = m.get("arguments");
        return new ToolCallRequest(str(m.get("id")), str(m.get("name")),
            args instanceof Map<?, ?> am ? (Map<String, Object>) am : Map.of(),
            strOrNull(m.get("thoughtSignature")));
    }

    static Map<String, Object> certificateToMap(Certificate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("toolName", c.toolName());
        m.put("callId", c.callId());
        m.put("argsHash", c.argsHash());
        List<Map<String, Object>> claims = new ArrayList<>();
        for (var claim : c.claims()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("kind", claim.kind());
            cm.put("path", claim.path());
            cm.put("expectedSha256", claim.expectedSha256());
            cm.put("detail", claim.detail());
            claims.add(cm);
        }
        m.put("claims", claims);
        m.put("verifierKind", c.verifierKind());
        m.put("issuedAt", c.issuedAt().toString());
        return m;
    }

    static Certificate certificateFromMap(Map<String, Object> m) {
        List<Certificate.EffectClaim> claims = new ArrayList<>();
        Object raw = m.get("claims");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> cm) {
                    claims.add(new Certificate.EffectClaim(
                        str(cm.get("kind")), str(cm.get("path")),
                        strOrNull(cm.get("expectedSha256")), str(cm.get("detail"))));
                }
            }
        }
        Instant issuedAt;
        try {
            issuedAt = Instant.parse(str(m.get("issuedAt")));
        } catch (Exception e) {
            issuedAt = Instant.EPOCH;
        }
        return new Certificate(str(m.get("toolName")), str(m.get("callId")),
            str(m.get("argsHash")), claims, str(m.get("verifierKind")), issuedAt);
    }

    static Map<String, Object> usageToMap(ChatResponse.TokenUsage u) {
        if (u == null) u = ChatResponse.TokenUsage.empty();
        return Map.of("promptTokens", u.promptTokens(),
            "completionTokens", u.completionTokens(), "totalTokens", u.totalTokens());
    }

    static ChatResponse.TokenUsage usageFromMap(Map<String, Object> m) {
        if (m == null) return ChatResponse.TokenUsage.empty();
        return new ChatResponse.TokenUsage(num(m.get("promptTokens")),
            num(m.get("completionTokens")), num(m.get("totalTokens")));
    }

    static Map<String, Object> snapshotToMap(Budget.Snapshot s) {
        return Map.of("inputTokens", s.inputTokens(), "outputTokens", s.outputTokens(),
            "totalTokens", s.totalTokens(), "costUsd", s.costUsd(),
            "maxTokens", s.maxTokens(), "maxCostUsd", s.maxCostUsd());
    }

    static Budget.Snapshot snapshotFromMap(Map<String, Object> m) {
        Object maxT = m.get("maxTokens");
        Object maxC = m.get("maxCostUsd");
        return new Budget.Snapshot(num(m.get("inputTokens")), num(m.get("outputTokens")),
            num(m.get("totalTokens")), dbl(m.get("costUsd")),
            maxT instanceof Number n ? n.longValue() : Budget.UNLIMITED_TOKENS,
            maxC instanceof Number n ? n.doubleValue() : Budget.UNLIMITED_COST);
    }

    static Map<String, Object> resultToMap(AgentResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("output", r.output());
        m.put("iterations", r.iterations());
        m.put("toolCallsMade", r.toolCallsMade());
        m.put("usage", usageToMap(r.tokenUsage()));
        m.put("completed", r.completed());
        return m;
    }

    static AgentResult resultFromMap(Map<String, Object> m) {
        return new AgentResult(strOrNull(m.get("output")), num(m.get("iterations")),
            num(m.get("toolCallsMade")),
            usageFromMap(optMap(m.get("usage"), "RunFinished.result.usage")),
            Boolean.TRUE.equals(m.get("completed")));
    }

    /**
     * Require an object-typed value from a decoded journal record. A missing
     * or wrong-typed value means the journal is corrupt — fail with a
     * diagnostic naming the field instead of a bare
     * {@link ClassCastException} or {@link NullPointerException}.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> reqMap(Object o, String field) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new DurableException("Corrupt journal record: expected an object for '" + field
            + "' but found " + describe(o));
    }

    /** Like {@link #reqMap} but tolerates a missing value (still rejects a wrong-typed one). */
    private static Map<String, Object> optMap(Object o, String field) {
        if (o == null) return null;
        return reqMap(o, field);
    }

    /** Like {@link #optMap} but yields an empty map instead of null when missing. */
    private static Map<String, Object> optMapOrEmpty(Object o, String field) {
        Map<String, Object> m = optMap(o, field);
        return m == null ? Map.of() : m;
    }

    private static String describe(Object o) {
        if (o == null) return "nothing";
        String s = String.valueOf(o);
        if (s.length() > 60) s = s.substring(0, 60) + "…";
        return o.getClass().getSimpleName() + "(" + s + ")";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String strOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}

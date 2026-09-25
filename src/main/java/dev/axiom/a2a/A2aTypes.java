package dev.axiom.a2a;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A2A v1.0 task model: tasks, statuses, artifacts, and parts. Field names
 * follow the protocol ({@code taskId}, {@code contextId}, {@code kind},
 * {@code parts}) so Axiom agents interoperate with foreign A2A agents.
 */
public final class A2aTypes {

    /** Task lifecycle states. */
    public static final class States {
        public static final String SUBMITTED = "submitted";
        public static final String WORKING = "working";
        public static final String INPUT_REQUIRED = "input-required";
        public static final String COMPLETED = "completed";
        public static final String FAILED = "failed";
        public static final String CANCELED = "canceled";
        public static final String REJECTED = "rejected";

        private States() {}

        public static final Set<String> TERMINAL =
            Set.of(COMPLETED, FAILED, CANCELED, REJECTED);
    }

    /** A message/task part: text or structured data. */
    public sealed interface Part permits Part.TextPart, Part.DataPart {
        record TextPart(String text) implements Part {}
        record DataPart(Map<String, Object> data, String mimeType) implements Part {}

        static Map<String, Object> toJson(Part p) {
            if (p instanceof TextPart t) {
                return Map.of("kind", "text", "text", t.text() == null ? "" : t.text());
            }
            DataPart d = (DataPart) p;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "data");
            m.put("data", d.data() == null ? Map.of() : d.data());
            if (d.mimeType() != null) m.put("mimeType", d.mimeType());
            return m;
        }

        @SuppressWarnings("unchecked")
        static Part fromJson(Map<String, Object> m) {
            String kind = String.valueOf(m.get("kind"));
            if ("data".equals(kind)) {
                Object data = m.get("data");
                return new DataPart(data instanceof Map<?, ?> dm ? (Map<String, Object>) dm : Map.of(),
                    m.get("mimeType") == null ? null : String.valueOf(m.get("mimeType")));
            }
            return new TextPart(m.get("text") == null ? "" : String.valueOf(m.get("text")));
        }
    }

    /** One artifact produced by a task: an id, a name, and its parts. */
    public record Artifact(String artifactId, String name, List<Part> parts) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("artifactId", artifactId);
            m.put("name", name);
            List<Map<String, Object>> ps = new ArrayList<>();
            for (Part p : parts) ps.add(Part.toJson(p));
            m.put("parts", ps);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static Artifact fromJson(Map<String, Object> m) {
            List<Part> parts = new ArrayList<>();
            if (m.get("parts") instanceof List<?> list) {
                for (Object o : list) parts.add(Part.fromJson((Map<String, Object>) o));
            }
            return new Artifact(String.valueOf(m.get("artifactId")),
                String.valueOf(m.getOrDefault("name", "result")), parts);
        }
    }

    /** A task's status: lifecycle state plus timestamp. */
    public record TaskStatus(String state, String timestamp) {
        public boolean isTerminal() {
            return States.TERMINAL.contains(state);
        }
    }

    /** An A2A task. */
    public record A2aTask(String id, String contextId, TaskStatus status,
                          List<Artifact> artifacts) {
        public boolean isTerminal() {
            return status() != null && status().isTerminal();
        }

        /** The first text part of the first artifact, if any. */
        public Optional<String> artifactText() {
            for (Artifact a : artifacts) {
                for (Part p : a.parts()) {
                    if (p instanceof Part.TextPart t) return Optional.of(t.text());
                }
            }
            return Optional.empty();
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("contextId", contextId);
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("state", status.state());
            st.put("timestamp", status.timestamp());
            m.put("status", st);
            List<Map<String, Object>> as = new ArrayList<>();
            for (Artifact a : artifacts) as.add(a.toJson());
            m.put("artifacts", as);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static A2aTask fromJson(Map<String, Object> m) {
            Map<String, Object> st = m.get("status") instanceof Map<?, ?> sm
                ? (Map<String, Object>) sm : Map.of();
            List<Artifact> artifacts = new ArrayList<>();
            if (m.get("artifacts") instanceof List<?> list) {
                for (Object o : list) artifacts.add(Artifact.fromJson((Map<String, Object>) o));
            }
            return new A2aTask(
                String.valueOf(m.get("id")),
                m.get("contextId") == null ? null : String.valueOf(m.get("contextId")),
                new TaskStatus(String.valueOf(st.getOrDefault("state", States.SUBMITTED)),
                    st.get("timestamp") == null ? null : String.valueOf(st.get("timestamp"))),
                artifacts);
        }
    }

    private A2aTypes() {}
}

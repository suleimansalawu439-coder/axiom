package dev.axiom.a2a;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The A2A v1.0 agent card: how agents discover each other. Served at
 * {@code /.well-known/agent-card.json} by {@link A2aServer} and parsed from
 * any (including foreign) A2A agent by {@link A2aClient#getAgentCard}.
 */
public record AgentCard(String name, String description, String url, String version,
                        Capabilities capabilities, List<String> defaultInputModes,
                        List<String> defaultOutputModes, List<AgentSkill> skills) {

    public record Capabilities(boolean streaming, boolean pushNotifications,
                               boolean stateTransitionHistory) {}

    public record AgentSkill(String id, String name, String description,
                             List<String> tags, List<String> examples) {}

    /** Minimal card: name, description, url, and one skill per description. */
    public static AgentCard simple(String name, String description, String url,
                                   String... skillDescriptions) {
        List<AgentSkill> skills = new ArrayList<>();
        for (int i = 0; i < skillDescriptions.length; i++) {
            skills.add(new AgentSkill("skill-" + i, "skill-" + i,
                skillDescriptions[i], List.of(), List.of()));
        }
        return new AgentCard(name, description, url, "1.0.0",
            new Capabilities(true, false, false),
            List.of("text"), List.of("text"), skills);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("url", url);
        m.put("version", version);
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("streaming", capabilities.streaming());
        caps.put("pushNotifications", capabilities.pushNotifications());
        caps.put("stateTransitionHistory", capabilities.stateTransitionHistory());
        m.put("capabilities", caps);
        m.put("defaultInputModes", defaultInputModes);
        m.put("defaultOutputModes", defaultOutputModes);
        List<Map<String, Object>> ss = new ArrayList<>();
        for (AgentSkill s : skills) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", s.id());
            sm.put("name", s.name());
            sm.put("description", s.description());
            sm.put("tags", s.tags());
            sm.put("examples", s.examples());
            ss.add(sm);
        }
        m.put("skills", ss);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static AgentCard fromJson(Map<String, Object> m) {
        Map<String, Object> caps = m.get("capabilities") instanceof Map<?, ?> c
            ? (Map<String, Object>) c : Map.of();
        List<AgentSkill> skills = new ArrayList<>();
        if (m.get("skills") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> s = (Map<String, Object>) o;
                skills.add(new AgentSkill(str(s.get("id")), str(s.get("name")),
                    str(s.get("description")),
                    strList(s.get("tags")), strList(s.get("examples"))));
            }
        }
        return new AgentCard(
            str(m.get("name")), str(m.get("description")),
            m.get("url") == null ? null : String.valueOf(m.get("url")),
            m.get("version") == null ? "1.0.0" : String.valueOf(m.get("version")),
            new Capabilities(bool(caps.get("streaming")), bool(caps.get("pushNotifications")),
                bool(caps.get("stateTransitionHistory"))),
            strList(m.get("defaultInputModes")), strList(m.get("defaultOutputModes")),
            skills);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static boolean bool(Object o) { return Boolean.TRUE.equals(o); }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object e : l) out.add(String.valueOf(e));
        return out;
    }
}

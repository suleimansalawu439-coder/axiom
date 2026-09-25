package dev.axiom.guardrails;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.capabilities.Capability;
import dev.axiom.capabilities.Ensures;
import dev.axiom.capabilities.Requires;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolRegistry;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Fail-closed enforcer for the compile-time capability policy.
 *
 * <p>One policy, two enforcement points: the annotation processor proves the
 * policy is <em>coherent and satisfiable</em> at build time (every
 * {@code @Requires} token is {@code @Ensures}-able by some tool in the
 * compilation); this guardrail enforces it against the <em>actual</em> tool
 * calls the LLM makes at runtime. The compiler cannot see the future — the
 * model's choices are runtime — so neither half is sufficient alone.
 *
 * <p>How it works:
 * <ul>
 *   <li>Built via {@link #forRegistry(ToolRegistry)}: reads the
 *       {@code META-INF/axiom/policy/*.json} artifacts the processor emitted
 *       for each registered tool holder (same single-source-of-truth pattern
 *       as the tool schemas), with a reflection fallback for holders compiled
 *       without the processor.</li>
 *   <li>{@link #checkToolCall} runs before every tool dispatch: if the
 *       tool's {@code @Requires} tokens are not all present in the session
 *       token set, the call is blocked loudly
 *       ({@link GuardrailViolationException}, journaled as a
 *       {@code GuardrailBlocked} event) — never silently skipped.</li>
 *   <li>{@link #onToolCompleted} records the completed tool's
 *       {@code @Ensures} tokens. The token set is therefore a projection of
 *       the durable journal's {@code tool_call_completed} records: on resume,
 *       replayed completions re-establish the tokens in order, so the policy
 *       survives crashes.</li>
 * </ul>
 *
 * <p>Known limitations, stated plainly:
 * <ul>
 *   <li>Tools with no policy entry (synthetic tools, MCP-discovered at
 *       runtime) have no requirements and ensure nothing — they are allowed
 *       through. Conservative capability defaults for runtime-discovered
 *       tools are future work.</li>
 *   <li>A token is only as truthful as the tool that ensures it: the
 *       compiler checks the token is <em>declared</em>, not that the tool
 *       semantically earned it (a "backup" of an empty directory still
 *       yields {@code BACKUP}). Semantic honesty needs trajectory evals /
 *       LLM judges — a different layer.</li>
 * </ul>
 */
public final class CapabilityGuardrail implements Guardrail {

    /** Per-tool policy: declared effects, required tokens, ensured tokens. */
    public record ToolPolicy(String toolName, Set<Capability> capabilities,
                             Set<Capability> requires, Set<Capability> ensures) {
        static ToolPolicy empty(String toolName) {
            return new ToolPolicy(toolName, Set.of(), Set.of(), Set.of());
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, ToolPolicy> policies;
    /** Session token -> tool names that ensure it (for violation hints). */
    private final Map<Capability, Set<String>> ensurers = new EnumMap<>(Capability.class);
    /** Tokens established by completed tool calls in this session. */
    private final Set<Capability> sessionTokens = EnumSet.noneOf(Capability.class);

    private CapabilityGuardrail(Map<String, ToolPolicy> policies) {
        this.policies = Map.copyOf(policies);
        for (ToolPolicy p : policies.values()) {
            for (Capability token : p.ensures()) {
                ensurers.computeIfAbsent(token, k -> new LinkedHashSet<>()).add(p.toolName());
            }
        }
    }

    /**
     * Build the guardrail from a registry. Policy artifacts first
     * (compile-time single source of truth); per-tool reflection fallback
     * for holders compiled without the annotation processor.
     */
    public static CapabilityGuardrail forRegistry(ToolRegistry registry) {
        Map<String, ToolPolicy> policies = new LinkedHashMap<>();
        Map<String, Map<String, ToolPolicy>> artifactByHolder = new HashMap<>();
        for (var def : registry.all()) {
            String toolName = def.name();
            ToolPolicy policy = null;
            String holder = registry.toolHolderClasses().get(toolName);
            if (holder != null) {
                policy = artifactByHolder
                    .computeIfAbsent(holder, CapabilityGuardrail::loadArtifact)
                    .get(toolName);
            }
            if (policy == null && def.method() != null) {
                policy = fromReflection(toolName, def.method());
            }
            policies.put(toolName, policy != null ? policy : ToolPolicy.empty(toolName));
        }
        return new CapabilityGuardrail(policies);
    }

    @Override
    public String name() {
        return "capability-policy";
    }

    /**
     * Fail-closed precondition check: every {@code @Requires} token of the
     * tool must be present in the session token set, else block loudly.
     */
    @Override
    public Verdict checkToolCall(String toolName, Map<String, Object> arguments) {
        ToolPolicy policy = policies.get(toolName);
        if (policy == null) {
            return Verdict.allow(); // unknown tool: no policy entry (see class javadoc)
        }
        EnumSet<Capability> missing = EnumSet.noneOf(Capability.class);
        missing.addAll(policy.requires());
        missing.removeAll(sessionTokens);
        if (missing.isEmpty()) {
            return Verdict.allow();
        }
        return Verdict.block(
            "Tool '%s' requires session token(s) %s, but no completed tool call in this run has ensured them. %s"
                .formatted(toolName, missing, hintFor(missing)));
    }

    /**
     * Record the completed tool's {@code @Ensures} tokens. Called for every
     * completion — including completions replayed from the durable journal
     * on resume — so tokens track the journaled truth, not just this
     * process's memory.
     */
    @Override
    public void onToolCompleted(String toolName) {
        ToolPolicy policy = policies.get(toolName);
        if (policy != null) {
            sessionTokens.addAll(policy.ensures());
        }
    }

    /** Tokens currently held in this session (defensive copy). */
    public Set<Capability> sessionTokens() {
        return EnumSet.copyOf(sessionTokens); // sessionTokens is always an EnumSet: safe even when empty
    }

    /** The loaded policy for a tool, if any. */
    public Optional<ToolPolicy> policyOf(String toolName) {
        return Optional.ofNullable(policies.get(toolName));
    }

    private String hintFor(Set<Capability> missing) {
        List<String> hints = new ArrayList<>();
        for (Capability token : missing) {
            Set<String> who = ensurers.getOrDefault(token, Set.of());
            hints.add(who.isEmpty()
                ? "Nothing in this registry ensures " + token + "."
                : token + " is ensured by: " + who + " — call one of them first.");
        }
        return String.join(" ", hints);
    }

    // ------------------------------------------------------------------
    // Policy loading
    // ------------------------------------------------------------------

    /** Load one holder's policy artifact; empty map when absent/unreadable. */
    @SuppressWarnings("unchecked")
    private static Map<String, ToolPolicy> loadArtifact(String holderBinaryName) {
        String resource = "/META-INF/axiom/policy/"
            + holderBinaryName.replace('.', '/') + ".json";
        try (var in = CapabilityGuardrail.class.getResourceAsStream(resource)) {
            if (in == null) return Map.of();
            Map<String, Object> root = MAPPER.readValue(in, Map.class);
            if (!holderBinaryName.equals(root.get("class"))) return Map.of();
            Object tools = root.get("tools");
            if (!(tools instanceof List<?> list)) return Map.of();
            Map<String, ToolPolicy> out = new LinkedHashMap<>();
            for (Object t : list) {
                if (t instanceof Map<?, ?> m) {
                    Object n = m.get("name");
                    if (n instanceof String name) {
                        out.put(name, new ToolPolicy(name,
                            parseCaps(m.get("capabilities")),
                            parseCaps(m.get("requires")),
                            parseCaps(m.get("ensures"))));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            return Map.of(); // corrupt artifact: reflection fallback handles it
        }
    }

    private static Set<Capability> parseCaps(Object raw) {
        if (!(raw instanceof List<?> list)) return Set.of();
        Set<Capability> out = EnumSet.noneOf(Capability.class);
        for (Object o : list) {
            if (o instanceof String s) {
                try {
                    out.add(Capability.valueOf(s));
                } catch (IllegalArgumentException ignored) {
                    // Unknown capability in artifact: ignore, never crash loading.
                }
            }
        }
        return out.isEmpty() ? Set.of() : EnumSet.copyOf(out);
    }

    /**
     * Reflection fallback for holders compiled without the annotation
     * processor: read the annotations straight off the method. Mirrors the
     * processor's validation leniently (non-token members are dropped — the
     * processor would have failed the build for them).
     */
    private static ToolPolicy fromReflection(String toolName, Method method) {
        Tool tool = method.getAnnotation(Tool.class);
        Requires req = method.getAnnotation(Requires.class);
        Ensures ens = method.getAnnotation(Ensures.class);
        return new ToolPolicy(toolName,
            toSet(tool == null ? new Capability[0] : tool.capabilities()),
            tokensOnly(req == null ? new Capability[0] : req.value()),
            tokensOnly(ens == null ? new Capability[0] : ens.value()));
    }

    private static Set<Capability> toSet(Capability[] declared) {
        if (declared.length == 0) return Set.of();
        return EnumSet.copyOf(Arrays.asList(declared));
    }

    private static Set<Capability> tokensOnly(Capability[] declared) {
        EnumSet<Capability> out = EnumSet.noneOf(Capability.class);
        for (Capability c : declared) {
            if (c.isSessionToken()) out.add(c);
        }
        return out.isEmpty() ? Set.of() : EnumSet.copyOf(out);
    }
}

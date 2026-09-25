package dev.axiom.verify;

import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolInvoker;

import java.util.Map;
import java.util.Objects;

/**
 * Wraps a {@link ToolDefinition} with a {@link Verifier}, producing a new
 * definition whose invoker carries the verifier. The agent loop
 * (see {@code ReActAgent}) detects the wrapped invoker, attests the effect
 * right after the tool body returns, independently re-verifies the
 * certificate, journals both as first-class events — and aborts the run
 * fail-closed on any mismatch.
 *
 * <p>Example:
 * <pre>{@code
 * ToolDefinition raw = registry.find("write_file").orElseThrow();
 * registry.replace(AttestedTool.wrap(raw, new Verifiers.FileWriteVerifier()));
 * }</pre>
 */
public final class AttestedTool {

    private AttestedTool() {}

    /**
     * A {@link ToolInvoker} that carries its {@link Verifier}. The agent
     * loop looks for this interface; plain invokers are treated as
     * unverifiable (marked, never silently verified).
     */
    public interface AttestingInvoker extends ToolInvoker {
        /** The verifier that attests and re-checks this tool's effects. */
        Verifier verifier();
    }

    /**
     * Wrap {@code inner} so its effects are attested by {@code verifier}.
     * Name, description, schema, approval requirement, timeout and
     * idempotency are preserved; only the invoker is replaced.
     */
    public static ToolDefinition wrap(ToolDefinition inner, Verifier verifier) {
        Objects.requireNonNull(inner, "inner");
        Objects.requireNonNull(verifier, "verifier");
        ToolInvoker delegate = inner.invoker();
        AttestingInvoker invoker = new AttestingInvoker() {
            @Override public Verifier verifier() { return verifier; }

            @Override public Object invoke(Map<String, Object> arguments) throws Exception {
                return delegate.invoke(arguments);
            }

            @Override public String toString() {
                return "AttestingInvoker(" + inner.name() + ", " + verifier.kind() + ")";
            }
        };
        return ToolDefinition.of(inner.name(), inner.description(), inner.jsonSchema(),
            inner.requiresApproval(), inner.timeoutSeconds(), invoker, inner.idempotent());
    }
}

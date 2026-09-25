package dev.axiom.capabilities;

/**
 * Effect capabilities and session tokens for agent tools.
 *
 * <p>The lattice has two kinds of members:
 * <ul>
 *   <li><b>Effect capabilities</b> ({@code READ} … {@code PRIVATE_DATA}) —
 *       what a tool <em>does</em> to the world. Declared on
 *       {@code @Tool(capabilities = …)} by the tool author, who knows what
 *       the tool does.</li>
 *   <li><b>Session tokens</b> ({@code APPROVAL}, {@code BACKUP}) — facts
 *       about the <em>session</em>, not the world. Tools {@code @Ensures}
 *       them and other tools {@code @Requires} them. This is deliberately
 *       STRIPS/PDDL-shaped: preconditions and effects over a small token
 *       vocabulary.</li>
 * </ul>
 *
 * <p>Implication order: {@code DESTRUCTIVE} implies {@code WRITE} (deleting
 * is a kind of mutation). Everything implies itself; nothing else implies
 * anything. The lattice is intentionally tiny — v1 has three rule shapes
 * (ordering, taint, containment) and the lattice stays small on purpose.
 */
public enum Capability {
    /** Observes state without mutating it. */
    READ,
    /** Mutates non-destructive state. */
    WRITE,
    /** Deletes or irreversibly mutates state. Implies {@link #WRITE}. */
    DESTRUCTIVE,
    /** Touches the network. */
    NETWORK,
    /** Spends money or consumes paid quota. */
    SPEND,
    /** Returns data carrying privacy obligations (a taint source). */
    PRIVATE_DATA,
    /** Human approval was obtained — a <em>session token</em>, not an effect. */
    APPROVAL,
    /** A fresh backup exists — a <em>session token</em>, not an effect. */
    BACKUP;

    /**
     * Capability implication: {@code a.implies(b)} when holding {@code a}
     * also grants {@code b}. Reflexive; additionally
     * {@code DESTRUCTIVE.implies(WRITE)}.
     */
    public boolean implies(Capability other) {
        if (this == other) return true;
        return this == DESTRUCTIVE && other == WRITE;
    }

    /** True for the session-token members ({@code APPROVAL}, {@code BACKUP}). */
    public boolean isSessionToken() {
        return this == APPROVAL || this == BACKUP;
    }
}

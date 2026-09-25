package dev.axiom.capabilities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The capability lattice: implication order and the token/effect split. */
class CapabilityTest {

    @Test
    void implicationIsReflexive() {
        for (Capability c : Capability.values()) {
            assertTrue(c.implies(c), c + " should imply itself");
        }
    }

    @Test
    void destructiveImpliesWrite() {
        assertTrue(Capability.DESTRUCTIVE.implies(Capability.WRITE),
            "deleting is a kind of mutation");
        assertFalse(Capability.WRITE.implies(Capability.DESTRUCTIVE),
            "mutation must not imply deletion");
    }

    @Test
    void noOtherImplications() {
        assertFalse(Capability.READ.implies(Capability.WRITE));
        assertFalse(Capability.WRITE.implies(Capability.READ));
        assertFalse(Capability.NETWORK.implies(Capability.SPEND));
        assertFalse(Capability.SPEND.implies(Capability.NETWORK));
        assertFalse(Capability.READ.implies(Capability.PRIVATE_DATA));
        // Tokens are session facts, not effects: no implication either way.
        assertFalse(Capability.BACKUP.implies(Capability.WRITE));
        assertFalse(Capability.WRITE.implies(Capability.BACKUP));
        assertFalse(Capability.APPROVAL.implies(Capability.SPEND));
    }

    @Test
    void sessionTokenMembership() {
        assertTrue(Capability.APPROVAL.isSessionToken());
        assertTrue(Capability.BACKUP.isSessionToken());
        for (Capability c : new Capability[]{
                Capability.READ, Capability.WRITE, Capability.DESTRUCTIVE,
                Capability.NETWORK, Capability.SPEND, Capability.PRIVATE_DATA}) {
            assertFalse(c.isSessionToken(), c + " is an effect, not a session token");
        }
    }
}

package dev.axiom.guardrails;

/**
 * A policy check applied to agent inputs and outputs. Guardrails run inside
 * the ReAct loop: {@link #checkInput} gates the user's task before the run
 * starts, {@link #checkOutput} gates the final answer before it is returned.
 * Both default to allow, so a guardrail can implement only the side it cares
 * about.
 */
public interface Guardrail {

    /** Human-readable name, used in violation messages and tracing. */
    String name();

    default Verdict checkInput(String task) {
        return Verdict.allow();
    }

    default Verdict checkOutput(String output) {
        return Verdict.allow();
    }
}

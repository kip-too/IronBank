package shilingi.agent;

/**
 * A proposed action that the invariants refuse.
 *
 * <p>SPEC.md section 11: "a proposed action that violates one is rejected regardless of how good
 * the reasoning sounds. This distinction is the demo's strongest single moment - show a model
 * proposing something sensible-sounding that the system refuses."
 *
 * <p>Carries the invariant's identifier from SPEC.md section 9, so a refusal can be read back
 * and traced to the rule it enforces rather than to a message somebody once wrote.
 */
public class InvariantViolation extends RuntimeException {

    private final String invariant;
    private final ProposedAction refused;

    public InvariantViolation(String invariant, ProposedAction refused, String explanation) {
        super(invariant + ": " + explanation);
        this.invariant = invariant;
        this.refused = refused;
    }

    /** e.g. {@code "I8"}. */
    public String invariant() {
        return invariant;
    }

    public ProposedAction refused() {
        return refused;
    }
}

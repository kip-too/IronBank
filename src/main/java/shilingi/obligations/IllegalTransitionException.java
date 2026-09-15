package shilingi.obligations;

/**
 * Thrown when something tries to move an obligation along an arrow SPEC.md section 7 does not
 * draw.
 *
 * <p>The database trigger {@code obligation_state_machine} is the mechanism. This is the early,
 * legible failure on top of it.
 */
public class IllegalTransitionException extends RuntimeException {

    private final ObligationStatus from;
    private final ObligationStatus to;

    public IllegalTransitionException(ObligationStatus from, ObligationStatus to) {
        super("An obligation cannot move from " + from + " to " + to
              + ". SPEC.md section 7 allows SCHEDULED to FUNDED to PAID, and nothing else - "
              + "paying something that was never funded records money leaving that was never set aside.");
        this.from = from;
        this.to = to;
    }

    public ObligationStatus from() {
        return from;
    }

    public ObligationStatus to() {
        return to;
    }
}

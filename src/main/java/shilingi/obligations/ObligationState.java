package shilingi.obligations;

/**
 * SPEC.md section 7's obligation machine as an observer sees it - the stored statuses, plus the
 * one that is derived.
 *
 * <pre>
 *   SCHEDULED -> FUNDED -> PAID
 *        |
 *        +----> OVERDUE   (due date passed, no funds allocated)
 * </pre>
 *
 * <p>Two enums rather than one, on purpose. {@link ObligationStatus} is what is stored and what
 * transitions; this is what is true when you look. An enum containing a value that can never be
 * written is a trap for whoever writes the next repository method, so the two are kept apart and
 * {@link Obligation#stateOn} is the only bridge.
 *
 * <p>Note where the OVERDUE arrow comes from in SPEC.md section 7: <b>SCHEDULED, not FUNDED.</b>
 * An obligation whose funds are set aside is not overdue when its date passes - it is funded and
 * unpaid, which is an operational matter. Unfunded and past due is a planning failure, and those
 * are different things.
 */
public enum ObligationState {

    SCHEDULED,
    FUNDED,
    PAID,

    /**
     * Due date passed with nothing allocated. SPEC.md section 7: "this is a bug in the agent's
     * planning, and must be visible as one."
     */
    OVERDUE
}

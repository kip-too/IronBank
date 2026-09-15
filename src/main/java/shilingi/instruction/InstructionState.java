package shilingi.instruction;

/**
 * SPEC.md section 7's instruction machine, as stored.
 *
 * <pre>
 *   CREATED
 *      |  submitted to rail
 *      v
 *   SUBMITTED --------------+
 *      |                    |  rail says nothing, timeout expires
 *      | rail confirms      v
 *      |              AWAITING_RESOLUTION   &lt;- not terminal, and not failure
 *      |                    |
 *      |                    |  later evidence arrives
 *      |       +------------+------------+
 *      v       v            v            v
 *   SETTLED  SETTLED      FAILED   MANUAL_REVIEW
 * </pre>
 *
 * <p>This enum exists for the database column and for reading. <b>It is not what enforces the
 * machine.</b> The transitions live on the types in {@link Instruction}, so that an illegal move
 * does not compile - see that interface, and ADR-020.
 */
public enum InstructionState {

    /** Written, not yet sent anywhere. */
    CREATED,

    /** Sent to the rail. The outcome is not yet known, but we know we sent it. */
    SUBMITTED,

    /**
     * The rail said nothing and the timeout expired.
     *
     * <p>SPEC.md section 7: "not terminal, and not failure". PROBLEM.md I7: "A payment
     * instruction whose outcome is unknown is never retried. Unknown is not failure."
     *
     * <p>SPEC.md section 7 again: "this is the state that most systems do not have, and its
     * absence is why they double-pay. An instruction in this state is never retried. It is
     * re-queried. The only exits are evidence or a human."
     */
    AWAITING_RESOLUTION,

    /** The money moved. Terminal. */
    SETTLED,

    /** The money did not move, and we know that. Terminal unless deliberately retried. */
    FAILED,

    /** Nobody can tell. A human must look. Terminal as far as this system is concerned. */
    MANUAL_REVIEW
}

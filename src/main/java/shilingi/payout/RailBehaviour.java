package shilingi.payout;

/**
 * How {@link MockPayoutRail} misbehaves. SPEC.md section 12 lists these six exactly, and is
 * emphatic about why:
 *
 * <blockquote>
 * "Those last three are not decoration. They are the failures that produce F5, and a mock that
 * cannot produce them cannot prove the system survives them."
 * </blockquote>
 */
public enum RailBehaviour {

    /** Accept, then call back saying it worked. */
    SUCCEED,

    /** Accept, then call back saying it did not. */
    FAIL,

    /**
     * Accept, and then never call back at all.
     *
     * <p>This is the one that produces AWAITING_RESOLUTION, and therefore the one that separates
     * this system from the ones that double-pay.
     */
    NEVER_REPLY,

    /** Accept, then deliver the same callback twice - same rail reference, same everything. */
    CALLBACK_TWICE,

    /** Accept several, then deliver their callbacks in reverse order. */
    CALLBACK_OUT_OF_ORDER,

    /** Call back about an instruction this rail was never given. */
    CALLBACK_FOR_UNKNOWN_INSTRUCTION,

    /**
     * Accept, then call back with the right reference and the wrong amount.
     *
     * <p>A seventh, beyond SPEC.md section 12's six. SPEC.md section 17 scenario 4 asks for it and
     * SPEC.md section 13 states the rule it tests - "amounts differ, exception, always, regardless
     * of size" - so the mock has to be able to produce it.
     */
    CALLBACK_WITH_WRONG_AMOUNT
}

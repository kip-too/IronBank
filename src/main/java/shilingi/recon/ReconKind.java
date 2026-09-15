package shilingi.recon;

/**
 * The four ways SPEC.md section 13's three-way match can fail.
 *
 * <pre>
 *   INTENT      what we meant to do
 *   SETTLEMENT  what the rail says happened
 *   LEDGER      what the books say
 * </pre>
 */
public enum ReconKind {

    /**
     * A settlement with no intent behind it. SPEC.md section 13 sends these to suspense 1900.
     *
     * <p>PROBLEM.md F3: money with no link to the obligation it settles. "Matching becomes a
     * monthly memory exercise, and old items are quietly abandoned."
     */
    UNMATCHED_INBOUND,

    /**
     * Right reference, wrong amount. SPEC.md section 13: "an exception, always, regardless of
     * size." One cent counts.
     */
    AMOUNT_MISMATCH,

    /**
     * An intent with no settlement, older than the threshold.
     *
     * <p>Below the threshold this is money in flight and nobody needs to know. Above it, somebody
     * does.
     */
    IN_FLIGHT_STALE,

    /** A callback that arrived, was recorded, and could not be acted on. */
    CALLBACK_NOT_APPLIED
}

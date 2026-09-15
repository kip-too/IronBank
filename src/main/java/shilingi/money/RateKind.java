package shilingi.money;

/**
 * Whether a rate is the middle of the market, or the one actually obtained.
 *
 * <p>PROBLEM.md section 4 defines both, and the gap between them is the whole of the spread:
 * a cost that never appears as a fee because it was baked into the rate.
 */
public enum RateKind {

    /**
     * The reference rate, the middle of the market. <b>Nobody actually trades at it.</b> It
     * exists so you can measure how far from it you traded (PROBLEM.md section 4).
     */
    MID,

    /** The rate actually obtained. */
    EXECUTED
}

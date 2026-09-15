package shilingi.money;

/**
 * The currencies this system knows about, with the scale of their minor unit.
 *
 * <p>From SPEC.md section 5:
 * <pre>
 *   KES   cent           scale 2
 *   USDC  micro-dollar   scale 6
 * </pre>
 *
 * <p>KES is the functional currency (PROBLEM.md section 4). Every posting ultimately
 * carries a shilling value.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>USD is deliberately absent.</b> SPEC.md section 6 gives account 1200 Receivables
 *       the currency USD, but section 5 defines a minor unit for KES and USDC only. Adding
 *       a USD constant here would require inventing its scale, and would also decide a
 *       question that is still open: whether USD and USDC are one currency for the purpose
 *       of the per-currency balance check (invariant I2). The worked example in
 *       PROBLEM.md section 5 day 12 debits the USDC wallet and credits a USD receivable in
 *       the same entry, which does not balance per currency if the two codes are distinct.
 *       Raised with Kurgat; not guessed here.</li>
 *   <li>No ISO 4217 numeric codes, no symbols, no locale-aware names. Nothing needs them yet.</li>
 * </ul>
 */
public enum Currency {

    /** Kenyan shilling. Functional currency. Minor unit: cent. */
    KES(2),

    /** USD Coin. Foreign currency as held. Minor unit: micro-dollar. */
    USDC(6);

    private final int scale;

    Currency(int scale) {
        this.scale = scale;
    }

    /**
     * Number of decimal places between the major unit and the minor unit this currency is
     * stored in. KES is stored in cents, so 2. USDC is stored in micro-dollars, so 6.
     */
    public int scale() {
        return scale;
    }
}

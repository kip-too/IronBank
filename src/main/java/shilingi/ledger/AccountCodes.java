package shilingi.ledger;

/**
 * The account codes from SPEC.md section 6, named.
 *
 * <p>These are not magic strings invented here - every one is written in the specification's
 * chart of accounts and seeded by migration V2. Naming them means a rule like I5 can be
 * expressed as {@code EXCHANGE_DIFFERENCE_REALISED} rather than as {@code "6100"} scattered
 * through several files, and it means a typo is a compile error rather than a posting to an
 * account that does not exist.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li>This is a convenience for referring to accounts, not a second source of truth about
 *       them. The chart lives in the database. {@code ChartOfAccountsTest} is what proves the
 *       two agree.</li>
 * </ul>
 */
public final class AccountCodes {

    private AccountCodes() {
    }

    public static final String BANK_KES = "1000";
    public static final String WALLET_USDC = "1100";
    public static final String RECEIVABLES = "1200";
    public static final String SUSPENSE = "1900";
    public static final String PAYABLES = "2000";
    public static final String REVENUE = "4000";

    /** Realised exchange difference. The money moved or converted. */
    public static final String EXCHANGE_DIFFERENCE_REALISED = "6100";

    /** Unrealised exchange difference. Nothing moved; we re-measured what we are still holding. */
    public static final String EXCHANGE_DIFFERENCE_UNREALISED = "6110";

    /** What the provider took quietly, inside the rate. */
    public static final String CONVERSION_SPREAD = "6200";

    /** What the provider took openly, as a separate charge. */
    public static final String CONVERSION_FEE = "6210";
}

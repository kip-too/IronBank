package shilingi.ledger;

/**
 * The kinds of account in the chart. Taken from SPEC.md section 6, which labels each of the ten
 * accounts as asset, liability, income, expense or other.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li>No normal-balance rule attached to the type. Nothing in SPEC.md says an asset may not
 *       carry a credit balance, and for a wallet that is drawn down it plainly may. Attaching
 *       a sign rule to the type would be inventing one.</li>
 *   <li>No statement classification (which of these roll into profit and loss versus the
 *       balance sheet). No report needs it yet.</li>
 * </ul>
 */
public enum AccountType {
    ASSET,
    LIABILITY,
    INCOME,
    EXPENSE,

    /**
     * SPEC.md section 6 gives exchange difference, realised and unrealised, the type "other".
     * It is neither revenue nor an expense: the market moved, the business did not trade.
     * PROBLEM.md section 5 is emphatic about this - recording it as revenue overstates the top
     * line and hides an FX position.
     */
    OTHER
}

package shilingi.ledger;

import shilingi.money.Currency;

import java.util.Objects;

/**
 * One line of the chart of accounts (SPEC.md section 6).
 *
 * <p>Accounts are seeded by migration V2 and are read-only at runtime. There is deliberately no
 * way to create one from application code: an account that can be created on demand is an
 * account that can be invented to make a total agree, which is exactly what invariant I3
 * forbids.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li>No parent/child hierarchy or rollups. The chart is ten flat accounts.</li>
 *   <li>No opening balance. Balances are derived from the journal, and only from the journal -
 *       that is what makes replay (SPEC.md section 14) mean something.</li>
 *   <li>No active/closed flag. Nothing closes an account yet.</li>
 * </ul>
 *
 * @param code       the four-digit code, e.g. {@code "6100"}. This is the identity of the
 *                   account, in the database as well as here.
 * @param name       as written in SPEC.md section 6.
 * @param type       asset, liability, income, expense or other.
 * @param currency   the one currency this account is denominated in. A posting to it must be
 *                   in this currency - enforced by a composite foreign key in V3.
 * @param suspense   true only for 1900. PROBLEM.md section 4: suspense is not a bin, every item
 *                   in it is a question with a date attached.
 */
public record Account(String code, String name, AccountType type, Currency currency, boolean suspense) {

    public Account {
        Objects.requireNonNull(code, "code is required");
        Objects.requireNonNull(name, "name is required");
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(currency, "currency is required");
    }
}

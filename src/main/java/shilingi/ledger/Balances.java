package shilingi.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import shilingi.money.Currency;
import shilingi.money.Money;

/**
 * Balances, derived from the journal and from nothing else.
 *
 * <p>SPEC.md section 14: replay "must not read anything outside the journal". These queries are
 * the shape replay will use - they sum postings, and there is no stored balance anywhere for
 * them to disagree with. An account's balance is not a fact this system keeps; it is a fact this
 * system can always work out.
 *
 * <h2>Two different balances, and they are not interchangeable</h2>
 * A foreign-currency account has two of them, and confusing them is how a wallet ends up
 * carrying a value it does not hold:
 * <ul>
 *   <li>{@link #balanceOf} sums {@code amount_minor} - how many dollars are actually there.</li>
 *   <li>{@link #carryingValueOf} sums {@code functional_amount_minor} - what those dollars are
 *       carried at in the books, which is the sum of what each receipt was worth when it
 *       arrived, not what they are worth today.</li>
 * </ul>
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No as-at date.</b> These are balances now, over the whole journal. Replay (day 13)
 *       needs balances as at a business date and will add that.</li>
 *   <li>No caching, and no stored balance column. A stored balance is a second answer to a
 *       question that already has one.</li>
 * </ul>
 */
@Component
public class Balances {

    private final JdbcTemplate jdbc;

    public Balances(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * How much of its own currency an account holds. For 1100 that is dollars, in micro-dollars.
     *
     * @param currency the account's currency, which is what the returned amount is denominated in
     */
    public Money balanceOf(String accountCode, Currency currency) {
        Long total = jdbc.queryForObject(
                "select coalesce(sum(amount_minor), 0) from posting where account_code = ?",
                Long.class, accountCode);
        return Money.of(total == null ? 0L : total, currency);
    }

    /**
     * What an account's holdings are carried at, in shillings. For a KES account this is the
     * same number as {@link #balanceOf}; for 1100 it is the weighted-average cost of the
     * dollars held, which is what the exchange difference on a conversion is measured against.
     */
    public Money carryingValueOf(String accountCode) {
        Long total = jdbc.queryForObject(
                "select coalesce(sum(functional_amount_minor), 0) from posting where account_code = ?",
                Long.class, accountCode);
        return Money.of(total == null ? 0L : total, Currency.KES);
    }
}

package shilingi.replay;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import shilingi.ledger.Balances;
import shilingi.money.Currency;
import shilingi.money.Money;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reconstructs any day's closing position from the journal, and from nothing else.
 *
 * <p>SPEC.md section 14, and invariant I10 - which PROBLEM.md section 6 calls "the one to keep
 * coming back to. It is the whole point. A reviewer in March should be able to replay September
 * and get the same numbers."
 *
 * <h2>What SPEC.md section 14 asks for, and what this design can honestly give</h2>
 * Section 14 says to reconstruct balances "then compare with the stored balances". <b>There are
 * no stored balances.</b> {@link Balances} derives every figure by summing postings, deliberately
 * - a stored balance is a second answer to a question that already has one, and the second answer
 * is the one that goes wrong quietly.
 *
 * <p>So a naive reading of section 14 would have this compare {@code sum(postings)} against
 * {@code sum(postings)} and call the tautology a pass. Instead it does three things, and they are
 * worth different amounts:
 *
 * <ol>
 *   <li><b>An entry-by-entry fold, checked against the aggregate query.</b> Two genuinely
 *       different code paths over the same rows - a fold in Java against a {@code sum()} in
 *       PostgreSQL. Weak, but it is a real disagreement if they ever differ.</li>
 *   <li><b>Every entry re-checked, not trusted.</b> Balance in shillings, and balance per currency
 *       where the entry is single-currency. The ledger checked these when they were written;
 *       this checks them again from what is actually stored, and names the entry if one fails.</li>
 *   <li><b>The strong one: proof that the answer depends on nothing outside the journal.</b>
 *       This class touches {@code journal_entry} and {@code posting} and no other table. Not the
 *       rate feed, not the wallet, not the instruction table, not the rail.
 *       {@code ReplayTest.the_rate_feed_can_be_deleted_and_replay_is_unchanged} deletes every
 *       rate and replays to the same figures. Section 14: "The moment replay reaches outside the
 *       records, it stops proving anything."</li>
 * </ol>
 *
 * <h2>Does this prove correctness, or only consistency?</h2>
 * PROBLEM.md section 8 question 8 asks exactly that, and the honest answer is <b>consistency and
 * reproducibility, not correctness</b>.
 *
 * <p>Replay cannot tell you the rate on the 12th was really 131.50. It can tell you that whatever
 * rate was used is still on the posting, that the entry balanced then and balances now, that
 * nothing has been edited since, and that September's closing position can be produced in March
 * without anyone remembering anything. Correctness of the inputs is what the rate source, the
 * reconciler and a human are for.
 *
 * <p>That is a smaller claim than "replay proves the books are right", and it is the one that is
 * true. Saying it plainly is worth more than a green tick that means less than it looks.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No streaming.</b> The whole journal is read into memory. Fine at this size; a journal
 *       longer than memory needs a cursor, and the fold itself would not change.</li>
 *   <li><b>No verification of the rate against any source.</b> Deliberately - see above.</li>
 *   <li><b>No reversal awareness.</b> SPEC.md section 17 scenario 19 is replay after a correcting
 *       entry. Nothing special is needed: a reversal is two ordinary entries and the fold sums
 *       them like any others, which is exactly the property that makes corrections safe. There is
 *       a test.</li>
 * </ul>
 */
@Service
public class Replay {

    private final JdbcTemplate jdbc;
    private final Balances balances;

    public Replay(JdbcTemplate jdbc, Balances balances) {
        this.jdbc = jdbc;
        this.balances = balances;
    }

    /** A place where the records disagree with themselves. Always names the entry, per section 14. */
    public record Discrepancy(String what, Long entryId, String accountCode,
                              Money replayed, Money stored) {

        @Override
        public String toString() {
            return what + (entryId == null ? "" : " [entry " + entryId + "]")
                   + (accountCode == null ? "" : " [account " + accountCode + "]")
                   + (replayed == null ? "" : " replayed=" + replayed)
                   + (stored == null ? "" : " stored=" + stored);
        }
    }

    /**
     * @param functionalBalances account code to shilling balance, folded entry by entry
     * @param currencyBalances   account code to balance in that account's own currency
     * @param discrepancies      empty means the records agree with themselves
     */
    public record Result(LocalDate asAt, int entriesRead, int postingsRead,
                         Map<String, Money> functionalBalances,
                         Map<String, Money> currencyBalances,
                         List<Discrepancy> discrepancies) {

        public boolean reproduced() {
            return discrepancies.isEmpty();
        }

        /** Every entry in the journal sums to zero in shillings, so the whole journal does too. */
        public Money totalInShillings() {
            Money total = Money.zero(Currency.KES);
            for (Money balance : functionalBalances.values()) {
                total = total.plus(balance);
            }
            return total;
        }
    }

    /** The position as at the end of a business date. */
    public Result asAt(LocalDate asAt) {
        // The ONLY two tables this class reads. Adding a third would quietly end the guarantee.
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select p.entry_id,
                       p.account_code,
                       p.amount_minor,
                       p.currency,
                       p.functional_amount_minor,
                       e.business_date
                  from posting p
                  join journal_entry e on e.id = p.entry_id
                 where e.business_date <= ?
                 order by e.business_date, e.id, p.id
                """, asAt);

        Map<String, Money> functional = new TreeMap<>();
        Map<String, Money> byCurrency = new TreeMap<>();
        Map<Long, Money> entryShillings = new LinkedHashMap<>();
        Map<Long, Map<Currency, Money>> entryByCurrency = new LinkedHashMap<>();

        for (Map<String, Object> row : rows) {
            long entryId = ((Number) row.get("entry_id")).longValue();
            String account = (String) row.get("account_code");
            Currency currency = Currency.valueOf((String) row.get("currency"));
            Money amount = Money.of(((Number) row.get("amount_minor")).longValue(), currency);
            Money shillings = Money.of(
                    ((Number) row.get("functional_amount_minor")).longValue(), Currency.KES);

            functional.merge(account, shillings, Money::plus);
            byCurrency.merge(account, amount, Money::plus);

            entryShillings.merge(entryId, shillings, Money::plus);
            entryByCurrency.computeIfAbsent(entryId, id -> new LinkedHashMap<>())
                    .merge(currency, amount, Money::plus);
        }

        List<Discrepancy> discrepancies = new ArrayList<>();
        checkEachEntry(entryShillings, entryByCurrency, discrepancies);
        checkAgainstTheAggregate(asAt, functional, byCurrency, discrepancies);

        return new Result(asAt, entryShillings.size(), rows.size(),
                Map.copyOf(functional), Map.copyOf(byCurrency), List.copyOf(discrepancies));
    }

    /** The whole journal, to today. */
    public Result all() {
        return asAt(LocalDate.MAX);
    }

    /**
     * Invariant I2, re-derived rather than trusted.
     *
     * <p>The ledger refused an unbalanced entry when it was written and the database refused one
     * at commit. This asks the same question of what is actually stored now - which is a different
     * question, because it would catch a row that got past both.
     */
    private void checkEachEntry(Map<Long, Money> entryShillings,
                                Map<Long, Map<Currency, Money>> entryByCurrency,
                                List<Discrepancy> discrepancies) {
        entryShillings.forEach((entryId, total) -> {
            if (!total.isZero()) {
                discrepancies.add(new Discrepancy(
                        "entry does not balance in shillings", entryId, null, total, null));
            }
        });

        entryByCurrency.forEach((entryId, totals) -> {
            if (totals.size() != 1) {
                // Cross-currency entries balance in shillings only - ADR-013 sets out why.
                return;
            }
            totals.forEach((currency, total) -> {
                if (!total.isZero()) {
                    discrepancies.add(new Discrepancy(
                            "single-currency entry does not balance in " + currency,
                            entryId, null, total, null));
                }
            });
        });
    }

    /**
     * The fold, against the aggregate.
     *
     * <p>Only meaningful for a replay of the whole journal: {@link Balances} has no as-at date, so
     * comparing a partial replay against it would compare two different questions.
     */
    private void checkAgainstTheAggregate(LocalDate asAt, Map<String, Money> functional,
                                          Map<String, Money> byCurrency,
                                          List<Discrepancy> discrepancies) {
        if (!asAt.equals(LocalDate.MAX)) {
            return;
        }

        functional.forEach((account, replayed) -> {
            Money stored = balances.carryingValueOf(account);
            if (!replayed.equals(stored)) {
                discrepancies.add(new Discrepancy(
                        "folded shilling balance disagrees with the aggregate query",
                        null, account, replayed, stored));
            }
        });

        byCurrency.forEach((account, replayed) -> {
            Money stored = balances.balanceOf(account, replayed.currency());
            if (!replayed.equals(stored)) {
                discrepancies.add(new Discrepancy(
                        "folded currency balance disagrees with the aggregate query",
                        null, account, replayed, stored));
            }
        });
    }
}

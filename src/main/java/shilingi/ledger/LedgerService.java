package shilingi.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import shilingi.clock.ClockPort;
import shilingi.money.Currency;
import shilingi.money.Money;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The only way into the journal.
 *
 * <p>SPEC.md section 8: "The ledger's job is to refuse things." Everything below is a refusal;
 * the single line that writes is at the bottom, and it is reached only by an entry that has
 * survived all seven checks.
 *
 * <h2>The order of the checks is fixed, and deliberate</h2>
 * An entry can break several rules at once. SPEC.md section 8 requires a distinct exception per
 * case, so one of them has to be thrown first, and which one must not vary between runs - a bad
 * entry that reports a different rule each time makes tests flaky and reviews confusing.
 *
 * <p>The order runs from <b>checks that make the entry meaningless</b> to <b>checks that make it
 * wrong</b>:
 * <ol>
 *   <li>fewer than two postings - one line is not an entry at all</li>
 *   <li>business date in the future - the entry could not have happened</li>
 *   <li>unknown account - the line points nowhere</li>
 *   <li>missing rate (I1) - the line's shilling value cannot be re-derived</li>
 *   <li>realised and unrealised together (I5) - the entry mixes two kinds of fact</li>
 *   <li>unbalanced within a single currency (I2) - the more specific arithmetic failure</li>
 *   <li>unbalanced in shillings (I2) - the universal one</li>
 * </ol>
 *
 * <h2>Nothing is written unless everything passes</h2>
 * Every check runs before any insert, inside one transaction. A rejection therefore leaves no
 * trace: no half-written entry, no orphan postings, nothing to clean up.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No reversal or correction helper.</b> SPEC.md section 6 says a correction is a new
 *       entry that reverses and re-posts; building the helper that constructs one is not day 3's
 *       work and would need a rule about how a reversal is dated, which SPEC.md does not give.</li>
 *   <li><b>No check that a posting's functional amount follows from its own rate.</b> That is the
 *       round-once conversion rule and it lands on day 4. Until then an entry can balance in
 *       shillings while an individual line's shilling value does not follow from its rate.</li>
 *   <li><b>No check on which accounts may legitimately face each other.</b> Nothing stops an
 *       entry debiting revenue and crediting suspense. SPEC.md gives no such rule, and inventing
 *       one would be inventing a financial rule.</li>
 *   <li>No batch posting, no partial success. One entry at a time, all or nothing.</li>
 * </ul>
 */
@Service
public class LedgerService {

    private final ClockPort clock;
    private final AccountRepository accounts;
    private final JournalStore journal;

    public LedgerService(ClockPort clock, AccountRepository accounts, JournalStore journal) {
        this.clock = clock;
        this.accounts = accounts;
        this.journal = journal;
    }

    /**
     * Checks an entry against every rule and writes it, or refuses it and writes nothing.
     *
     * @return the entry as stored, with database identities filled in
     * @throws LedgerRejection one of seven distinct subclasses, naming the rule that was broken
     */
    @Transactional
    public JournalEntry post(JournalEntry entry) {
        if (entry.id() != null) {
            throw new IllegalArgumentException(
                    "Entry " + entry.id() + " has already been posted. Postings are immutable; "
                    + "a correction is a new entry that reverses and re-posts.");
        }

        rejectTooFewPostings(entry);
        rejectFutureBusinessDate(entry);
        rejectUnknownAccounts(entry);
        rejectMissingRates(entry);
        rejectRealisedAndUnrealisedTogether(entry);
        rejectUnbalancedWithinASingleCurrency(entry);
        rejectUnbalancedInShillings(entry);

        return journal.append(entry);
    }

    private void rejectTooFewPostings(JournalEntry entry) {
        if (entry.postings().size() < 2) {
            throw new LedgerRejection.TooFewPostings(entry.postings().size());
        }
    }

    private void rejectFutureBusinessDate(JournalEntry entry) {
        // SPEC.md section 4: the clock is injected. Never LocalDate.now().
        var today = clock.businessDate();
        if (entry.businessDate().isAfter(today)) {
            throw new LedgerRejection.FutureBusinessDate(entry.businessDate(), today);
        }
    }

    private void rejectUnknownAccounts(JournalEntry entry) {
        for (Posting posting : entry.postings()) {
            if (accounts.findByCode(posting.accountCode()).isEmpty()) {
                throw new LedgerRejection.UnknownAccount(posting.accountCode());
            }
        }
    }

    /**
     * Invariant I1.
     *
     * <p><b>Honest note:</b> this branch is currently unreachable through the public API, because
     * {@link Posting}'s compact constructor throws {@link MissingRateException} before an invalid
     * posting can be built - which is the stronger guard, and the one SPEC.md section 9's design
     * note about I7 argues for in general ("make the transition unrepresentable, not merely
     * unused"). It is kept because SPEC.md section 8 requires the ledger to reject this case, and
     * because a later change to {@code Posting} should not silently remove the rule. The database
     * check constraint is the third layer.
     */
    private void rejectMissingRates(JournalEntry entry) {
        for (Posting posting : entry.postings()) {
            if (posting.amount().currency() != Currency.KES
                && (posting.rateValue() == null
                    || posting.rateSource() == null
                    || posting.rateTimestamp() == null)) {
                throw new MissingRateException(posting.accountCode(), posting.amount().currency());
            }
        }
    }

    private void rejectRealisedAndUnrealisedTogether(JournalEntry entry) {
        Set<String> codes = new HashSet<>();
        for (Posting posting : entry.postings()) {
            codes.add(posting.accountCode());
        }
        if (codes.contains(AccountCodes.EXCHANGE_DIFFERENCE_REALISED)
            && codes.contains(AccountCodes.EXCHANGE_DIFFERENCE_UNREALISED)) {
            throw new LedgerRejection.RealisedAndUnrealisedTogether();
        }
    }

    /**
     * Invariant I2, first half - but only for entries that are wholly in one currency.
     *
     * <p>A cross-currency entry is not required to balance in each currency, and cannot be: not
     * one of the four entries in PROBLEM.md section 5 does. See ADR-013, which sets out the
     * arithmetic. For a single-currency entry the check does real work - two USDC postings can
     * balance in dollars while disagreeing in shillings, and the reverse - so both halves of I2
     * are live.
     */
    private void rejectUnbalancedWithinASingleCurrency(JournalEntry entry) {
        Map<Currency, Money> totals = new EnumMap<>(Currency.class);
        for (Posting posting : entry.postings()) {
            Currency currency = posting.amount().currency();
            totals.merge(currency, posting.amount(), Money::plus);
        }

        if (totals.size() != 1) {
            return;
        }

        Map.Entry<Currency, Money> only = totals.entrySet().iterator().next();
        if (!only.getValue().isZero()) {
            throw new LedgerRejection.UnbalancedInCurrency(only.getKey(), only.getValue().minorUnits());
        }
    }

    /** Invariant I2, second half. The universal one: every entry balances in shillings. */
    private void rejectUnbalancedInShillings(JournalEntry entry) {
        Money total = Money.zero(Currency.KES);
        for (Posting posting : entry.postings()) {
            total = total.plus(posting.functionalAmount());
        }
        if (!total.isZero()) {
            throw new LedgerRejection.UnbalancedInShillings(total.minorUnits());
        }
    }
}

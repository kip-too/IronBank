package shilingi.books;

import org.springframework.stereotype.Service;

import shilingi.clock.ClockPort;
import shilingi.ledger.AccountCodes;
import shilingi.ledger.Balances;
import shilingi.ledger.JournalEntry;
import shilingi.ledger.LedgerService;
import shilingi.ledger.Posting;
import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.money.Rate;
import shilingi.rate.RatePort;
import shilingi.receivables.Receivable;
import shilingi.receivables.ReceivableRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * Turns the business events in PROBLEM.md section 5 into journal entries.
 *
 * <p>Until this existed, receivables and obligations were records with state machines and no
 * consequence in the books - the gap flagged from day 6 onwards. This closes it for the three
 * events the worked example actually contains.
 *
 * <h2>Which rate each event uses, and why</h2>
 * <ul>
 *   <li><b>An invoice</b> uses the mid rate <i>on its issue date</i>. PROBLEM.md section 5 day 1:
 *       "USD 10,000 invoiced. Rate that day: 129.00."</li>
 *   <li><b>A receipt</b> uses the mid rate <i>on the day the money arrived</i> for what came in,
 *       and the <b>receivable's own issue-date rate</b> for what is being cleared. The difference
 *       between those two is the exchange difference, and it is the entire point of day 12:
 *       "the dollar simply strengthened between the day the invoice was raised and the day it was
 *       paid."</li>
 *   <li><b>A revaluation</b> uses the closing mid rate against the wallet's weighted-average
 *       carrying value.</li>
 * </ul>
 *
 * <p>No rate is ever guessed. If the feed has no quote for the date an event belongs to, the
 * posting is refused - see ADR-015 and ADR-019, and {@link NoRateForDateException}.
 *
 * <h2>What this deliberately does NOT do: post a payout</h2>
 * Paying an obligation is two entries, and the first of them cannot be written:
 *
 * <pre>
 *   accrue:  Dr &lt;payroll expense&gt;   Cr 2000 Payables      &lt;-- no such account exists
 *   pay:     Dr 2000 Payables        Cr 1000 Bank
 * </pre>
 *
 * <p>SPEC.md section 6's chart has no expense account for payroll, rent, suppliers or statutory
 * outgoings. Every account in it exists because of a rule in section 9, and "what the business
 * spends money on" is not one of those rules.
 *
 * <p>Posting only the payment half would leave account 2000 carrying a debit balance - a figure
 * that is wrong, in an account that should never hold it, and which somebody would eventually
 * "correct". That is a plug with a respectable name, and invariant I3 forbids it.
 *
 * <p>So payouts are not posted, and <b>this is raised as a finding rather than worked around.</b>
 * Note that PROBLEM.md section 5 never posts one either: its day 20 is the <i>conversion</i> that
 * funds payroll, not the payroll payment. The worked example is complete without it. See ADR-025.
 *
 * <h2>What else this does not handle</h2>
 * <ul>
 *   <li><b>No partial settlement of a receivable.</b> A receipt clears an invoice or it does not.
 *       A part payment would reach the reconciler as an amount that does not match, which SPEC.md
 *       section 13 says is "an exception, always".</li>
 *   <li><b>No credit notes</b>, so an invoice raised in error cannot be reversed by a business
 *       event - only by a correcting journal entry made by hand.</li>
 *   <li><b>Nothing marks obligations FUNDED.</b> Which obligation gets which shillings is SPEC.md
 *       section 17 scenario 16's question, unanswered anywhere.</li>
 * </ul>
 */
@Service
public class Bookkeeper {

    private final LedgerService ledger;
    private final RatePort rates;
    private final Balances balances;
    private final ReceivableRepository receivables;
    private final ClockPort clock;

    public Bookkeeper(LedgerService ledger, RatePort rates, Balances balances,
                      ReceivableRepository receivables, ClockPort clock) {
        this.ledger = ledger;
        this.rates = rates;
        this.balances = balances;
        this.receivables = receivables;
        this.clock = clock;
    }

    /**
     * PROBLEM.md section 5 day 1: an invoice is raised.
     *
     * <pre>
     *   Dr 1200 Receivables    USD 10,000 at 129.00   1,290,000
     *     Cr 4000 Revenue                             1,290,000
     * </pre>
     *
     * <p>The receivable becomes "a shilling number AND a dollar number. Both are true. Both are
     * stored" - which is exactly what a {@link Posting} holds.
     */
    public JournalEntry recordInvoice(Receivable receivable) {
        Rate rate = rateOn(receivable.issueDate());
        Money shillingValue = rate.toShillings(receivable.amount());

        return ledger.post(JournalEntry.of(
                receivable.issueDate(),
                "Invoice to " + receivable.counterparty() + " for " + receivable.amount(),
                sourceRefFor(receivable),
                clock.instant(),
                List.of(
                        foreign(AccountCodes.RECEIVABLES, receivable.amount(), rate, shillingValue),
                        Posting.inShillings(AccountCodes.REVENUE, shillingValue.negate()))));
    }

    /**
     * PROBLEM.md section 5 day 12: the money arrives.
     *
     * <pre>
     *   Dr 1100 Wallet — USDC     10,000 at 131.50    1,315,000
     *     Cr 1200 Receivables     10,000 at 129.00    1,290,000
     *     Cr 6100 Exchange difference (realised)         25,000
     * </pre>
     *
     * <p><b>The 25,000 is not revenue.</b> The business did not sell anything extra; the dollar
     * strengthened between the day the invoice was raised and the day it was paid. PROBLEM.md
     * calls booking it as revenue "the single most common error in a small set of books", and the
     * reason it is not possible here is that the credit goes to 6100 by construction.
     *
     * @param receivableId the invoice this receipt settles. A receipt with no invoice is an orphan
     *                     credit (PROBLEM.md F3) and belongs in suspense, not here.
     * @param receivedOn   the day the money arrived, whose rate values it
     */
    public JournalEntry recordReceipt(long receivableId, LocalDate receivedOn) {
        Receivable receivable = receivables.findById(receivableId).orElseThrow(() ->
                new IllegalArgumentException("No receivable " + receivableId));

        if (!receivable.isOutstanding()) {
            throw new IllegalStateException(
                    "Receivable " + receivableId + " is already settled. A second receipt against it "
                    + "is a reconciliation exception, not a repeat settlement (PROBLEM.md F3).");
        }

        Rate receiptRate = rateOn(receivedOn);
        Rate issueRate = rateOn(receivable.issueDate());

        Money received = receiptRate.toShillings(receivable.amount());
        Money carried = issueRate.toShillings(receivable.amount());
        Money exchangeDifference = received.minus(carried);

        List<Posting> postings = new java.util.ArrayList<>(3);
        postings.add(foreign(AccountCodes.WALLET_USDC, receivable.amount(), receiptRate, received));
        postings.add(foreign(AccountCodes.RECEIVABLES, receivable.amount().negate(), issueRate,
                carried.negate()));

        if (!exchangeDifference.isZero()) {
            // A gain is a credit, so the sign flips. When the dollar weakened instead, this is a
            // debit and nothing about the entry changes shape.
            postings.add(Posting.inShillings(AccountCodes.EXCHANGE_DIFFERENCE_REALISED,
                    exchangeDifference.negate()));
        }

        JournalEntry entry = ledger.post(JournalEntry.of(
                receivedOn,
                "Received " + receivable.amount() + " from " + receivable.counterparty(),
                sourceRefFor(receivable),
                clock.instant(),
                postings));

        receivables.settle(receivableId);
        return entry;
    }

    /**
     * PROBLEM.md section 5 day 30: month-end revaluation.
     *
     * <pre>
     *   Dr 6110 Exchange difference (unrealised)   2,800
     *     Cr 1100 Wallet — USDC                    2,800
     * </pre>
     *
     * <p>"Nothing moved. Nobody paid anybody. The books simply told the truth about what the
     * remaining dollars are worth today."
     *
     * <p>Two things make this different from every other entry here, and both are deliberate:
     * <ul>
     *   <li>It touches <b>6110 and never 6100</b>. Invariant I5 is enforced at the ledger, so an
     *       entry that touched both would be refused - one is history, the other is an opinion
     *       about today.</li>
     *   <li>The wallet posting moves <b>zero dollars</b> and a non-zero shilling value. That is
     *       what a revaluation is: the holding is unchanged and its worth is not.</li>
     * </ul>
     *
     * @return the entry, or empty when the wallet is empty or already carried at today's rate -
     *         an entry of zeroes says nothing and still has to be read by somebody
     */
    public java.util.Optional<JournalEntry> revalueWallet(LocalDate asAt) {
        Money held = balances.balanceOf(AccountCodes.WALLET_USDC, Currency.USDC);
        if (held.isZero()) {
            return java.util.Optional.empty();
        }

        Rate closing = rateOn(asAt);
        Money carried = balances.carryingValueOf(AccountCodes.WALLET_USDC);
        Money worthToday = closing.toShillings(held);
        Money movement = worthToday.minus(carried);

        if (movement.isZero()) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(ledger.post(JournalEntry.of(
                asAt,
                "Month-end revaluation of " + held + " at " + closing.value().toPlainString(),
                "revaluation/" + asAt,
                clock.instant(),
                List.of(
                        Posting.inShillings(AccountCodes.EXCHANGE_DIFFERENCE_UNREALISED,
                                movement.negate()),
                        new Posting(null, null, AccountCodes.WALLET_USDC,
                                Money.zero(Currency.USDC),
                                closing.value(), closing.source(), closing.timestamp(),
                                movement)))));
    }

    private Rate rateOn(LocalDate date) {
        return rates.midRateOn(date).orElseThrow(() -> new NoRateForDateException(date));
    }

    private static Posting foreign(String accountCode, Money amount, Rate rate, Money shillingValue) {
        return new Posting(null, null, accountCode, amount,
                rate.value(), rate.source(), rate.timestamp(), shillingValue);
    }

    private static String sourceRefFor(Receivable receivable) {
        return "receivable/" + receivable.id();
    }
}

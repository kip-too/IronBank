package shilingi.fx;

import org.springframework.stereotype.Service;

import shilingi.ledger.AccountCodes;
import shilingi.ledger.JournalEntry;
import shilingi.ledger.Posting;
import shilingi.money.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns a conversion that has happened into postings, keeping three facts apart that most
 * systems blend into one meaningless number.
 *
 * <p>SPEC.md section 10. PROBLEM.md section 5 is the acceptance test, and every figure in it -
 * 4,200, 2,400, and the fee - falls out of the two entries below.
 *
 * <h2>What the two entries are</h2>
 *
 * <p><b>Entry one, the movement.</b> Real money moved, so real accounts move:
 * <pre>
 *   Dr 1000 Bank — KES                  what the shillings actually came to
 *     Cr 1100 Wallet — USDC             the dollars, at what the books carried them at
 *     Cr 6100 Exchange difference       the difference between those two
 * </pre>
 *
 * <p><b>Entry two, the cost of conversion.</b> This is SPEC.md section 10's "separate memo
 * entry", and it balances like any other entry, because invariant I2 admits no exceptions:
 * <pre>
 *   Dr 6200 Conversion spread           what the provider took quietly, inside the rate
 *     Cr 6100 Exchange difference       ...which is where it was hiding
 *   Dr 6210 Conversion fee              what the provider took openly
 *     Cr 2000 Payables                  ...which is owed to them
 * </pre>
 *
 * <h2>Why the spread's other side is 6100</h2>
 * This is the part worth reading twice, and it is the reason the memo entry can balance at all
 * without inventing an account (see ADR-016).
 *
 * <p>The spread is <b>not additional money leaving</b>. Nobody bills for it. It is already
 * inside the exchange difference: the business received shillings at 132.20 when the market was
 * at 132.60, so the difference it booked is 2,400 smaller than the market alone would explain.
 * Posting {@code Dr 6200 / Cr 6100} does not add a cost - it <b>reclassifies</b> one that was
 * already there, moving it out of "what the market did to us" and into "what our provider took".
 *
 * <p>After both entries, account 6100 holds 6,600: the difference measured against the
 * mid-market rate, which is the honest measure of what the market did. Account 6200 holds 2,400.
 * The two net to 4,200, which is what actually happened. Three numbers, three causes, and the
 * business can act on each one differently - change provider, change timing, or accept it.
 *
 * <h2>Why the fee's other side is 2000 Payables</h2>
 * The fee is real money, unlike the spread. PROBLEM.md section 5 has the bank receive the full
 * 793,200 with no deduction, so the fee was not netted off what arrived - it is owed separately.
 * If a provider instead deducts its fee from the proceeds, that is a different fact and needs a
 * different entry; see ADR-016 for what would change.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No month-end revaluation.</b> SPEC.md section 15 makes it a stretch goal. It touches
 *       6110 only, never 6100, and moves no money.</li>
 *   <li><b>Nothing is posted here.</b> The engine produces entries; {@code LedgerService} is the
 *       only thing that writes them, and it will apply all seven of its checks to both.</li>
 *   <li><b>No decision about whether to convert.</b> That is the agent, on day 8. This engine is
 *       told what happened and records it.</li>
 *   <li><b>The fee is taken as charged, not computed.</b> SPEC.md section 10 says "fee = as
 *       charged". Note that PROBLEM.md section 5 states a fee of 7,139 shillings where 0.9% of
 *       793,200 is exactly 7,138.80 - see the test and ADR-016; nothing here rounds a fee,
 *       because nothing here derives one.</li>
 * </ul>
 */
@Service
public class FxEngine {

    private final CarryingValuePolicy carryingValue;

    public FxEngine(CarryingValuePolicy carryingValue) {
        this.carryingValue = carryingValue;
    }

    /**
     * @param facts     what happened
     * @param recordedAt the moment the record is being written, from the injected clock
     */
    public ConversionPostings postingsFor(ConversionFacts facts, Instant recordedAt) {
        Instant at = facts.executed().timestamp();

        CarryingValuation carried = carryingValue.valueLeaving(facts.converted(), at);

        Money received = facts.executed().toShillings(facts.converted());
        Money atMid = facts.mid().toShillings(facts.converted());

        // Realised exchange difference: what the shillings came to, less what the dollars were
        // carried at. PROBLEM.md section 5 day 20: 793,200 - 789,000 = 4,200.
        Money exchangeDifference = received.minus(carried.shillingValue());

        // Spread: the difference between what the market was at and what we got.
        //
        // Defined as (value at mid) - (value at executed), two round-once conversions
        // subtracted - NOT as a separate multiplication of (mid - executed) by the dollars.
        // The two agree to within a cent, and that cent matters: this definition makes
        //     exchangeDifference + spread == atMid - carryingValue
        // true exactly, by arithmetic. The other definition makes it true usually, and the
        // odd stray cent would have nowhere to go except an invented entry. See ADR-016.
        Money spread = atMid.minus(received);

        return new ConversionPostings(
                movementEntry(facts, carried, received, exchangeDifference, recordedAt),
                costOfConversionEntry(facts, spread, recordedAt));
    }

    private JournalEntry movementEntry(ConversionFacts facts, CarryingValuation carried,
                                       Money received, Money exchangeDifference, Instant recordedAt) {
        List<Posting> postings = new ArrayList<>(3);

        postings.add(Posting.inShillings(AccountCodes.BANK_KES, received));

        // The dollars leave, at what the books carried them at - not at what they just sold for.
        postings.add(new Posting(null, null, AccountCodes.WALLET_USDC,
                facts.converted().negate(),
                carried.rate().value(),
                carried.rate().source(),
                carried.rate().timestamp(),
                carried.shillingValue().negate()));

        // A gain is a credit, so the sign flips. PROBLEM.md section 5 is emphatic that this is
        // not revenue: the business did not sell anything extra.
        postings.add(Posting.inShillings(AccountCodes.EXCHANGE_DIFFERENCE_REALISED,
                exchangeDifference.negate()));

        return JournalEntry.of(facts.businessDate(),
                "Converted " + facts.converted() + " at " + facts.executed().value().toPlainString(),
                facts.sourceRef(), recordedAt, postings);
    }

    /**
     * SPEC.md section 10's memo entry. Empty when there is nothing to say: a conversion exactly
     * at mid with no fee charged produces no entry rather than an entry full of zeroes.
     */
    private Optional<JournalEntry> costOfConversionEntry(ConversionFacts facts, Money spread,
                                                         Instant recordedAt) {
        if (spread.isZero() && facts.fee().isZero()) {
            return Optional.empty();
        }

        List<Posting> postings = new ArrayList<>(4);

        if (!spread.isZero()) {
            // A negative spread means the executed rate beat the mid. SPEC.md section 10:
            // "record it as negative and do not hide it." Both legs simply carry the sign.
            postings.add(Posting.inShillings(AccountCodes.CONVERSION_SPREAD, spread));
            postings.add(Posting.inShillings(AccountCodes.EXCHANGE_DIFFERENCE_REALISED, spread.negate()));
        }

        if (!facts.fee().isZero()) {
            postings.add(Posting.inShillings(AccountCodes.CONVERSION_FEE, facts.fee()));
            postings.add(Posting.inShillings(AccountCodes.PAYABLES, facts.fee().negate()));
        }

        return Optional.of(JournalEntry.of(facts.businessDate(),
                "Cost of converting " + facts.converted() + ": spread and fee, kept apart",
                facts.sourceRef(), recordedAt, postings));
    }
}

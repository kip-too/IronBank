package shilingi.ledger;

import shilingi.money.Currency;
import shilingi.money.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One line of a journal entry (SPEC.md section 6).
 *
 * <p>A posting is <b>immutable</b>, here and in the database. There is no setter, and migration
 * V3 installs a trigger that rejects UPDATE, DELETE and TRUNCATE on the table. A correction is
 * a new entry that reverses and re-posts.
 *
 * <h2>The rate is three flat fields, not a Rate object</h2>
 * SPEC.md section 6 lists the posting's fields as {@code rate_value, rate_source,
 * rate_timestamp} - flat - while listing {@code Rate} separately as a type with a fourth field,
 * {@code kind}. That is followed literally rather than tidied up, and the reason is sound: what
 * the posting stores is the rate that was used at that moment, and the posting has no business
 * remembering whether that rate was quoted as mid-market or executed. The {@code Rate} type
 * arrives on day 4 and will be what <i>builds</i> a posting; it is not what a posting holds.
 *
 * <h2>Signs</h2>
 * SPEC.md section 5: debits positive, credits negative. {@code amount} and {@code functionalAmount}
 * carry the same sign as each other, because a credit of dollars is a credit of their shilling
 * value. Nothing here enforces that they agree in sign - a rate is always positive, so they
 * cannot disagree unless a caller constructs nonsense, and day 3's ledger is where nonsense is
 * refused.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No balance check.</b> Whether the postings of an entry sum to zero is invariant I2
 *       and belongs to the ledger service on day 3, not to a single line.</li>
 *   <li><b>No check that functionalAmount actually equals amount times rate.</b> That is the
 *       round-once conversion rule (SPEC.md section 5) and it lands on day 4. Until then this
 *       type will accept a functional amount that does not follow from its own rate.</li>
 *   <li>No reversal helper. Day 3 or later, once there is a ledger to post the reversal through.</li>
 * </ul>
 *
 * @param id               database identity, null until inserted.
 * @param entryId          the entry this line belongs to, null until its entry is inserted.
 * @param accountCode      which account. Must exist, and its currency must match {@code amount}'s.
 * @param amount           the amount in the account's own currency, in minor units.
 * @param rateValue        shillings per unit of foreign currency, scale 8. Null only for KES.
 * @param rateSource       where the rate came from. Null only for KES.
 * @param rateTimestamp    when the rate was quoted. Null only for KES.
 * @param functionalAmount the shilling value of this posting at the moment it was made. Always
 *                         KES. Stored, never recomputed on read.
 */
public record Posting(
        Long id,
        Long entryId,
        String accountCode,
        Money amount,
        BigDecimal rateValue,
        String rateSource,
        Instant rateTimestamp,
        Money functionalAmount) {

    public Posting {
        Objects.requireNonNull(accountCode, "accountCode is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(functionalAmount, "functionalAmount is required");

        if (functionalAmount.currency() != Currency.KES) {
            throw new IllegalArgumentException(
                    "functionalAmount must be in KES, the functional currency, but was "
                    + functionalAmount.currency());
        }

        // Invariant I1, mirrored from the database constraint of the same name. The constraint
        // is the mechanism; this is the early, readable failure on top of it.
        if (amount.currency() != Currency.KES) {
            if (rateValue == null || rateSource == null || rateTimestamp == null) {
                throw new MissingRateException(accountCode, amount.currency());
            }
            if (rateValue.signum() <= 0) {
                throw new IllegalArgumentException(
                        "A rate must be positive, but was " + rateValue.toPlainString());
            }
        }

        if (amount.currency() == Currency.KES && functionalAmount.minorUnits() != amount.minorUnits()) {
            throw new IllegalArgumentException(
                    "A shilling posting is its own functional value, but amount was " + amount
                    + " and functionalAmount was " + functionalAmount);
        }
    }

    /** A posting in the functional currency needs no rate, because there is nothing to convert. */
    public static Posting inShillings(String accountCode, Money amount) {
        if (amount.currency() != Currency.KES) {
            throw new IllegalArgumentException(
                    "inShillings requires a KES amount, but was " + amount.currency());
        }
        return new Posting(null, null, accountCode, amount, null, null, null, amount);
    }

    /** True for a debit. SPEC.md section 5: debits positive. */
    public boolean isDebit() {
        return amount.isPositive();
    }

    /** True for a credit. SPEC.md section 5: credits negative. */
    public boolean isCredit() {
        return amount.isNegative();
    }
}

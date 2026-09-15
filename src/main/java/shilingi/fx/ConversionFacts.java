package shilingi.fx;

import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.money.Rate;
import shilingi.money.RateKind;

import java.time.LocalDate;
import java.util.Objects;

/**
 * What is known about a conversion that has already happened.
 *
 * <p>SPEC.md section 10 lists the inputs: the amount converted in dollars, the executed rate, the
 * mid-market rate at the same moment, the explicit fee charged, and the carrying rate. The last
 * of those is not here because it is not an input from outside - it is derived from the journal
 * by a {@link CarryingValuePolicy}.
 *
 * <p>Note "has already happened". This engine does not decide to convert and does not perform a
 * conversion. It reads facts about one and produces the postings that record it.
 *
 * @param businessDate the day the conversion belongs to
 * @param converted    dollars given up. Positive.
 * @param executed     the rate actually obtained. Must be EXECUTED.
 * @param mid          the mid-market rate at the same moment. Must be MID. Nobody trades at it;
 *                     it is here so the spread can be measured against it.
 * @param fee          the explicit fee, in shillings, AS CHARGED. SPEC.md section 10 says "fee =
 *                     as charged", so this is data supplied by whoever knows what the provider
 *                     billed - the engine does not compute a percentage of anything. May be zero.
 * @param sourceRef    what this conversion is, for the reconciler to match on later
 */
public record ConversionFacts(
        LocalDate businessDate,
        Money converted,
        Rate executed,
        Rate mid,
        Money fee,
        String sourceRef) {

    public ConversionFacts {
        Objects.requireNonNull(businessDate, "businessDate is required");
        Objects.requireNonNull(converted, "converted is required");
        Objects.requireNonNull(executed, "executed is required");
        Objects.requireNonNull(mid, "mid is required");
        Objects.requireNonNull(fee, "fee is required");

        if (converted.currency() == Currency.KES) {
            throw new IllegalArgumentException("A conversion gives up foreign currency, not shillings");
        }
        if (!converted.isPositive()) {
            throw new IllegalArgumentException("The amount converted must be positive, but was " + converted);
        }
        if (executed.kind() != RateKind.EXECUTED) {
            throw new IllegalArgumentException(
                    "The executed rate must be of kind EXECUTED, but was " + executed.kind()
                    + ". Mid-market and executed are not interchangeable - the gap between them is the spread.");
        }
        if (mid.kind() != RateKind.MID) {
            throw new IllegalArgumentException(
                    "The mid-market rate must be of kind MID, but was " + mid.kind());
        }
        if (fee.currency() != Currency.KES) {
            throw new IllegalArgumentException("The fee is charged in shillings, but was " + fee.currency());
        }
        if (fee.isNegative()) {
            throw new IllegalArgumentException(
                    "A fee cannot be negative (" + fee + "). A rate better than mid is a negative SPREAD, "
                    + "which is recorded as such, not as a negative fee.");
        }
    }
}

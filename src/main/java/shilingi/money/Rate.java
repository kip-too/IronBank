package shilingi.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * How many shillings one unit of a foreign currency is worth, at a stated moment, from a stated
 * source.
 *
 * <p>PROBLEM.md section 4: <i>"A rate without a timestamp and a source is not a rate, it is a
 * rumour."</i> SPEC.md section 5 requires that a rate carrying fewer than all four of value,
 * source, timestamp and kind <b>cannot be constructed</b> - enforced here in the constructor,
 * not in a validator somebody can forget to call.
 *
 * <h2>Direction is fixed</h2>
 * A rate is always <b>shillings per one unit of foreign currency</b>, because that is how
 * PROBLEM.md section 4 defines it. There is therefore exactly one conversion method,
 * {@link #toShillings(Money)}, and no way to ask this type to convert in the other direction.
 * An inverse would need a division and so a second rounding decision, and SPEC.md section 5
 * specifies only the multiplication.
 *
 * <h2>Scale</h2>
 * SPEC.md section 5 fixes the rate scale at 8. A value carrying more significant decimals than
 * that is <b>refused</b>, not rounded - see ADR-014. A value carrying fewer is widened to 8,
 * which is exact and loses nothing.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No inverse conversion (shillings to foreign).</b> The agent needs one on day 8, and
 *       SPEC.md section 11 states its rounding separately - "rounded up to whole dollars" -
 *       which is a different rule at a different granularity from the one here. It is built
 *       there, with its own rule, rather than guessed at here.</li>
 *   <li><b>No arithmetic between rates.</b> No averaging, no inverting, no interpolating a rate
 *       for a date the feed does not cover. SPEC.md gives no rule for any of those, and a
 *       plausible one would be exactly the kind of invention CLAUDE.md rule 2 forbids.</li>
 *   <li>No currency pair. Every rate in this system is KES against the one foreign currency.</li>
 * </ul>
 *
 * @param value     shillings per one unit of foreign currency, normalised to scale 8.
 * @param source    where it came from. Required.
 * @param timestamp when it was quoted. Required.
 * @param kind      mid-market or executed. Required.
 */
public record Rate(BigDecimal value, String source, Instant timestamp, RateKind kind) {

    /** SPEC.md section 5: "A rate is stored with a scale of 8." */
    public static final int SCALE = 8;

    public Rate {
        Objects.requireNonNull(value, "A rate needs a value");
        Objects.requireNonNull(source, "A rate needs a source: a rate without one is a rumour");
        Objects.requireNonNull(timestamp, "A rate needs a timestamp: a rate without one is a rumour");
        Objects.requireNonNull(kind, "A rate needs a kind: mid-market and executed are not interchangeable");

        if (source.isBlank()) {
            throw new IllegalArgumentException(
                    "A rate needs a source, and a blank one is not a source");
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(
                    "A rate must be positive, but was " + value.toPlainString());
        }

        // More decimals than the stored scale can hold. Refused rather than rounded: rounding
        // here would mean the stored rate is not the rate anybody quoted, and no figure derived
        // from it could be checked against the source. See ADR-014.
        int significantDecimals = value.stripTrailingZeros().scale();
        if (significantDecimals > SCALE) {
            throw new RatePrecisionException(value, significantDecimals);
        }

        value = value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static Rate mid(String value, String source, Instant timestamp) {
        return new Rate(new BigDecimal(value), source, timestamp, RateKind.MID);
    }

    public static Rate executed(String value, String source, Instant timestamp) {
        return new Rate(new BigDecimal(value), source, timestamp, RateKind.EXECUTED);
    }

    /**
     * Converts a foreign amount to its shilling value, applying SPEC.md section 5's conversion
     * rule exactly:
     *
     * <ol>
     *   <li>take the amount in minor units,</li>
     *   <li>multiply by the rate at full precision,</li>
     *   <li>adjust for the scale difference between the two currencies,</li>
     *   <li>round once, at the end, {@code HALF_UP}, to the target currency's minor unit.</li>
     * </ol>
     *
     * <p><b>Round once and only once.</b> Steps 2 and 3 are exact: {@code multiply} on
     * {@link BigDecimal} is exact, and {@code movePointRight} only shifts the decimal point.
     * There is exactly one {@code setScale} in this method, and it is the last thing that
     * happens. Rounding an intermediate value and then rounding again produces a different
     * answer, and the difference is small enough to survive review and large enough to matter
     * across a year.
     *
     * <p>Worked: USDC 10,000 at 131.50. The amount is 10,000,000,000 micro-dollars; USDC has
     * scale 6 and KES has scale 2, so the point moves left by 4.
     * {@code 10,000,000,000 x 131.50 = 1,315,000,000,000}, shifted left 4 gives
     * {@code 131,500,000} cents, which is KES 1,315,000.00 - the figure in PROBLEM.md section 5.
     *
     * @throws CurrencyMismatchException if handed shillings. A KES amount needs no rate, and
     *                                   applying one to it is always a mistake.
     */
    public Money toShillings(Money foreignAmount) {
        Objects.requireNonNull(foreignAmount, "foreignAmount is required");

        if (foreignAmount.currency() == Currency.KES) {
            throw new CurrencyMismatchException(Currency.KES, Currency.KES);
        }

        BigDecimal exact = BigDecimal.valueOf(foreignAmount.minorUnits())
                .multiply(value)
                .movePointRight(Currency.KES.scale() - foreignAmount.currency().scale());

        return Money.of(exact.setScale(0, RoundingMode.HALF_UP).longValueExact(), Currency.KES);
    }

    @Override
    public String toString() {
        return value.toPlainString() + " (" + kind + ", " + source + ", " + timestamp + ")";
    }
}

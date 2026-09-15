package shilingi.agent;

import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.money.Rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * SPEC.md section 11's first version of the decision rule, worked out. Kept dull and legible, as
 * that section insists:
 *
 * <pre>
 *   needed  = obligations due within horizon, not yet funded
 *   cover   = needed + buffer (buffer default 10%)
 *   have    = KES balance
 *   short   = max(0, cover - have)
 *   convert = short, converted at the current mid rate, rounded up to whole dollars
 * </pre>
 *
 * <p>No cleverness about rate timing. SPEC.md section 11 makes rate-aware timing a stretch goal,
 * "and only once the boring version is proven".
 *
 * <h2>Two rounding rules, and they are not the same rule</h2>
 * <ul>
 *   <li><b>The buffer rounds {@code HALF_UP} to the cent</b>, following SPEC.md section 5's
 *       general rule, because it is ordinary money arithmetic.</li>
 *   <li><b>The conversion rounds {@code CEILING} to a whole dollar</b>, because SPEC.md
 *       section 11 says "rounded up to whole dollars" - a different rule at a different
 *       granularity. Rounding this one to nearest would sometimes convert a dollar too few and
 *       leave an obligation short, which is the failure the buffer exists to prevent.</li>
 * </ul>
 * Each rounds exactly once.
 *
 * <h2>Why the inverse conversion lives here and not on {@link Rate}</h2>
 * {@code Rate} converts in one direction only, because SPEC.md section 5 specifies only the
 * multiplication. Going the other way needs a division and therefore a rounding decision, and
 * the decision here - round up, to whole dollars - is <b>SPEC.md section 11's planning rule</b>,
 * not a general fact about rates. Putting it on {@code Rate} would make an agent policy look
 * like arithmetic.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>Obligations denominated in dollars are excluded from {@code needed}</b>, because
 *       SPEC.md section 11's rule compares against "KES balance". They are not ignored though:
 *       the dollars they will need are <b>reserved</b>, so a dollar debt is never made unpayable
 *       in order to fund a shilling one. See ADR-022. <b>Nothing still pays them</b> - reserving
 *       is not acting, and that gap remains.</li>
 *   <li>No partial allocation across obligations. SPEC.md section 17 scenario 16 - "funds enough
 *       for one and a half" - is answered here only in aggregate: the plan converts what covers
 *       the total, and does not decide which obligation goes short.</li>
 * </ul>
 *
 * @param needed          unfunded shilling obligations due inside the horizon
 * @param cover           needed plus the buffer
 * @param have            the shilling balance
 * @param inFlightCover   shillings that conversions already instructed will produce, valued at
 *                        the same mid rate the plan sizes conversions with. Subtracted from the
 *                        gap so two cycles before a settlement do not each propose the whole of
 *                        it - see ADR-022.
 * @param reserved        dollars held back for obligations denominated in dollars
 * @param shortfall       what is missing after shillings held and shillings on their way, never
 *                        negative
 * @param requiredDollars dollars that would cover the shortfall at the mid rate, rounded up.
 *                        Empty when there is no rate to work it out with - see ADR-019.
 * @param held            dollars actually AVAILABLE to convert: the wallet balance less what is
 *                        in flight and less what is reserved. Carried so the guard can tell
 *                        "there is nothing to convert" from "we cannot work out how much" -
 *                        two very different situations that both leave mustConvert at zero.
 * @param mustConvert     what the agent is actually obliged to convert: the required dollars,
 *                        capped at what the wallet holds. This is the figure invariant I8 judges
 *                        a proposal against.
 */
public record FundingPlan(
        Money needed,
        Money cover,
        Money have,
        Money inFlightCover,
        Money reserved,
        Money shortfall,
        Optional<Money> requiredDollars,
        Money held,
        Money mustConvert) {

    public static FundingPlan from(TreasuryPosition position, BigDecimal buffer) {
        Money needed = position.unfundedShillingObligationsTotal();

        // cover = needed + buffer. One rounding, HALF_UP, to the cent.
        Money cover = Money.of(
                BigDecimal.valueOf(needed.minorUnits())
                        .multiply(BigDecimal.ONE.add(buffer))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact(),
                Currency.KES);

        Money have = position.shillingBalance();

        // Shillings already on their way. Valued at the same mid rate the plan uses to size a
        // conversion, so the arithmetic is self-consistent: if the rule says 5,883 dollars covers
        // this gap, then 5,883 dollars in flight covers it. See ADR-022.
        Money inFlightCover = position.midRate()
                .map(rate -> rate.toShillings(position.conversionsInFlight()))
                .orElse(Money.zero(Currency.KES));

        Money difference = cover.minus(have).minus(inFlightCover);
        Money shortfall = difference.isPositive() ? difference : Money.zero(Currency.KES);

        Optional<Money> requiredDollars = position.midRate()
                .map(rate -> wholeDollarsToCover(shortfall, rate));

        // What is actually available to convert: what is held, less what is already committed to
        // a conversion, less what dollar-denominated obligations will need. Never negative.
        Money reserved = position.foreignObligationsReserve();
        Money available = position.foreignBalance()
                .minus(position.conversionsInFlight())
                .minus(reserved);
        if (available.isNegative()) {
            available = Money.zero(Currency.USDC);
        }

        Money usable = available;
        Money mustConvert = requiredDollars
                .map(required -> required.compareTo(usable) > 0 ? usable : required)
                .orElse(Money.zero(Currency.USDC));

        return new FundingPlan(needed, cover, have, inFlightCover, reserved, shortfall,
                requiredDollars, available, mustConvert);
    }

    /**
     * SPEC.md section 11: "converted at the current mid rate, rounded up to whole dollars."
     *
     * <p>Rounded <b>up</b>, and to a <b>whole dollar</b>, not to a micro-dollar. Both halves
     * matter: converting 2,262 dollars when 2,262.44 were needed leaves the obligation short by
     * exactly the amount that was rounded away.
     */
    static Money wholeDollarsToCover(Money shillingShortfall, Rate rate) {
        if (shillingShortfall.isZero()) {
            return Money.zero(Currency.USDC);
        }

        BigDecimal shillings = BigDecimal.valueOf(shillingShortfall.minorUnits())
                .movePointLeft(Currency.KES.scale());

        BigDecimal wholeDollars = shillings.divide(rate.value(), 0, RoundingMode.CEILING);

        return Money.of(
                wholeDollars.movePointRight(Currency.USDC.scale()).longValueExact(),
                Currency.USDC);
    }

    /** True when shillings alone do not cover what is due inside the horizon. */
    public boolean hasShortfall() {
        return shortfall.isPositive();
    }

    /**
     * True when the agent is obliged to convert something. When this is true, holding dollars is
     * refused by invariant I8.
     */
    public boolean conversionIsRequired() {
        return mustConvert.isPositive();
    }

    /**
     * True when there is a shortfall and dollars that could be put against it, but no rate to
     * work out how many - so the agent cannot form a conversion at all.
     *
     * <p>This is NOT the same as having nothing to convert, though both leave
     * {@link #mustConvert()} at zero. A rate outage must not quietly excuse leaving an obligation
     * unfunded: without this distinction, invariant I8 is bypassed by an absence rather than by
     * an argument. See ADR-019.
     */
    public boolean cannotBeWorkedOut() {
        return shortfall.isPositive() && requiredDollars.isEmpty() && held.isPositive();
    }
}

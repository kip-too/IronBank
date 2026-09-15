package shilingi.fx;

import org.springframework.stereotype.Component;

import shilingi.ledger.AccountCodes;
import shilingi.ledger.Balances;
import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.money.Rate;
import shilingi.money.RateKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Weighted average cost, derived from the journal (SPEC.md section 10, open item O2).
 *
 * <p>The wallet's carrying value is the sum of {@code functional_amount_minor} over every
 * posting to account 1100 - what each receipt of dollars was worth on the day it arrived. Its
 * balance is the sum of {@code amount_minor}. Nothing is stored and nothing is recalculated on
 * receipt; the blend is simply what those two sums say, which means it cannot drift away from
 * the journal and cannot be wrong in a way replay would not catch.
 *
 * <h2>The rounding decision that matters here</h2>
 * There are two ways to work out what 6,000 of the 10,000 dollars held are carried at, and they
 * are not equivalent:
 *
 * <ol>
 *   <li><b>Derive a blended rate, then multiply.</b>
 *       {@code rate = carryingValue / balance}, rounded to scale 8; then
 *       {@code value = round(leaving x rate)}. <b>This rounds twice</b>, and it has a worse
 *       consequence than a lost cent: when the whole wallet is converted, the value computed
 *       this way need not equal the carrying value, so emptying the wallet of dollars can leave
 *       a cent or two of shilling value behind it. A carrying value sitting on a wallet that
 *       holds nothing is a number with no meaning, and the only way to remove it later is to
 *       invent an entry for it - a plug.</li>
 *   <li><b>Take the proportion directly.</b>
 *       {@code value = round(carryingValue x leaving / balance)}, one rounding, at the end.
 *       When {@code leaving == balance} this is exactly {@code carryingValue}, by arithmetic
 *       rather than by luck, so <b>emptying the wallet always leaves exactly zero</b>.</li>
 * </ol>
 *
 * <p>The second is used. SPEC.md section 10 says "the wallet carries one blended rate", and it
 * does - {@link CarryingValuation#rate()} reports it, and it is what the posting records to
 * satisfy invariant I1. But the rate describes the valuation rather than producing it. See
 * ADR-016.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No first-in-first-out.</b> O2 is still open. FIFO would need the individual receipts
 *       and their rates, which the journal has, plus a rule for which lot leaves first - and a
 *       place to record how much of each lot remains. It is a different implementation of
 *       {@link CarryingValuePolicy} and nothing else changes.</li>
 *   <li><b>No as-at date.</b> The blend is over the whole journal to date. Revaluing as at a
 *       past date needs the date-filtered balances that replay will add.</li>
 *   <li><b>A wallet holding zero dollars but a non-zero carrying value cannot be valued.</b>
 *       That state should be unreachable given the proportional rule above; if it ever arises it
 *       is a finding, and it fails loudly rather than dividing by zero.</li>
 * </ul>
 */
@Component
public class WeightedAverageCarryingValue implements CarryingValuePolicy {

    /** Named so that a posting's rate_source says where the number came from, not just what it is. */
    public static final String SOURCE = "carrying-value/weighted-average";

    private final Balances balances;

    public WeightedAverageCarryingValue(Balances balances) {
        this.balances = balances;
    }

    @Override
    public CarryingValuation valueLeaving(Money foreignAmountLeaving, Instant asOf) {
        if (foreignAmountLeaving.currency() == Currency.KES) {
            throw new IllegalArgumentException(
                    "Carrying value applies to foreign currency; shillings are the functional currency");
        }
        if (!foreignAmountLeaving.isPositive()) {
            throw new IllegalArgumentException(
                    "The amount leaving must be positive, but was " + foreignAmountLeaving);
        }

        Money held = balances.balanceOf(AccountCodes.WALLET_USDC, foreignAmountLeaving.currency());
        Money carried = balances.carryingValueOf(AccountCodes.WALLET_USDC);

        if (foreignAmountLeaving.compareTo(held) > 0) {
            throw new NothingToConvertException(foreignAmountLeaving, held);
        }

        // Proportion, rounded exactly once. See the class note for why this is not
        // "work out a rate, then multiply".
        BigDecimal value = BigDecimal.valueOf(carried.minorUnits())
                .multiply(BigDecimal.valueOf(foreignAmountLeaving.minorUnits()))
                .divide(BigDecimal.valueOf(held.minorUnits()), 0, RoundingMode.HALF_UP);

        Money shillingValue = Money.of(value.longValueExact(), Currency.KES);

        return new CarryingValuation(shillingValue, blendedRate(held, carried, asOf));
    }

    /**
     * The weighted-average rate the wallet carries: shillings per one dollar, at scale 8.
     *
     * <p>Reported for the posting's {@code rate_value}, because invariant I1 requires it. The
     * shilling figure on that posting comes from the proportional calculation above, so on an
     * awkward blend the two can differ by a cent. That is deliberate and is the lesser of the
     * two evils described in the class note - ADR-016 sets out the trade.
     */
    private Rate blendedRate(Money held, Money carried, Instant asOf) {
        // carried is in cents (scale 2), held is in micro-dollars (scale 6). Shillings per whole
        // dollar therefore needs the point moved right by (6 - 2) after the division.
        BigDecimal rate = BigDecimal.valueOf(carried.minorUnits())
                .divide(BigDecimal.valueOf(held.minorUnits()),
                        Rate.SCALE + (held.currency().scale() - Currency.KES.scale()),
                        RoundingMode.HALF_UP)
                .movePointRight(held.currency().scale() - Currency.KES.scale());

        return new Rate(rate.setScale(Rate.SCALE, RoundingMode.HALF_UP), SOURCE, asOf, RateKind.EXECUTED);
    }
}

package shilingi.fx;

import shilingi.money.Money;
import shilingi.money.Rate;

import java.util.Objects;

/**
 * What a quantity of foreign currency is carried at, and the rate that describes it.
 *
 * @param shillingValue the authoritative figure: what the books carry those dollars at. Computed
 *                      directly from the wallet's carrying value, not by applying {@code rate}.
 * @param rate          the weighted-average carrying rate, at scale 8. Reported because
 *                      invariant I1 requires every foreign posting to carry a rate, a source and
 *                      a timestamp. It DESCRIBES the valuation; it does not produce it - see
 *                      WeightedAverageCarryingValue for why those are different things.
 */
public record CarryingValuation(Money shillingValue, Rate rate) {

    public CarryingValuation {
        Objects.requireNonNull(shillingValue, "shillingValue is required");
        Objects.requireNonNull(rate, "rate is required");
    }
}

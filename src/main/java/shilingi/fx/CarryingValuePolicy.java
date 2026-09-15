package shilingi.fx;

import shilingi.money.Money;

import java.time.Instant;

/**
 * How the books decide what the dollars being given up were carried at.
 *
 * <p>SPEC.md section 10 and open item O2: <i>"whether to use weighted average or
 * first-in-first-out. Weighted average is assumed until Kurgat rules otherwise; <b>the method
 * must sit behind a single interface so the answer can change without touching anything
 * else.</b>"</i>
 *
 * <p>This is that interface, and it is the only place the question is asked. {@link FxEngine}
 * does not know which policy it is talking to, and nothing else in the system asks about
 * carrying value at all. Swapping weighted average for first-in-first-out means writing one new
 * implementation and changing one bean definition.
 *
 * <h2>Why this returns a value and a rate, rather than just a rate</h2>
 * A blended rate alone is not enough, and using one would reintroduce the problem the
 * round-once rule exists to prevent. See
 * {@link WeightedAverageCarryingValue#valueLeaving(Money, Instant)} - deriving a rate first and
 * then multiplying by it rounds twice, and can strand a cent of carrying value in a wallet that
 * holds no dollars. The value is computed directly; the rate is reported alongside it because
 * invariant I1 requires every foreign posting to carry one.
 */
public interface CarryingValuePolicy {

    /**
     * What the books carry the given quantity of foreign currency at, if it leaves now.
     *
     * @param foreignAmountLeaving a positive amount in the foreign currency
     * @param asOf                 the moment of the conversion, used as the reported rate's timestamp
     * @throws NothingToConvertException if the wallet holds less than this, or holds nothing
     */
    CarryingValuation valueLeaving(Money foreignAmountLeaving, Instant asOf);
}

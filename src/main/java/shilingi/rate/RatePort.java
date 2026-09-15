package shilingi.rate;

import shilingi.money.Rate;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Where mid-market rates come from (SPEC.md section 12).
 *
 * <h2>Absence is returned, not decided</h2>
 * {@link #midRateOn(LocalDate)} returns an {@link Optional} rather than throwing or falling back
 * to a nearby date. SPEC.md section 17 scenario 7 - <i>"payroll falls due on a day when the rate
 * feed has no entry"</i> - asks what should happen, and SPEC.md nowhere answers it.
 *
 * <p>Whether a missing rate means "use yesterday's", "refuse to act", or "escalate" is a
 * financial decision with real consequences: carrying yesterday's rate forward silently is how
 * a stale figure enters the books looking exactly like a fresh one. This port therefore reports
 * what it has and declines to decide, and the caller has to face the empty case. When Kurgat
 * rules on scenario 7, the rule goes in the caller, not here.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No interpolation, no carry-forward, no nearest-date lookup.</b> Every one of those is
 *       an invented financial rule.</li>
 *   <li><b>No executed rates.</b> An executed rate is what a conversion got, not what a feed
 *       quoted; it is recorded on the posting. See V5's header.</li>
 *   <li>No live source. SPEC.md section 12 allows one but forbids it becoming a dependency of
 *       any test.</li>
 * </ul>
 */
public interface RatePort {

    /**
     * The mid-market rate quoted for a business date, if the feed has one.
     *
     * @return empty when the feed has no entry for that date. That is a normal answer, not a
     *         failure, and the caller must handle it.
     */
    Optional<Rate> midRateOn(LocalDate businessDate);
}

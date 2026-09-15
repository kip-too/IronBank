package shilingi.money;

import java.math.BigDecimal;

/**
 * Thrown when a rate carries more significant decimal places than SPEC.md section 5's scale of 8.
 *
 * <p>SPEC.md section 17 scenario 18 is "a rate with more decimal places than the rate scale
 * allows", and SPEC.md gives no rule for what to do. The decision - refuse rather than round -
 * is recorded in ADR-014. In short: rounding at the point of ingestion would mean the stored
 * rate is not the rate the source quoted, and nothing derived from it could be checked back
 * against that source. That is failure F1 arriving by the back door.
 *
 * <p>The right place to decide what to do about an over-precise quote is the rate adapter that
 * received it, explicitly and visibly. Not this constructor, silently.
 */
public class RatePrecisionException extends RuntimeException {

    private final BigDecimal offered;
    private final int decimals;

    public RatePrecisionException(BigDecimal offered, int decimals) {
        super("Rate " + offered.toPlainString() + " has " + decimals
              + " significant decimal places, but a rate is stored at scale " + Rate.SCALE
              + " (SPEC.md section 5). It is refused rather than rounded: a rounded rate is not "
              + "the rate anybody quoted, and no figure derived from it could be checked. "
              + "Decide what to do about the extra precision in the rate adapter, where it is visible.");
        this.offered = offered;
        this.decimals = decimals;
    }

    public BigDecimal offered() {
        return offered;
    }

    public int decimals() {
        return decimals;
    }
}

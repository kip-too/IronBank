package shilingi.fx;

import shilingi.money.Money;

/**
 * Thrown when a conversion is asked for that the wallet cannot cover.
 *
 * <p>SPEC.md section 17 scenario 12 is "an obligation due today, with no dollars and no
 * shillings". The honest answer is that the conversion does not happen and the shortfall is
 * visible, not that the books record a conversion of money nobody had.
 */
public class NothingToConvertException extends RuntimeException {

    private final Money requested;
    private final Money held;

    public NothingToConvertException(Money requested, Money held) {
        super("Cannot convert " + requested + ": the wallet holds " + held
              + ". Converting more than is held would record a movement that did not happen.");
        this.requested = requested;
        this.held = held;
    }

    public Money requested() {
        return requested;
    }

    public Money held() {
        return held;
    }
}

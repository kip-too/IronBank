package shilingi.payout;

import shilingi.money.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The shilling rail (SPEC.md section 12).
 *
 * <p><b>This is where the regulatory boundary sits.</b> PROBLEM.md section 7: this project "does
 * not convert currency. It does not hold customer money. It does not move real value." The only
 * implementation is a mock, and that is a design constraint rather than an unfinished piece of
 * work - the exemption for genuine software providers turns on what the software actually does.
 *
 * <h2>Accepted is not done</h2>
 * {@link #send} returns an {@link Acceptance}, which means "I have taken this", not "the money has
 * arrived". The outcome comes later, through a callback, and may never come at all. Every real
 * payment rail behaves this way and a mock that returned success synchronously would let the rest
 * of the system be written wrongly and pass its tests.
 *
 * <h2>Re-query, never retry</h2>
 * {@link #status} is how an instruction in AWAITING_RESOLUTION is chased. SPEC.md section 7: "An
 * instruction in this state is never retried. It is re-queried." Asking is safe; sending again is
 * not, which is why one of those is on this interface and the other is a method that does not
 * exist.
 */
public interface PayoutPort {

    /**
     * Hands a payment to the rail.
     *
     * @return proof that the rail took it. Not proof that it happened.
     */
    Acceptance send(PayoutRequest request);

    /**
     * Asks the rail what became of a payment, without sending anything.
     *
     * @return empty when the rail still has no answer - which is a real answer and the reason
     *         AWAITING_RESOLUTION exists
     */
    Optional<PayoutOutcome> status(String externalRef);

    /**
     * A payment to make.
     *
     * @param externalRef OUR reference, unique in our database, carried through the whole round
     *                    trip so the callback can be matched back (SPEC.md section 12).
     */
    record PayoutRequest(String externalRef, Money amount, String beneficiary, Instant requestedAt) {

        public PayoutRequest {
            Objects.requireNonNull(externalRef, "A payout needs our external reference");
            Objects.requireNonNull(amount, "A payout needs an amount");
            Objects.requireNonNull(beneficiary, "A payout needs a beneficiary");
            Objects.requireNonNull(requestedAt, "A payout needs a time, from the injected clock");

            if (externalRef.isBlank()) {
                throw new IllegalArgumentException("A payout needs a non-blank external reference");
            }
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("A payout must be for a positive amount");
            }
        }
    }

    /**
     * What the rail says when it takes a payment.
     *
     * @param railRef the RAIL's own reference for this. Different from ours, and it is what a
     *                callback is keyed on - see SPEC.md section 13 and V10.
     */
    record Acceptance(String externalRef, String railRef, Instant acceptedAt) {

        public Acceptance {
            Objects.requireNonNull(externalRef, "externalRef is required");
            Objects.requireNonNull(railRef, "railRef is required");
            Objects.requireNonNull(acceptedAt, "acceptedAt is required");
        }
    }
}

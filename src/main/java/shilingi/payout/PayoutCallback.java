package shilingi.payout;

import shilingi.money.Money;

import java.time.Instant;
import java.util.Objects;

/**
 * What a rail tells us, later, about a payment it took.
 *
 * <h2>These field names are ours, not a real rail's</h2>
 * SPEC.md open item O4 asks whether this should mirror a real mobile money API's request and
 * callback format, and answers: <i>"Yes, if the format can be verified from current
 * documentation."</i>
 *
 * <p>It could not be. See ADR-024: the Safaricom developer portal did not respond on 2026-09-15,
 * and CLAUDE.md rule 1 forbids writing API field names from memory. So these names are this
 * project's own, plainly chosen, and {@link #rawPayload} carries whatever the rail actually sent
 * so that nothing is lost in the translation. Mapping a real format onto this shape is one class.
 *
 * @param rail        which rail. Part of the uniqueness key, because two rails may use the same
 *                    reference and mean different things.
 * @param railRef     the rail's own reference for this event. What repeat delivery is detected on.
 * @param externalRef our reference, carried through the round trip.
 * @param outcome     succeeded or failed. Silence is not an outcome and never arrives here.
 * @param amount      what the rail says moved. Compared against the instruction - a difference is
 *                    an exception, always, regardless of size (SPEC.md section 13).
 * @param occurredAt  when the rail says it happened.
 * @param rawPayload  verbatim JSON of what was received. Evidence.
 */
public record PayoutCallback(
        String rail,
        String railRef,
        String externalRef,
        PayoutOutcome outcome,
        Money amount,
        Instant occurredAt,
        String rawPayload) {

    public PayoutCallback {
        Objects.requireNonNull(rail, "A callback must say which rail it came from");
        Objects.requireNonNull(railRef, "A callback needs the rail's own reference");
        Objects.requireNonNull(externalRef, "A callback needs our reference to match on");
        Objects.requireNonNull(outcome, "A callback needs an outcome");
        Objects.requireNonNull(amount, "A callback needs an amount");
        Objects.requireNonNull(occurredAt, "A callback needs a time");
        Objects.requireNonNull(rawPayload, "A callback keeps what was actually sent");

        if (railRef.isBlank()) {
            throw new IllegalArgumentException(
                    "A callback needs the rail's reference; without one, a repeat delivery cannot "
                    + "be told from a new event");
        }
    }
}

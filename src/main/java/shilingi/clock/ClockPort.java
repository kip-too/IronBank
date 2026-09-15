package shilingi.clock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The only source of time in this system.
 *
 * <p>SPEC.md section 4: "Nothing anywhere calls now() directly. Time is injected. Without this,
 * none of the ageing, due-date or month-end behaviour can be tested, and demo day becomes a
 * hostage to the actual date."
 *
 * <p>So: no production code outside {@link SystemClockAdapter} may call {@code Instant.now()},
 * {@code LocalDate.now()}, {@code System.currentTimeMillis()} or any equivalent.
 *
 * <h2>The business date is not free of choices</h2>
 * {@link #businessDate()} turns an instant into the calendar day a transaction belongs to,
 * and which day that is depends on the zone. That is a financial decision, not a technical
 * one - it decides which month a posting falls in. This interface therefore requires the zone
 * to be supplied explicitly by whoever builds the adapter; there is no default anywhere in
 * this file, because a default here would be a rule nobody chose.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li>No business-day calendar. Weekends and Kenyan public holidays are not modelled.
 *       SPEC.md O3 assumes a reconciliation ageing threshold of "2 business days", which will
 *       need that calendar - it is not built and is not guessed at here.</li>
 *   <li>No guarantee that time moves forwards. {@link MutableClock} can be set backwards on
 *       purpose, because SPEC.md section 17 scenario 15 is "the clock moves backwards" and a
 *       clock that refuses to do so makes that scenario untestable.</li>
 *   <li>No monotonic or elapsed-time measurement. This is a wall clock for business dates.</li>
 * </ul>
 */
public interface ClockPort {

    /** The current instant, from whatever source this adapter represents. */
    Instant instant();

    /** The zone used to decide which calendar day an instant belongs to. */
    ZoneId zone();

    /**
     * The calendar day the current instant falls on, in this clock's zone. This is the date a
     * posting made now would carry, and the date the ledger compares against when it refuses
     * an entry dated in the future (SPEC.md section 8).
     */
    default LocalDate businessDate() {
        return LocalDate.ofInstant(instant(), zone());
    }
}

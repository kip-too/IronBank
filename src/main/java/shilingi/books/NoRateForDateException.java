package shilingi.books;

import java.time.LocalDate;

/**
 * Thrown when a business event falls on a day the rate feed has no quote for.
 *
 * <p>SPEC.md section 17 scenario 7. The alternative - carrying the previous day's rate forward -
 * would put a stale figure in the books looking exactly as authoritative as a fresh one, which is
 * PROBLEM.md F1 arriving quietly. ADR-015 declined to decide this at the rate port; ADR-019
 * decided it for the agent; this is the same decision for the books.
 *
 * <p>Refusing is not a dead end. The event is real and can be posted the moment a rate for that
 * date exists - which is a data problem with an obvious fix, unlike a wrong number nobody can spot.
 */
public class NoRateForDateException extends RuntimeException {

    private final LocalDate date;

    public NoRateForDateException(LocalDate date) {
        super("No mid-market rate is published for " + date + ", so this cannot be recorded in "
              + "shillings. Nothing is guessed and yesterday's rate is not carried forward: a "
              + "stale rate in the books looks exactly like a fresh one (PROBLEM.md F1).");
        this.date = date;
    }

    public LocalDate date() {
        return date;
    }
}

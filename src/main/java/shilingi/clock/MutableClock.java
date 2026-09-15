package shilingi.clock;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * A clock that only moves when something moves it. Used by tests, and by the demo, which runs
 * a thirty-day story on a scripted clock so it fits in ninety seconds (SPEC.md section 12).
 *
 * <p>This is production code, not test code: the demo depends on it.
 *
 * <h2>It can go backwards, on purpose</h2>
 * {@link #setTo(Instant)} accepts an earlier instant and {@link #advance(Duration)} accepts a
 * negative duration. SPEC.md section 17 scenario 15 is "the clock moves backwards", and the
 * question that scenario asks is what the rest of the system does about it. A clock that
 * refused to move backwards would answer that question by hiding it.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li>Not thread-safe in any useful sense beyond the volatile read. Two threads advancing
 *       this concurrently will interleave. Nothing needs that yet; SPEC.md section 17
 *       scenario 5 concerns two threads creating instructions, not two threads moving time.</li>
 * </ul>
 */
public final class MutableClock implements ClockPort {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant startingAt, ZoneId zone) {
        this.instant = Objects.requireNonNull(startingAt, "startingAt is required");
        this.zone = Objects.requireNonNull(zone, "zone is required");
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId zone() {
        return zone;
    }

    /** Moves the clock to an exact instant. May be earlier than the current one - see the class note. */
    public void setTo(Instant newInstant) {
        this.instant = Objects.requireNonNull(newInstant, "newInstant is required");
    }

    /** Moves the clock by a duration. A negative duration moves it backwards - see the class note. */
    public void advance(Duration by) {
        Objects.requireNonNull(by, "by is required");
        this.instant = this.instant.plus(by);
    }

    @Override
    public String toString() {
        return "MutableClock[instant=" + instant + ", zone=" + zone + "]";
    }
}

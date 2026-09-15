package shilingi.clock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * The one place in this codebase allowed to read the real system clock.
 *
 * <p>Wraps {@link java.time.Clock} rather than calling {@code Instant.now()}, so that even
 * this adapter can be handed a fixed clock in a test.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li>No clock skew detection, no NTP awareness. If the host clock is wrong, this is wrong.</li>
 * </ul>
 */
public final class SystemClockAdapter implements ClockPort {

    private final Clock clock;
    private final ZoneId zone;

    /**
     * @param zone the zone business dates are decided in. Required - see {@link ClockPort} on
     *             why there is no default.
     */
    public SystemClockAdapter(ZoneId zone) {
        this(Clock.system(Objects.requireNonNull(zone, "zone is required")), zone);
    }

    SystemClockAdapter(Clock clock, ZoneId zone) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.zone = Objects.requireNonNull(zone, "zone is required");
    }

    @Override
    public Instant instant() {
        return clock.instant();
    }

    @Override
    public ZoneId zone() {
        return zone;
    }

    @Override
    public String toString() {
        return "SystemClockAdapter[zone=" + zone + "]";
    }
}

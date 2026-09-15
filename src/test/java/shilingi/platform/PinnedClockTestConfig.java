package shilingi.platform;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import shilingi.clock.ClockPort;
import shilingi.clock.MutableClock;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Pins the clock for every test that touches the database.
 *
 * <p>SPEC.md section 4 puts the clock behind an adapter so that "demo day becomes a hostage to
 * the actual date". The same applies to the test suite, and more sharply: the ledger rejects an
 * entry whose business date is in the future, so a fixture dated 30 September is valid in
 * October and rejected in September. A suite that passes or fails depending on the day it is
 * run proves nothing on either day.
 *
 * <p>The pinned instant is deliberately later than every fixture date in the suite, so that
 * "in the past" is a property of the fixtures rather than a coincidence of the calendar. The
 * one test that is about the future derives its date from this clock rather than hardcoding one.
 *
 * <p>Declared {@code @Primary} rather than replacing {@code ClockConfig}'s bean, so that the
 * production wiring is still constructed and still has to work.
 * {@code FlywayMigrationTest} deliberately does not import this, and asserts against the real
 * configured bean.
 */
@TestConfiguration
public class PinnedClockTestConfig {

    /** Later than every business date used as a fixture anywhere in the suite. */
    public static final Instant PINNED = Instant.parse("2026-10-15T09:00:00Z");

    public static final ZoneId ZONE = ZoneId.of("Africa/Nairobi");

    @Bean
    @Primary
    public ClockPort pinnedClock() {
        return new MutableClock(PINNED, ZONE);
    }
}

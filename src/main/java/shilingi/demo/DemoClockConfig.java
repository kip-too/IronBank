package shilingi.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import shilingi.clock.ClockPort;
import shilingi.clock.MutableClock;

import java.time.Instant;
import java.time.ZoneId;

/**
 * The scripted clock the demo runs on.
 *
 * <p>SPEC.md section 12: "The demo runs on a scripted clock so that a thirty-day story fits in
 * ninety seconds." SPEC.md section 4 gives the reason it is possible at all: "Without this, none
 * of the ageing, due-date or month-end behaviour can be tested, and demo day becomes a hostage to
 * the actual date."
 *
 * <p>That is not a nicety here. The story's last act is a month-end revaluation dated 30
 * September, and the ledger refuses an entry whose business date is in the future. Run against the
 * real clock on any day before the 30th, the demo would be refused by its own rules - correctly.
 *
 * <p><b>This bean is the application's only clock while the {@code demo} profile is active.</b>
 * Everything - the ledger's future-date check, the agent's horizon, the obligation's overdue
 * derivation - sees the scripted time, because they all read the same injected
 * {@link ClockPort}. Nothing is special-cased for the demo, which is what makes the demo worth
 * watching.
 */
@Configuration
@Profile("demo")
public class DemoClockConfig {

    /** Where the story opens. Chosen so the seeded rates in V5 are the rates it uses. */
    public static final Instant OPENS_AT = Instant.parse("2026-09-01T06:00:00Z");

    public static final ZoneId ZONE = ZoneId.of("Africa/Nairobi");

    @Bean
    @Primary
    public MutableClock demoClock() {
        return new MutableClock(OPENS_AT, ZONE);
    }
}

package shilingi.clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClockPortTest {

    /**
     * A zone with a fixed offset, used so that the business-date tests assert the derivation
     * rule itself rather than smuggling in a claim about any particular country's offset.
     */
    private static final ZoneId PLUS_THREE = ZoneOffset.ofHours(3);

    @Nested
    @DisplayName("business date derivation")
    class BusinessDate {

        @Test
        void an_instant_is_dated_in_the_clocks_own_zone_not_in_utc() {
            // 22:30 UTC is already the next calendar day three hours east.
            Instant lateEveningUtc = Instant.parse("2026-09-30T22:30:00Z");

            MutableClock utcClock = new MutableClock(lateEveningUtc, ZoneOffset.UTC);
            MutableClock eastClock = new MutableClock(lateEveningUtc, PLUS_THREE);

            assertThat(utcClock.businessDate()).isEqualTo(LocalDate.of(2026, 9, 30));
            assertThat(eastClock.businessDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        }

        @Test
        void the_zone_decides_which_month_a_posting_falls_in() {
            // The reason the zone is a financial decision and not a technical default:
            // the same instant lands in September in one zone and October in the other,
            // which puts it either side of a month-end revaluation.
            Instant instant = Instant.parse("2026-09-30T22:30:00Z");

            assertThat(new MutableClock(instant, ZoneOffset.UTC).businessDate().getMonthValue()).isEqualTo(9);
            assertThat(new MutableClock(instant, PLUS_THREE).businessDate().getMonthValue()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("MutableClock")
    class Mutable {

        @Test
        void does_not_move_on_its_own() {
            MutableClock clock = new MutableClock(Instant.parse("2026-09-14T09:00:00Z"), PLUS_THREE);

            Instant first = clock.instant();
            Instant second = clock.instant();

            assertThat(first).isEqualTo(second).isEqualTo(Instant.parse("2026-09-14T09:00:00Z"));
        }

        @Test
        void advances_by_a_duration() {
            MutableClock clock = new MutableClock(Instant.parse("2026-09-14T09:00:00Z"), PLUS_THREE);

            clock.advance(Duration.ofDays(30));

            assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-14T09:00:00Z"));
        }

        @Test
        void can_be_set_to_an_exact_instant() {
            MutableClock clock = new MutableClock(Instant.parse("2026-09-14T09:00:00Z"), PLUS_THREE);

            clock.setTo(Instant.parse("2026-09-30T23:59:59Z"));

            assertThat(clock.instant()).isEqualTo(Instant.parse("2026-09-30T23:59:59Z"));
        }

        @Test
        void permits_moving_backwards_so_that_spec_section_17_scenario_15_is_testable() {
            // "The clock moves backwards." A clock that refused would answer that scenario
            // by hiding it. What the rest of the system does about it is tested elsewhere,
            // when there is a rest of the system.
            MutableClock clock = new MutableClock(Instant.parse("2026-09-14T09:00:00Z"), PLUS_THREE);

            assertThatCode(() -> clock.advance(Duration.ofHours(-6))).doesNotThrowAnyException();
            assertThat(clock.instant()).isEqualTo(Instant.parse("2026-09-14T03:00:00Z"));

            clock.setTo(Instant.parse("2026-01-01T00:00:00Z"));
            assertThat(clock.instant()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        }

        @Test
        void requires_a_starting_instant_and_a_zone() {
            assertThatThrownBy(() -> new MutableClock(null, PLUS_THREE))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new MutableClock(Instant.EPOCH, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("SystemClockAdapter")
    class SystemAdapter {

        @Test
        void reports_the_zone_it_was_given() {
            assertThat(new SystemClockAdapter(PLUS_THREE).zone()).isEqualTo(PLUS_THREE);
        }

        @Test
        void reads_the_underlying_clock_rather_than_calling_now() {
            // The package-private constructor exists so that even the adapter over the real
            // clock can be pinned in a test.
            Instant pinned = Instant.parse("2026-09-14T09:00:00Z");
            SystemClockAdapter adapter = new SystemClockAdapter(Clock.fixed(pinned, PLUS_THREE), PLUS_THREE);

            assertThat(adapter.instant()).isEqualTo(pinned);
            assertThat(adapter.businessDate()).isEqualTo(LocalDate.of(2026, 9, 14));
        }

        @Test
        void requires_a_zone() {
            assertThatThrownBy(() -> new SystemClockAdapter((ZoneId) null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void the_configured_zone_id_resolves_on_this_jvm() {
        // application.yml carries Africa/Nairobi as a recorded decision. This asserts only
        // that the identifier is real on this JVM's tz database - it is not a claim about
        // the offset, and it is not a ratification of the decision.
        assertThatCode(() -> ZoneId.of("Africa/Nairobi")).doesNotThrowAnyException();
    }
}

package shilingi.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class RateTest {

    private static final Instant QUOTED_AT = Instant.parse("2026-09-12T06:00:00Z");

    @Test
    @DisplayName("SPEC.md section 5: a rate missing any of its four parts cannot be constructed")
    void a_rate_cannot_be_built_without_all_four_parts() {
        // Enforced in the constructor, not in a validator somebody can forget to call.
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                new Rate(null, "feed", QUOTED_AT, RateKind.MID));

        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                new Rate(new BigDecimal("131.50"), null, QUOTED_AT, RateKind.MID))
                .withMessageContaining("rumour");

        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                new Rate(new BigDecimal("131.50"), "feed", null, RateKind.MID))
                .withMessageContaining("rumour");

        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                new Rate(new BigDecimal("131.50"), "feed", QUOTED_AT, null));
    }

    @Test
    void a_blank_source_is_not_a_source() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new Rate(new BigDecimal("131.50"), "   ", QUOTED_AT, RateKind.MID));
    }

    @Test
    void a_rate_must_be_positive() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                Rate.mid("0", "feed", QUOTED_AT));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                Rate.mid("-131.50", "feed", QUOTED_AT));
    }

    @Test
    @DisplayName("a rate is normalised to scale 8, losing nothing")
    void fewer_decimals_are_widened_to_the_stored_scale() {
        assertThat(Rate.mid("131.5", "feed", QUOTED_AT).value())
                .isEqualByComparingTo("131.5")
                .satisfies(v -> assertThat(v.scale()).isEqualTo(8));

        assertThat(Rate.mid("131", "feed", QUOTED_AT).value().toPlainString())
                .isEqualTo("131.00000000");
    }

    @Test
    @DisplayName("SPEC.md section 17 scenario 18: a rate with more decimals than the scale allows")
    void an_over_precise_rate_is_refused_not_rounded() {
        // ADR-014: rounding here would mean the stored rate is not the rate anybody quoted,
        // and no figure derived from it could be checked back against the source.
        assertThatExceptionOfType(RatePrecisionException.class).isThrownBy(() ->
                Rate.mid("131.123456789", "feed", QUOTED_AT))
                .satisfies(e -> {
                    assertThat(e.decimals()).isEqualTo(9);
                    assertThat(e).hasMessageContaining("refused rather than rounded");
                });
    }

    @Test
    @DisplayName("exactly eight decimals is accepted; nine is not")
    void the_boundary_is_at_eight() {
        assertThat(Rate.mid("131.12345678", "feed", QUOTED_AT).value().toPlainString())
                .isEqualTo("131.12345678");

        assertThatExceptionOfType(RatePrecisionException.class).isThrownBy(() ->
                Rate.mid("131.123456781", "feed", QUOTED_AT));
    }

    @Test
    @DisplayName("trailing zeros beyond the scale are not extra precision, so they are accepted")
    void trailing_zeros_do_not_count_as_precision() {
        // 131.500000000000 carries one significant decimal, not twelve. Refusing it would be
        // refusing a rate that fits perfectly well.
        assertThat(Rate.mid("131.500000000000", "feed", QUOTED_AT).value().toPlainString())
                .isEqualTo("131.50000000");
    }

    @Test
    void mid_and_executed_are_not_interchangeable() {
        Rate mid = Rate.mid("132.60", "feed", QUOTED_AT);
        Rate executed = Rate.executed("132.20", "provider", QUOTED_AT);

        assertThat(mid.kind()).isEqualTo(RateKind.MID);
        assertThat(executed.kind()).isEqualTo(RateKind.EXECUTED);
        assertThat(mid).isNotEqualTo(executed);
    }

    @Test
    @DisplayName("two rates with the same value but different kinds are different rates")
    void kind_is_part_of_identity() {
        assertThat(Rate.mid("132.20", "feed", QUOTED_AT))
                .isNotEqualTo(Rate.executed("132.20", "feed", QUOTED_AT));
    }

    @Test
    void toString_shows_the_value_its_kind_and_where_it_came_from() {
        assertThat(Rate.mid("131.50", "worked-example", QUOTED_AT))
                .hasToString("131.50000000 (MID, worked-example, 2026-09-12T06:00:00Z)");
    }
}

package shilingi.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Day 4 is done when the "round-once rule [is] proven" (SPEC.md section 15). This is that proof.
 *
 * <p>The interesting tests here are the ones that would pass under a wrong implementation too.
 * Asserting that 10,000 dollars at 131.50 gives 1,315,000 shillings proves almost nothing - it
 * comes out the same however many times you round. What proves the rule is a case where rounding
 * twice gives a different answer from rounding once, and then showing which answer this code
 * produces.
 */
class ConversionRoundingTest {

    private static final Instant QUOTED_AT = Instant.parse("2026-09-12T06:00:00Z");

    private static Rate mid(String value) {
        return Rate.mid(value, "test-fixture", QUOTED_AT);
    }

    private static Money usdc(long minorUnits) {
        return Money.of(minorUnits, Currency.USDC);
    }

    @Nested
    @DisplayName("the figures in PROBLEM.md section 5 fall out unaided")
    class TheWorkedExample {

        @Test
        @DisplayName("day 1: USD 10,000 at 129.00 is KES 1,290,000.00")
        void day_one() {
            assertThat(mid("129.00").toShillings(usdc(10_000_000_000L)))
                    .isEqualTo(Money.of(129_000_000L, Currency.KES));
        }

        @Test
        @DisplayName("day 12: USDC 10,000 at 131.50 is KES 1,315,000.00")
        void day_twelve() {
            assertThat(mid("131.50").toShillings(usdc(10_000_000_000L)))
                    .isEqualTo(Money.of(131_500_000L, Currency.KES));
        }

        @Test
        @DisplayName("day 20: USDC 6,000 at 132.20 executed is KES 793,200.00")
        void day_twenty_executed() {
            Rate executed = Rate.executed("132.20", "test-fixture", QUOTED_AT);

            assertThat(executed.toShillings(usdc(6_000_000_000L)))
                    .isEqualTo(Money.of(79_320_000L, Currency.KES));
        }

        @Test
        @DisplayName("day 20: the same 6,000 carried at 131.50 is KES 789,000.00, so the difference is 4,200")
        void day_twenty_carrying_value() {
            Money received = Rate.executed("132.20", "test-fixture", QUOTED_AT).toShillings(usdc(6_000_000_000L));
            Money carried = mid("131.50").toShillings(usdc(6_000_000_000L));

            assertThat(carried).isEqualTo(Money.of(78_900_000L, Currency.KES));
            assertThat(received.minus(carried)).isEqualTo(Money.of(420_000L, Currency.KES));
        }

        @Test
        @DisplayName("day 30: USDC 4,000 at 130.80 is KES 523,200.00, so the revaluation is 2,800")
        void day_thirty() {
            Money now = mid("130.80").toShillings(usdc(4_000_000_000L));
            Money carried = mid("131.50").toShillings(usdc(4_000_000_000L));

            assertThat(now).isEqualTo(Money.of(52_320_000L, Currency.KES));
            assertThat(carried).isEqualTo(Money.of(52_600_000L, Currency.KES));
            assertThat(carried.minus(now)).isEqualTo(Money.of(280_000L, Currency.KES));
        }
    }

    @Nested
    @DisplayName("SPEC.md section 5: round once, at the end, HALF_UP")
    class RoundOnce {

        /**
         * The whole point of the rule, in one test.
         *
         * <p>An implementation that rounds the intermediate value to the major unit and then
         * converts would get a different answer. This shows both numbers and asserts which one
         * this code produces.
         */
        @Test
        @DisplayName("rounding an intermediate value gives a different answer, and this code does not do it")
        void rounding_twice_would_differ_and_does_not_happen_here() {
            // 1 micro-dollar short of 7,777.777778 dollars, at an awkward rate.
            Money amount = usdc(7_777_777_777L);
            Rate rate = mid("131.56789012");

            Money roundedOnce = rate.toShillings(amount);

            // What a careless implementation does: convert to major units, round to the cent,
            // then scale up. Two roundings.
            BigDecimal majorUnits = BigDecimal.valueOf(amount.minorUnits())
                    .movePointLeft(Currency.USDC.scale());
            BigDecimal roundedIntermediate = majorUnits.setScale(2, RoundingMode.HALF_UP);
            long roundedTwice = roundedIntermediate.multiply(rate.value())
                    .movePointRight(Currency.KES.scale())
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();

            assertThat(roundedTwice)
                    .as("the two approaches must actually disagree, or this test proves nothing")
                    .isNotEqualTo(roundedOnce.minorUnits());

            assertThat(roundedOnce.minorUnits())
                    .as("the single rounding of the exact product is the right answer")
                    .isEqualTo(new BigDecimal("7777777777")
                            .multiply(rate.value())
                            .movePointRight(Currency.KES.scale() - Currency.USDC.scale())
                            .setScale(0, RoundingMode.HALF_UP)
                            .longValueExact());
        }

        @Test
        @DisplayName("exactly half a cent rounds up, not to even")
        void half_rounds_up_not_to_even() {
            // Chosen so the exact product lands on .5 of a cent. 1 micro-dollar at a rate of
            // 0.005 shillings per dollar gives 0.5 cents exactly.
            //   1 x 0.005 = 0.005, shifted left by 4 -> 0.0000005 ... use a rate that lands it.
            // 5,000 micro-dollars at 1.00 gives 0.5 cents exactly:
            //   5,000 x 1 = 5,000, shifted left 4 = 0.5
            Money result = mid("1.00").toShillings(usdc(5_000L));

            assertThat(result)
                    .as("HALF_UP, so 0.5 cents becomes 1 cent - not 0, which HALF_EVEN would give")
                    .isEqualTo(Money.of(1L, Currency.KES));
        }

        @Test
        @DisplayName("just below half rounds down")
        void just_below_half_rounds_down() {
            assertThat(mid("1.00").toShillings(usdc(4_999L))).isEqualTo(Money.zero(Currency.KES));
        }

        @Test
        @DisplayName("just above half rounds up")
        void just_above_half_rounds_up() {
            assertThat(mid("1.00").toShillings(usdc(5_001L))).isEqualTo(Money.of(1L, Currency.KES));
        }

        @Test
        @DisplayName("HALF_UP on a negative amount rounds away from zero")
        void negative_half_rounds_away_from_zero() {
            // Credits are negative (SPEC.md section 5), so negative amounts are converted
            // routinely. HALF_UP in BigDecimal means away from zero, symmetric with the positive
            // case - -0.5 cents becomes -1, not 0.
            assertThat(mid("1.00").toShillings(usdc(-5_000L)))
                    .isEqualTo(Money.of(-1L, Currency.KES));
        }

        @Test
        @DisplayName("converting zero gives zero, in shillings")
        void zero_converts_to_zero() {
            assertThat(mid("131.50").toShillings(usdc(0L))).isEqualTo(Money.zero(Currency.KES));
        }

        @Test
        @DisplayName("the full eight decimal places of the rate are used, not a truncation of them")
        void the_whole_rate_is_used() {
            // If the rate were truncated to two places before multiplying, this would give a
            // different and smaller answer.
            Money precise = mid("131.99999999").toShillings(usdc(1_000_000_000L));
            Money truncated = mid("131.99").toShillings(usdc(1_000_000_000L));

            assertThat(precise).isEqualTo(Money.of(13_200_000L, Currency.KES));
            assertThat(truncated).isEqualTo(Money.of(13_199_000L, Currency.KES));
        }
    }

    @Nested
    @DisplayName("conversion refuses what it should")
    class Refusals {

        @Test
        void refuses_to_apply_a_rate_to_shillings() {
            // A KES amount needs no rate (SPEC.md section 8), so applying one is always a bug.
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> mid("131.50").toShillings(Money.of(100L, Currency.KES)));
        }

        @Test
        @DisplayName("there is real headroom: the largest possible dollar amount at a plausible rate still fits")
        void the_largest_representable_amount_converts_without_overflowing() {
            // Long.MAX_VALUE micro-dollars is about 9.2 trillion dollars. Converting shrinks the
            // number rather than growing it - USDC has scale 6 and KES scale 2, so the point
            // moves LEFT by 4 - which is why a realistic conversion cannot overflow.
            // Worth knowing, and worth asserting, so nobody adds a defensive guard that is not
            // needed or removes one that is.
            assertThat(mid("131.50").toShillings(usdc(Long.MAX_VALUE)).minorUnits()).isPositive();
        }

        @Test
        void a_conversion_that_genuinely_overflows_throws_rather_than_wrapping() {
            // A rate of 100 million shillings to the dollar is absurd, and that is the point:
            // the only way to overflow is to leave reality, and when you do, it fails loudly
            // instead of wrapping into a plausible-looking wrong number.
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> mid("99999999.99999999").toShillings(usdc(Long.MAX_VALUE)));
        }
    }
}

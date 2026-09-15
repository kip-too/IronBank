package shilingi.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static shilingi.money.Currency.KES;
import static shilingi.money.Currency.USDC;

class MoneyTest {

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void holds_whole_minor_units_and_a_currency() {
            Money m = Money.of(1_290_000L, KES);

            assertThat(m.minorUnits()).isEqualTo(1_290_000L);
            assertThat(m.currency()).isEqualTo(KES);
        }

        @Test
        void zero_still_has_a_currency() {
            assertThat(Money.zero(KES).minorUnits()).isZero();
            assertThat(Money.zero(KES).currency()).isEqualTo(KES);
            assertThat(Money.zero(KES)).isNotEqualTo(Money.zero(USDC));
        }

        @Test
        void an_amount_without_a_currency_is_not_money() {
            assertThatThrownBy(() -> Money.of(1L, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires a currency");
        }

        @Test
        void carries_negative_amounts_without_complaint_because_credits_are_negative() {
            // SPEC.md section 5: debits positive, credits negative, internally.
            assertThat(Money.of(-1_290_000L, KES).minorUnits()).isEqualTo(-1_290_000L);
        }
    }

    @Nested
    @DisplayName("SPEC.md section 5: no floating point for money, anywhere")
    class RejectsFloatingPoint {

        @Test
        @SuppressWarnings("deprecation")
        void rejects_construction_from_a_double() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> Money.of(12_900.55d, KES))
                    .withMessageContaining("floating point");
        }

        @Test
        @SuppressWarnings("deprecation")
        void rejects_construction_from_a_float() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> Money.of(12_900.55f, KES))
                    .withMessageContaining("floating point");
        }

        @Test
        @SuppressWarnings("deprecation")
        void rejects_a_double_that_happens_to_be_a_whole_number_too() {
            // The dangerous case: it looks harmless, so it would be waved through.
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> Money.of(1_290_000.0d, KES));
        }

        @Test
        void an_integer_literal_still_reaches_the_long_factory() {
            // Proves the double overload has not swallowed ordinary integer construction.
            assertThat(Money.of(1_290_000, KES).minorUnits()).isEqualTo(1_290_000L);
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        void adds_within_a_currency() {
            assertThat(Money.of(1_290_000L, KES).plus(Money.of(25_000L, KES)))
                    .isEqualTo(Money.of(1_315_000L, KES));
        }

        @Test
        void subtracts_within_a_currency() {
            // PROBLEM.md section 5 day 12: 1,315,000 received less 1,290,000 carried = 25,000.
            assertThat(Money.of(1_315_000L, KES).minus(Money.of(1_290_000L, KES)))
                    .isEqualTo(Money.of(25_000L, KES));
        }

        @Test
        void negates() {
            assertThat(Money.of(793_200L, KES).negate()).isEqualTo(Money.of(-793_200L, KES));
            assertThat(Money.of(-793_200L, KES).negate()).isEqualTo(Money.of(793_200L, KES));
            assertThat(Money.zero(KES).negate()).isEqualTo(Money.zero(KES));
        }

        @Test
        void refuses_to_add_across_currencies() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of(1L, KES).plus(Money.of(1L, USDC)))
                    .withMessageContaining("no implicit conversion");
        }

        @Test
        void refuses_to_subtract_across_currencies() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of(1L, KES).minus(Money.of(1L, USDC)));
        }

        @Test
        void mismatch_exception_names_both_currencies() {
            CurrencyMismatchException thrown = null;
            try {
                Money.of(1L, KES).plus(Money.of(1L, USDC));
            } catch (CurrencyMismatchException e) {
                thrown = e;
            }
            assertThat(thrown).isNotNull();
            assertThat(thrown.left()).isEqualTo(KES);
            assertThat(thrown.right()).isEqualTo(USDC);
        }
    }

    @Nested
    @DisplayName("overflow is loud, never silent")
    class Overflow {

        @Test
        void addition_overflow_throws_rather_than_wrapping() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> Money.of(Long.MAX_VALUE, KES).plus(Money.of(1L, KES)));
        }

        @Test
        void subtraction_overflow_throws_rather_than_wrapping() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> Money.of(Long.MIN_VALUE, KES).minus(Money.of(1L, KES)));
        }

        @Test
        void negation_overflow_throws_rather_than_wrapping() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> Money.of(Long.MIN_VALUE, KES).negate());
        }
    }

    @Nested
    @DisplayName("value semantics and ordering")
    class ValueSemantics {

        @Test
        void equal_when_amount_and_currency_are_equal() {
            assertThat(Money.of(100L, KES)).isEqualTo(Money.of(100L, KES));
            assertThat(Money.of(100L, KES)).hasSameHashCodeAs(Money.of(100L, KES));
        }

        @Test
        void the_same_number_in_different_currencies_is_not_the_same_money() {
            assertThat(Money.of(100L, KES)).isNotEqualTo(Money.of(100L, USDC));
        }

        @Test
        void orders_within_a_currency() {
            assertThat(Money.of(1L, KES)).isLessThan(Money.of(2L, KES));
            assertThat(Money.of(-1L, KES)).isLessThan(Money.zero(KES));
            assertThat(Money.of(2L, KES)).isEqualByComparingTo(Money.of(2L, KES));
        }

        @Test
        void refuses_to_order_across_currencies() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of(1L, KES).compareTo(Money.of(1L, USDC)));
        }

        @Test
        void sign_predicates() {
            assertThat(Money.of(1L, KES).isPositive()).isTrue();
            assertThat(Money.of(1L, KES).isNegative()).isFalse();
            assertThat(Money.of(-1L, KES).isNegative()).isTrue();
            assertThat(Money.zero(KES).isZero()).isTrue();
            assertThat(Money.zero(KES).isPositive()).isFalse();
            assertThat(Money.zero(KES).isNegative()).isFalse();
        }
    }

    @Nested
    @DisplayName("toString, for logs and failure messages only")
    class Display {

        @Test
        void shows_minor_units_and_the_shifted_decimal_for_kes() {
            assertThat(Money.of(1_290_000L, KES)).hasToString("KES 1290000 (12900.00)");
        }

        @Test
        void shows_six_decimal_places_for_usdc() {
            assertThat(Money.of(10_000_000_000L, USDC)).hasToString("USDC 10000000000 (10000.000000)");
        }

        @Test
        void shows_negatives_as_negatives() {
            assertThat(Money.of(-2_800L, KES)).hasToString("KES -2800 (-28.00)");
        }
    }
}

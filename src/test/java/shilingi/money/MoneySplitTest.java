package shilingi.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static shilingi.money.Currency.KES;
import static shilingi.money.Currency.USDC;

/**
 * Day 4 is done when the "residue rule [is] proven" (SPEC.md section 15). This is that proof.
 *
 * <p>SPEC.md section 5: the difference goes to the <b>last</b> part, and is never scattered
 * proportionally.
 */
class MoneySplitTest {

    private static Money sum(List<Money> parts) {
        Money total = Money.zero(parts.get(0).currency());
        for (Money part : parts) {
            total = total.plus(part);
        }
        return total;
    }

    @Test
    @DisplayName("SPEC.md section 17 scenario 17: 1,000 shillings split three ways")
    void one_thousand_shillings_split_three_ways() {
        // KES 1,000.00 is 100,000 cents. A third is 33,333.33 cents, which does not exist.
        List<Money> parts = Money.of(100_000L, KES).split(3);

        assertThat(parts).containsExactly(
                Money.of(33_333L, KES),
                Money.of(33_333L, KES),
                Money.of(33_334L, KES));
    }

    @Test
    @DisplayName("the residue goes to the last part, and only to the last part")
    void the_residue_lands_on_the_last_part_alone() {
        // 100,000 split 7 ways: 14,285 each with 5 cents left over.
        List<Money> parts = Money.of(100_000L, KES).split(7);

        assertThat(parts.subList(0, 6))
                .as("every part but the last is the plain truncated share")
                .containsOnly(Money.of(14_285L, KES));

        assertThat(parts.get(6))
                .as("the whole residue is on the last part, not spread over several")
                .isEqualTo(Money.of(14_290L, KES));
    }

    @Test
    @DisplayName("the parts always sum back to the whole")
    void the_parts_sum_back_to_the_whole() {
        // This is the property that makes the rule worth having: no plug is ever needed to make
        // a split agree, for any amount and any number of parts.
        for (long amount : new long[]{0L, 1L, 7L, 99L, 100_000L, 131_500_000L, 999_999_937L}) {
            for (int parts = 1; parts <= 13; parts++) {
                Money whole = Money.of(amount, KES);
                assertThat(sum(whole.split(parts)))
                        .as("%s split %d ways", whole, parts)
                        .isEqualTo(whole);
            }
        }
    }

    @Test
    @DisplayName("negative amounts split symmetrically, because credits are negative")
    void negative_amounts_split_by_magnitude() {
        List<Money> parts = Money.of(-100_000L, KES).split(3);

        assertThat(parts).containsExactly(
                Money.of(-33_333L, KES),
                Money.of(-33_333L, KES),
                Money.of(-33_334L, KES));

        assertThat(sum(parts)).isEqualTo(Money.of(-100_000L, KES));
    }

    @Test
    @DisplayName("an amount smaller than the number of parts still balances")
    void an_amount_too_small_to_share_puts_everything_on_the_last_part() {
        // 3 cents split 5 ways. Four parts get nothing and the last gets 3. Odd-looking, and
        // exactly what the rule says: the difference goes to the last part.
        List<Money> parts = Money.of(3L, KES).split(5);

        assertThat(parts).containsExactly(
                Money.zero(KES), Money.zero(KES), Money.zero(KES), Money.zero(KES),
                Money.of(3L, KES));
    }

    @Test
    void splitting_into_one_part_returns_the_whole() {
        assertThat(Money.of(100_000L, KES).split(1)).containsExactly(Money.of(100_000L, KES));
    }

    @Test
    void an_exact_division_leaves_no_residue() {
        assertThat(Money.of(99_999L, KES).split(3)).containsOnly(Money.of(33_333L, KES));
    }

    @Test
    void splits_keep_their_currency() {
        assertThat(Money.of(10_000_000_000L, USDC).split(3))
                .allSatisfy(part -> assertThat(part.currency()).isEqualTo(USDC));
    }

    @Test
    void refuses_to_split_into_fewer_than_one_part() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Money.of(100L, KES).split(0));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Money.of(100L, KES).split(-1));
    }

    @Test
    void a_split_is_immutable() {
        List<Money> parts = Money.of(100_000L, KES).split(3);

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> parts.add(Money.zero(KES)));
    }
}

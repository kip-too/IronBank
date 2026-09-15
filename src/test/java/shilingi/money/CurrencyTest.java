package shilingi.money;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static shilingi.money.Currency.KES;
import static shilingi.money.Currency.USDC;

class CurrencyTest {

    @Test
    void scales_match_the_table_in_spec_section_5() {
        assertThat(KES.scale()).isEqualTo(2);
        assertThat(USDC.scale()).isEqualTo(6);
    }

    @Test
    void only_the_two_currencies_spec_section_5_defines_a_minor_unit_for_exist() {
        // USD is absent on purpose. SPEC.md section 6 gives account 1200 the currency USD but
        // section 5 defines no minor unit for it, and whether USD and USDC are one currency
        // for the per-currency balance check (I2) is an open question. This test exists so
        // that adding a currency is a deliberate act that breaks a test, not a quiet edit.
        assertThat(Currency.values()).containsExactly(KES, USDC);
    }
}

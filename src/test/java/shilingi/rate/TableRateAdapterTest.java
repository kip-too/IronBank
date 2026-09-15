package shilingi.rate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.money.Rate;
import shilingi.money.RateKind;
import shilingi.platform.AbstractDatabaseTest;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TableRateAdapterTest extends AbstractDatabaseTest {

    @Autowired
    private RatePort rates;

    @Test
    @DisplayName("returns the seeded mid-market rate with its source and timestamp")
    void reads_a_rate_whole() {
        Rate rate = rates.midRateOn(LocalDate.of(2026, 9, 12)).orElseThrow();

        assertThat(rate.value()).isEqualByComparingTo("131.50");
        assertThat(rate.source()).isEqualTo("worked-example");
        assertThat(rate.timestamp()).isEqualTo(Instant.parse("2026-09-12T06:00:00Z"));
        assertThat(rate.kind()).isEqualTo(RateKind.MID);
    }

    @Test
    @DisplayName("every rate in the worked example is reachable by its date")
    void the_worked_examples_four_rates_are_all_there() {
        assertThat(rates.midRateOn(LocalDate.of(2026, 9, 1)).orElseThrow().value())
                .isEqualByComparingTo("129.00");
        assertThat(rates.midRateOn(LocalDate.of(2026, 9, 12)).orElseThrow().value())
                .isEqualByComparingTo("131.50");
        assertThat(rates.midRateOn(LocalDate.of(2026, 9, 20)).orElseThrow().value())
                .isEqualByComparingTo("132.60");
        assertThat(rates.midRateOn(LocalDate.of(2026, 9, 30)).orElseThrow().value())
                .isEqualByComparingTo("130.80");
    }

    @Test
    @DisplayName("SPEC.md section 17 scenario 7: a day the feed has no entry for")
    void a_date_with_no_quote_returns_empty_rather_than_guessing() {
        // The day between two quotes. There is no rule in SPEC.md for what to do here, so the
        // port reports absence and declines to carry yesterday's rate forward. Carrying it
        // forward silently is how a stale figure enters the books looking like a fresh one.
        assertThat(rates.midRateOn(LocalDate.of(2026, 9, 13))).isEmpty();
        assertThat(rates.midRateOn(LocalDate.of(2026, 1, 1))).isEmpty();
    }

    @Test
    @DisplayName("the same date gives the same rate every time, so the demo and the tests are deterministic")
    void the_table_is_deterministic() {
        assertThat(rates.midRateOn(LocalDate.of(2026, 9, 12)))
                .isEqualTo(rates.midRateOn(LocalDate.of(2026, 9, 12)));
    }

    @Test
    @DisplayName("a rate read from the table converts to the figure in PROBLEM.md section 5")
    void a_rate_from_the_table_reproduces_the_worked_example() {
        Rate rate = rates.midRateOn(LocalDate.of(2026, 9, 12)).orElseThrow();

        assertThat(rate.toShillings(Money.of(10_000_000_000L, Currency.USDC)))
                .isEqualTo(Money.of(131_500_000L, Currency.KES));
    }

    @Test
    @DisplayName("the adapter offers no way to write a rate")
    void rates_arrive_by_migration_not_by_code() {
        // An adapter that could insert a rate could insert a convenient one.
        assertThat(TableRateAdapter.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .allSatisfy(name -> assertThat(name).doesNotStartWith("save")
                        .doesNotStartWith("insert")
                        .doesNotStartWith("create")
                        .doesNotStartWith("update")
                        .doesNotStartWith("delete"));
    }
}

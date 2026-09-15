package shilingi.fx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import shilingi.ledger.AccountCodes;
import shilingi.ledger.Balances;
import shilingi.ledger.JournalEntry;
import shilingi.ledger.LedgerService;
import shilingi.ledger.Posting;
import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.money.Rate;
import shilingi.platform.AbstractDatabaseTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Day 5 is done when "every figure in the worked example falls out unaided" (SPEC.md section 15).
 *
 * <p>SPEC.md section 10: <i>"The worked example in PROBLEM.md section 5 is the acceptance test.
 * Every figure in it - 25,000 / 4,200 / 2,400 / 7,139 / 2,800 - must fall out of the engine with
 * no adjustment. If one does not, the engine is wrong, not the example."</i>
 *
 * <p>All figures below are in CENTS. PROBLEM.md writes shillings, and KES has scale 2, so
 * 4,200 shillings is 420,000 here.
 */
class FxEngineTest extends AbstractDatabaseTest {

    @Autowired
    private FxEngine engine;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private Balances balances;

    private static final Instant DAY_12_AT = Instant.parse("2026-09-12T06:00:00Z");
    private static final Instant DAY_20_AT = Instant.parse("2026-09-20T06:00:00Z");
    private static final Instant RECORDED_AT = Instant.parse("2026-09-20T09:00:00Z");

    private static Money usdc(long dollars) {
        return Money.of(dollars * 1_000_000L, Currency.USDC);
    }

    private static Money kes(long cents) {
        return Money.of(cents, Currency.KES);
    }

    /** Puts dollars in the wallet at a stated rate, the way a receipt would. */
    private void receiveDollars(LocalDate on, long dollars, String rate, Instant at) {
        Money amount = usdc(dollars);
        Money value = Rate.mid(rate, "test-fixture", at).toShillings(amount);

        ledger.post(JournalEntry.of(on, "Received " + amount, "test/receipt", at,
                List.of(
                        new Posting(null, null, AccountCodes.WALLET_USDC, amount,
                                new BigDecimal(rate), "test-fixture", at, value),
                        Posting.inShillings(AccountCodes.REVENUE, value.negate()))));
    }

    private ConversionPostings convert(long dollars, String executed, String mid, long feeCents) {
        return engine.postingsFor(new ConversionFacts(
                LocalDate.of(2026, 9, 20),
                usdc(dollars),
                Rate.executed(executed, "provider", DAY_20_AT),
                Rate.mid(mid, "feed", DAY_20_AT),
                kes(feeCents),
                "test/conversion"), RECORDED_AT);
    }

    private static Money postedTo(JournalEntry entry, String accountCode) {
        return entry.postings().stream()
                .filter(p -> p.accountCode().equals(accountCode))
                .map(Posting::functionalAmount)
                .reduce(Money.zero(Currency.KES), Money::plus);
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("PROBLEM.md section 5, carried all the way through")
    class TheWorkedExample {

        @Test
        @DisplayName("day 20: every figure falls out - 4,200 difference, 2,400 spread, the fee as charged")
        void the_whole_of_day_twenty() {
            // Day 12: USDC 10,000 arrives and is carried at 131.50.
            receiveDollars(LocalDate.of(2026, 9, 12), 10_000, "131.50", DAY_12_AT);

            // Day 20: 6,000 converted. Executed 132.20, mid that morning 132.60,
            // explicit fee 0.9% of 793,200 = 713,880 cents exactly.
            ConversionPostings result = convert(6_000, "132.20", "132.60", 713_880L);

            JournalEntry movement = result.movement();
            JournalEntry cost = result.costOfConversion().orElseThrow();

            // --- the movement ---------------------------------------------------------
            assertThat(postedTo(movement, AccountCodes.BANK_KES))
                    .as("KES 793,200 received")
                    .isEqualTo(kes(79_320_000L));

            assertThat(postedTo(movement, AccountCodes.WALLET_USDC))
                    .as("the dollars leave at what the books carried them at: KES 789,000")
                    .isEqualTo(kes(-78_900_000L));

            assertThat(postedTo(movement, AccountCodes.EXCHANGE_DIFFERENCE_REALISED))
                    .as("4,200 of realised exchange difference - a credit, so negative")
                    .isEqualTo(kes(-420_000L));

            // --- the cost of conversion -----------------------------------------------
            assertThat(postedTo(cost, AccountCodes.CONVERSION_SPREAD))
                    .as("2,400 of spread: 0.40 per dollar over 6,000 dollars")
                    .isEqualTo(kes(240_000L));

            assertThat(postedTo(cost, AccountCodes.CONVERSION_FEE))
                    .as("the fee, exactly as charged")
                    .isEqualTo(kes(713_880L));

            assertThat(postedTo(cost, AccountCodes.PAYABLES))
                    .as("the fee is owed to the provider, not netted off what arrived")
                    .isEqualTo(kes(-713_880L));
        }

        @Test
        @DisplayName("after both entries, 6100 holds the market's 6,600 and 6200 holds the provider's 2,400")
        void the_split_is_the_point() {
            receiveDollars(LocalDate.of(2026, 9, 12), 10_000, "131.50", DAY_12_AT);

            ConversionPostings result = convert(6_000, "132.20", "132.60", 713_880L);
            result.all().forEach(ledger::post);

            // The receipt itself booked no exchange difference in this fixture, so everything in
            // 6100 comes from the conversion. 4,200 from the movement, plus 2,400 reclassified
            // out of it by the spread leg.
            assertThat(balances.carryingValueOf(AccountCodes.EXCHANGE_DIFFERENCE_REALISED))
                    .as("what the market did, measured honestly against mid: 6,600")
                    .isEqualTo(kes(-660_000L));

            assertThat(balances.carryingValueOf(AccountCodes.CONVERSION_SPREAD))
                    .as("what the provider took quietly: 2,400")
                    .isEqualTo(kes(240_000L));

            assertThat(balances.carryingValueOf(AccountCodes.CONVERSION_FEE))
                    .as("what the provider took openly")
                    .isEqualTo(kes(713_880L));

            // 6,600 credit less 2,400 debit nets to the 4,200 that actually happened.
            assertThat(balances.carryingValueOf(AccountCodes.EXCHANGE_DIFFERENCE_REALISED)
                    .plus(balances.carryingValueOf(AccountCodes.CONVERSION_SPREAD)))
                    .isEqualTo(kes(-420_000L));
        }

        @Test
        @DisplayName("the wallet is left holding exactly the 4,000 at 526,000 that day 30 expects")
        void the_wallet_is_left_where_day_thirty_starts() {
            receiveDollars(LocalDate.of(2026, 9, 12), 10_000, "131.50", DAY_12_AT);
            convert(6_000, "132.20", "132.60", 713_880L).all().forEach(ledger::post);

            assertThat(balances.balanceOf(AccountCodes.WALLET_USDC, Currency.USDC))
                    .as("USDC 4,000 still held")
                    .isEqualTo(usdc(4_000));

            assertThat(balances.carryingValueOf(AccountCodes.WALLET_USDC))
                    .as("carried at 4,000 x 131.50 = 526,000, which is what PROBLEM.md day 30 opens with")
                    .isEqualTo(kes(52_600_000L));
        }

        @Test
        @DisplayName("both entries survive the ledger's own seven checks")
        void the_engines_output_is_postable() {
            receiveDollars(LocalDate.of(2026, 9, 12), 10_000, "131.50", DAY_12_AT);

            // The engine does not write anything. If either entry failed to balance, or broke
            // I1 or I5, LedgerService would refuse it here.
            assertThat(convert(6_000, "132.20", "132.60", 713_880L).all())
                    .allSatisfy(entry -> assertThat(ledger.post(entry).id()).isNotNull());
        }

        @Test
        @DisplayName("the fee: 0.9% of 793,200 is 7,138.80, not the 7,139 PROBLEM.md prints")
        void the_fee_is_taken_as_charged_and_never_derived() {
            // SPEC.md section 10 says "fee = as charged", so the fee is an input and the engine
            // computes no percentage of anything. Recording the arithmetic here because SPEC.md
            // section 10 also says 7,139 must "fall out of the engine": in cents, 0.9% of
            // 79,320,000 is exactly 713,880 with no rounding at all, which is KES 7,138.80.
            // 7,139 is only reachable by rounding to whole shillings, and KES has scale 2.
            // See ADR-016.
            assertThat(79_320_000L * 9 / 1000).isEqualTo(713_880L);

            receiveDollars(LocalDate.of(2026, 9, 12), 10_000, "131.50", DAY_12_AT);

            // Whatever the provider bills is what gets recorded - including 7,139 exactly.
            ConversionPostings asPrinted = convert(6_000, "132.20", "132.60", 713_900L);
            assertThat(postedTo(asPrinted.costOfConversion().orElseThrow(), AccountCodes.CONVERSION_FEE))
                    .isEqualTo(kes(713_900L));
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 17, the awkward cases")
    class AdversarialScenarios {

        @Test
        @DisplayName("scenario 8: dollars arrive at three different rates, then half are converted")
        void three_receipts_then_half_converted() {
            // 1,000 at 130.00 = 130,000 ; 2,000 at 132.00 = 264,000 ; 3,000 at 131.00 = 393,000
            // 6,000 dollars carried at 787,000 shillings.
            receiveDollars(LocalDate.of(2026, 9, 1), 1_000, "130.00", DAY_12_AT);
            receiveDollars(LocalDate.of(2026, 9, 2), 2_000, "132.00", DAY_12_AT);
            receiveDollars(LocalDate.of(2026, 9, 3), 3_000, "131.00", DAY_12_AT);

            assertThat(balances.carryingValueOf(AccountCodes.WALLET_USDC)).isEqualTo(kes(78_700_000L));

            // Half of them, at 132.00.
            ConversionPostings result = convert(3_000, "132.00", "132.00", 0L);

            // Hand check: half of 787,000 is 393,500. Sold for 3,000 x 132 = 396,000.
            // The difference is 2,500. No calculator needed, which is the point.
            assertThat(postedTo(result.movement(), AccountCodes.WALLET_USDC)).isEqualTo(kes(-39_350_000L));
            assertThat(postedTo(result.movement(), AccountCodes.BANK_KES)).isEqualTo(kes(39_600_000L));
            assertThat(postedTo(result.movement(), AccountCodes.EXCHANGE_DIFFERENCE_REALISED))
                    .isEqualTo(kes(-250_000L));

            // The blended rate recorded on the wallet posting: 787,000 / 6,000 = 131.16666667.
            assertThat(result.movement().postings().stream()
                    .filter(p -> p.accountCode().equals(AccountCodes.WALLET_USDC))
                    .findFirst().orElseThrow().rateValue())
                    .isEqualByComparingTo("131.16666667");
        }

        @Test
        @DisplayName("scenario 9: the executed rate beats the mid, so the spread is negative and is not hidden")
        void beating_the_mid_gives_a_negative_spread() {
            receiveDollars(LocalDate.of(2026, 9, 12), 10_000, "131.50", DAY_12_AT);

            // Executed 132.60 against a mid of 132.20 - we did better than the market middle.
            ConversionPostings result = convert(6_000, "132.60", "132.20", 0L);

            assertThat(postedTo(result.costOfConversion().orElseThrow(), AccountCodes.CONVERSION_SPREAD))
                    .as("SPEC.md section 10: record it as negative and do not hide it")
                    .isEqualTo(kes(-240_000L));

            // And it still posts: a negative expense is a credit, and the entry balances.
            result.all().forEach(ledger::post);
            assertThat(balances.carryingValueOf(AccountCodes.CONVERSION_SPREAD)).isEqualTo(kes(-240_000L));
        }

        @Test
        @DisplayName("scenario 10: a fee larger than the exchange difference changes nothing structural")
        void a_fee_bigger_than_the_difference() {
            receiveDollars(LocalDate.of(2026, 9, 12), 10_000, "131.50", DAY_12_AT);

            // Difference will be 420,000; the fee is ten times that.
            ConversionPostings result = convert(6_000, "132.20", "132.60", 4_200_000L);
            result.all().forEach(ledger::post);

            // Nothing nets off against anything. Four accounts, four separate facts.
            assertThat(balances.carryingValueOf(AccountCodes.EXCHANGE_DIFFERENCE_REALISED))
                    .isEqualTo(kes(-660_000L));
            assertThat(balances.carryingValueOf(AccountCodes.CONVERSION_FEE)).isEqualTo(kes(4_200_000L));
            assertThat(balances.carryingValueOf(AccountCodes.PAYABLES)).isEqualTo(kes(-4_200_000L));
        }

        @Test
        @DisplayName("scenario 11: converting the entire wallet leaves exactly zero carrying value")
        void emptying_the_wallet_leaves_nothing_behind() {
            // An awkward blend on purpose: 1,000 at 130.00 and 2,000 at 131.00 gives a
            // weighted average of 130.666... which does not terminate.
            receiveDollars(LocalDate.of(2026, 9, 1), 1_000, "130.00", DAY_12_AT);
            receiveDollars(LocalDate.of(2026, 9, 2), 2_000, "131.00", DAY_12_AT);

            convert(3_000, "132.00", "132.00", 0L).all().forEach(ledger::post);

            assertThat(balances.balanceOf(AccountCodes.WALLET_USDC, Currency.USDC))
                    .as("no dollars left")
                    .isEqualTo(Money.zero(Currency.USDC));

            assertThat(balances.carryingValueOf(AccountCodes.WALLET_USDC))
                    .as("and no shilling value stranded on a wallet that holds nothing - "
                        + "that residue is what would later need a plug to remove")
                    .isEqualTo(Money.zero(Currency.KES));
        }

        @Test
        @DisplayName("scenario 12: converting more than is held is refused, not recorded")
        void cannot_convert_what_is_not_held() {
            receiveDollars(LocalDate.of(2026, 9, 12), 1_000, "131.50", DAY_12_AT);

            assertThatExceptionOfType(NothingToConvertException.class)
                    .isThrownBy(() -> convert(6_000, "132.20", "132.60", 0L));
        }

        @Test
        void converting_from_an_empty_wallet_is_refused() {
            assertThatExceptionOfType(NothingToConvertException.class)
                    .isThrownBy(() -> convert(1, "132.20", "132.60", 0L));
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the memo entry appears only when there is something to say")
    class TheCostEntry {

        @Test
        void a_conversion_exactly_at_mid_with_no_fee_produces_no_cost_entry() {
            receiveDollars(LocalDate.of(2026, 9, 12), 10_000, "131.50", DAY_12_AT);

            ConversionPostings result = convert(6_000, "132.20", "132.20", 0L);

            assertThat(result.costOfConversion())
                    .as("an entry full of zeroes says nothing and would still have to be read")
                    .isEmpty();
            assertThat(result.all()).hasSize(1);
        }

        @Test
        void spread_with_no_fee_produces_a_two_line_cost_entry() {
            receiveDollars(LocalDate.of(2026, 9, 12), 10_000, "131.50", DAY_12_AT);

            JournalEntry cost = convert(6_000, "132.20", "132.60", 0L).costOfConversion().orElseThrow();

            assertThat(cost.postings()).hasSize(2);
            assertThat(ledger.post(cost).id()).isNotNull();
        }

        @Test
        void a_fee_with_no_spread_produces_a_two_line_cost_entry() {
            receiveDollars(LocalDate.of(2026, 9, 12), 10_000, "131.50", DAY_12_AT);

            JournalEntry cost = convert(6_000, "132.20", "132.20", 713_880L)
                    .costOfConversion().orElseThrow();

            assertThat(cost.postings()).hasSize(2);
            assertThat(ledger.post(cost).id()).isNotNull();
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the engine refuses inputs that would produce a meaningless record")
    class Refusals {

        @Test
        @DisplayName("I4: a mid rate cannot be passed as the executed rate")
        void the_two_rates_are_not_interchangeable() {
            // If these could be swapped, the spread would silently become zero and the whole
            // point of PROBLEM.md section 5 would disappear without any error.
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    new ConversionFacts(LocalDate.of(2026, 9, 20), usdc(6_000),
                            Rate.mid("132.20", "feed", DAY_20_AT),
                            Rate.mid("132.60", "feed", DAY_20_AT),
                            kes(0L), "test"))
                    .withMessageContaining("not interchangeable");

            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    new ConversionFacts(LocalDate.of(2026, 9, 20), usdc(6_000),
                            Rate.executed("132.20", "provider", DAY_20_AT),
                            Rate.executed("132.60", "provider", DAY_20_AT),
                            kes(0L), "test"));
        }

        @Test
        void a_negative_fee_is_refused_because_that_is_what_a_negative_spread_is_for() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    new ConversionFacts(LocalDate.of(2026, 9, 20), usdc(6_000),
                            Rate.executed("132.20", "provider", DAY_20_AT),
                            Rate.mid("132.60", "feed", DAY_20_AT),
                            kes(-1L), "test"))
                    .withMessageContaining("negative SPREAD");
        }

        @Test
        void a_conversion_must_give_up_foreign_currency() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    new ConversionFacts(LocalDate.of(2026, 9, 20), kes(100L),
                            Rate.executed("132.20", "provider", DAY_20_AT),
                            Rate.mid("132.60", "feed", DAY_20_AT),
                            kes(0L), "test"));
        }

        @Test
        void a_conversion_of_nothing_is_refused() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    new ConversionFacts(LocalDate.of(2026, 9, 20), usdc(0),
                            Rate.executed("132.20", "provider", DAY_20_AT),
                            Rate.mid("132.60", "feed", DAY_20_AT),
                            kes(0L), "test"));
        }
    }
}

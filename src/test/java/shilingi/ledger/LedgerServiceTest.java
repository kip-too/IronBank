package shilingi.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.platform.AbstractDatabaseTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Day 3 is done when "all six rejection tests pass" (SPEC.md section 15). This is them, plus I5,
 * which SPEC.md section 9 also places in the ledger at post time.
 *
 * <p>Each rejection is named after the rule it proves, per SPEC.md section 9.
 */
class LedgerServiceTest extends AbstractDatabaseTest {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Every fixture date below is in the past relative to PinnedClockTestConfig's fixed clock,
     * which AbstractDatabaseTest supplies. The one test that is about the future derives its
     * date from that clock rather than hardcoding one.
     *
     * <p>Shilling figures are in CENTS: PROBLEM.md section 5 writes "1,315,000 shillings", and
     * KES has scale 2, so that is 131,500,000 minor units.
     */
    private static final LocalDate DAY_12 = LocalDate.of(2026, 9, 12);
    private static final Instant WRITTEN_AT = Instant.parse("2026-09-12T09:00:00Z");
    private static final Instant RATE_AT = Instant.parse("2026-09-12T06:00:00Z");

    @Autowired
    private shilingi.clock.ClockPort clock;

    private static Posting kes(String account, long minorUnits) {
        return Posting.inShillings(account, Money.of(minorUnits, Currency.KES));
    }

    private static Posting usdc(String account, long usdcMinorUnits, String rate, long kesMinorUnits) {
        return new Posting(null, null, account,
                Money.of(usdcMinorUnits, Currency.USDC),
                new BigDecimal(rate), "test-fixture", RATE_AT,
                Money.of(kesMinorUnits, Currency.KES));
    }

    private static JournalEntry entry(String description, List<Posting> postings) {
        return JournalEntry.of(DAY_12, description, "test/ledger", WRITTEN_AT, postings);
    }

    private long postingCount() {
        return jdbc.queryForObject("select count(*) from posting", Long.class);
    }

    private long entryCount() {
        return jdbc.queryForObject("select count(*) from journal_entry", Long.class);
    }

    // ------------------------------------------------------------------------------------
    // The six rejections of SPEC.md section 8
    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 8: the ledger's job is to refuse things")
    class TheSixRejections {

        @Test
        @DisplayName("1. postings do not sum to zero within any single currency")
        void rejects_an_entry_that_does_not_balance_within_its_only_currency() {
            var rejection = assertThatExceptionOfType(LedgerRejection.UnbalancedInCurrency.class)
                    .isThrownBy(() -> ledger.post(entry("off by one shilling", List.of(
                            kes(AccountCodes.BANK_KES, 1_290_000L),
                            kes(AccountCodes.REVENUE, -1_289_999L)))));

            rejection.satisfies(e -> {
                assertThat(e.currency()).isEqualTo(Currency.KES);
                assertThat(e.totalMinorUnits()).isEqualTo(1L);
            });
        }

        @Test
        @DisplayName("1b. a single-currency USDC entry must balance in dollars, not merely in shillings")
        void rejects_a_dollar_entry_that_balances_in_shillings_but_not_in_dollars() {
            // This is the case where the per-currency half of I2 does work the shilling check
            // cannot: the two legs balance to zero in shillings at their own rates, while the
            // dollar amounts do not cancel.
            assertThatExceptionOfType(LedgerRejection.UnbalancedInCurrency.class)
                    .isThrownBy(() -> ledger.post(entry("dollars that do not cancel", List.of(
                            usdc(AccountCodes.WALLET_USDC, 10_000_000_000L, "131.50000000", 131_500_000L),
                            usdc(AccountCodes.RECEIVABLES, -9_000_000_000L, "146.11111111", -131_500_000L)))));
        }

        @Test
        @DisplayName("2. postings do not sum to zero in shillings")
        void rejects_an_entry_that_does_not_balance_in_shillings() {
            var rejection = assertThatExceptionOfType(LedgerRejection.UnbalancedInShillings.class)
                    .isThrownBy(() -> ledger.post(entry("dollars cancel, shillings do not", List.of(
                            usdc(AccountCodes.WALLET_USDC, 10_000_000_000L, "131.50000000", 131_500_000L),
                            usdc(AccountCodes.RECEIVABLES, -10_000_000_000L, "129.00000000", -129_000_000L)))));

            // The 25,000 shillings (2,500,000 cents) of exchange difference has been left out. PROBLEM.md section 5
            // day 12 is exactly this entry with the 6100 leg present.
            rejection.satisfies(e -> assertThat(e.totalMinorUnits()).isEqualTo(2_500_000L));
        }

        @Test
        @DisplayName("3. a posting lacks a rate, rate source or rate timestamp when its currency is not KES")
        void rejects_a_foreign_posting_with_no_rate() {
            // I1. The rejection happens in Posting's constructor, which is the stronger guard -
            // the invalid posting cannot be built at all, so it never reaches the ledger. The
            // ledger keeps its own check as a second layer; see LedgerService.rejectMissingRates.
            assertThatExceptionOfType(MissingRateException.class).isThrownBy(() ->
                    new Posting(null, null, AccountCodes.WALLET_USDC,
                            Money.of(10_000_000_000L, Currency.USDC),
                            null, null, null,
                            Money.of(131_500_000L, Currency.KES)));
        }

        @Test
        @DisplayName("4. a posting references an account that does not exist")
        void rejects_a_posting_to_an_account_outside_the_chart() {
            var rejection = assertThatExceptionOfType(LedgerRejection.UnknownAccount.class)
                    .isThrownBy(() -> ledger.post(entry("posting to nowhere", List.of(
                            kes("7777", 1_000L),
                            kes(AccountCodes.REVENUE, -1_000L)))));

            rejection.satisfies(e -> assertThat(e.accountCode()).isEqualTo("7777"));
        }

        @Test
        @DisplayName("5. the entry has fewer than two postings")
        void rejects_an_entry_with_one_posting() {
            var rejection = assertThatExceptionOfType(LedgerRejection.TooFewPostings.class)
                    .isThrownBy(() -> ledger.post(entry("half a transaction", List.of(
                            kes(AccountCodes.BANK_KES, 0L)))));

            rejection.satisfies(e -> assertThat(e.count()).isEqualTo(1));
        }

        @Test
        @DisplayName("5b. an entry with no postings at all is refused too")
        void rejects_an_entry_with_no_postings() {
            assertThatExceptionOfType(LedgerRejection.TooFewPostings.class)
                    .isThrownBy(() -> ledger.post(entry("nothing at all", List.of())));
        }

        @Test
        @DisplayName("6. the business date is in the future relative to the injected clock")
        void rejects_an_entry_dated_after_today() {
            LocalDate tomorrow = clock.businessDate().plusDays(1);

            var rejection = assertThatExceptionOfType(LedgerRejection.FutureBusinessDate.class)
                    .isThrownBy(() -> ledger.post(JournalEntry.of(
                            tomorrow, "not yet", "test/ledger", WRITTEN_AT,
                            List.of(kes(AccountCodes.BANK_KES, 1_000L),
                                    kes(AccountCodes.REVENUE, -1_000L)))));

            rejection.satisfies(e -> {
                assertThat(e.businessDate()).isEqualTo(tomorrow);
                assertThat(e.today()).isEqualTo(clock.businessDate());
            });
        }

        @Test
        @DisplayName("6b. an entry dated today is accepted; the boundary is after, not on")
        void accepts_an_entry_dated_today() {
            JournalEntry posted = ledger.post(JournalEntry.of(
                    clock.businessDate(), "today is not the future", "test/ledger", WRITTEN_AT,
                    List.of(kes(AccountCodes.BANK_KES, 1_000L),
                            kes(AccountCodes.REVENUE, -1_000L))));

            assertThat(posted.id()).isNotNull();
        }
    }

    // ------------------------------------------------------------------------------------
    // I5, which SPEC.md section 9 also places in the ledger at post time
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("I5: realised and unrealised exchange difference never share an entry")
    void rejects_an_entry_touching_both_6100_and_6110() {
        // PROBLEM.md section 5: one is history, the other is an opinion about today.
        assertThatExceptionOfType(LedgerRejection.RealisedAndUnrealisedTogether.class)
                .isThrownBy(() -> ledger.post(entry("history and opinion in one breath", List.of(
                        kes(AccountCodes.BANK_KES, 10_000L),
                        kes(AccountCodes.EXCHANGE_DIFFERENCE_REALISED, -6_000L),
                        kes(AccountCodes.EXCHANGE_DIFFERENCE_UNREALISED, -4_000L)))));
    }

    // ------------------------------------------------------------------------------------
    // A rejection writes nothing
    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 8: rejection means nothing is written")
    class NothingIsWritten {

        @Test
        void a_rejected_entry_leaves_no_trace_in_the_journal() {
            assertThat(entryCount()).isZero();
            assertThat(postingCount()).isZero();

            assertThatExceptionOfType(LedgerRejection.class)
                    .isThrownBy(() -> ledger.post(entry("off by one", List.of(
                            kes(AccountCodes.BANK_KES, 1_290_000L),
                            kes(AccountCodes.REVENUE, -1_289_999L)))));

            assertThat(entryCount()).as("no half-written entry").isZero();
            assertThat(postingCount()).as("no orphan postings").isZero();
        }

        @Test
        void a_rejection_caught_late_still_writes_nothing() {
            // The unknown-account check runs third, after two postings have already been
            // validated. Nothing is inserted until every check has passed.
            assertThatExceptionOfType(LedgerRejection.UnknownAccount.class)
                    .isThrownBy(() -> ledger.post(entry("good, good, bad", List.of(
                            kes(AccountCodes.BANK_KES, 1_000L),
                            kes(AccountCodes.REVENUE, -500L),
                            kes("8888", -500L)))));

            assertThat(entryCount()).isZero();
            assertThat(postingCount()).isZero();
        }
    }

    // ------------------------------------------------------------------------------------
    // What the ledger accepts
    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the entries from PROBLEM.md section 5 are accepted as written")
    class AcceptsTheWorkedExample {

        @Test
        @DisplayName("day 1: invoice raised - a cross-currency entry that balances only in shillings")
        void day_one_invoice() {
            JournalEntry posted = ledger.post(JournalEntry.of(
                    LocalDate.of(2026, 9, 1), "USD 10,000 invoiced to Client A at 129.00",
                    "invoice/A-001", WRITTEN_AT,
                    List.of(usdc(AccountCodes.RECEIVABLES, 10_000_000_000L, "129.00000000", 129_000_000L),
                            kes(AccountCodes.REVENUE, -129_000_000L))));

            assertThat(posted.id()).isNotNull();
            assertThat(posted.postings()).hasSize(2);
        }

        @Test
        @DisplayName("day 12: money arrives - 25,000 of realised exchange difference, not revenue")
        void day_twelve_receipt() {
            JournalEntry posted = ledger.post(JournalEntry.of(
                    DAY_12, "USDC 10,000 received, carried in at 129.00, now worth 131.50",
                    "invoice/A-001", WRITTEN_AT,
                    List.of(usdc(AccountCodes.WALLET_USDC, 10_000_000_000L, "131.50000000", 131_500_000L),
                            usdc(AccountCodes.RECEIVABLES, -10_000_000_000L, "129.00000000", -129_000_000L),
                            kes(AccountCodes.EXCHANGE_DIFFERENCE_REALISED, -2_500_000L))));

            assertThat(posted.id()).isNotNull();

            // The 25,000 is in 6100 and nowhere near 4000. PROBLEM.md section 5 calls recording
            // it as revenue "the single most common error in a small set of books".
            assertThat(posted.postings())
                    .filteredOn(p -> p.accountCode().equals(AccountCodes.EXCHANGE_DIFFERENCE_REALISED))
                    .singleElement()
                    .satisfies(p -> assertThat(p.functionalAmount())
                            .isEqualTo(Money.of(-2_500_000L, Currency.KES)));
        }

        @Test
        @DisplayName("day 30: month-end revaluation - nothing moved, and the entry still balances")
        void day_thirty_revaluation() {
            // The wallet's dollar amount does not change at all. Only its shilling value does.
            // A USDC posting of zero dollars carrying a non-zero shilling value is exactly what
            // an unrealised revaluation is.
            JournalEntry posted = ledger.post(JournalEntry.of(
                    LocalDate.of(2026, 9, 30), "Month-end revaluation of USDC 4,000 at 130.80",
                    "revaluation/2026-09", WRITTEN_AT,
                    List.of(kes(AccountCodes.EXCHANGE_DIFFERENCE_UNREALISED, 280_000L),
                            usdc(AccountCodes.WALLET_USDC, 0L, "130.80000000", -280_000L))));

            assertThat(posted.id()).isNotNull();
            assertThat(posted.postings())
                    .filteredOn(p -> p.accountCode().equals(AccountCodes.WALLET_USDC))
                    .singleElement()
                    .satisfies(p -> {
                        assertThat(p.amount()).isEqualTo(Money.zero(Currency.USDC));
                        assertThat(p.functionalAmount()).isEqualTo(Money.of(-280_000L, Currency.KES));
                    });
        }
    }

    @Test
    @DisplayName("an entry that has already been posted cannot be posted again")
    void a_posted_entry_cannot_be_reposted() {
        JournalEntry posted = ledger.post(entry("once", List.of(
                kes(AccountCodes.BANK_KES, 1_000L),
                kes(AccountCodes.REVENUE, -1_000L))));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ledger.post(posted))
                .withMessageContaining("already been posted");
    }
}

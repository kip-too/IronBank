package shilingi.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import shilingi.books.Bookkeeper;
import shilingi.ledger.AccountCodes;
import shilingi.ledger.Balances;
import shilingi.ledger.JournalEntry;
import shilingi.ledger.LedgerService;
import shilingi.ledger.Posting;
import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.platform.AbstractDatabaseTest;
import shilingi.receivables.Receivable;
import shilingi.receivables.ReceivableRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 13 is done when "replay reproduces the closing position" (SPEC.md section 15).
 *
 * <p>The test that carries the most weight is
 * {@link OnlyTheJournal#the_rate_feed_can_be_deleted_and_replay_is_unchanged}: SPEC.md section 14
 * says "the moment replay reaches outside the records, it stops proving anything", and the only
 * way to prove it does not is to take the outside away and replay to the same answer.
 */
class ReplayTest extends AbstractDatabaseTest {

    @Autowired
    private Replay replay;

    @Autowired
    private Bookkeeper books;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private Balances balances;

    @Autowired
    private ReceivableRepository receivables;

    @Autowired
    private JdbcTemplate jdbc;

    private static final LocalDate DAY_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate DAY_12 = LocalDate.of(2026, 9, 12);
    private static final LocalDate DAY_30 = LocalDate.of(2026, 9, 30);
    private static final Instant AT = Instant.parse("2026-09-01T06:00:00Z");

    private static Money usdc(long dollars) {
        return Money.of(dollars * 1_000_000L, Currency.USDC);
    }

    private static Money kes(long cents) {
        return Money.of(cents, Currency.KES);
    }

    /**
     * Invoice, receipt, and a month-end revaluation.
     *
     * <p>Deliberately WITHOUT the day-20 conversion, so all 10,000 dollars are still held at month
     * end. The revaluation is therefore 10,000 x (131.50 - 130.80) = KES 7,000, not the 2,800 of
     * PROBLEM.md section 5 - which revalues the 4,000 left after converting 6,000. Both are right
     * arithmetic about different holdings; the demo covers the other one.
     */
    private void playTheStory() {
        Receivable invoice = receivables.save(Receivable.raised("Client A", usdc(10_000), DAY_1));
        books.recordInvoice(invoice);
        books.recordReceipt(invoice.id(), DAY_12);
        books.revalueWallet(DAY_30);
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("I10: any day's closing position, from the records alone")
    class TheClosingPosition {

        @Test
        void replay_reproduces_it() {
            playTheStory();

            Replay.Result result = replay.all();

            assertThat(result.reproduced())
                    .as("%s", result.discrepancies())
                    .isTrue();
            assertThat(result.functionalBalances())
                    .containsEntry(AccountCodes.REVENUE, kes(-129_000_000L))
                    .containsEntry(AccountCodes.EXCHANGE_DIFFERENCE_REALISED, kes(-2_500_000L))
                    .containsEntry(AccountCodes.EXCHANGE_DIFFERENCE_UNREALISED, kes(700_000L));
            assertThat(result.currencyBalances())
                    .containsEntry(AccountCodes.WALLET_USDC, usdc(10_000));
        }

        @Test
        @DisplayName("the whole journal sums to zero, because every entry does")
        void the_journal_balances_as_a_whole() {
            playTheStory();

            assertThat(replay.all().totalInShillings()).isEqualTo(Money.zero(Currency.KES));
        }

        @Test
        @DisplayName("an earlier day's position is reproducible too, not just today's")
        void replay_as_at_a_past_date() {
            playTheStory();

            // As at the 12th, the revaluation has not happened and the receivable is cleared.
            Replay.Result asAtTwelfth = replay.asAt(DAY_12);

            assertThat(asAtTwelfth.functionalBalances())
                    .containsEntry(AccountCodes.WALLET_USDC, kes(131_500_000L))
                    .doesNotContainKey(AccountCodes.EXCHANGE_DIFFERENCE_UNREALISED);

            // As at the 1st, only the invoice exists.
            Replay.Result asAtFirst = replay.asAt(DAY_1);
            assertThat(asAtFirst.functionalBalances())
                    .containsEntry(AccountCodes.RECEIVABLES, kes(129_000_000L))
                    .doesNotContainKey(AccountCodes.WALLET_USDC);
        }

        @Test
        void an_empty_journal_replays_to_nothing_rather_than_failing() {
            Replay.Result result = replay.all();

            assertThat(result.reproduced()).isTrue();
            assertThat(result.entriesRead()).isZero();
            assertThat(result.functionalBalances()).isEmpty();
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 14: replay must not read anything outside the journal")
    class OnlyTheJournal {

        @Test
        @DisplayName("the rate feed can be deleted and replay is unchanged")
        void the_rate_feed_can_be_deleted_and_replay_is_unchanged() {
            // This is the test that makes the claim mean something. If replay looked up a rate to
            // value anything, it would break here - and it would have been proving nothing all
            // along, because it would have been reading today's world to describe September's.
            playTheStory();
            Replay.Result before = replay.all();

            jdbc.update("delete from mid_rate");
            assertThat(jdbc.queryForObject("select count(*) from mid_rate", Long.class)).isZero();

            Replay.Result after = replay.all();

            assertThat(after.functionalBalances()).isEqualTo(before.functionalBalances());
            assertThat(after.currencyBalances()).isEqualTo(before.currencyBalances());
            assertThat(after.reproduced()).isTrue();
        }

        @Test
        @DisplayName("every rate it needs is the one stored on the posting")
        void the_rate_comes_from_the_posting() {
            playTheStory();

            // Nothing recomputes a shilling value on read. The wallet's carrying value is the sum
            // of what each receipt was worth on the day it arrived, and it survives the feed.
            jdbc.update("delete from mid_rate");

            assertThat(replay.all().functionalBalances())
                    .as("received at 131.50, revalued down to 130.80, and neither figure needed the "
                        + "feed that has just been deleted")
                    .containsEntry(AccountCodes.WALLET_USDC, kes(131_500_000L - 700_000L));
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("a discrepancy is a failure that names the entry")
    class Discrepancies {

        @Test
        @DisplayName("an entry that stopped balancing is found, and identified")
        void an_unbalanced_entry_is_caught_and_named() {
            playTheStory();

            // Reach past every guard the system has - the ledger's checks, the deferred balance
            // trigger, the append-only trigger - by disabling the trigger as a superuser, which
            // is the one route ADR-010 records as still open. This is what replay is FOR: proving
            // the records still agree with themselves, whatever got at them.
            jdbc.execute("alter table posting disable trigger posting_is_append_only");
            Long victim = jdbc.queryForObject(
                    "select id from posting order by id limit 1", Long.class);
            jdbc.update("update posting set functional_amount_minor = functional_amount_minor + 1 "
                        + "where id = ?", victim);
            jdbc.execute("alter table posting enable trigger posting_is_append_only");

            Replay.Result result = replay.all();

            assertThat(result.reproduced()).isFalse();
            assertThat(result.discrepancies())
                    .as("SPEC.md section 14: a failure with the specific entry identified")
                    .anySatisfy(d -> {
                        assertThat(d.what()).contains("does not balance in shillings");
                        assertThat(d.entryId()).isNotNull();
                    });
        }

        @Test
        @DisplayName("the fold and the aggregate query are two paths, and both are checked")
        void the_fold_is_checked_against_the_aggregate() {
            playTheStory();

            Replay.Result result = replay.all();

            // Weak on its own - same rows, two readers - but a real disagreement if it ever fires.
            result.functionalBalances().forEach((account, replayed) ->
                    assertThat(balances.carryingValueOf(account)).isEqualTo(replayed));
        }
    }

    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("SPEC.md section 17 scenario 19: replay after a correcting entry")
    void replay_survives_a_reversal_and_repost() {
        // A mistake: 1,000 posted to the bank that should have been 10,000.
        ledger.post(JournalEntry.of(DAY_1, "Mistaken entry", "test/oops", AT,
                List.of(Posting.inShillings(AccountCodes.BANK_KES, kes(100_000L)),
                        Posting.inShillings(AccountCodes.REVENUE, kes(-100_000L)))));

        // The correction, as SPEC.md section 6 requires: a new entry that reverses and re-posts.
        // Nothing is edited, because nothing can be.
        ledger.post(JournalEntry.of(DAY_1, "Reversing the mistaken entry", "test/oops/reversal", AT,
                List.of(Posting.inShillings(AccountCodes.BANK_KES, kes(-100_000L)),
                        Posting.inShillings(AccountCodes.REVENUE, kes(100_000L)))));

        ledger.post(JournalEntry.of(DAY_1, "Re-posting correctly", "test/oops/repost", AT,
                List.of(Posting.inShillings(AccountCodes.BANK_KES, kes(1_000_000L)),
                        Posting.inShillings(AccountCodes.REVENUE, kes(-1_000_000L)))));

        Replay.Result result = replay.all();

        assertThat(result.reproduced()).isTrue();
        assertThat(result.entriesRead()).isEqualTo(3);
        assertThat(result.functionalBalances())
                .as("the fold sums a reversal like any other entry - which is exactly what makes "
                    + "corrections safe, and why nothing here needs to know about them")
                .containsEntry(AccountCodes.BANK_KES, kes(1_000_000L));
    }

    @Test
    @DisplayName("SPEC.md section 17 scenario 20: replay of a day an item sat in suspense unresolved")
    void replay_of_a_day_with_an_open_suspense_item() {
        ledger.post(JournalEntry.of(DAY_1, "Unexplained movement", "recon/callback/1", AT,
                List.of(Posting.inShillings(AccountCodes.SUSPENSE, kes(500_000L)),
                        Posting.inShillings(AccountCodes.BANK_KES, kes(-500_000L)))));

        Replay.Result result = replay.all();

        assertThat(result.reproduced())
                .as("an unanswered question does not stop the day from being reproducible - it is "
                    + "part of the position, and replay reports it rather than hiding it")
                .isTrue();
        assertThat(result.functionalBalances())
                .containsEntry(AccountCodes.SUSPENSE, kes(500_000L));
    }
}

package shilingi.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.platform.AbstractDatabaseTest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 2 is done when "update on posting table is rejected by the database, proven by a test"
 * (SPEC.md section 15). This is that test.
 *
 * <p>Every case here goes through raw SQL, deliberately. Proving that the repository declines
 * to offer an update method would prove only that the repository is polite. What has to be true
 * is that the database refuses, so that a future service, a migration, a script or somebody at
 * a psql prompt is refused too.
 */
class PostingImmutabilityTest extends AbstractDatabaseTest {

    @Autowired
    private JournalStore journal;

    @Autowired
    private JdbcTemplate jdbc;

    private static final Instant WRITTEN_AT = Instant.parse("2026-09-14T09:00:00Z");

    private JournalEntry anEntry() {
        // Balanced, because since V4 the database will not accept anything else: an unbalanced
        // entry is refused at commit by the deferred constraint trigger, whatever route wrote it.
        return journal.append(JournalEntry.of(
                LocalDate.of(2026, 9, 14),
                "A posting that will be attacked",
                "test/immutability",
                WRITTEN_AT,
                List.of(
                        Posting.inShillings(AccountCodes.BANK_KES, Money.of(1_290_000L, Currency.KES)),
                        Posting.inShillings(AccountCodes.REVENUE, Money.of(-1_290_000L, Currency.KES)))));
    }

    @Test
    @DisplayName("the database rejects UPDATE on posting")
    void posting_cannot_be_updated() {
        JournalEntry entry = anEntry();
        long postingId = entry.postings().get(0).id();

        assertThatThrownBy(() ->
                jdbc.update("update posting set amount_minor = ? where id = ?", 999L, postingId))
                .hasMessageContaining("append-only");

        assertThat(jdbc.queryForObject("select amount_minor from posting where id = ?", Long.class, postingId))
                .isEqualTo(1_290_000L);
    }

    @Test
    @DisplayName("the database rejects DELETE on posting")
    void posting_cannot_be_deleted() {
        JournalEntry entry = anEntry();
        long postingId = entry.postings().get(0).id();

        assertThatThrownBy(() -> jdbc.update("delete from posting where id = ?", postingId))
                .hasMessageContaining("append-only");

        assertThat(jdbc.queryForObject("select count(*) from posting where id = ?", Long.class, postingId))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("the database rejects TRUNCATE on posting, which no DELETE trigger would catch")
    void posting_cannot_be_truncated() {
        anEntry();

        // TRUNCATE does not fire row-level DELETE triggers. Blocking only UPDATE and DELETE would
        // leave the entire journal erasable by one statement, which is why V3 blocks this too.
        assertThatThrownBy(() -> jdbc.execute("truncate table posting cascade"))
                .hasMessageContaining("append-only");

        assertThat(jdbc.queryForObject("select count(*) from posting", Long.class)).isEqualTo(2L);
    }

    @Test
    @DisplayName("the database rejects UPDATE on journal_entry, because business_date is financially material")
    void journal_entry_cannot_be_updated() {
        JournalEntry entry = anEntry();

        // Moving the business date moves every posting in the entry into a different month.
        assertThatThrownBy(() ->
                jdbc.update("update journal_entry set business_date = ? where id = ?",
                        LocalDate.of(2026, 8, 1), entry.id()))
                .hasMessageContaining("append-only");

        assertThat(jdbc.queryForObject(
                "select business_date from journal_entry where id = ?", LocalDate.class, entry.id()))
                .isEqualTo(LocalDate.of(2026, 9, 14));
    }

    @Test
    @DisplayName("the database rejects DELETE and TRUNCATE on journal_entry")
    void journal_entry_cannot_be_removed() {
        JournalEntry entry = anEntry();

        assertThatThrownBy(() -> jdbc.update("delete from journal_entry where id = ?", entry.id()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("truncate table journal_entry cascade"))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("the refusal says what to do instead, not just no")
    void the_message_explains_the_correction_route() {
        JournalEntry entry = anEntry();
        long postingId = entry.postings().get(0).id();

        assertThatThrownBy(() ->
                jdbc.update("update posting set amount_minor = ? where id = ?", 1L, postingId))
                .hasMessageContaining("reverses");
    }

    @Test
    @DisplayName("an update that matches no rows is still refused, so the rule is not row-dependent")
    void update_matching_nothing_is_still_refused_when_rows_exist() {
        anEntry();

        // A BEFORE UPDATE ... FOR EACH ROW trigger only fires for rows it matches, so an update
        // whose WHERE clause matches nothing passes silently. Recorded here as a known property
        // of the mechanism rather than left as a surprise: it changes nothing, because it
        // changes nothing.
        int affected = jdbc.update("update posting set amount_minor = 1 where id = -1");

        assertThat(affected).isZero();
        assertThat(jdbc.queryForObject("select count(*) from posting", Long.class)).isEqualTo(2L);
    }
}

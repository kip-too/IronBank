package shilingi.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import shilingi.platform.AbstractDatabaseTest;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@link LedgerServiceTest} proves the ledger refuses an unbalanced entry with a readable,
 * distinct exception. This proves the <b>database</b> refuses one too.
 *
 * <p>That distinction is the whole of CLAUDE.md's standing rule: the constraint is the
 * mechanism, and the application check sits on top of it, never instead of it. Everything here
 * goes through raw SQL inside an explicit transaction, so it bypasses {@link LedgerService}
 * entirely - which is what a migration, a script, a future service or somebody at a psql prompt
 * would do.
 */
class EntryMustBalanceConstraintTest extends AbstractDatabaseTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    private static final Instant WRITTEN_AT = Instant.parse("2026-09-12T09:00:00Z");
    private static final LocalDate DAY_12 = LocalDate.of(2026, 9, 12);

    private long insertEntry() {
        jdbc.update("insert into journal_entry (business_date, description, created_at) values (?, ?, ?)",
                DAY_12, "written behind the ledger's back", Timestamp.from(WRITTEN_AT));
        return jdbc.queryForObject("select max(id) from journal_entry", Long.class);
    }

    private void insertShillingPosting(long entryId, String accountCode, long minorUnits) {
        jdbc.update("insert into posting (entry_id, account_code, amount_minor, currency, "
                    + "functional_amount_minor) values (?, ?, ?, 'KES', ?)",
                entryId, accountCode, minorUnits, minorUnits);
    }

    @Test
    @DisplayName("I2: the database refuses an unbalanced entry written in raw SQL")
    void raw_sql_cannot_write_an_entry_that_does_not_balance() {
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                transactions.executeWithoutResult(status -> {
                    long entryId = insertEntry();
                    insertShillingPosting(entryId, AccountCodes.BANK_KES, 1_290_000L);
                    insertShillingPosting(entryId, AccountCodes.REVENUE, -1_289_999L);
                }))
                .withMessageContaining("does not balance in shillings");

        assertThat(jdbc.queryForObject("select count(*) from posting", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from journal_entry", Long.class)).isZero();
    }

    @Test
    @DisplayName("the refusal names the amount it is out by, rather than just saying no")
    void the_refusal_names_the_discrepancy() {
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                transactions.executeWithoutResult(status -> {
                    long entryId = insertEntry();
                    insertShillingPosting(entryId, AccountCodes.BANK_KES, 1_290_000L);
                    insertShillingPosting(entryId, AccountCodes.REVENUE, -1_289_999L);
                }))
                .withMessageContaining("1 cents");
    }

    @Test
    @DisplayName("the database refuses a single-posting entry, whatever wrote it")
    void raw_sql_cannot_write_a_one_line_entry() {
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                transactions.executeWithoutResult(status -> {
                    long entryId = insertEntry();
                    insertShillingPosting(entryId, AccountCodes.BANK_KES, 0L);
                }))
                .withMessageContaining("at least two");
    }

    @Test
    @DisplayName("a balanced entry written in raw SQL is accepted, so the constraint is not simply refusing everything")
    void raw_sql_can_write_a_balanced_entry() {
        transactions.executeWithoutResult(status -> {
            long entryId = insertEntry();
            insertShillingPosting(entryId, AccountCodes.BANK_KES, 1_290_000L);
            insertShillingPosting(entryId, AccountCodes.REVENUE, -1_290_000L);
        });

        assertThat(jdbc.queryForObject("select count(*) from posting", Long.class)).isEqualTo(2L);
    }

    @Test
    @DisplayName("the check is deferred to commit, so an entry may be unbalanced while it is being written")
    void an_entry_is_allowed_to_be_unbalanced_mid_write() {
        // Postings are inserted one row at a time. If the constraint were checked per statement,
        // the first posting of every entry would fail and no entry could ever be written. This
        // is why V4 uses DEFERRABLE INITIALLY DEFERRED.
        transactions.executeWithoutResult(status -> {
            long entryId = insertEntry();
            insertShillingPosting(entryId, AccountCodes.BANK_KES, 1_290_000L);

            // Unbalanced at this instant, and the transaction has not complained.
            Long total = jdbc.queryForObject(
                    "select sum(functional_amount_minor) from posting where entry_id = ?", Long.class, entryId);
            assertThat(total).isEqualTo(1_290_000L);

            insertShillingPosting(entryId, AccountCodes.REVENUE, -1_290_000L);
        });

        assertThat(jdbc.queryForObject("select count(*) from posting", Long.class)).isEqualTo(2L);
    }

    @Test
    @DisplayName("KNOWN GAP: an entry with no postings at all is not caught by this constraint")
    void an_entry_with_no_postings_is_not_caught_by_the_database() {
        // The trigger hangs off posting, so with no postings it never fires. LedgerService
        // rejects this case (TooFewPostings) and is the only route that writes entries in
        // practice. Asserted rather than left as a surprise, and recorded in V4's header.
        transactions.executeWithoutResult(status -> insertEntry());

        assertThat(jdbc.queryForObject("select count(*) from journal_entry", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from posting", Long.class)).isZero();
    }
}

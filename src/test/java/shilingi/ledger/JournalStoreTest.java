package shilingi.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalStoreTest extends AbstractDatabaseTest {

    @Autowired
    private JournalStore journal;

    @Autowired
    private JdbcTemplate jdbc;

    private static final Instant WRITTEN_AT = Instant.parse("2026-09-14T09:00:00Z");
    private static final Instant RATE_AT = Instant.parse("2026-09-12T06:00:00Z");
    private static final LocalDate DAY_12 = LocalDate.of(2026, 9, 12);

    /** The shilling leg that makes a fixture balance. V4 refuses an entry that does not. */
    private Posting balancingShillings(long minorUnits) {
        return Posting.inShillings(AccountCodes.REVENUE, Money.of(minorUnits, Currency.KES));
    }

    /** PROBLEM.md section 5, day 12: USDC 10,000 received at 131.50. */
    private Posting tenThousandDollarsReceived() {
        return new Posting(null, null, "1100",
                Money.of(10_000_000_000L, Currency.USDC),
                new BigDecimal("131.50000000"),
                "test-fixture",
                RATE_AT,
                Money.of(131_500_000L, Currency.KES));
    }

    @Test
    void writes_an_entry_and_reads_it_back_unchanged() {
        JournalEntry written = journal.append(JournalEntry.of(
                DAY_12, "USDC 10,000 received from Client A", "invoice/A-001", WRITTEN_AT,
                List.of(tenThousandDollarsReceived(), balancingShillings(-131_500_000L))));

        assertThat(written.id()).isNotNull();

        JournalEntry read = journal.findById(written.id()).orElseThrow();

        assertThat(read.businessDate()).isEqualTo(DAY_12);
        assertThat(read.description()).isEqualTo("USDC 10,000 received from Client A");
        assertThat(read.sourceRef()).isEqualTo("invoice/A-001");
        assertThat(read.createdAt()).isEqualTo(WRITTEN_AT);
        assertThat(read.postings()).hasSize(2);

        Posting p = read.postings().get(0);
        assertThat(p.accountCode()).isEqualTo("1100");
        assertThat(p.amount()).isEqualTo(Money.of(10_000_000_000L, Currency.USDC));
        assertThat(p.functionalAmount()).isEqualTo(Money.of(131_500_000L, Currency.KES));
        assertThat(p.rateSource()).isEqualTo("test-fixture");
        assertThat(p.rateTimestamp()).isEqualTo(RATE_AT);
    }

    @Test
    @DisplayName("the rate survives the round trip at scale 8, not rounded to the nearest anything")
    void the_stored_rate_keeps_its_scale() {
        JournalEntry written = journal.append(JournalEntry.of(
                DAY_12, "rate precision", null, WRITTEN_AT,
                List.of(tenThousandDollarsReceived(), balancingShillings(-131_500_000L))));

        BigDecimal readBack = journal.findById(written.id()).orElseThrow().postings().get(0).rateValue();

        assertThat(readBack).isEqualByComparingTo("131.50000000");
        assertThat(readBack.scale()).isEqualTo(8);
    }

    @Test
    @DisplayName("the functional amount is stored, not recomputed on read")
    void the_functional_amount_is_a_stored_fact() {
        // SPEC.md section 6: the shilling value of a posting is a fact about the moment it was
        // made. Nothing in the read path multiplies anything by anything, so a later change of
        // rate cannot reach backwards and rewrite this number. Proven by storing a functional
        // amount that does NOT follow from the rate and observing that it comes back untouched.
        Posting deliberatelyInconsistent = new Posting(null, null, "1100",
                Money.of(1_000_000L, Currency.USDC),
                new BigDecimal("131.50000000"),
                "test-fixture", RATE_AT,
                Money.of(7L, Currency.KES));

        JournalEntry written = journal.append(JournalEntry.of(
                DAY_12, "stored not derived", null, WRITTEN_AT,
                List.of(deliberatelyInconsistent, balancingShillings(-7L))));

        assertThat(journal.findById(written.id()).orElseThrow().postings().get(0).functionalAmount())
                .isEqualTo(Money.of(7L, Currency.KES));
    }

    @Test
    @DisplayName("I1: the database refuses a foreign posting with no rate")
    void a_foreign_posting_without_a_rate_is_refused_by_the_database() {
        // Posting's own constructor refuses this too, so the insert is made in raw SQL to prove
        // the constraint is the mechanism and the Java check is only the early warning.
        long entryId = anEmptyEntryId();

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                jdbc.update("insert into posting (entry_id, account_code, amount_minor, currency, "
                            + "functional_amount_minor) values (?, ?, ?, ?, ?)",
                        entryId, "1100", 10_000_000_000L, "USDC", 131_500_000L));
    }

    @Test
    @DisplayName("I1: the Posting type refuses a foreign posting with no rate, before the database sees it")
    void a_foreign_posting_without_a_rate_is_refused_in_java() {
        assertThatExceptionOfType(MissingRateException.class).isThrownBy(() ->
                new Posting(null, null, "1100", Money.of(1L, Currency.USDC),
                        null, null, null, Money.zero(Currency.KES)))
                .withMessageContaining("rumour");
    }

    @Test
    @DisplayName("a posting must be in its own account's currency")
    void a_posting_cannot_be_in_a_currency_its_account_does_not_hold() {
        long entryId = anEmptyEntryId();

        // Account 1000 is the KES bank account. Posting dollars into it is meaningless.
        assertThatThrownBy(() ->
                jdbc.update("insert into posting (entry_id, account_code, amount_minor, currency, "
                            + "rate_value, rate_source, rate_timestamp, functional_amount_minor) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                        entryId, "1000", 1L, "USDC", new BigDecimal("131.50000000"),
                        "test-fixture", java.sql.Timestamp.from(RATE_AT), 131L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void a_posting_to_an_account_that_does_not_exist_is_refused() {
        long entryId = anEmptyEntryId();

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                jdbc.update("insert into posting (entry_id, account_code, amount_minor, currency, "
                            + "functional_amount_minor) values (?, ?, ?, ?, ?)",
                        entryId, "7777", 1L, "KES", 1L));
    }

    @Test
    @DisplayName("a shilling posting is its own functional value, enforced by the database")
    void a_shilling_posting_cannot_disagree_with_itself() {
        long entryId = anEmptyEntryId();

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                jdbc.update("insert into posting (entry_id, account_code, amount_minor, currency, "
                            + "functional_amount_minor) values (?, ?, ?, ?, ?)",
                        entryId, "1000", 100L, "KES", 101L));
    }

    @Test
    void a_negative_or_zero_rate_is_refused() {
        long entryId = anEmptyEntryId();

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                jdbc.update("insert into posting (entry_id, account_code, amount_minor, currency, "
                            + "rate_value, rate_source, rate_timestamp, functional_amount_minor) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                        entryId, "1100", 1L, "USDC", new BigDecimal("0.00000000"),
                        "test-fixture", java.sql.Timestamp.from(RATE_AT), 0L));
    }

    @Test
    @DisplayName("created_at comes from the caller, because the database has no DEFAULT now()")
    void created_at_has_no_database_default() {
        long entryId = anEmptyEntryId();

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                jdbc.update("insert into journal_entry (business_date, description) values (?, ?)",
                        DAY_12, "no created_at supplied"));

        assertThat(entryId).isPositive();
    }

    @Test
    @DisplayName("a functional amount must be in shillings")
    void the_functional_amount_must_be_kes() {
        assertThatThrownBy(() ->
                new Posting(null, null, "1100", Money.of(1L, Currency.USDC),
                        new BigDecimal("131.50000000"), "test-fixture", RATE_AT,
                        Money.of(1L, Currency.USDC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be in KES");
    }

    @Test
    void entries_come_back_oldest_first() {
        journal.append(JournalEntry.of(LocalDate.of(2026, 9, 20), "second", null, WRITTEN_AT,
                List.of(Posting.inShillings(AccountCodes.BANK_KES, Money.of(1L, Currency.KES)),
                        balancingShillings(-1L))));
        journal.append(JournalEntry.of(LocalDate.of(2026, 9, 12), "first", null, WRITTEN_AT,
                List.of(Posting.inShillings(AccountCodes.BANK_KES, Money.of(2L, Currency.KES)),
                        balancingShillings(-2L))));

        assertThat(journal.findAll()).extracting(JournalEntry::description)
                .containsExactly("first", "second");
    }

    private long anEmptyEntryId() {
        jdbc.update("insert into journal_entry (business_date, description, created_at) values (?, ?, ?)",
                DAY_12, "scaffolding for a posting test", java.sql.Timestamp.from(WRITTEN_AT));
        return jdbc.queryForObject("select max(id) from journal_entry", Long.class);
    }
}

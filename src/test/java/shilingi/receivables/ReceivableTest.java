package shilingi.receivables;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.platform.AbstractDatabaseTest;
import shilingi.platform.PinnedClockTestConfig;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceivableTest extends AbstractDatabaseTest {

    @Autowired
    private ReceivableRepository receivables;

    @Autowired
    private JdbcTemplate jdbc;

    private static final LocalDate TODAY = LocalDate.ofInstant(
            PinnedClockTestConfig.PINNED, PinnedClockTestConfig.ZONE);

    private static Money usdc(long dollars) {
        return Money.of(dollars * 1_000_000L, Currency.USDC);
    }

    private Receivable invoiceClientA(LocalDate issued) {
        return receivables.save(Receivable.raised("Client A", usdc(10_000), issued));
    }

    @Test
    @DisplayName("PROBLEM.md section 5 day 1: an invoice is raised and is outstanding")
    void an_invoice_is_raised_outstanding() {
        Receivable raised = invoiceClientA(LocalDate.of(2026, 9, 1));

        assertThat(raised.id()).isNotNull();
        assertThat(raised.status()).isEqualTo(ReceivableStatus.OUTSTANDING);
        assertThat(raised.amount()).isEqualTo(usdc(10_000));
        assertThat(raised.counterparty()).isEqualTo("Client A");
    }

    @Test
    @DisplayName("PROBLEM.md section 5 day 12: the money arrives and the invoice settles")
    void an_invoice_settles() {
        Receivable raised = invoiceClientA(LocalDate.of(2026, 9, 1));

        assertThat(receivables.settle(raised.id()).status()).isEqualTo(ReceivableStatus.SETTLED);
        assertThat(receivables.outstanding()).isEmpty();
    }

    @Test
    @DisplayName("settling twice is refused, because one receipt must not explain two invoices")
    void a_second_settlement_is_an_exception_not_a_no_op() {
        // PROBLEM.md F3 is orphan credits. Quietly accepting a repeat settlement is how a
        // receipt ends up matched to an invoice that was already paid by a different one.
        Receivable raised = invoiceClientA(LocalDate.of(2026, 9, 1));
        receivables.settle(raised.id());

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> receivables.settle(raised.id()))
                .withMessageContaining("reconciliation exception");
    }

    @Test
    @DisplayName("a settled receivable does not become outstanding again")
    void settlement_is_terminal_at_the_database_too() {
        Receivable raised = invoiceClientA(LocalDate.of(2026, 9, 1));
        receivables.settle(raised.id());

        assertThatThrownBy(() ->
                jdbc.update("update receivable set status = 'OUTSTANDING' where id = ?", raised.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("its own event");
    }

    @Test
    @DisplayName("an invoice cannot be quietly restated")
    void only_the_status_may_change() {
        Receivable raised = invoiceClientA(LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() ->
                jdbc.update("update receivable set amount_minor = 1 where id = ?", raised.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Only the status");

        assertThatThrownBy(() ->
                jdbc.update("update receivable set counterparty = 'Client B' where id = ?", raised.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("age is measured from the issue date against the injected clock")
    void age_is_derived_not_stored() {
        // There is no due date to be late against - SPEC.md section 6 gives a receivable an
        // issue_date only - so age is what this system can honestly say about an invoice.
        Receivable raised = invoiceClientA(TODAY.minusDays(45));

        assertThat(raised.ageInDaysOn(TODAY)).isEqualTo(45L);
        assertThat(raised.ageInDaysOn(raised.issueDate())).isZero();
    }

    @Test
    void outstanding_reads_oldest_invoice_first() {
        invoiceClientA(TODAY.minusDays(10));
        invoiceClientA(TODAY.minusDays(60));
        invoiceClientA(TODAY.minusDays(30));

        assertThat(receivables.outstanding())
                .extracting(Receivable::issueDate)
                .containsExactly(TODAY.minusDays(60), TODAY.minusDays(30), TODAY.minusDays(10));
    }

    @Test
    void a_receivable_must_be_for_a_positive_amount_and_have_a_counterparty() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                Receivable.raised("Client A", Money.zero(Currency.USDC), TODAY));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                Receivable.raised("  ", usdc(1), TODAY));
    }

    @Test
    @DisplayName("the database refuses a status outside the two that exist")
    void no_other_status_can_be_written() {
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                jdbc.update("insert into receivable (counterparty, currency, amount_minor, issue_date, status) "
                            + "values (?, ?, ?, ?, ?)",
                        "Client A", "USDC", 1_000_000L, TODAY, "OVERDUE"));
    }
}

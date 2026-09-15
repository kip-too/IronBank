package shilingi.obligations;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

/**
 * Day 6 is done when "overdue is reachable and visible" (SPEC.md section 15).
 *
 * <p>Reachable is proven by moving the clock past a due date. Visible is proven by
 * {@link ObligationRepository#overdue()} returning it without anything having run in between -
 * no sweeper, no job, no scheduled task.
 */
class ObligationTest extends AbstractDatabaseTest {

    @Autowired
    private ObligationRepository obligations;

    @Autowired
    private JdbcTemplate jdbc;

    /** The pinned clock, so tests can move time rather than wait for it. */
    private static final LocalDate TODAY = LocalDate.ofInstant(
            PinnedClockTestConfig.PINNED, PinnedClockTestConfig.ZONE);

    private static Money kes(long cents) {
        return Money.of(cents, Currency.KES);
    }

    private Obligation payrollDue(LocalDate on) {
        return obligations.save(Obligation.scheduled("Payroll September", kes(80_000_000L), on));
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 7: OVERDUE, which is worked out rather than remembered")
    class Overdue {

        @Test
        @DisplayName("an obligation becomes overdue when its date passes, with nothing having run")
        void overdue_is_reachable_by_the_passage_of_time_alone() {
            payrollDue(TODAY.plusDays(5));

            assertThat(obligations.overdueOn(TODAY)).isEmpty();
            assertThat(obligations.overdueOn(TODAY.plusDays(5)))
                    .as("due today is not yet past due")
                    .isEmpty();

            assertThat(obligations.overdueOn(TODAY.plusDays(6)))
                    .as("the day after, and no sweeper ran in between")
                    .extracting(Obligation::name)
                    .containsExactly("Payroll September");
        }

        @Test
        @DisplayName("overdue is visible through the repository, which is what section 7 demands")
        void overdue_is_visible() {
            // "this is a bug in the agent's planning, and must be visible as one"
            payrollDue(TODAY.minusDays(1));

            assertThat(obligations.overdue())
                    .extracting(Obligation::name)
                    .containsExactly("Payroll September");
        }

        @Test
        @DisplayName("the derived state agrees with the query")
        void the_state_and_the_query_say_the_same_thing() {
            Obligation late = payrollDue(TODAY.minusDays(1));

            assertThat(late.stateOn(TODAY)).isEqualTo(ObligationState.OVERDUE);
            assertThat(late.isOverdueOn(TODAY)).isTrue();
            assertThat(obligations.overdueOn(TODAY)).extracting(Obligation::id)
                    .containsExactly(late.id());
        }

        @Test
        @DisplayName("SPEC.md section 7: the OVERDUE arrow comes off SCHEDULED, not FUNDED")
        void a_funded_obligation_is_not_overdue_when_its_date_passes() {
            // Funded and unpaid past its date is an operational matter. Unfunded and past due is
            // a planning failure. Section 7 draws the arrow from SCHEDULED only.
            Obligation funded = payrollDue(TODAY.minusDays(10));
            obligations.updateStatus(funded.id(), ObligationStatus.FUNDED);

            assertThat(obligations.overdueOn(TODAY)).isEmpty();
            assertThat(obligations.findById(funded.id()).orElseThrow().stateOn(TODAY))
                    .isEqualTo(ObligationState.FUNDED);
        }

        @Test
        @DisplayName("SPEC.md section 17 scenario 13: an obligation created with a due date in the past")
        void a_backdated_obligation_is_immediately_overdue_and_says_so() {
            // Accepted rather than refused. Refusing would refuse to record something true, and
            // hiding a real overdue debt is worse than having one. See ADR-017.
            Obligation backdated = payrollDue(TODAY.minusMonths(2));

            assertThat(backdated.id()).isNotNull();
            assertThat(backdated.stateOn(TODAY)).isEqualTo(ObligationState.OVERDUE);
            assertThat(obligations.overdue()).extracting(Obligation::id).contains(backdated.id());
        }

        @Test
        @DisplayName("the database refuses to store OVERDUE at all")
        void overdue_cannot_be_written_as_a_status() {
            // If it could be stored it could be stale, and a stale OVERDUE is invisible exactly
            // when the job that sets it has failed.
            assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                    jdbc.update("insert into obligation (name, currency, amount_minor, due_date, status) "
                                + "values (?, ?, ?, ?, ?)",
                            "Rent", "KES", 5_000_000L, TODAY, "OVERDUE"));
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 7: SCHEDULED to FUNDED to PAID, and nothing else")
    class TheStateMachine {

        @Test
        void the_happy_path() {
            Obligation o = payrollDue(TODAY.plusDays(5));

            assertThat(o.status()).isEqualTo(ObligationStatus.SCHEDULED);
            assertThat(o.isFunded()).isFalse();

            assertThat(obligations.updateStatus(o.id(), ObligationStatus.FUNDED).status())
                    .isEqualTo(ObligationStatus.FUNDED);
            assertThat(obligations.updateStatus(o.id(), ObligationStatus.PAID).status())
                    .isEqualTo(ObligationStatus.PAID);

            assertThat(obligations.findById(o.id()).orElseThrow().stateOn(TODAY))
                    .isEqualTo(ObligationState.PAID);
        }

        @Test
        @DisplayName("SCHEDULED cannot skip straight to PAID")
        void funding_cannot_be_skipped() {
            Obligation o = payrollDue(TODAY.plusDays(5));

            assertThatExceptionOfType(IllegalTransitionException.class)
                    .isThrownBy(() -> obligations.updateStatus(o.id(), ObligationStatus.PAID))
                    .withMessageContaining("never funded");
        }

        @Test
        void a_paid_obligation_does_not_go_back() {
            Obligation o = payrollDue(TODAY.plusDays(5));
            obligations.updateStatus(o.id(), ObligationStatus.FUNDED);
            obligations.updateStatus(o.id(), ObligationStatus.PAID);

            assertThatExceptionOfType(IllegalTransitionException.class)
                    .isThrownBy(() -> obligations.updateStatus(o.id(), ObligationStatus.FUNDED));
            assertThatExceptionOfType(IllegalTransitionException.class)
                    .isThrownBy(() -> obligations.updateStatus(o.id(), ObligationStatus.SCHEDULED));
        }

        @Test
        @DisplayName("the database refuses an illegal transition too, not just the repository")
        void the_state_machine_is_enforced_at_the_database() {
            Obligation o = payrollDue(TODAY.plusDays(5));

            assertThatThrownBy(() ->
                    jdbc.update("update obligation set status = 'PAID' where id = ?", o.id()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("SCHEDULED to FUNDED to PAID");
        }

        @Test
        @DisplayName("only the status may change; the amount and the due date cannot")
        void an_obligation_cannot_be_quietly_restated() {
            // The agent plans against these numbers. Changing them underneath it would make the
            // intent log a record of a decision about figures that no longer exist.
            Obligation o = payrollDue(TODAY.plusDays(5));

            assertThatThrownBy(() ->
                    jdbc.update("update obligation set amount_minor = 1 where id = ?", o.id()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Only the status");

            assertThatThrownBy(() ->
                    jdbc.update("update obligation set due_date = ? where id = ?",
                            TODAY.plusYears(1), o.id()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("what the agent will ask on day 8")
    class PlanningQueries {

        @Test
        @DisplayName("unfunded obligations inside a horizon, which is what invariant I8 is checked against")
        void unfunded_due_by_a_date() {
            Obligation soon = payrollDue(TODAY.plusDays(5));
            Obligation later = obligations.save(
                    Obligation.scheduled("Rent November", kes(5_000_000L), TODAY.plusDays(60)));
            Obligation alreadyFunded = obligations.save(
                    Obligation.scheduled("Statutory", kes(1_200_000L), TODAY.plusDays(10)));
            obligations.updateStatus(alreadyFunded.id(), ObligationStatus.FUNDED);

            assertThat(obligations.unfundedDueBy(TODAY.plusDays(35)))
                    .extracting(Obligation::id)
                    .containsExactly(soon.id())
                    .doesNotContain(later.id(), alreadyFunded.id());
        }

        @Test
        @DisplayName("an overdue obligation is still unfunded and still needs money")
        void overdue_obligations_are_included_in_the_funding_gap() {
            Obligation late = payrollDue(TODAY.minusDays(3));

            assertThat(obligations.unfundedDueBy(TODAY.plusDays(35)))
                    .extracting(Obligation::id)
                    .contains(late.id());
        }

        @Test
        void the_calendar_reads_soonest_first() {
            payrollDue(TODAY.plusDays(20));
            payrollDue(TODAY.plusDays(5));
            payrollDue(TODAY.plusDays(12));

            assertThat(obligations.findAll())
                    .extracting(Obligation::dueDate)
                    .containsExactly(TODAY.plusDays(5), TODAY.plusDays(12), TODAY.plusDays(20));
        }
    }

    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("an obligation for nothing, or for a negative amount, is not an obligation")
    void an_obligation_must_be_for_a_positive_amount() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                Obligation.scheduled("Nothing", kes(0L), TODAY));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                Obligation.scheduled("Negative", kes(-1L), TODAY));
    }

    @Test
    void an_obligation_needs_a_name() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                Obligation.scheduled("   ", kes(100L), TODAY));
    }

    @Test
    @DisplayName("stateOn refuses to guess the date, because nothing calls now()")
    void the_date_must_be_supplied() {
        Obligation o = Obligation.scheduled("Payroll", kes(100L), TODAY);

        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> o.stateOn(null))
                .withMessageContaining("injected clock");
    }

    @Test
    @DisplayName("obligations may be denominated in dollars as well as shillings")
    void an_obligation_can_be_foreign() {
        // PROBLEM.md section 1 lists only KES outgoings, but SPEC.md section 6 gives an
        // obligation a currency, so nothing here assumes shillings.
        Obligation inDollars = obligations.save(Obligation.scheduled(
                "Overseas supplier", Money.of(1_000_000_000L, Currency.USDC), TODAY.plusDays(10)));

        assertThat(obligations.findById(inDollars.id()).orElseThrow().amount().currency())
                .isEqualTo(Currency.USDC);
    }
}

package shilingi.recon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import shilingi.instruction.Instruction;
import shilingi.instruction.InstructionRepository;
import shilingi.instruction.InstructionType;
import shilingi.intent.Intent;
import shilingi.intent.IntentLog;
import shilingi.ledger.AccountCodes;
import shilingi.ledger.Balances;
import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.payout.CallbackIngest;
import shilingi.payout.MockPayoutRail;
import shilingi.payout.PayoutPort;
import shilingi.payout.RailBehaviour;
import shilingi.platform.AbstractDatabaseTest;
import shilingi.platform.PinnedClockTestConfig;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SPEC.md section 13's three-way match, and the ageing that turns operational noise into an
 * exception.
 */
class ReconcilerTest extends AbstractDatabaseTest {

    @Autowired
    private Reconciler reconciler;

    @Autowired
    private MockPayoutRail rail;

    @Autowired
    private CallbackIngest ingest;

    @Autowired
    private InstructionRepository instructions;

    @Autowired
    private IntentLog intents;

    @Autowired
    private Balances balances;

    @Autowired
    private JdbcTemplate jdbc;

    private static final LocalDate TODAY = LocalDate.ofInstant(
            PinnedClockTestConfig.PINNED, PinnedClockTestConfig.ZONE);

    private static Money kes(long cents) {
        return Money.of(cents, Currency.KES);
    }

    private Instruction.Submitted submitted(String ref, Money amount) {
        long intentId = intents.append(Intent.proposing(
                PinnedClockTestConfig.PINNED, "test", "because", "{}", "{}")).id();
        Instruction.Submitted s = instructions
                .create(intentId, ref, InstructionType.PAYOUT, amount, PinnedClockTestConfig.PINNED)
                .submit(PinnedClockTestConfig.PINNED);
        instructions.transitionTo(s);
        return s;
    }

    private void deliver() {
        rail.deliverPending(ingest::accept);
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("settlement without intent goes to suspense, not to nothing")
    class UnmatchedInbound {

        @Test
        @DisplayName("PROBLEM.md F3: money nobody asked for is raised and posted to 1900")
        void an_unmatched_callback_reaches_suspense() {
            rail.reset().behaving(RailBehaviour.CALLBACK_FOR_UNKNOWN_INSTRUCTION);
            submitted("payout/real", kes(80_000_000L));
            rail.send(new PayoutPort.PayoutRequest("payout/real", kes(80_000_000L),
                    "Payroll", PinnedClockTestConfig.PINNED));
            deliver();

            Reconciler.Report report = reconciler.reconcile();

            assertThat(report.raised()).isEqualTo(1);
            assertThat(report.open()).singleElement().satisfies(item -> {
                assertThat(item.kind()).isEqualTo(ReconKind.UNMATCHED_INBOUND);
                assertThat(item.detail()).contains("no instruction carries");
                assertThat(item.firstSeen()).isEqualTo(TODAY);
            });

            assertThat(balances.carryingValueOf(AccountCodes.SUSPENSE))
                    .as("I6: unexplained money lands in 1900 with a date on it")
                    .isEqualTo(kes(80_000_000L));
        }

        @Test
        @DisplayName("running it again raises nothing and, crucially, does not re-date the item")
        void reconciliation_is_idempotent() {
            rail.reset().behaving(RailBehaviour.CALLBACK_FOR_UNKNOWN_INSTRUCTION);
            submitted("payout/real", kes(1_000L));
            rail.send(new PayoutPort.PayoutRequest("payout/real", kes(1_000L),
                    "Payroll", PinnedClockTestConfig.PINNED));
            deliver();

            reconciler.reconcile();
            Reconciler.Report second = reconciler.reconcile();

            assertThat(second.raised()).isZero();
            assertThat(second.alreadyKnown()).isEqualTo(1);
            assertThat(reconciler.all()).hasSize(1);

            assertThat(balances.carryingValueOf(AccountCodes.SUSPENSE))
                    .as("and it is not posted to suspense twice")
                    .isEqualTo(kes(1_000L));
        }

        @Test
        @DisplayName("first_seen cannot be moved, or an item could stay young by being looked at")
        void the_ageing_cannot_be_reset() {
            rail.reset().behaving(RailBehaviour.CALLBACK_FOR_UNKNOWN_INSTRUCTION);
            submitted("payout/real", kes(1_000L));
            rail.send(new PayoutPort.PayoutRequest("payout/real", kes(1_000L),
                    "Payroll", PinnedClockTestConfig.PINNED));
            deliver();
            reconciler.reconcile();

            long id = reconciler.open().get(0).id();

            // A DIFFERENT date. Setting it to the value it already holds is a no-op that the
            // trigger correctly allows, and a test that did that would prove nothing.
            assertThatThrownBy(() ->
                    jdbc.update("update recon_item set first_seen = ? where id = ?",
                            TODAY.plusDays(5), id))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("stay young for ever");
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("amounts differ: an exception, always, regardless of size")
    class AmountMismatch {

        @Test
        void one_cent_is_enough() {
            rail.reset().behaving(RailBehaviour.CALLBACK_WITH_WRONG_AMOUNT);
            submitted("payout/short", kes(80_000_000L));
            rail.send(new PayoutPort.PayoutRequest("payout/short", kes(80_000_000L),
                    "Payroll", PinnedClockTestConfig.PINNED));
            deliver();

            reconciler.reconcile();

            assertThat(reconciler.open()).singleElement().satisfies(item -> {
                assertThat(item.kind()).isEqualTo(ReconKind.AMOUNT_MISMATCH);
                assertThat(item.detail()).contains("regardless of size");
            });
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("intent without settlement: in flight, or stale")
    class InFlight {

        @Test
        @DisplayName("something sent today is in flight, and nobody needs to know")
        void fresh_instructions_are_not_raised() {
            submitted("payout/fresh", kes(1_000L));

            assertThat(reconciler.reconcile().open())
                    .as("below the threshold it is operational noise")
                    .isEmpty();
        }

        @Test
        @DisplayName("something still unsettled past the threshold becomes an exception")
        void a_stale_instruction_is_raised() {
            // Created a fortnight ago, and still nobody knows what happened to it.
            long intentId = intents.append(Intent.proposing(
                    PinnedClockTestConfig.PINNED, "test", "because", "{}", "{}")).id();
            jdbc.update("""
                    insert into instruction (intent_id, external_ref, created_at, type,
                                             amount_minor, currency, state, attempts)
                    values (?, 'payout/ancient', ?, 'PAYOUT', 1000, 'KES', 'AWAITING_RESOLUTION', 1)
                    """, intentId,
                    java.sql.Timestamp.from(PinnedClockTestConfig.PINNED.minusSeconds(14 * 86400)));

            Reconciler.Report report = reconciler.reconcile();

            assertThat(report.open()).singleElement().satisfies(item -> {
                assertThat(item.kind()).isEqualTo(ReconKind.IN_FLIGHT_STALE);
                assertThat(item.detail()).contains("re-queried, never retried");
            });
            assertThat(report.exceptions())
                    .as("above the threshold it appears on the screen")
                    .hasSize(1);
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("ageing, in business days")
    class Ageing {

        private ReconItem itemFirstSeenOn(LocalDate when) {
            return new ReconItem(1L, ReconKind.UNMATCHED_INBOUND, "callback", "1", when,
                    ReconItem.ReconState.OPEN, java.util.Optional.empty(), "x");
        }

        @Test
        @DisplayName("weekends do not count, so a Friday item is one business day old on Monday")
        void weekends_are_excluded() {
            LocalDate friday = LocalDate.of(2026, 9, 4);
            assertThat(friday.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.FRIDAY);

            ReconItem item = itemFirstSeenOn(friday);

            assertThat(item.ageInBusinessDaysOn(friday)).isZero();
            assertThat(item.ageInBusinessDaysOn(friday.plusDays(1)))
                    .as("Saturday")
                    .isZero();
            assertThat(item.ageInBusinessDaysOn(friday.plusDays(3)))
                    .as("Monday - one business day, three calendar days")
                    .isEqualTo(1L);
            assertThat(item.ageInDaysOn(friday.plusDays(3)))
                    .as("and the calendar age says three, which is a different question")
                    .isEqualTo(3L);
        }

        @Test
        @DisplayName("an item is never negatively aged, whatever the clock does")
        void the_clock_moving_backwards_does_not_produce_a_negative_age() {
            // SPEC.md section 17 scenario 15. MutableClock permits going backwards on purpose.
            ReconItem item = itemFirstSeenOn(LocalDate.of(2026, 9, 20));

            assertThat(item.ageInBusinessDaysOn(LocalDate.of(2026, 9, 1))).isZero();
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("resolving takes a name and an explanation")
    class Resolution {

        private long anOpenItem() {
            rail.reset().behaving(RailBehaviour.CALLBACK_FOR_UNKNOWN_INSTRUCTION);
            submitted("payout/real", kes(1_000L));
            rail.send(new PayoutPort.PayoutRequest("payout/real", kes(1_000L),
                    "Payroll", PinnedClockTestConfig.PINNED));
            deliver();
            reconciler.reconcile();
            return reconciler.open().get(0).id();
        }

        @Test
        void an_item_can_be_answered() {
            long id = anOpenItem();

            reconciler.resolve(id, "K. Kurgat", "the provider confirmed it was another client's payment");

            assertThat(reconciler.open()).isEmpty();
            assertThat(reconciler.all()).singleElement()
                    .satisfies(item -> assertThat(item.isOpen()).isFalse());
        }

        @Test
        @DisplayName("an item cannot close itself")
        void a_resolution_needs_somebody_behind_it() {
            long id = anOpenItem();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> reconciler.resolve(id, "  ", "it was fine"))
                    .withMessageContaining("nobody answered");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> reconciler.resolve(id, "K. Kurgat", " "));
        }

        @Test
        @DisplayName("a resolved item does not re-open; a new question is a new item")
        void resolution_is_final() {
            long id = anOpenItem();
            reconciler.resolve(id, "K. Kurgat", "explained");

            assertThatThrownBy(() ->
                    jdbc.update("update recon_item set state = 'OPEN' where id = ?", id))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("a new question is a new item");
        }

        @Test
        void a_recon_item_cannot_be_deleted() {
            long id = anOpenItem();

            assertThatThrownBy(() -> jdbc.update("delete from recon_item where id = ?", id))
                    .hasMessageContaining("append-only");
        }
    }

    @Test
    @DisplayName("a matched, settled instruction raises nothing at all")
    void the_happy_path_is_silent() {
        rail.reset().behaving(RailBehaviour.SUCCEED);
        submitted("payout/fine", kes(1_000L));
        rail.send(new PayoutPort.PayoutRequest("payout/fine", kes(1_000L),
                "Payroll", PinnedClockTestConfig.PINNED));
        deliver();

        Reconciler.Report report = reconciler.reconcile();

        assertThat(report.raised()).isZero();
        assertThat(report.open()).isEmpty();
        assertThat(balances.carryingValueOf(AccountCodes.SUSPENSE)).isEqualTo(Money.zero(Currency.KES));
    }
}

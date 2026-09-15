package shilingi.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import shilingi.instruction.Instruction;
import shilingi.instruction.InstructionRepository;
import shilingi.instruction.InstructionState;
import shilingi.instruction.InstructionType;
import shilingi.ledger.AccountCodes;
import shilingi.ledger.JournalEntry;
import shilingi.ledger.LedgerService;
import shilingi.ledger.Posting;
import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.money.Rate;
import shilingi.obligations.Obligation;
import shilingi.obligations.ObligationRepository;
import shilingi.platform.AbstractDatabaseTest;
import shilingi.platform.PinnedClockTestConfig;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * SPEC.md section 11 step 7, and the two gaps in the agent that day 9's instruction states made
 * fixable: money already in flight, and dollars owed in dollars.
 *
 * <p>See ADR-022. Both were real defects rather than missing features - before this, two decision
 * cycles run before a settlement would each have proposed the whole shortfall, converting roughly
 * twice what was needed.
 */
class TreasuryServiceTest extends AbstractDatabaseTest {

    @Autowired
    private TreasuryAgent agent;

    @Autowired
    private TreasuryService treasury;

    @Autowired
    private InstructionRepository instructions;

    @Autowired
    private ObligationRepository obligations;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcTemplate jdbc;

    private static final LocalDate TODAY = LocalDate.ofInstant(
            PinnedClockTestConfig.PINNED, PinnedClockTestConfig.ZONE);
    private static final Instant AT = PinnedClockTestConfig.PINNED;

    private static Money kes(long cents) {
        return Money.of(cents, Currency.KES);
    }

    private static Money usdc(long dollars) {
        return Money.of(dollars * 1_000_000L, Currency.USDC);
    }

    private void publishMidRate(String value) {
        jdbc.update("insert into mid_rate (rate_date, value, source, quoted_at) values (?, ?, ?, ?)",
                TODAY, new BigDecimal(value), "test-feed", Timestamp.from(AT));
    }

    private void payroll(long cents, int daysFromToday) {
        obligations.save(Obligation.scheduled("Payroll", kes(cents), TODAY.plusDays(daysFromToday)));
    }

    /** KES 100,000 in the bank, so the arithmetic matches the worked figures below. */
    private void haveShillings(long cents) {
        ledger.post(JournalEntry.of(TODAY, "opening shillings", "test/setup", AT,
                List.of(Posting.inShillings(AccountCodes.BANK_KES, kes(cents)),
                        Posting.inShillings(AccountCodes.REVENUE, kes(-cents)))));
    }

    private void haveDollars(long dollars, String rate) {
        Money amount = usdc(dollars);
        Money value = Rate.mid(rate, "test-fixture", AT).toShillings(amount);
        ledger.post(JournalEntry.of(TODAY, "opening dollars", "test/setup", AT,
                List.of(new Posting(null, null, AccountCodes.WALLET_USDC, amount,
                                new BigDecimal(rate), "test-fixture", AT, value),
                        Posting.inShillings(AccountCodes.REVENUE, value.negate()))));
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 11 step 7: only then create the instruction")
    class StepSeven {

        @Test
        void an_approved_conversion_becomes_an_instruction_referencing_its_intent() {
            payroll(80_000_000L, 5);
            haveShillings(10_000_000L);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            Decision decision = agent.runCycle("daily");
            Instruction.Created created = treasury.act(decision).orElseThrow();

            assertThat(created.intentId()).isEqualTo(decision.intent().id());
            assertThat(created.state()).isEqualTo(InstructionState.CREATED);
            assertThat(created.amount()).isEqualTo(usdc(5_883));
            assertThat(created.externalRef()).isEqualTo("convert/" + decision.intent().id());
        }

        @Test
        @DisplayName("a refused decision produces nothing at all - not a row marked refused")
        void a_refusal_creates_no_instruction() {
            payroll(80_000_000L, 5);
            haveShillings(10_000_000L);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            Decision refused = agent.decideOn("t", new ProposedAction.Hold("waiting"), "because");

            assertThat(refused.refused()).isTrue();
            assertThat(treasury.act(refused)).isEmpty();
            assertThat(instructions.findAll())
                    .as("a row somebody could un-refuse by changing one column would not be a refusal")
                    .isEmpty();
        }

        @Test
        void a_permitted_hold_produces_nothing_either() {
            publishMidRate("132.60");

            Decision decision = agent.runCycle("daily");

            assertThat(decision.approved()).isTrue();
            assertThat(treasury.act(decision)).isEmpty();
        }

        @Test
        @DisplayName("acting on the same decision twice hits the unique constraint, not a second instruction")
        void the_external_reference_makes_the_second_attempt_fail() {
            // F5. The reference is derived from the intent, so it is the same both times, and
            // SPEC.md section 13's constraint does the work rather than an application check.
            payroll(80_000_000L, 5);
            haveShillings(10_000_000L);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            Decision decision = agent.runCycle("daily");
            treasury.act(decision);

            assertThatExceptionOfType(DataIntegrityViolationException.class)
                    .isThrownBy(() -> treasury.act(decision));

            assertThat(instructions.findAll()).hasSize(1);
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("ADR-022: money already in flight is not converted twice")
    class InFlight {

        @Test
        @DisplayName("a second cycle before settlement does not propose the gap again")
        void the_gap_is_not_proposed_twice() {
            payroll(80_000_000L, 5);
            haveShillings(10_000_000L);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            // Cycle one: 780,000 short, so 5,883 dollars.
            Decision first = agent.runCycle("daily");
            assertThat(first.plan().mustConvert()).isEqualTo(usdc(5_883));
            treasury.act(first);

            // Cycle two, before anything settles. The shillings have not arrived, but they are
            // on their way, and the dollars behind them are spoken for.
            Decision second = agent.runCycle("daily");

            assertThat(second.plan().inFlightCover())
                    .as("5,883 at 132.60 is 780,085.80, which covers the gap")
                    .isEqualTo(kes(78_008_580L));
            assertThat(second.plan().shortfall()).isEqualTo(Money.zero(Currency.KES));
            assertThat(second.plan().mustConvert()).isEqualTo(Money.zero(Currency.USDC));
            assertThat(second.approved())
                    .as("holding is now legitimate: the gap is already being closed")
                    .isTrue();
            assertThat(treasury.act(second)).isEmpty();
        }

        @Test
        @DisplayName("dollars in flight are not available to convert again")
        void in_flight_dollars_are_not_available() {
            payroll(80_000_000L, 5);
            haveShillings(10_000_000L);
            haveDollars(6_000, "131.50");
            publishMidRate("132.60");

            Decision first = agent.runCycle("daily");
            treasury.act(first);

            assertThat(agent.runCycle("daily").plan().held())
                    .as("6,000 held less what is committed")
                    .isEqualTo(usdc(6_000).minus(first.plan().mustConvert()));
        }

        @Test
        @DisplayName("a conversion that fails is no longer in flight, and the gap reopens")
        void a_failed_conversion_returns_the_dollars_to_the_plan() {
            payroll(80_000_000L, 5);
            haveShillings(10_000_000L);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            Decision first = agent.runCycle("daily");
            Instruction.Created created = treasury.act(first).orElseThrow();

            // It is sent, and the rail rejects it.
            Instruction.Submitted submitted = created.submit(AT);
            instructions.transitionTo(submitted);
            instructions.transitionTo(submitted.rejected(AT.plusSeconds(5), "rail: insufficient gas"));

            Decision after = agent.runCycle("daily");

            assertThat(after.plan().inFlightCover()).isEqualTo(Money.zero(Currency.KES));
            assertThat(after.plan().mustConvert())
                    .as("the gap is open again and the agent sees it, without anything resetting state")
                    .isEqualTo(usdc(5_883));
        }

        @Test
        @DisplayName("an instruction that is AWAITING_RESOLUTION still counts as in flight")
        void an_unknown_outcome_keeps_the_dollars_committed() {
            // The safe direction. Its outcome is unknown, so the dollars may or may not be gone -
            // and converting them again is exactly the double-spend AWAITING_RESOLUTION exists
            // to prevent.
            payroll(80_000_000L, 5);
            haveShillings(10_000_000L);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            Instruction.Created created = treasury.act(agent.runCycle("daily")).orElseThrow();
            Instruction.Submitted submitted = created.submit(AT);
            instructions.transitionTo(submitted);
            instructions.transitionTo(submitted.timedOut(AT.plusSeconds(120), "silence"));

            assertThat(instructions.conversionsInFlight()).isEqualTo(usdc(5_883));
            assertThat(agent.runCycle("daily").plan().mustConvert()).isEqualTo(Money.zero(Currency.USDC));
        }

        @Test
        void a_settled_conversion_is_no_longer_in_flight() {
            payroll(80_000_000L, 5);
            haveShillings(10_000_000L);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            Instruction.Created created = treasury.act(agent.runCycle("daily")).orElseThrow();
            Instruction.Submitted submitted = created.submit(AT);
            instructions.transitionTo(submitted);
            instructions.transitionTo(submitted.confirmed(AT.plusSeconds(30), "tx 0xabc"));

            assertThat(instructions.conversionsInFlight()).isEqualTo(Money.zero(Currency.USDC));
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("ADR-022: dollars owed in dollars are reserved, not converted away")
    class ForeignObligations {

        @Test
        @DisplayName("a dollar obligation holds back the dollars it will need")
        void foreign_obligations_reserve_dollars() {
            payroll(80_000_000L, 5);
            obligations.save(Obligation.scheduled("Overseas supplier", usdc(4_000), TODAY.plusDays(10)));
            haveShillings(10_000_000L);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            FundingPlan plan = agent.runCycle("daily").plan();

            assertThat(plan.reserved()).isEqualTo(usdc(4_000));
            assertThat(plan.held())
                    .as("10,000 held less 4,000 reserved")
                    .isEqualTo(usdc(6_000));
            assertThat(plan.mustConvert())
                    .as("the shilling gap wants 5,883 and 6,000 are free, so it is still met")
                    .isEqualTo(usdc(5_883));
        }

        @Test
        @DisplayName("a dollar debt is never made unpayable in order to fund a shilling one")
        void the_reserve_caps_the_conversion() {
            payroll(80_000_000L, 5);
            obligations.save(Obligation.scheduled("Overseas supplier", usdc(8_000), TODAY.plusDays(10)));
            haveShillings(10_000_000L);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            Decision decision = agent.runCycle("daily");

            assertThat(decision.plan().mustConvert())
                    .as("only 2,000 dollars are free, so only 2,000 can be converted")
                    .isEqualTo(usdc(2_000));
            assertThat(decision.approved()).isTrue();
            assertThat(decision.intent().reasoning())
                    .contains("reserved for obligations denominated in dollars");
        }

        @Test
        @DisplayName("reserving is not paying, and the reasoning says so")
        void reserving_is_not_acting() {
            obligations.save(Obligation.scheduled("Overseas supplier", usdc(2_000), TODAY.plusDays(10)));
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            assertThat(agent.runCycle("daily").intent().reasoning())
                    .contains("nothing here pays them");
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("ADR-021: MANUAL_REVIEW is no longer a trap")
    class ManualReviewHasAnExit {

        private Instruction.ManualReview escalated(String ref) {
            long intentId = agent.runCycle("setup").intent().id();
            Instruction.Created created = instructions.create(
                    intentId, ref, InstructionType.CONVERSION, usdc(100), AT);
            Instruction.Submitted submitted = created.submit(AT);
            instructions.transitionTo(submitted);
            Instruction.AwaitingResolution unknown = submitted.timedOut(AT, "silence");
            instructions.transitionTo(unknown);
            Instruction.ManualReview review = unknown.escalate(AT, "rail unreachable for a day");
            instructions.transitionTo(review);
            return review;
        }

        @Test
        void a_human_can_resolve_it_as_settled() {
            Instruction.ManualReview review = escalated("mr/1");

            Instruction.Settled settled = review.resolvedByHumanAsSettled(
                    AT.plusSeconds(86400), "K. Kurgat", "found tx 0xabc on the explorer");
            instructions.transitionTo(settled);

            assertThat(instructions.findById(review.id()).orElseThrow().state())
                    .isEqualTo(InstructionState.SETTLED);
            assertThat(settled.evidence()).contains("K. Kurgat").contains("0xabc");
        }

        @Test
        void a_human_can_resolve_it_as_failed_and_then_it_may_be_retried() {
            Instruction.ManualReview review = escalated("mr/2");

            Instruction.Failed failed = review.resolvedByHumanAsFailed(
                    AT.plusSeconds(86400), "K. Kurgat", "provider confirmed it never left");
            instructions.transitionTo(failed);

            // Only now, with somebody's name against "it did not happen", may it be sent again.
            instructions.transitionTo(failed.retry(AT.plusSeconds(90000)));

            assertThat(instructions.findById(review.id()).orElseThrow().attempts()).isEqualTo(2);
        }

        @Test
        @DisplayName("a resolution without a name or without evidence is refused")
        void an_anonymous_resolution_is_not_a_resolution() {
            Instruction.ManualReview review = escalated("mr/3");

            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    review.resolvedByHumanAsSettled(AT, "  ", "saw it"))
                    .withMessageContaining("took responsibility");

            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    review.resolvedByHumanAsSettled(AT, "K. Kurgat", " "))
                    .withMessageContaining("not evidence");
        }

        @Test
        @DisplayName("there is still no retry from MANUAL_REVIEW, at the database either")
        void manual_review_cannot_be_retried_directly() {
            Instruction.ManualReview review = escalated("mr/4");

            assertThat(java.util.Arrays.stream(Instruction.ManualReview.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName))
                    .noneMatch(n -> n.toLowerCase().contains("retry"));

            assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                    jdbc.update("update instruction set attempts = attempts + 1 where id = ?",
                            review.id()))
                    .withMessageContaining("Resolve it to FAILED first");
        }
    }
}

package shilingi.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import shilingi.intent.IntentLog;
import shilingi.ledger.AccountCodes;
import shilingi.ledger.JournalEntry;
import shilingi.ledger.LedgerService;
import shilingi.ledger.Posting;
import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.money.Rate;
import shilingi.obligations.Obligation;
import shilingi.obligations.ObligationRepository;
import shilingi.obligations.ObligationStatus;
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
 * Day 8 is done when "a proposed action violating I8 is refused" (SPEC.md section 15).
 *
 * <p>The tests that matter most are in {@link TheRefusal} - particularly the one where the
 * reasoning is excellent and the action is still refused, which is the distinction SPEC.md
 * section 11 calls "the demo's strongest single moment".
 */
class TreasuryAgentTest extends AbstractDatabaseTest {

    @Autowired
    private TreasuryAgent agent;

    @Autowired
    private ObligationRepository obligations;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private IntentLog intents;

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

    /** Puts a mid rate on the pinned clock's today, since V5 only seeds September dates. */
    private void publishMidRate(String value) {
        jdbc.update("insert into mid_rate (rate_date, value, source, quoted_at) values (?, ?, ?, ?)",
                TODAY, new BigDecimal(value), "test-feed", Timestamp.from(AT));
    }

    private Obligation payroll(long cents, int daysFromToday) {
        return obligations.save(Obligation.scheduled(
                "Payroll", kes(cents), TODAY.plusDays(daysFromToday)));
    }

    /** Shillings into account 1000, balanced against revenue. */
    private void haveShillings(long cents) {
        ledger.post(JournalEntry.of(TODAY, "opening shillings", "test/setup", AT,
                List.of(Posting.inShillings(AccountCodes.BANK_KES, kes(cents)),
                        Posting.inShillings(AccountCodes.REVENUE, kes(-cents)))));
    }

    /** Dollars into account 1100 at a stated rate, balanced against revenue. */
    private void haveDollars(long dollars, String rate) {
        Money amount = usdc(dollars);
        Money value = Rate.mid(rate, "test-fixture", AT).toShillings(amount);

        ledger.post(JournalEntry.of(TODAY, "opening dollars", "test/setup", AT,
                List.of(new Posting(null, null, AccountCodes.WALLET_USDC, amount,
                                new BigDecimal(rate), "test-fixture", AT, value),
                        Posting.inShillings(AccountCodes.REVENUE, value.negate()))));
    }

    private long instructionCount() {
        return jdbc.queryForObject("select count(*) from instruction", Long.class);
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("invariant I8: an obligation is never left unfunded because the agent held dollars")
    class TheRefusal {

        @Test
        @DisplayName("holding dollars with an obligation unfunded inside the horizon is refused")
        void holding_is_refused_when_something_is_unfunded() {
            payroll(80_000_000L, 5);          // KES 800,000 due in 5 days
            haveShillings(10_000_000L);       // KES 100,000 in the bank
            haveDollars(10_000, "131.50");    // plenty of dollars
            publishMidRate("132.60");

            Decision decision = agent.decideOn("manual review",
                    new ProposedAction.Hold("waiting for a better rate"),
                    "The shilling has weakened three days running and the trend looks set to "
                    + "continue, so holding dollars for another week should get a materially "
                    + "better conversion and improve the month's result.");

            assertThat(decision.refused())
                    .as("the reasoning is plausible and the action is still refused")
                    .isTrue();
            assertThat(decision.violation().orElseThrow().invariant()).isEqualTo("I8");
            assertThat(decision.violation().orElseThrow())
                    .hasMessageContaining("must not be left without funds");
        }

        @Test
        @DisplayName("the refusal does not depend on the quality of the reasoning")
        void the_guard_cannot_see_the_reasoning() {
            // The same action, argued badly, gets the same answer. That is the only way
            // SPEC.md section 11's claim can be true in practice.
            payroll(80_000_000L, 5);
            haveShillings(10_000_000L);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            Decision wellArgued = agent.decideOn("t",
                    new ProposedAction.Hold("waiting"), "A carefully reasoned, well-evidenced case.");
            Decision badlyArgued = agent.decideOn("t",
                    new ProposedAction.Hold("waiting"), "dunno, feels right");

            assertThat(wellArgued.refused()).isEqualTo(badlyArgued.refused()).isTrue();
            assertThat(wellArgued.violation().orElseThrow().invariant())
                    .isEqualTo(badlyArgued.violation().orElseThrow().invariant());
        }

        @Test
        @DisplayName("converting less than is needed is the same violation as converting nothing")
        void holding_back_part_of_what_is_needed_is_still_holding() {
            payroll(80_000_000L, 5);
            haveShillings(10_000_000L);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            Decision decision = agent.decideOn("t",
                    new ProposedAction.Convert(usdc(100)),
                    "Converting a token amount to keep some optionality.");

            assertThat(decision.refused()).isTrue();
            assertThat(decision.violation().orElseThrow())
                    .hasMessageContaining("Holding back part of what is needed");
        }

        @Test
        @DisplayName("a refused decision is still written to the intent log, with the refusal in it")
        void the_refusal_itself_is_recorded() {
            payroll(80_000_000L, 5);
            haveShillings(10_000_000L);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            Decision decision = agent.decideOn("t",
                    new ProposedAction.Hold("waiting for a better rate"),
                    "The shilling looks set to weaken further.");

            assertThat(intents.findById(decision.intent().id())).isPresent();
            assertThat(decision.intent().proposedAction())
                    .contains("\"permitted\":false")
                    .contains("\"refusedBy\":\"I8\"");
            assertThat(decision.intent().reasoning()).contains("shilling looks set to weaken");
        }

        @Test
        @DisplayName("a refused decision creates no instruction")
        void refusal_means_nothing_acts() {
            payroll(80_000_000L, 5);
            haveShillings(10_000_000L);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            Decision decision = agent.decideOn("t", new ProposedAction.Hold("waiting"), "because");

            assertThat(decision.approved()).isFalse();
            assertThat(instructionCount())
                    .as("day 9's treasury service is gated on approved(); nothing may act on this")
                    .isZero();
        }

        @Test
        @DisplayName("require() throws for callers that want the exception")
        void the_guard_can_also_throw() {
            payroll(80_000_000L, 5);
            haveShillings(10_000_000L);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            TreasuryInvariants invariants = new TreasuryInvariants();
            FundingPlan plan = FundingPlan.from(agent.readPosition(), new BigDecimal("0.10"));

            assertThatExceptionOfType(InvariantViolation.class).isThrownBy(() ->
                    invariants.require(new ProposedAction.Hold("waiting"), plan));
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("when holding is legitimate, it is permitted")
    class WhenHoldingIsFine {

        @Test
        @DisplayName("shillings already cover everything due")
        void no_shortfall_means_no_obligation_to_convert() {
            payroll(80_000_000L, 5);
            haveShillings(200_000_000L);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            Decision decision = agent.runCycle("daily");

            assertThat(decision.approved()).isTrue();
            assertThat(decision.action()).isInstanceOf(ProposedAction.Hold.class);
            assertThat(decision.plan().hasShortfall()).isFalse();
        }

        @Test
        @DisplayName("SPEC.md section 17 scenario 12: an obligation due with no dollars and no shillings")
        void an_empty_wallet_is_not_a_violation() {
            // The agent cannot fix this by converting, so refusing it would be punishing the
            // agent for arithmetic. The unfunded obligation stays visible.
            payroll(80_000_000L, 0);

            Decision decision = agent.runCycle("daily");

            assertThat(decision.approved())
                    .as("there is nothing to convert, so holding nothing is permitted")
                    .isTrue();
            assertThat(decision.plan().hasShortfall()).isTrue();
            assertThat(decision.plan().conversionIsRequired()).isFalse();
            assertThat(obligations.unfundedDueBy(TODAY)).hasSize(1);
        }

        @Test
        @DisplayName("obligations beyond the horizon do not force a conversion today")
        void the_horizon_bounds_the_question() {
            payroll(80_000_000L, 90);     // well past the 35-day horizon
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            Decision decision = agent.runCycle("daily");

            assertThat(decision.approved()).isTrue();
            assertThat(decision.plan().needed()).isEqualTo(Money.zero(Currency.KES));
        }

        @Test
        @DisplayName("an already funded obligation does not count towards the gap")
        void funded_obligations_are_not_counted() {
            Obligation funded = payroll(80_000_000L, 5);
            obligations.updateStatus(funded.id(), ObligationStatus.FUNDED);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            assertThat(agent.runCycle("daily").plan().needed()).isEqualTo(Money.zero(Currency.KES));
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 11's dull rule, worked by hand")
    class TheDullRule {

        @Test
        @DisplayName("needed, cover, have, short, convert - every step checkable with a calculator")
        void the_arithmetic() {
            // needed  = 800,000 shillings          (80,000,000 cents)
            // cover   = needed + 10%  = 880,000    (88,000,000 cents)
            // have    = 100,000                    (10,000,000 cents)
            // short   = 780,000                    (78,000,000 cents)
            // convert = 780,000 / 132.60 = 5882.35... -> 5,883 whole dollars, rounded UP
            payroll(80_000_000L, 5);
            haveShillings(10_000_000L);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            FundingPlan plan = agent.runCycle("daily").plan();

            assertThat(plan.needed()).isEqualTo(kes(80_000_000L));
            assertThat(plan.cover()).isEqualTo(kes(88_000_000L));
            assertThat(plan.have()).isEqualTo(kes(10_000_000L));
            assertThat(plan.shortfall()).isEqualTo(kes(78_000_000L));
            assertThat(plan.requiredDollars().orElseThrow()).isEqualTo(usdc(5_883));
            assertThat(plan.mustConvert()).isEqualTo(usdc(5_883));
        }

        @Test
        @DisplayName("SPEC.md section 11: rounded UP to whole dollars, never to nearest")
        void the_conversion_rounds_up_to_a_whole_dollar() {
            // 5,882 dollars at 132.60 is 779,953.20 - sixty-seven shillings short of the target.
            // Rounding to nearest would have chosen it.
            Rate mid = Rate.mid("132.60", "test-feed", AT);

            assertThat(FundingPlan.wholeDollarsToCover(kes(78_000_000L), mid)).isEqualTo(usdc(5_883));
            assertThat(mid.toShillings(usdc(5_882))).isLessThan(kes(78_000_000L));
            assertThat(mid.toShillings(usdc(5_883))).isGreaterThan(kes(78_000_000L));
        }

        @Test
        @DisplayName("the conversion is capped at what the wallet actually holds")
        void you_cannot_convert_what_you_do_not_have() {
            payroll(80_000_000L, 5);
            haveDollars(1_000, "131.50");
            publishMidRate("132.60");

            FundingPlan plan = agent.runCycle("daily").plan();

            assertThat(plan.requiredDollars().orElseThrow()).isEqualTo(usdc(6_637));
            assertThat(plan.mustConvert())
                    .as("capped at the balance, so I8 asks for what is possible")
                    .isEqualTo(usdc(1_000));
        }

        @Test
        void several_obligations_inside_the_horizon_are_summed() {
            payroll(80_000_000L, 5);
            obligations.save(Obligation.scheduled("Rent", kes(15_000_000L), TODAY.plusDays(10)));
            obligations.save(Obligation.scheduled("Statutory", kes(5_000_000L), TODAY.plusDays(20)));
            publishMidRate("132.60");

            assertThat(agent.runCycle("daily").plan().needed()).isEqualTo(kes(100_000_000L));
        }

        @Test
        @DisplayName("an overdue obligation is still unfunded and still counted")
        void overdue_obligations_are_in_the_gap() {
            payroll(80_000_000L, -3);
            publishMidRate("132.60");

            assertThat(agent.runCycle("daily").plan().needed()).isEqualTo(kes(80_000_000L));
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 11 step 6: the intent is written and committed before anything acts")
    class TheRecord {

        @Test
        void every_cycle_writes_exactly_one_intent() {
            payroll(80_000_000L, 5);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            assertThat(intents.count()).isZero();
            Decision decision = agent.runCycle("daily");

            assertThat(intents.count()).isEqualTo(1L);
            assertThat(decision.intent().id()).isNotNull();
        }

        @Test
        @DisplayName("the snapshot carries everything read in steps 1 to 3")
        void the_snapshot_is_complete() {
            Obligation p = payroll(80_000_000L, 5);
            haveShillings(10_000_000L);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            String snapshot = agent.runCycle("daily").intent().inputsSnapshot();

            assertThat(snapshot)
                    .contains("\"horizonDays\":35")
                    .contains("\"buffer\":\"0.10\"")
                    .contains("\"shillingBalanceMinor\":10000000")
                    .contains("\"foreignBalanceMinor\":10000000000")
                    .contains("\"midRate\":\"132.60000000\"")
                    .contains("\"neededMinor\":80000000")
                    .contains("\"coverMinor\":88000000")
                    .contains("\"shortfallMinor\":78000000")
                    .contains("\"mustConvertMinor\":5883000000")
                    .contains("\"id\":" + p.id());
        }

        @Test
        @DisplayName("the reasoning explains the decision in words, not just in numbers")
        void the_reasoning_is_readable() {
            payroll(80_000_000L, 5);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            assertThat(agent.runCycle("daily").intent().reasoning())
                    .contains("Planning horizon")
                    .contains("unfunded shilling obligation")
                    .contains("Proposing CONVERT");
        }

        @Test
        @DisplayName("the snapshot does not change when the obligations do")
        void the_snapshot_is_a_copy() {
            Obligation p = payroll(80_000_000L, 5);
            haveDollars(10_000, "131.50");
            publishMidRate("132.60");

            Decision decision = agent.runCycle("daily");
            obligations.updateStatus(p.id(), ObligationStatus.FUNDED);

            assertThat(intents.findById(decision.intent().id()).orElseThrow().inputsSnapshot())
                    .contains("\"status\":\"SCHEDULED\"");
        }
    }

    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("SPEC.md section 17 scenario 7: payroll due on a day the rate feed has no entry")
    void with_no_rate_the_agent_proposes_nothing_and_says_why() {
        // No publishMidRate call, so the feed has nothing for today.
        payroll(80_000_000L, 5);
        haveDollars(10_000, "131.50");

        Decision decision = agent.runCycle("daily");

        assertThat(decision.action()).isInstanceOf(ProposedAction.Hold.class);
        assertThat(decision.intent().reasoning()).contains("Proposing HOLD");
        assertThat(((ProposedAction.Hold) decision.action()).because())
                .contains("no mid-market rate is published");

        assertThat(decision.plan().requiredDollars())
                .as("without a rate there is no way to say how many dollars would cover it")
                .isEmpty();
        assertThat(decision.plan().conversionIsRequired())
                .as("so no conversion can be formed")
                .isFalse();

        // And I8 refuses the hold anyway. A rate outage must not quietly excuse leaving payroll
        // unfunded while dollars sit in the wallet - that would let an absence bypass the
        // invariant, which is worse than an argument defeating it because nothing announces it.
        assertThat(decision.refused())
                .as("a missing rate does not excuse leaving an obligation unfunded")
                .isTrue();
        assertThat(decision.violation().orElseThrow())
                .hasMessageContaining("a human must look");
        assertThat(instructionCount()).isZero();
    }

    @Test
    @DisplayName("a dollar-denominated obligation is visible but the v1 rule does not act on it")
    void foreign_obligations_are_flagged_not_silently_dropped() {
        obligations.save(Obligation.scheduled("Overseas supplier", usdc(2_000), TODAY.plusDays(10)));
        haveDollars(10_000, "131.50");
        publishMidRate("132.60");

        Decision decision = agent.runCycle("daily");

        assertThat(decision.plan().needed())
                .as("not counted in the shilling shortfall, because converting does not fund it")
                .isEqualTo(Money.zero(Currency.KES));
        assertThat(decision.intent().reasoning())
                .contains("Their dollars are reserved, but nothing here pays them");
        assertThat(decision.intent().inputsSnapshot()).contains("Overseas supplier");
    }
}

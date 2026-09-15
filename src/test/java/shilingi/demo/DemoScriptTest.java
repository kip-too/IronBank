package shilingi.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import shilingi.agent.Decision;
import shilingi.instruction.InstructionRepository;
import shilingi.intent.IntentLog;
import shilingi.ledger.AccountCodes;
import shilingi.money.Currency;
import shilingi.money.Money;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 12 is done when the demo "runs start to finish without a human touching anything"
 * (SPEC.md section 15). This runs it, twice, and checks every figure against PROBLEM.md section 5.
 *
 * <p>Note this test does NOT extend {@code AbstractDatabaseTest}: the script resets the database
 * itself, because a story that cannot be replayed from the start is not a demo but a first run.
 * Proving that is what {@link #the_story_can_be_played_twice} is for.
 */
@SpringBootTest
@ActiveProfiles({"test", "demo"})
class DemoScriptTest {

    @Autowired
    private DemoScript demo;

    @Autowired
    private IntentLog intents;

    @Autowired
    private InstructionRepository instructions;

    private static Money kes(long cents) {
        return Money.of(cents, Currency.KES);
    }

    private static Money usdc(long dollars) {
        return Money.of(dollars * 1_000_000L, Currency.USDC);
    }

    @Test
    @DisplayName("it runs start to finish, with nothing touching it")
    void the_whole_story_plays() {
        DemoScript.Story story = demo.play();

        assertThat(story.acts())
                .as("every act of the thirty-day story")
                .hasSizeGreaterThanOrEqualTo(6);
        assertThat(story.convertedDollars()).isEqualTo(usdc(6_000));
    }

    @Test
    @DisplayName("it can be played again, from the beginning, with the same result")
    void the_story_can_be_played_twice() {
        DemoScript.Story first = demo.play();
        Money exchangeDifferenceAfterFirstRun =
                demo.balanceOf(AccountCodes.EXCHANGE_DIFFERENCE_REALISED);

        DemoScript.Story second = demo.play();

        assertThat(second.convertedDollars()).isEqualTo(first.convertedDollars());
        assertThat(second.sheet().total()).isEqualTo(first.sheet().total());

        // The balances are equal to the first run's, not double them - which is what would happen
        // if the reset were not real and the second story simply piled on top of the first.
        assertThat(demo.balanceOf(AccountCodes.EXCHANGE_DIFFERENCE_REALISED))
                .isEqualTo(exchangeDifferenceAfterFirstRun)
                .isEqualTo(kes(-3_160_000L));
        assertThat(demo.walletDollars()).isEqualTo(usdc(4_000));
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("every figure matches PROBLEM.md section 5, so an audience can check it")
    class TheFigures {

        @Test
        void the_correct_column() {
            demo.play();

            assertThat(demo.balanceOf(AccountCodes.REVENUE))
                    .as("revenue is the invoice and ONLY the invoice - the opening cash is brought "
                        + "forward against Payables, because cash you already had is not something "
                        + "you earned this month")
                    .isEqualTo(kes(-129_000_000L));

            assertThat(demo.balanceOf(AccountCodes.PAYABLES))
                    .as("opening cash 84,400 held, plus the 7,138.80 fee owed to the provider")
                    .isEqualTo(kes(-8_440_000L).plus(kes(-713_880L)));

            assertThat(demo.balanceOf(AccountCodes.EXCHANGE_DIFFERENCE_REALISED))
                    .as("25,000 on receipt + 4,200 on conversion + 2,400 reclassified out as spread"
                        + " = 31,600 credit, measured honestly against mid")
                    .isEqualTo(kes(-3_160_000L));

            assertThat(demo.balanceOf(AccountCodes.CONVERSION_SPREAD))
                    .as("2,400 - what the provider took quietly, inside the rate")
                    .isEqualTo(kes(240_000L));

            assertThat(demo.balanceOf(AccountCodes.CONVERSION_FEE))
                    .as("0.9% of 793,200 = 7,138.80 exactly, taken as charged and never derived")
                    .isEqualTo(kes(713_880L));

            assertThat(demo.balanceOf(AccountCodes.EXCHANGE_DIFFERENCE_UNREALISED))
                    .as("2,800 of month-end revaluation, and it never touches 6100")
                    .isEqualTo(kes(280_000L));

            assertThat(demo.balanceOf(AccountCodes.BANK_KES))
                    .as("84,400 opening plus 793,200 converted, less the 12,500 that left "
                        + "for a reason nobody can explain")
                    .isEqualTo(kes(87_760_000L - 1_250_000L));

            assertThat(demo.balanceOf(AccountCodes.SUSPENSE))
                    .as("I6: the unexplained movement sits in 1900 with a date on it")
                    .isEqualTo(kes(1_250_000L));

            assertThat(demo.walletDollars())
                    .as("4,000 still held, which is where PROBLEM.md day 30 opens")
                    .isEqualTo(usdc(4_000));
        }

        @Test
        @DisplayName("the naive column: one number, arithmetically correct, and it means nothing")
        void the_naive_column() {
            DemoScript.Story story = demo.play();

            assertThat(story.sheet().total())
                    .as("793,200 - exactly what the bank credited. The sum is right.")
                    .isEqualTo(kes(79_320_000L));
            assertThat(story.sheet().rows()).hasSize(1);
            assertThat(story.sheet().labelForTotal()).isEqualTo("Revenue");
        }

        @Test
        @DisplayName("three of the four questions, the sheet cannot answer at all")
        void the_naive_column_cannot_answer() {
            DemoScript.Story story = demo.play();
            NaiveSheet sheet = story.sheet();

            assertThat(sheet.answerTo(NaiveSheet.Question.WHAT_WAS_EARNED)).isNotNull();
            assertThat(sheet.answerTo(NaiveSheet.Question.HOW_MUCH_WAS_THE_DOLLAR_MOVING)).isNull();
            assertThat(sheet.answerTo(NaiveSheet.Question.WHAT_DID_THE_PROVIDER_CHARGE)).isNull();
            assertThat(sheet.answerTo(NaiveSheet.Question.CAN_THE_DAY_BE_REPRODUCED)).isNull();
        }

        @Test
        @DisplayName("and the two revenue figures differ, without the naive one being miscalculated")
        void the_comparison() {
            DemoScript.Story story = demo.play();

            Money naiveCallsItRevenue = story.sheet().total();
            Money actuallyEarned = kes(129_000_000L);

            assertThat(naiveCallsItRevenue).isNotEqualTo(actuallyEarned);
            assertThat(naiveCallsItRevenue)
                    .as("the naive figure is cash received, correctly summed, wrongly labelled")
                    .isEqualTo(kes(79_320_000L));
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 11: the demo's strongest single moment")
    class TheRefusal {

        @Test
        @DisplayName("a plausible, well-argued proposal is refused anyway")
        void the_proposal_is_refused() {
            DemoScript.Story story = demo.play();
            Decision refusal = story.refusal();

            assertThat(refusal.refused()).isTrue();
            assertThat(refusal.violation().orElseThrow().invariant()).isEqualTo("I8");
            assertThat(refusal.intent().reasoning())
                    .as("the reasoning is specific and sounds right, which is the point")
                    .contains("weakened three sessions running");
        }

        @Test
        @DisplayName("the refusal is written down, and no instruction is created from it")
        void the_refusal_is_recorded_and_acts_on_nothing() {
            DemoScript.Story story = demo.play();

            assertThat(intents.findById(story.refusal().intent().id())).isPresent();
            assertThat(story.refusal().intent().proposedAction())
                    .contains("\"permitted\":false")
                    .contains("\"refusedBy\":\"I8\"");

            assertThat(instructions.findAll())
                    .as("one instruction in the whole story, and it is the permitted one")
                    .hasSize(1)
                    .allSatisfy(i -> assertThat(i.intentId())
                            .isNotEqualTo(story.refusal().intent().id()));
        }

        @Test
        @DisplayName("and then the dull rule runs and is permitted, so the system is not merely obstructive")
        void the_permitted_decision_follows() {
            DemoScript.Story story = demo.play();

            assertThat(story.permitted().approved()).isTrue();
            assertThat(story.instructionRef()).startsWith("convert/");
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("PROBLEM.md F3: money that moved for a reason nobody knows")
    class TheUnansweredQuestion {

        @Test
        void it_is_raised_aged_and_posted_to_suspense() {
            DemoScript.Story story = demo.play();

            assertThat(story.unanswered()).singleElement().satisfies(item -> {
                assertThat(item.kind().name()).isEqualTo("UNMATCHED_INBOUND");
                assertThat(item.firstSeen()).isEqualTo(LocalDate.of(2026, 9, 25));
                assertThat(item.detail()).contains("no instruction carries");
            });
        }

        @Test
        @DisplayName("by month end it has crossed the threshold and become an exception")
        void it_ages_into_an_exception() {
            DemoScript.Story story = demo.play();

            assertThat(story.exceptions())
                    .as("raised on the 25th, still open on the 30th - past two business days")
                    .hasSize(1);
        }

        @Test
        @DisplayName("and it cannot be closed without somebody putting their name to it")
        void closing_it_takes_a_person() {
            DemoScript.Story story = demo.play();

            assertThat(story.unanswered().get(0).isOpen()).isTrue();
        }
    }

    @Test
    @DisplayName("the 6,000 comes from the agent's rule, not from a special case")
    void nothing_is_hardcoded_to_match_the_document() {
        // The opening balance is the one free variable, chosen so the rule lands on PROBLEM.md's
        // figure. Prove the rule is doing the work: the arithmetic is reproduced here from the
        // documented inputs, and it has to agree.
        //
        //   cover 880,000 - have 84,400 = 795,600 short
        //   795,600 / 132.60 = 6,000.0 exactly, then rounded UP to whole dollars
        DemoScript.Story story = demo.play();

        long shortInCents = 88_000_000L - DemoScript.OPENING_BANK_BALANCE.minorUnits();
        assertThat(shortInCents).isEqualTo(79_560_000L);

        java.math.BigDecimal dollars = java.math.BigDecimal.valueOf(shortInCents)
                .movePointLeft(2)
                .divide(new java.math.BigDecimal("132.60"), 0, java.math.RoundingMode.CEILING);

        assertThat(dollars.longValueExact()).isEqualTo(6_000L);
        assertThat(story.convertedDollars()).isEqualTo(usdc(dollars.longValueExact()));
    }
}

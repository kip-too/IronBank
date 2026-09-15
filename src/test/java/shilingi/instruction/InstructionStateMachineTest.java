package shilingi.instruction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import shilingi.intent.Intent;
import shilingi.intent.IntentLog;
import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.platform.AbstractDatabaseTest;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 9 is done when "retry is unrepresentable in that state, proven" (SPEC.md section 15).
 *
 * <p>{@link Unrepresentable} is that proof. SPEC.md section 9 asks for more than a rule that is
 * never broken - it asks for one that <b>cannot</b> be broken - so the tests check the shape of
 * the type, not the behaviour of a method.
 */
class InstructionStateMachineTest extends AbstractDatabaseTest {

    @Autowired
    private InstructionRepository instructions;

    @Autowired
    private IntentLog intents;

    @Autowired
    private JdbcTemplate jdbc;

    private static final Instant AT = Instant.parse("2026-09-20T06:00:00Z");

    private static Money usdc(long dollars) {
        return Money.of(dollars * 1_000_000L, Currency.USDC);
    }

    private long anIntent() {
        return intents.append(Intent.proposing(AT, "test", "because", "{}", "{}")).id();
    }

    private Instruction.Created anInstruction(String ref) {
        return instructions.create(anIntent(), ref, InstructionType.CONVERSION, usdc(6_000), AT);
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("I7: the retry is unrepresentable, not merely unused")
    class Unrepresentable {

        @Test
        @DisplayName("AwaitingResolution has no retry, resubmit or send method at all")
        void the_method_does_not_exist() {
            // SPEC.md section 9: "do not implement it as an if statement that skips the retry.
            // Implement it so that the retry method CANNOT BE CALLED on an instruction in that
            // state." So there is nothing to call.
            assertThat(Arrays.stream(Instruction.AwaitingResolution.class.getDeclaredMethods())
                    .map(Method::getName))
                    .noneMatch(name -> name.toLowerCase().contains("retry")
                                       || name.toLowerCase().contains("resubmit")
                                       || name.toLowerCase().contains("submit")
                                       || name.toLowerCase().contains("send"));
        }

        @Test
        @DisplayName("exactly one state carries retry, and it is FAILED")
        void only_a_known_failure_can_be_retried() {
            // F5 happens when something with an UNKNOWN outcome is retried. A failure the rail
            // reported is known, so retrying it is ordinary operations.
            assertThat(hasRetry(Instruction.Failed.class)).isTrue();

            assertThat(hasRetry(Instruction.AwaitingResolution.class)).isFalse();
            assertThat(hasRetry(Instruction.Submitted.class))
                    .as("in flight is also an unknown outcome")
                    .isFalse();
            assertThat(hasRetry(Instruction.Settled.class)).isFalse();
            assertThat(hasRetry(Instruction.Created.class)).isFalse();
            assertThat(hasRetry(Instruction.ManualReview.class)).isFalse();
        }

        @Test
        @DisplayName("the only ways out of AWAITING_RESOLUTION are evidence and a human")
        void the_exits_are_exactly_what_section_7_draws() {
            assertThat(Arrays.stream(Instruction.AwaitingResolution.class.getDeclaredMethods())
                    .map(Method::getName)
                    .filter(name -> name.startsWith("resolved") || name.startsWith("escalate")))
                    .containsExactlyInAnyOrder("resolvedAsSettled", "resolvedAsFailed", "escalate");
        }

        private static boolean hasRetry(Class<?> type) {
            return Arrays.stream(type.getDeclaredMethods()).anyMatch(m -> m.getName().equals("retry"));
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("I7 at the database, for everything that is not Java")
    class AtTheDatabase {

        @Test
        @DisplayName("an AWAITING_RESOLUTION instruction cannot have its attempt count changed")
        void a_retry_in_the_data_is_refused() {
            // A retry looks like exactly one thing in the table: the attempt count going up.
            Instruction.Created created = anInstruction("convert/1");
            instructions.transitionTo(created.submit(AT));
            Instruction submitted = instructions.findById(created.id()).orElseThrow();
            instructions.transitionTo(((Instruction.Submitted) submitted).timedOut(AT, "no reply"));

            assertThatThrownBy(() ->
                    jdbc.update("update instruction set attempts = attempts + 1 where id = ?",
                            created.id()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("never be retried");
        }

        @Test
        @DisplayName("it cannot be moved back to SUBMITTED either")
        void awaiting_resolution_does_not_go_back_to_submitted() {
            Instruction.Created created = anInstruction("convert/2");
            instructions.transitionTo(created.submit(AT));
            Instruction submitted = instructions.findById(created.id()).orElseThrow();
            instructions.transitionTo(((Instruction.Submitted) submitted).timedOut(AT, "no reply"));

            assertThatThrownBy(() ->
                    jdbc.update("update instruction set state = 'SUBMITTED' where id = ?", created.id()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("no such arrow");
        }

        @Test
        @DisplayName("what an instruction instructs cannot be changed underneath it")
        void only_state_and_attempts_may_move() {
            Instruction.Created created = anInstruction("convert/3");

            assertThatThrownBy(() ->
                    jdbc.update("update instruction set amount_minor = 1 where id = ?", created.id()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Only the state");

            assertThatThrownBy(() ->
                    jdbc.update("update instruction set external_ref = 'something-else' where id = ?",
                            created.id()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("an arrow SPEC.md section 7 does not draw is refused")
        void undrawn_arrows_are_refused() {
            Instruction.Created created = anInstruction("convert/4");

            assertThatThrownBy(() ->
                    jdbc.update("update instruction set state = 'SETTLED' where id = ?", created.id()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("no such arrow");
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 7's arrows, walked")
    class TheMachine {

        @Test
        void created_to_submitted_to_settled() {
            Instruction.Created created = anInstruction("convert/5");
            assertThat(created.attempts()).isZero();

            Instruction.Submitted submitted = created.submit(AT);
            assertThat(submitted.attempts()).isEqualTo(1);
            instructions.transitionTo(submitted);

            Instruction.Settled settled = submitted.confirmed(AT.plusSeconds(30), "tx 0xabc");
            instructions.transitionTo(settled);

            assertThat(instructions.findById(created.id()).orElseThrow().state())
                    .isEqualTo(InstructionState.SETTLED);
        }

        @Test
        @DisplayName("the timeout path: SUBMITTED to AWAITING_RESOLUTION, which is not failure")
        void the_timeout_path() {
            Instruction.Created created = anInstruction("convert/6");
            Instruction.Submitted submitted = created.submit(AT);
            instructions.transitionTo(submitted);

            Instruction.AwaitingResolution unknown =
                    submitted.timedOut(AT.plusSeconds(120), "rail did not reply within 120s");
            instructions.transitionTo(unknown);

            assertThat(unknown.state()).isEqualTo(InstructionState.AWAITING_RESOLUTION);
            assertThat(unknown.state())
                    .as("not failure - SPEC.md section 7 is explicit")
                    .isNotEqualTo(InstructionState.FAILED);
            assertThat(instructions.awaitingResolution()).extracting(Instruction::id)
                    .containsExactly(created.id());
        }

        @Test
        @DisplayName("evidence resolves it either way, and the attempt count never moves")
        void evidence_resolves_an_unknown() {
            Instruction.Created created = anInstruction("convert/7");
            Instruction.Submitted submitted = created.submit(AT);
            instructions.transitionTo(submitted);

            Instruction.AwaitingResolution unknown = submitted.timedOut(AT, "silence");
            instructions.transitionTo(unknown);

            Instruction.Settled settled = unknown.resolvedAsSettled(
                    AT.plusSeconds(3600), "re-query found tx 0xabc in block 12345");
            instructions.transitionTo(settled);

            assertThat(settled.attempts())
                    .as("resolving is not retrying; nothing was sent again")
                    .isEqualTo(1);
            assertThat(instructions.findById(created.id()).orElseThrow().state())
                    .isEqualTo(InstructionState.SETTLED);
        }

        @Test
        @DisplayName("SPEC.md section 17 scenario 6: the rail times out, then succeeds an hour later")
        void a_late_success_resolves_rather_than_duplicates() {
            // The agent has already planned around the silence. The instruction is AWAITING
            // RESOLUTION, so there is nothing to un-send and nothing was sent twice.
            Instruction.Created created = anInstruction("convert/8");
            Instruction.Submitted submitted = created.submit(AT);
            instructions.transitionTo(submitted);

            Instruction.AwaitingResolution unknown = submitted.timedOut(AT.plusSeconds(120), "silence");
            instructions.transitionTo(unknown);

            assertThat(unknown.unresolvedFor(AT.plusSeconds(3720))).isEqualTo(Duration.ofHours(1));

            instructions.transitionTo(unknown.resolvedAsSettled(AT.plusSeconds(3720), "late callback"));

            assertThat(instructions.findById(created.id()).orElseThrow().attempts())
                    .as("one attempt, one settlement")
                    .isEqualTo(1);
        }

        @Test
        void a_human_can_be_asked_to_look() {
            Instruction.Created created = anInstruction("convert/9");
            Instruction.Submitted submitted = created.submit(AT);
            instructions.transitionTo(submitted);
            Instruction.AwaitingResolution unknown = submitted.timedOut(AT, "silence");
            instructions.transitionTo(unknown);

            instructions.transitionTo(unknown.escalate(AT.plusSeconds(86400), "rail unreachable for a day"));

            assertThat(instructions.findById(created.id()).orElseThrow().state())
                    .isEqualTo(InstructionState.MANUAL_REVIEW);
        }

        @Test
        @DisplayName("a known failure can be retried, and the attempt count records it")
        void a_known_failure_is_safe_to_retry() {
            Instruction.Created created = anInstruction("convert/10");
            Instruction.Submitted submitted = created.submit(AT);
            instructions.transitionTo(submitted);

            Instruction.Failed failed = submitted.rejected(AT.plusSeconds(5), "rail: insufficient gas");
            instructions.transitionTo(failed);

            Instruction.Submitted again = failed.retry(AT.plusSeconds(600));
            instructions.transitionTo(again);

            assertThat(again.attempts()).isEqualTo(2);
            assertThat(again.externalRef())
                    .as("same reference, so the rail's own idempotency sees the same request")
                    .isEqualTo("convert/10");
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the constraints inherited from V7 still hold")
    class Inherited {

        @Test
        @DisplayName("I9: an instruction cannot exist without a committed intent")
        void no_instruction_without_an_intent() {
            assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                    instructions.create(999L, "convert/x", InstructionType.CONVERSION, usdc(1), AT));
        }

        @Test
        @DisplayName("F5: the same external reference cannot be used twice")
        void the_external_reference_is_unique() {
            anInstruction("convert/dup");

            assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                    instructions.create(anIntent(), "convert/dup", InstructionType.CONVERSION, usdc(1), AT));
        }
    }

    @Test
    void an_instruction_must_be_for_a_positive_amount() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new Instruction.Details(1L, 1L, "ref", InstructionType.CONVERSION,
                        Money.zero(Currency.USDC), AT, 0));
    }

    @Test
    void an_instruction_needs_a_usable_external_reference() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new Instruction.Details(1L, 1L, "  ", InstructionType.CONVERSION, usdc(1), AT, 0))
                .withMessageContaining("uniqueness is what prevents double payment");
    }
}

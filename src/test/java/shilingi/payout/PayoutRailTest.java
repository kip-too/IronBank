package shilingi.payout;

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
import shilingi.intent.Intent;
import shilingi.intent.IntentLog;
import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.platform.AbstractDatabaseTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 10 is done when "duplicate callback changes nothing; unknown callback reaches
 * reconciliation" (SPEC.md section 15).
 *
 * <p>Both are in {@link TheSixBehaviours}, alongside the other four SPEC.md section 12 requires.
 * That section is emphatic about why they matter: "Those last three are not decoration. They are
 * the failures that produce F5, and a mock that cannot produce them cannot prove the system
 * survives them."
 */
class PayoutRailTest extends AbstractDatabaseTest {

    @Autowired
    private MockPayoutRail rail;

    @Autowired
    private CallbackIngest ingest;

    @Autowired
    private InstructionRepository instructions;

    @Autowired
    private IntentLog intents;

    @Autowired
    private JdbcTemplate jdbc;

    private static final Instant AT = Instant.parse("2026-09-20T06:00:00Z");

    private static Money kes(long cents) {
        return Money.of(cents, Currency.KES);
    }

    /** An instruction in SUBMITTED, which is the state a callback normally finds. */
    private Instruction.Created anInstruction(String ref, Money amount) {
        long intentId = intents.append(Intent.proposing(AT, "test", "because", "{}", "{}")).id();
        return instructions.create(intentId, ref, InstructionType.PAYOUT, amount, AT);
    }

    private Instruction.Submitted submitted(String ref, Money amount) {
        Instruction.Submitted s = anInstruction(ref, amount).submit(AT);
        instructions.transitionTo(s);
        return s;
    }

    private PayoutPort.PayoutRequest request(String ref, Money amount) {
        return new PayoutPort.PayoutRequest(ref, amount, "Payroll beneficiary", AT);
    }

    /** Collects what the rail delivers, so a test can see order and repeats. */
    private List<PayoutCallback> drain() {
        List<PayoutCallback> seen = new ArrayList<>();
        rail.deliverPending(seen::add);
        return seen;
    }

    private List<IngestResultRecord> drainIntoIngest() {
        List<IngestResultRecord> results = new ArrayList<>();
        rail.deliverPending(cb -> results.add(new IngestResultRecord(cb, ingest.accept(cb))));
        return results;
    }

    private record IngestResultRecord(PayoutCallback callback, CallbackIngest.IngestResult result) {
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 12: accepted is not done")
    class AcceptedIsNotDone {

        @Test
        void send_returns_an_acceptance_and_nothing_has_happened_yet() {
            rail.reset();
            Instruction.Submitted instruction = submitted("payout/1", kes(80_000_000L));

            PayoutPort.Acceptance acceptance = rail.send(request("payout/1", kes(80_000_000L)));

            assertThat(acceptance.externalRef()).isEqualTo("payout/1");
            assertThat(acceptance.railRef()).isNotBlank().isNotEqualTo("payout/1");

            assertThat(instructions.findById(instruction.id()).orElseThrow().state())
                    .as("accepted by the rail is not settled in the books")
                    .isEqualTo(InstructionState.SUBMITTED);
            assertThat(ingest.count()).isZero();
        }

        @Test
        @DisplayName("our reference is carried through the whole round trip")
        void the_external_reference_survives_the_round_trip() {
            rail.reset();
            submitted("payout/roundtrip", kes(1_000L));

            rail.send(request("payout/roundtrip", kes(1_000L)));

            assertThat(drain()).singleElement()
                    .satisfies(cb -> assertThat(cb.externalRef()).isEqualTo("payout/roundtrip"));
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 12: the six behaviours")
    class TheSixBehaviours {

        @Test
        @DisplayName("1. succeed")
        void succeed() {
            rail.reset().behaving(RailBehaviour.SUCCEED);
            Instruction.Submitted instruction = submitted("payout/ok", kes(80_000_000L));
            rail.send(request("payout/ok", kes(80_000_000L)));

            List<IngestResultRecord> results = drainIntoIngest();

            assertThat(results).singleElement()
                    .satisfies(r -> assertThat(r.result().disposition())
                            .isEqualTo(CallbackIngest.Disposition.APPLIED));
            assertThat(instructions.findById(instruction.id()).orElseThrow().state())
                    .isEqualTo(InstructionState.SETTLED);
        }

        @Test
        @DisplayName("2. fail")
        void fail() {
            rail.reset().behaving(RailBehaviour.FAIL);
            Instruction.Submitted instruction = submitted("payout/no", kes(80_000_000L));
            rail.send(request("payout/no", kes(80_000_000L)));

            drainIntoIngest();

            assertThat(instructions.findById(instruction.id()).orElseThrow().state())
                    .isEqualTo(InstructionState.FAILED);
        }

        @Test
        @DisplayName("3. time out - the rail simply never replies")
        void time_out() {
            rail.reset().behaving(RailBehaviour.NEVER_REPLY);
            Instruction.Submitted instruction = submitted("payout/silence", kes(80_000_000L));
            rail.send(request("payout/silence", kes(80_000_000L)));

            assertThat(rail.pendingCount()).as("nothing will ever be delivered").isZero();
            assertThat(drainIntoIngest()).isEmpty();
            assertThat(ingest.count()).isZero();

            // The instruction is not failed. It is unknown, and unknown is not failure.
            instructions.transitionTo(instruction.timedOut(AT.plusSeconds(120), "no reply in 120s"));

            assertThat(instructions.findById(instruction.id()).orElseThrow().state())
                    .isEqualTo(InstructionState.AWAITING_RESOLUTION);
            assertThat(instructions.awaitingResolution()).hasSize(1);
        }

        @Test
        @DisplayName("4. deliver the callback twice - and the second one changes nothing")
        void callback_twice() {
            // Day 10's gate, first half.
            rail.reset().behaving(RailBehaviour.CALLBACK_TWICE);
            Instruction.Submitted instruction = submitted("payout/dup", kes(80_000_000L));
            rail.send(request("payout/dup", kes(80_000_000L)));

            List<IngestResultRecord> results = drainIntoIngest();

            assertThat(results).hasSize(2);
            assertThat(results.get(0).result().disposition())
                    .isEqualTo(CallbackIngest.Disposition.APPLIED);
            assertThat(results.get(1).result().disposition())
                    .as("the rail's own reference is the same, so it cannot be recorded twice")
                    .isEqualTo(CallbackIngest.Disposition.ALREADY_SEEN);
            assertThat(results.get(1).result().changedSomething()).isFalse();

            assertThat(ingest.count()).as("one event, one record").isEqualTo(1L);
            assertThat(instructions.findById(instruction.id()).orElseThrow())
                    .satisfies(i -> {
                        assertThat(i.state()).isEqualTo(InstructionState.SETTLED);
                        assertThat(i.attempts()).as("nothing was sent again").isEqualTo(1);
                    });
        }

        @Test
        @DisplayName("5. deliver the callbacks out of order")
        void callback_out_of_order() {
            rail.reset().behaving(RailBehaviour.CALLBACK_OUT_OF_ORDER);
            Instruction.Submitted first = submitted("payout/a", kes(100L));
            Instruction.Submitted second = submitted("payout/b", kes(200L));

            rail.send(request("payout/a", kes(100L)));
            rail.send(request("payout/b", kes(200L)));

            List<IngestResultRecord> results = drainIntoIngest();

            assertThat(results).extracting(r -> r.callback().externalRef())
                    .as("the second payment is reported before the first")
                    .containsExactly("payout/b", "payout/a");

            // Order does not matter, because each callback carries the reference it belongs to.
            assertThat(instructions.findById(first.id()).orElseThrow().state())
                    .isEqualTo(InstructionState.SETTLED);
            assertThat(instructions.findById(second.id()).orElseThrow().state())
                    .isEqualTo(InstructionState.SETTLED);
        }

        @Test
        @DisplayName("6. a callback for an instruction the rail was never given")
        void callback_for_unknown_instruction() {
            // Day 10's gate, second half. PROBLEM.md F3: money that arrived with no link to the
            // obligation it settles. The worst thing to do is nothing, quietly.
            rail.reset().behaving(RailBehaviour.CALLBACK_FOR_UNKNOWN_INSTRUCTION);
            rail.send(request("payout/real", kes(80_000_000L)));

            List<IngestResultRecord> results = drainIntoIngest();

            assertThat(results).singleElement().satisfies(r -> {
                assertThat(r.callback().externalRef()).startsWith("never-sent/");
                assertThat(r.result().disposition()).isEqualTo(CallbackIngest.Disposition.UNMATCHED);
                assertThat(r.result().instructionId()).isEmpty();
            });

            assertThat(ingest.unresolved())
                    .as("it reaches reconciliation rather than being swallowed")
                    .hasSize(1);
            assertThat(ingest.count()).as("and it is recorded, not discarded").isEqualTo(1L);
        }

        @Test
        @DisplayName("7. SPEC.md section 17 scenario 4: right reference, wrong amount")
        void callback_with_the_wrong_amount() {
            rail.reset().behaving(RailBehaviour.CALLBACK_WITH_WRONG_AMOUNT);
            Instruction.Submitted instruction = submitted("payout/short", kes(80_000_000L));
            rail.send(request("payout/short", kes(80_000_000L)));

            List<IngestResultRecord> results = drainIntoIngest();

            assertThat(results).singleElement()
                    .satisfies(r -> assertThat(r.result().disposition())
                            .isEqualTo(CallbackIngest.Disposition.AMOUNT_MISMATCH));

            assertThat(instructions.findById(instruction.id()).orElseThrow().state())
                    .as("SPEC.md section 13: an exception, always, regardless of size - one cent is "
                        + "not settled on either figure")
                    .isEqualTo(InstructionState.SUBMITTED);
            assertThat(ingest.unresolved()).hasSize(1);
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 13: recorded before it is processed")
    class RecordedFirst {

        @Test
        void the_callback_is_recorded_whatever_is_decided_about_it() {
            rail.reset().behaving(RailBehaviour.CALLBACK_FOR_UNKNOWN_INSTRUCTION);
            rail.send(request("payout/x", kes(100L)));
            drainIntoIngest();

            assertThat(jdbc.queryForObject(
                    "select count(*) from inbound_callback where disposition = 'UNMATCHED'",
                    Long.class)).isEqualTo(1L);
        }

        @Test
        @DisplayName("what a callback said cannot be edited afterwards")
        void the_record_is_write_once() {
            rail.reset();
            submitted("payout/frozen", kes(100L));
            rail.send(request("payout/frozen", kes(100L)));
            long id = drainIntoIngest().get(0).result().callbackId();

            assertThatThrownBy(() ->
                    jdbc.update("update inbound_callback set amount_minor = 1 where id = ?", id))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("cannot be changed");
        }

        @Test
        @DisplayName("a callback already dealt with cannot be dealt with again")
        void processing_happens_once() {
            rail.reset();
            submitted("payout/once", kes(100L));
            rail.send(request("payout/once", kes(100L)));
            long id = drainIntoIngest().get(0).result().callbackId();

            assertThatThrownBy(() ->
                    jdbc.update("update inbound_callback set disposition = 'UNMATCHED' where id = ?", id))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("already been dealt with");
        }

        @Test
        void callbacks_cannot_be_deleted() {
            rail.reset();
            submitted("payout/keep", kes(100L));
            rail.send(request("payout/keep", kes(100L)));
            long id = drainIntoIngest().get(0).result().callbackId();

            assertThatThrownBy(() -> jdbc.update("delete from inbound_callback where id = ?", id))
                    .hasMessageContaining("append-only");
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 17, the awkward arrivals")
    class AwkwardArrivals {

        @Test
        @DisplayName("scenario 2: the callback arrives after the instruction was marked AWAITING_RESOLUTION")
        void a_late_callback_resolves_rather_than_retries() {
            rail.reset().behaving(RailBehaviour.SUCCEED);
            Instruction.Submitted instruction = submitted("payout/late", kes(100L));
            rail.send(request("payout/late", kes(100L)));

            // Four hours of silence in between, and the instruction is marked unknown.
            Instruction.AwaitingResolution unknown =
                    instruction.timedOut(AT.plusSeconds(120), "no reply");
            instructions.transitionTo(unknown);

            // Then the callback finally arrives.
            drainIntoIngest();

            assertThat(instructions.findById(instruction.id()).orElseThrow())
                    .satisfies(i -> {
                        assertThat(i.state()).isEqualTo(InstructionState.SETTLED);
                        assertThat(i.attempts())
                                .as("resolved, not retried - one attempt, one settlement")
                                .isEqualTo(1);
                    });
        }

        @Test
        @DisplayName("a callback for an instruction that has already settled changes nothing")
        void a_callback_that_cannot_apply_is_recorded_not_forced() {
            rail.reset().behaving(RailBehaviour.SUCCEED);
            Instruction.Submitted instruction = submitted("payout/done", kes(100L));
            instructions.transitionTo(instruction.confirmed(AT, "settled by another route"));

            rail.send(request("payout/done", kes(100L)));
            List<IngestResultRecord> results = drainIntoIngest();

            assertThat(results).singleElement()
                    .satisfies(r -> assertThat(r.result().disposition())
                            .isEqualTo(CallbackIngest.Disposition.NOT_APPLICABLE));
            assertThat(ingest.unresolved()).hasSize(1);
        }

        @Test
        @DisplayName("re-querying sends nothing, so it can never cause a second payment")
        void status_is_safe_to_call_on_an_unknown() {
            rail.reset().behaving(RailBehaviour.SUCCEED);
            submitted("payout/query", kes(100L));
            rail.send(request("payout/query", kes(100L)));

            int sentBefore = rail.acceptedRequests().size();
            rail.status("payout/query");
            rail.status("payout/query");
            rail.status("payout/query");

            assertThat(rail.acceptedRequests())
                    .as("SPEC.md section 7: it is re-queried, never retried")
                    .hasSize(sentBefore);
        }

        @Test
        void a_rail_that_never_replied_has_no_status_to_give() {
            rail.reset().behaving(RailBehaviour.NEVER_REPLY);
            submitted("payout/nothing", kes(100L));
            rail.send(request("payout/nothing", kes(100L)));

            assertThat(rail.status("payout/nothing"))
                    .as("silence is a real answer, and it is why AWAITING_RESOLUTION exists")
                    .isEmpty();
        }
    }
}

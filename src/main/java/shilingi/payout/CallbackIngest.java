package shilingi.payout;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import shilingi.clock.ClockPort;
import shilingi.instruction.Instruction;
import shilingi.instruction.InstructionRepository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Takes what a rail says and decides what, if anything, it means.
 *
 * <p>SPEC.md section 13, in the order that section states it:
 *
 * <ol>
 *   <li><b>Record it</b>, keyed on the rail's own reference, with a unique constraint.</li>
 *   <li><b>Then</b> process it.</li>
 * </ol>
 *
 * <h2>Why recording comes first, and commits on its own</h2>
 * {@link #record} runs in {@code REQUIRES_NEW}, so the evidence that a callback arrived survives
 * whatever happens next. If processing throws, the row remains with a null {@code processed_at} -
 * <b>an event that arrived and was not dealt with</b>, which is a reconciliation item. Processing
 * first and recording after would lose exactly the events worth keeping: the ones handled badly.
 *
 * <h2>Five dispositions, and only one of them changes anything</h2>
 * <ul>
 *   <li>{@code APPLIED} - matched an instruction and moved it.</li>
 *   <li>{@code ALREADY_SEEN} - a repeat. <b>Changes nothing</b>, which is the day 10 gate.</li>
 *   <li>{@code UNMATCHED} - no instruction carries that reference. Not an error to swallow; it
 *       is money the system cannot explain, and it goes to reconciliation (SPEC.md section 13,
 *       and PROBLEM.md F3).</li>
 *   <li>{@code AMOUNT_MISMATCH} - right reference, wrong amount. SPEC.md section 13: "an
 *       exception, always, regardless of size". Nothing is settled on a figure that disagrees.</li>
 *   <li>{@code NOT_APPLICABLE} - the instruction was in no state to receive it, for instance
 *       already settled by a different rail reference.</li>
 * </ul>
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>It posts nothing to the ledger.</b> A settled payout should produce journal entries,
 *       and what those entries are depends on an allocation rule SPEC.md does not give. The
 *       instruction moves; the books do not, yet.</li>
 *   <li><b>No reconciliation screen.</b> {@link #unresolved()} is the query day 13's reconciler
 *       will build on; it is not the reconciler.</li>
 *   <li><b>No signature or authenticity check.</b> A real rail signs its callbacks. Verifying that
 *       is a thing the real adapter would do and this mock cannot meaningfully rehearse.</li>
 * </ul>
 */
@Service
public class CallbackIngest {

    private final JdbcTemplate jdbc;
    private final InstructionRepository instructions;
    private final ClockPort clock;

    public CallbackIngest(JdbcTemplate jdbc, InstructionRepository instructions, ClockPort clock) {
        this.jdbc = jdbc;
        this.instructions = instructions;
        this.clock = clock;
    }

    /** What happened to a callback, and why. */
    public record IngestResult(long callbackId, Disposition disposition, Optional<Long> instructionId) {

        public boolean changedSomething() {
            return disposition == Disposition.APPLIED;
        }
    }

    public enum Disposition {
        APPLIED, ALREADY_SEEN, UNMATCHED, AMOUNT_MISMATCH, NOT_APPLICABLE
    }

    /**
     * The whole of SPEC.md section 13's inbound path.
     *
     * @return what was decided. Never throws for an unexpected callback - an unexpected callback
     *         is information, and throwing it away is what PROBLEM.md F3 is about.
     */
    public IngestResult accept(PayoutCallback callback) {
        Optional<Long> recorded = record(callback);

        if (recorded.isEmpty()) {
            // The unique constraint on (rail, rail_ref) refused it. We have seen this event.
            Long existingId = jdbc.queryForObject(
                    "select id from inbound_callback where rail = ? and rail_ref = ?",
                    Long.class, callback.rail(), callback.railRef());
            return new IngestResult(existingId, Disposition.ALREADY_SEEN, Optional.empty());
        }

        return process(recorded.get(), callback);
    }

    /**
     * Writes the callback down and commits, before anything is decided about it.
     *
     * @return the new row's id, or empty when this rail reference has been seen before
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Optional<Long> record(PayoutCallback callback) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "insert into inbound_callback (rail, rail_ref, external_ref, outcome, "
                        + "amount_minor, currency, occurred_at, received_at, raw_payload) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?::json)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, callback.rail());
                ps.setString(2, callback.railRef());
                ps.setString(3, callback.externalRef());
                ps.setString(4, callback.outcome().name());
                ps.setLong(5, callback.amount().minorUnits());
                ps.setString(6, callback.amount().currency().name());
                ps.setTimestamp(7, Timestamp.from(callback.occurredAt()));
                ps.setTimestamp(8, Timestamp.from(clock.instant()));
                ps.setString(9, callback.rawPayload());
                return ps;
            }, keys);

            return Optional.of(((Number) keys.getKeys().get("id")).longValue());

        } catch (DuplicateKeyException alreadySeen) {
            return Optional.empty();
        }
    }

    @Transactional
    IngestResult process(long callbackId, PayoutCallback callback) {
        Optional<Instruction> match = instructions.findByExternalRef(callback.externalRef());

        if (match.isEmpty()) {
            // PROBLEM.md F3: money that arrived with no link to the obligation it settles. The
            // worst thing to do here is nothing quietly.
            return settle(callbackId, Disposition.UNMATCHED, null);
        }

        Instruction instruction = match.get();

        if (!instruction.amount().equals(callback.amount())) {
            // SPEC.md section 13: "amounts differ - exception, always, regardless of size."
            // Note what does NOT happen: the instruction is not settled on the rail's figure,
            // and it is not settled on ours either.
            return settle(callbackId, Disposition.AMOUNT_MISMATCH, instruction.id());
        }

        Optional<Instruction> moved = applyTo(instruction, callback);

        if (moved.isEmpty()) {
            return settle(callbackId, Disposition.NOT_APPLICABLE, instruction.id());
        }

        instructions.transitionTo(moved.get());
        return settle(callbackId, Disposition.APPLIED, instruction.id());
    }

    /**
     * Moves the instruction, if its current state can receive this callback.
     *
     * <p>The pattern match is exhaustive over the sealed type, so a new state cannot be added
     * without this being made to say what it means. SPEC.md section 17 scenario 2 - a callback
     * arriving after the instruction has been marked AWAITING_RESOLUTION - is the
     * {@code AwaitingResolution} branch, and it resolves rather than retries.
     */
    private Optional<Instruction> applyTo(Instruction instruction, PayoutCallback callback) {
        String evidence = callback.rail() + " " + callback.railRef();

        return switch (instruction) {
            case Instruction.Submitted submitted -> Optional.of(
                    callback.outcome() == PayoutOutcome.SUCCEEDED
                            ? submitted.confirmed(callback.occurredAt(), evidence)
                            : submitted.rejected(callback.occurredAt(), evidence));

            case Instruction.AwaitingResolution unknown -> Optional.of(
                    callback.outcome() == PayoutOutcome.SUCCEEDED
                            ? unknown.resolvedAsSettled(callback.occurredAt(), evidence)
                            : unknown.resolvedAsFailed(callback.occurredAt(), evidence));

            // Everything else is a callback arriving where it cannot apply: never sent, already
            // finished, or waiting on a person. None of those is an error, and none of them is
            // a reason to force a transition.
            case Instruction.Created ignored -> Optional.empty();
            case Instruction.Settled ignored -> Optional.empty();
            case Instruction.Failed ignored -> Optional.empty();
            case Instruction.ManualReview ignored -> Optional.empty();
        };
    }

    private IngestResult settle(long callbackId, Disposition disposition, Long instructionId) {
        jdbc.update("update inbound_callback set processed_at = ?, disposition = ?, "
                    + "matched_instruction_id = ? where id = ?",
                Timestamp.from(clock.instant()), disposition.name(), instructionId, callbackId);

        return new IngestResult(callbackId, disposition, Optional.ofNullable(instructionId));
    }

    /**
     * Everything reconciliation needs to look at: callbacks that matched nothing, disagreed on an
     * amount, could not be applied, or were never processed at all.
     *
     * <p>Day 13's reconciler turns these into ReconItems with an age. This is the query underneath.
     */
    public List<Long> unresolved() {
        return jdbc.queryForList(
                "select id from inbound_callback "
                + "where processed_at is null "
                + "   or disposition in ('UNMATCHED', 'AMOUNT_MISMATCH', 'NOT_APPLICABLE') "
                + "order by received_at, id",
                Long.class);
    }

    /** What was decided about a callback, for tests and for the screen. */
    public Optional<String> dispositionOf(long callbackId) {
        return jdbc.queryForList("select disposition from inbound_callback where id = ?",
                String.class, callbackId).stream().findFirst();
    }

    public long count() {
        Long count = jdbc.queryForObject("select count(*) from inbound_callback", Long.class);
        return count == null ? 0L : count;
    }
}

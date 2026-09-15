package shilingi.intent;

import java.time.Instant;
import java.util.Objects;

/**
 * A decision the agent made, written down before it acted (SPEC.md section 6).
 *
 * <p>This is the answer to PROBLEM.md F6: "Somebody (or something) decided to convert 6,000 on a
 * Tuesday. Nobody can say why." A human converting money remembers roughly what they did. A
 * program does not remember anything it was not told to write down.
 *
 * <h2>It happened, so it cannot be un-happened</h2>
 * SPEC.md section 7: "Intent is append-only. It has no state machine." There is no
 * {@code withSomething} method on this record and no update in {@link IntentLog}, and the
 * database rejects UPDATE, DELETE and TRUNCATE on the table. An intent that could be edited once
 * the outcome was known would be worse than no intent at all - it would look like evidence while
 * being a reconstruction.
 *
 * <h2>Written and committed BEFORE the instruction</h2>
 * SPEC.md section 11: "Steps 6 and 7 are in that order and the order is not negotiable." The
 * mechanism is the {@code NOT NULL} foreign key from {@code instruction.intent_id} to this
 * table's {@code id} - an instruction simply cannot be inserted until its intent is committed.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No outcome, and no link to one.</b> An intent records what was believed and proposed,
 *       not what happened. SPEC.md section 17 scenario 6 asks what the record should say when
 *       the reasoning turns out wrong but the outcome was good - this design answers it by
 *       keeping the two apart: the intent says what was believed, the journal says what
 *       happened, and the reconciler puts them side by side on day 13.</li>
 *   <li><b>No structure inside the snapshot or the action.</b> Both are JSON text. What belongs
 *       in them is the agent's business, on day 8.</li>
 *   <li>No author or actor field. SPEC.md section 6 lists five fields and none is "who".
 *       Multi-user is out of scope (SPEC.md section 2).</li>
 * </ul>
 *
 * @param id             database identity, null until appended
 * @param createdAt      from the injected clock. Never {@code Instant.now()}.
 * @param trigger        what caused this decision cycle to run
 * @param reasoning      why, in words, for whoever asks in March
 * @param inputsSnapshot JSON: a COPY of everything the agent read before deciding
 * @param proposedAction JSON: what it proposes to do, before it does it
 */
public record Intent(
        Long id,
        Instant createdAt,
        String trigger,
        String reasoning,
        String inputsSnapshot,
        String proposedAction) {

    public Intent {
        Objects.requireNonNull(createdAt, "An intent needs a timestamp, from the injected clock");
        Objects.requireNonNull(trigger, "An intent needs a trigger: what made the agent look?");
        Objects.requireNonNull(reasoning, "An intent needs reasoning: an unexplained decision is F6");
        Objects.requireNonNull(inputsSnapshot, "An intent needs a snapshot of what was seen");
        Objects.requireNonNull(proposedAction, "An intent needs a proposed action");

        if (trigger.isBlank()) {
            throw new IllegalArgumentException("An intent needs a trigger, and a blank one is not one");
        }
        if (reasoning.isBlank()) {
            throw new IllegalArgumentException(
                    "An intent needs reasoning. A decision recorded without a reason is the failure "
                    + "this log exists to prevent (PROBLEM.md F6).");
        }
    }

    public static Intent proposing(Instant createdAt, String trigger, String reasoning,
                                   String inputsSnapshot, String proposedAction) {
        return new Intent(null, createdAt, trigger, reasoning, inputsSnapshot, proposedAction);
    }
}

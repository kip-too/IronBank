package shilingi.instruction;

import shilingi.money.Money;

import java.time.Instant;
import java.util.Objects;

/**
 * A thing to be done, and the state machine that governs it (SPEC.md sections 6 and 7).
 *
 * <h2>Invariant I7, made unrepresentable</h2>
 * SPEC.md section 9 is unusually specific about how this must be built:
 *
 * <blockquote>
 * "I7 deserves a design note: do not implement it as an {@code if} statement that skips the
 * retry. Implement it so that the retry method <b>cannot be called</b> on an instruction in that
 * state - a different type, or a guard in the state machine itself. Rules enforced by discipline
 * get broken at 2am on day 12."
 * </blockquote>
 *
 * <p>So each state is its own type, and a transition is a method that returns the next type.
 * {@code retry()} exists on exactly one of them - {@link Failed} - and therefore:
 *
 * <pre>
 *   AwaitingResolution unknown = ...;
 *   unknown.retry();   // does not compile. There is no such method.
 * </pre>
 *
 * <p>Not "does not run". Not "throws". <b>Does not compile.</b> There is no flag to get wrong, no
 * branch to invert, and no 2am to survive. {@code InstructionStateMachineTest} proves it two
 * ways: by reflection over the type, and by compiling a probe file that fails.
 *
 * <p>The database carries the same rule underneath, in V8's transition trigger, because a type
 * system does not protect a SQL prompt.
 *
 * <h2>Why retry is safe on FAILED and nowhere else</h2>
 * F5 is silent double payment, and it happens when something is retried whose outcome was
 * <i>unknown</i>. A {@link Failed} instruction is not unknown - the rail said it did not happen.
 * Retrying a known failure is ordinary operations. Retrying an unknown is how money leaves twice
 * and does not come back.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>{@link ManualReview} exits only through a named person.</b> SPEC.md section 7 draws no
 *       arrow out of it at all, which made it a trap - see ADR-021. Its two exits require a name
 *       and evidence, and neither of them is a retry.</li>
 *   <li><b>No re-query mechanism.</b> SPEC.md section 7 says an unknown instruction "is
 *       re-queried"; asking the rail again is the settlement adapter's job (day 11) and the
 *       payout adapter's (day 10). What arrives back is evidence, and
 *       {@link AwaitingResolution#resolvedAsSettled} / {@link AwaitingResolution#resolvedAsFailed}
 *       are where it lands.</li>
 *   <li>No link to the settlement record. {@code Settlement} is its own entity in SPEC.md
 *       section 6 and arrives with the reconciler.</li>
 * </ul>
 */
public sealed interface Instruction {

    Details details();

    InstructionState state();

    default long id() {
        return details().id();
    }

    default long intentId() {
        return details().intentId();
    }

    default String externalRef() {
        return details().externalRef();
    }

    default Money amount() {
        return details().amount();
    }

    default int attempts() {
        return details().attempts();
    }

    /**
     * The parts of an instruction that do not change as it moves through the machine.
     *
     * @param intentId    invariant I9: an instruction cannot exist without an intent, enforced by
     *                    a {@code NOT NULL} foreign key.
     * @param externalRef unique in the database. SPEC.md section 13 calls this "the single most
     *                    important constraint in the schema".
     * @param attempts    how many times this has been sent to the rail. Only a submit increments
     *                    it, and only {@link Created} and {@link Failed} can submit.
     */
    record Details(long id, long intentId, String externalRef, InstructionType type,
                   Money amount, Instant createdAt, int attempts) {

        public Details {
            Objects.requireNonNull(externalRef, "An instruction needs an external reference");
            Objects.requireNonNull(type, "An instruction needs a type");
            Objects.requireNonNull(amount, "An instruction needs an amount");
            Objects.requireNonNull(createdAt, "An instruction needs a creation time, from the clock");

            if (externalRef.isBlank()) {
                throw new IllegalArgumentException(
                        "An instruction needs an external reference; a blank one cannot be unique "
                        + "in any useful sense, and uniqueness is what prevents double payment");
            }
            if (!amount.isPositive()) {
                throw new IllegalArgumentException(
                        "An instruction must be for a positive amount, but was " + amount);
            }
            if (attempts < 0) {
                throw new IllegalArgumentException("attempts cannot be negative");
            }
        }

        Details withOneMoreAttempt() {
            return new Details(id, intentId, externalRef, type, amount, createdAt, attempts + 1);
        }
    }

    // ------------------------------------------------------------------------------------

    /** Written down, not yet sent. */
    record Created(Details details) implements Instruction {

        @Override
        public InstructionState state() {
            return InstructionState.CREATED;
        }

        /** Sends it to the rail for the first time. */
        public Submitted submit(Instant at) {
            return new Submitted(details.withOneMoreAttempt(), at);
        }
    }

    /**
     * Sent. The outcome is not known yet, but the fact that it was sent is.
     *
     * <p><b>There is no retry here either</b>, and that is as important as the missing one on
     * {@link AwaitingResolution}: an instruction in flight has an unknown outcome too.
     */
    record Submitted(Details details, Instant submittedAt) implements Instruction {

        @Override
        public InstructionState state() {
            return InstructionState.SUBMITTED;
        }

        /** The rail confirmed it. */
        public Settled confirmed(Instant at, String evidence) {
            return new Settled(details, at, evidence);
        }

        /**
         * The rail said nothing and the timeout expired. SPEC.md section 7's most important
         * arrow: this is not failure, and what follows is never a retry.
         */
        public AwaitingResolution timedOut(Instant at, String because) {
            return new AwaitingResolution(details, at, because);
        }

        /**
         * The rail explicitly rejected it.
         *
         * <p><b>This arrow is not drawn in SPEC.md section 7</b>, which shows FAILED only under
         * AWAITING_RESOLUTION. It is added deliberately - see ADR-020. In short: the state exists
         * for outcomes that are <i>unknown</i>, and a rail reporting failure is a known outcome.
         * Routing it through AWAITING_RESOLUTION would blur exactly the distinction the state
         * exists to draw.
         */
        public Failed rejected(Instant at, String evidence) {
            return new Failed(details, at, evidence);
        }
    }

    /**
     * The state most systems do not have.
     *
     * <p>Note what is absent: there is no {@code retry}, no {@code resubmit}, no {@code send}.
     * Not disabled, not guarded - <b>absent</b>. The only ways out are evidence and a human.
     */
    record AwaitingResolution(Details details, Instant since, String because) implements Instruction {

        @Override
        public InstructionState state() {
            return InstructionState.AWAITING_RESOLUTION;
        }

        /** Evidence arrived: it did happen after all. */
        public Settled resolvedAsSettled(Instant at, String evidence) {
            return new Settled(details, at, evidence);
        }

        /** Evidence arrived: it did not happen. */
        public Failed resolvedAsFailed(Instant at, String evidence) {
            return new Failed(details, at, evidence);
        }

        /** Nobody can tell from the evidence. SPEC.md section 7: a human must look. */
        public ManualReview escalate(Instant at, String why) {
            return new ManualReview(details, at, why);
        }

        /** How long this has been unresolved, for the ageing SPEC.md section 13 asks for. */
        public java.time.Duration unresolvedFor(Instant now) {
            return java.time.Duration.between(since, now);
        }
    }

    /** The money moved. */
    record Settled(Details details, Instant at, String evidence) implements Instruction {

        @Override
        public InstructionState state() {
            return InstructionState.SETTLED;
        }
    }

    /**
     * The money did not move, and that is known rather than assumed.
     *
     * <p>The only state carrying {@link #retry(Instant)}, for the reason in the interface header.
     */
    record Failed(Details details, Instant at, String evidence) implements Instruction {

        @Override
        public InstructionState state() {
            return InstructionState.FAILED;
        }

        /**
         * Sends it again. Safe precisely because this state means the outcome is known.
         *
         * <p>Note this increments {@code attempts} on the same instruction, keeping the same
         * {@code externalRef} - so the rail's own idempotency sees the same reference, and the
         * database's unique constraint is untouched.
         */
        public Submitted retry(Instant at) {
            return new Submitted(details.withOneMoreAttempt(), at);
        }
    }

    /**
     * A human must look.
     *
     * <p>SPEC.md section 7 draws no arrow out of here. Leaving it with none made the state a trap
     * - anything that reached it stayed there for ever, including on demo day. So two exits are
     * added, and they are the same two that {@link AwaitingResolution} has: a human, having
     * looked, says what happened. See ADR-021.
     *
     * <p><b>There is still no retry.</b> A human who wants the payment sent again resolves this
     * to {@link Failed} first, stating that it did not happen, and retries from there. That
     * ordering is the point: somebody has to put their name to "this did not happen" before
     * anything is sent a second time.
     */
    record ManualReview(Details details, Instant since, String why) implements Instruction {

        @Override
        public InstructionState state() {
            return InstructionState.MANUAL_REVIEW;
        }

        /**
         * A person looked and found it had settled.
         *
         * @param who      who looked. Required: an anonymous resolution is not a resolution.
         * @param evidence what they saw. Required for the same reason.
         */
        public Settled resolvedByHumanAsSettled(Instant at, String who, String evidence) {
            return new Settled(details, at, attributed(who, evidence));
        }

        /** A person looked and found it had not happened. Retry becomes available from there. */
        public Failed resolvedByHumanAsFailed(Instant at, String who, String evidence) {
            return new Failed(details, at, attributed(who, evidence));
        }

        private static String attributed(String who, String evidence) {
            if (who == null || who.isBlank()) {
                throw new IllegalArgumentException(
                        "A manual resolution needs a name against it. The whole reason this state "
                        + "exists is that a person took responsibility for the answer.");
            }
            if (evidence == null || evidence.isBlank()) {
                throw new IllegalArgumentException(
                        "A manual resolution needs evidence. Somebody's word alone is not evidence.");
            }
            return "resolved by " + who + ": " + evidence;
        }
    }
}

package shilingi.agent;

import shilingi.intent.Intent;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of one decision cycle: what was seen, what was proposed, whether it was allowed,
 * and the committed intent that records all of it.
 *
 * <p>A refused decision is still a decision and still has an intent. SPEC.md section 11 says a
 * crash between writing the intent and creating the instruction leaves "a decision with no
 * action - recoverable and visible"; a refusal leaves exactly the same shape on purpose, and for
 * the same reason. The most interesting line in the log is the one where the system said no.
 *
 * @param intent    the committed record. Always present - even a refusal is written down.
 * @param plan      the arithmetic the decision rested on
 * @param action    what was proposed
 * @param violation the invariant that refused it, if one did
 */
public record Decision(Intent intent, FundingPlan plan, ProposedAction action,
                       Optional<InvariantViolation> violation) {

    public Decision {
        Objects.requireNonNull(intent, "Every decision has an intent, including a refused one");
        Objects.requireNonNull(plan, "plan is required");
        Objects.requireNonNull(action, "action is required");
        Objects.requireNonNull(violation, "violation is required (as an Optional, possibly empty)");
    }

    /**
     * Whether an instruction may be created from this.
     *
     * <p>Day 9's treasury service is what acts on this. It is the only gate between a proposal
     * and money moving, and a false answer here must stop the instruction being created at all -
     * not merely mark it.
     */
    public boolean approved() {
        return violation.isEmpty();
    }

    /** True when the system said no. The demo's strongest moment (SPEC.md section 11). */
    public boolean refused() {
        return violation.isPresent();
    }
}

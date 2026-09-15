package shilingi.agent;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The constraints, in Java, applied to a proposed action whatever proposed it.
 *
 * <p>SPEC.md section 11: <i>"The agent's reasoning may come from a language model. Its
 * constraints may not. The invariants are checked in Java, before the instruction is created,
 * and a proposed action that violates one is rejected regardless of how good the reasoning
 * sounds."</i>
 *
 * <p>Nothing in this class can see the reasoning, and that is deliberate rather than incidental.
 * It is handed an action and a set of facts. A well-argued proposal and a badly-argued one that
 * amount to the same action get the same answer, which is the only way the sentence above can be
 * true in practice.
 *
 * <h2>Invariant I8</h2>
 * SPEC.md section 9: <i>"Every SCHEDULED obligation with a due date inside the planning horizon
 * has funds allocated, or the agent must refuse to hold dollars."</i>
 *
 * <p>Read carefully, that is a rule about <b>holding</b>. So the check is:
 *
 * <blockquote>
 * When the plan says some dollars must be converted to cover what is due, a proposal that
 * converts fewer than that is holding dollars while an obligation goes unfunded, and is refused.
 * </blockquote>
 *
 * <p>Two consequences that fall out rather than being bolted on:
 * <ul>
 *   <li><b>A missing rate IS a violation, when dollars are held.</b> Without a rate the agent
 *       cannot say how many dollars would cover the gap, so {@code mustConvert} is zero - and
 *       without this case, a rate-feed outage would silently permit holding dollars while an
 *       obligation went unfunded. I8 would then be bypassed by an absence rather than defeated
 *       by an argument, which is a worse failure because nothing announces it.</li>
 *   <li><b>An empty wallet is not a violation.</b> {@code mustConvert} is capped at what is
 *       held, so with no dollars it is zero and holding nothing is permitted. SPEC.md section 17
 *       scenario 12 - an obligation due with no dollars and no shillings - is a shortfall the
 *       agent cannot fix, and refusing it would be punishing the agent for arithmetic. The
 *       unfunded obligation is still recorded and still visible.</li>
 *   <li><b>Converting too little is the same violation as converting nothing.</b> Holding back
 *       half the dollars that are needed is holding dollars.</li>
 * </ul>
 *
 * <h2>What this does not check</h2>
 * <ul>
 *   <li><b>Converting MORE than the plan requires is allowed.</b> I8 is about failing to convert,
 *       not about over-converting. Converting more than needed is a bet on the shilling in the
 *       other direction, and PROBLEM.md section 8 question 3 asks where the line is without
 *       answering it. <b>No rule in SPEC.md forbids it, so none is invented here.</b> It is
 *       visible in the intent log either way.</li>
 *   <li><b>Nothing about timing.</b> Whether now is a good moment to convert is rate-aware
 *       timing, a stretch goal in SPEC.md section 11, and not a constraint.</li>
 *   <li><b>Nothing about which obligation goes short</b> when funds cover only some of them
 *       (SPEC.md section 17 scenario 16). The plan works in aggregate.</li>
 * </ul>
 */
@Component
public class TreasuryInvariants {

    /**
     * Judges a proposal. Returns the violation rather than throwing, so that the refusal itself
     * can be written into the intent log - a decision to refuse is still a decision, and
     * PROBLEM.md F6 applies to it as much as to an action.
     *
     * @return empty when the action is permitted
     */
    public Optional<InvariantViolation> check(ProposedAction action, FundingPlan plan) {
        return switch (action) {
            case ProposedAction.Hold hold -> checkHold(hold, plan);
            case ProposedAction.Convert convert -> checkConvert(convert, plan);
        };
    }

    /** As {@link #check}, for callers that want the exception. */
    public void require(ProposedAction action, FundingPlan plan) {
        check(action, plan).ifPresent(violation -> {
            throw violation;
        });
    }

    private Optional<InvariantViolation> checkHold(ProposedAction.Hold hold, FundingPlan plan) {
        if (plan.cannotBeWorkedOut()) {
            // A rate outage is not a reason to leave payroll unfunded. It is a reason a human
            // needs to look. Refusing here is what makes the outage appear as an exception
            // rather than as a routine "nothing to do today".
            return Optional.of(new InvariantViolation("I8", hold,
                    "there is " + plan.shortfall() + " of unfunded obligations inside the planning "
                    + "horizon and the wallet holds " + plan.held() + ", but no mid-market rate is "
                    + "published for today, so how many dollars would cover it cannot be worked out. "
                    + "The obligation is unfunded and nothing can act on it: a human must look."));
        }

        if (plan.conversionIsRequired()) {
            return Optional.of(new InvariantViolation("I8", hold,
                    "there is " + plan.shortfall() + " of unfunded obligations inside the planning "
                    + "horizon and the wallet holds dollars that would cover " + plan.mustConvert()
                    + " of it. An obligation with a due date must not be left without funds because "
                    + "the agent decided to hold dollars."));
        }
        return Optional.empty();
    }

    private Optional<InvariantViolation> checkConvert(ProposedAction.Convert convert, FundingPlan plan) {
        if (convert.amount().currency() != plan.mustConvert().currency()) {
            return Optional.of(new InvariantViolation("I8", convert,
                    "the proposal converts " + convert.amount().currency()
                    + " but the wallet holds " + plan.mustConvert().currency()));
        }

        if (convert.amount().compareTo(plan.mustConvert()) < 0) {
            return Optional.of(new InvariantViolation("I8", convert,
                    "the proposal converts " + convert.amount() + ", which is less than the "
                    + plan.mustConvert() + " needed to cover obligations inside the horizon. "
                    + "Holding back part of what is needed is still holding dollars."));
        }

        return Optional.empty();
    }
}

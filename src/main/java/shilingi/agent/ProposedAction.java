package shilingi.agent;

import shilingi.money.Currency;
import shilingi.money.Money;

/**
 * What the agent proposes to do. SPEC.md section 11: the agent does one thing - decides how many
 * dollars to convert, and when - so there are exactly two shapes of answer.
 *
 * <p>Sealed, so that {@link TreasuryInvariants} can check every kind exhaustively and the
 * compiler complains if a third is ever added without the guard being taught about it.
 *
 * <p>A proposal is just a proposal. It may come from the dull rule in {@link TreasuryAgent}, or
 * from a language model, or from a person at a keyboard. SPEC.md section 11: "The agent's
 * reasoning may come from a language model. Its constraints may not." Nothing downstream cares
 * where a proposal came from, and nothing skips the guard because of where it came from.
 */
public sealed interface ProposedAction {

    /** A short description for the intent log and the demo screen. */
    String describe();

    /** Convert dollars to shillings. */
    record Convert(Money amount) implements ProposedAction {

        public Convert {
            if (amount == null) {
                throw new IllegalArgumentException("A conversion needs an amount");
            }
            if (amount.currency() == Currency.KES) {
                throw new IllegalArgumentException(
                        "A conversion gives up foreign currency, not shillings");
            }
            if (!amount.isPositive()) {
                throw new IllegalArgumentException(
                        "A conversion of " + amount + " is not an action. Proposing to do nothing "
                        + "is ProposedAction.Hold, which the invariants judge differently.");
            }
        }

        @Override
        public String describe() {
            return "CONVERT " + amount;
        }
    }

    /**
     * Keep the dollars and do nothing.
     *
     * <p>This is the action invariant I8 exists to judge. SPEC.md section 9: every SCHEDULED
     * obligation inside the horizon has funds allocated, "or the agent must refuse to hold
     * dollars". Holding is a position, not an absence of one - PROBLEM.md section 8 question 3
     * asks whether it is a treasury decision or a bet, and either way it is a choice that has to
     * be recorded and judged.
     */
    record Hold(String because) implements ProposedAction {

        public Hold {
            if (because == null || because.isBlank()) {
                throw new IllegalArgumentException(
                        "Holding dollars is a decision and needs a reason, the same as any other");
            }
        }

        @Override
        public String describe() {
            return "HOLD (" + because + ")";
        }
    }
}

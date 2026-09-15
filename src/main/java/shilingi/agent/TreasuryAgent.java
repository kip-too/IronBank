package shilingi.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import shilingi.clock.ClockPort;
import shilingi.intent.Intent;
import shilingi.intent.IntentLog;
import shilingi.intent.Snapshot;
import shilingi.ledger.AccountCodes;
import shilingi.instruction.InstructionRepository;
import shilingi.ledger.Balances;
import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.obligations.Obligation;
import shilingi.obligations.ObligationRepository;
import shilingi.rate.RatePort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Decides how many dollars to convert, and writes down why before anything acts on it.
 *
 * <p>SPEC.md section 11's cycle, in order:
 * <ol>
 *   <li>read the obligation calendar for the planning horizon</li>
 *   <li>read current balances, in both currencies</li>
 *   <li>read the current mid-market rate</li>
 *   <li>check I8 - is any obligation inside the horizon unfunded?</li>
 *   <li>decide</li>
 *   <li><b>write the intent, with reasoning and a snapshot of everything read in 1 to 3, and
 *       commit it</b></li>
 *   <li>only then create the instruction</li>
 * </ol>
 *
 * <p>Step 7 is not here. This class ends at step 6, returning a committed {@link Decision};
 * {@link TreasuryService} is what turns an approved one into an instruction. The ordering is not
 * a convention either way - {@code instruction.intent_id} is a {@code NOT NULL} foreign key, so
 * an instruction cannot exist until the intent is committed.
 *
 * <h2>The guard runs before the intent is written, not after</h2>
 * Steps 4 and 5 come before step 6, so the check happens in memory with nothing written yet, and
 * <b>the intent then records the refusal as well as the proposal.</b> Writing the intent first
 * and checking afterwards would produce the same refusal but lose the most interesting line in
 * the log - the one where the system said no and why.
 *
 * <h2>Anything may propose; only Java may permit</h2>
 * {@link #decideOn} takes a proposal and reasoning from outside - a language model, a person, a
 * test - and puts it through exactly the same {@link TreasuryInvariants} as the dull rule. There
 * is no path that skips the guard, and the guard cannot see the reasoning.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No rate-aware timing.</b> SPEC.md section 11 makes it a stretch goal, "and only once
 *       the boring version is proven".</li>
 *   <li><b>Nothing marks obligations funded.</b> An obligation becomes FUNDED when money is
 *       allocated to it, and which obligation gets which shillings is SPEC.md section 17
 *       scenario 16's question, unanswered anywhere. So the funding gap stays open in the records
 *       until somebody closes it.</li>
 *   <li><b>No scheduling.</b> Nothing calls this on a timer; a cycle happens when something asks
 *       for one. SPEC.md describes a decision cycle, not a daemon.</li>
 *   <li><b>No language model.</b> SPEC.md section 2 does not list one and SPEC.md section 1 rule
 *       6 requires asking before adding a dependency. {@link #decideOn} is the seam where one
 *       would attach, and the demo's refusal moment works through it without one.</li>
 * </ul>
 */
@Service
public class TreasuryAgent {

    private final ClockPort clock;
    private final ObligationRepository obligations;
    private final Balances balances;
    private final RatePort rates;
    private final InstructionRepository instructions;
    private final IntentLog intents;
    private final TreasuryInvariants invariants;
    private final int horizonDays;
    private final BigDecimal buffer;

    public TreasuryAgent(ClockPort clock,
                         ObligationRepository obligations,
                         Balances balances,
                         RatePort rates,
                         InstructionRepository instructions,
                         IntentLog intents,
                         TreasuryInvariants invariants,
                         @Value("${shilingi.agent.horizon-days}") int horizonDays,
                         @Value("${shilingi.agent.buffer}") BigDecimal buffer) {
        this.clock = clock;
        this.obligations = obligations;
        this.balances = balances;
        this.rates = rates;
        this.instructions = instructions;
        this.intents = intents;
        this.invariants = invariants;
        this.horizonDays = horizonDays;
        this.buffer = buffer;
    }

    /** Steps 1 to 3: everything the decision will rest on, read once. */
    public TreasuryPosition readPosition() {
        LocalDate today = clock.businessDate();
        LocalDate horizonEnd = today.plusDays(horizonDays);

        List<Obligation> unfunded = obligations.unfundedDueBy(horizonEnd);
        Money shillings = balances.balanceOf(AccountCodes.BANK_KES, Currency.KES);
        Money dollars = balances.balanceOf(AccountCodes.WALLET_USDC, Currency.USDC);
        Optional<shilingi.money.Rate> mid = rates.midRateOn(today);
        Money inFlight = instructions.conversionsInFlight();

        return new TreasuryPosition(today, horizonEnd, unfunded, shillings, dollars, mid, inFlight);
    }

    /** One full cycle using SPEC.md section 11's dull rule. */
    public Decision runCycle(String trigger) {
        TreasuryPosition position = readPosition();
        FundingPlan plan = FundingPlan.from(position, buffer);

        ProposedAction action = dullRule(position, plan);
        String reasoning = explain(position, plan, action);

        return judgeAndRecord(trigger, position, plan, action, reasoning);
    }

    /**
     * One cycle on a proposal that came from somewhere else, with its own reasoning.
     *
     * <p>The seam SPEC.md section 11 describes: a language model may write the reasoning and
     * propose the action, and the invariants still decide.
     */
    public Decision decideOn(String trigger, ProposedAction proposal, String reasoning) {
        TreasuryPosition position = readPosition();
        FundingPlan plan = FundingPlan.from(position, buffer);

        return judgeAndRecord(trigger, position, plan, proposal, reasoning);
    }

    private Decision judgeAndRecord(String trigger, TreasuryPosition position, FundingPlan plan,
                                    ProposedAction action, String reasoning) {
        // Step 4 and 5, before anything is written.
        Optional<InvariantViolation> violation = invariants.check(action, plan);

        // Step 6: write it down, including the refusal, and commit.
        Intent intent = intents.append(Intent.proposing(
                clock.instant(),
                trigger,
                reasoning,
                snapshotOf(position, plan),
                proposedActionOf(action, violation)));

        return new Decision(intent, plan, action, violation);
    }

    /** SPEC.md section 11's first version of the decision rule. Deliberately boring. */
    private ProposedAction dullRule(TreasuryPosition position, FundingPlan plan) {
        if (position.midRate().isEmpty()) {
            // SPEC.md section 17 scenario 7. There is no rule in SPEC.md for a missing rate, and
            // carrying yesterday's forward would put a stale figure in the books looking exactly
            // like a fresh one (ADR-015). So the agent proposes nothing and says why - and if an
            // obligation is unfunded, I8 will refuse this hold, which is the correct outcome:
            // the decision is recorded, no instruction is created, and it is visible as a problem.
            return new ProposedAction.Hold("no mid-market rate is published for " + position.today());
        }

        if (!plan.conversionIsRequired()) {
            return new ProposedAction.Hold(plan.hasShortfall()
                    ? "there is a shortfall but the wallet holds no dollars to convert"
                    : "shillings already cover everything due inside the horizon");
        }

        return new ProposedAction.Convert(plan.mustConvert());
    }

    private String explain(TreasuryPosition position, FundingPlan plan, ProposedAction action) {
        StringBuilder reasoning = new StringBuilder();
        reasoning.append("Planning horizon ").append(position.today())
                .append(" to ").append(position.horizonEnd()).append(". ");
        reasoning.append(position.unfundedShillingObligations().size())
                .append(" unfunded shilling obligation(s) totalling ").append(plan.needed())
                .append("; with the buffer that is ").append(plan.cover())
                .append(" against a shilling balance of ").append(plan.have()).append(". ");

        if (!position.conversionsInFlight().isZero()) {
            reasoning.append(position.conversionsInFlight())
                    .append(" is already in flight, covering ").append(plan.inFlightCover())
                    .append(" of the gap. ");
        }
        if (!plan.reserved().isZero()) {
            reasoning.append(plan.reserved())
                    .append(" is reserved for obligations denominated in dollars. ");
        }

        if (plan.hasShortfall()) {
            reasoning.append("Short by ").append(plan.shortfall()).append(". ");
            plan.requiredDollars().ifPresent(required ->
                    reasoning.append("That is ").append(required)
                            .append(" at the mid rate, rounded up to whole dollars; the wallet holds ")
                            .append(position.foreignBalance()).append(". "));
        } else {
            reasoning.append("No shortfall. ");
        }

        if (!position.unfundedForeignObligations().isEmpty()) {
            reasoning.append("NOTE: ").append(position.unfundedForeignObligations().size())
                    .append(" unfunded obligation(s) are denominated in foreign currency. Their "
                            + "dollars are reserved, but nothing here pays them. ");
        }

        reasoning.append("Proposing ").append(action.describe()).append(".");
        return reasoning.toString();
    }

    /** SPEC.md section 6: a copy of everything read in steps 1 to 3, not a reference to it. */
    private String snapshotOf(TreasuryPosition position, FundingPlan plan) {
        return Snapshot.of()
                .with("today", position.today().toString())
                .with("horizonEnd", position.horizonEnd().toString())
                .with("horizonDays", horizonDays)
                .with("buffer", buffer.toPlainString())
                .with("shillingBalanceMinor", position.shillingBalance().minorUnits())
                .with("foreignBalanceMinor", position.foreignBalance().minorUnits())
                .with("midRate", position.midRate().map(r -> r.value().toPlainString()).orElse(null))
                .with("midRateSource", position.midRate().map(shilingi.money.Rate::source).orElse(null))
                .with("neededMinor", plan.needed().minorUnits())
                .with("coverMinor", plan.cover().minorUnits())
                .with("shortfallMinor", plan.shortfall().minorUnits())
                .with("requiredDollarsMinor", plan.requiredDollars().map(Money::minorUnits).orElse(null))
                .with("mustConvertMinor", plan.mustConvert().minorUnits())
                .with("inFlightDollarsMinor", position.conversionsInFlight().minorUnits())
                .with("inFlightCoverMinor", plan.inFlightCover().minorUnits())
                .with("reservedForForeignObligationsMinor", plan.reserved().minorUnits())
                .with("availableToConvertMinor", plan.held().minorUnits())
                .with("unfundedObligations", position.unfunded().stream()
                        .map(o -> java.util.Map.<String, Object>of(
                                "id", o.id(),
                                "name", o.name(),
                                "amountMinor", o.amount().minorUnits(),
                                "currency", o.amount().currency().name(),
                                "dueDate", o.dueDate().toString(),
                                "status", o.status().name()))
                        .toList())
                .toJson();
    }

    private String proposedActionOf(ProposedAction action, Optional<InvariantViolation> violation) {
        Snapshot snapshot = Snapshot.of().with("action", action.describe());

        if (action instanceof ProposedAction.Convert convert) {
            snapshot.with("convertMinor", convert.amount().minorUnits())
                    .with("convertCurrency", convert.amount().currency().name());
        }

        snapshot.with("permitted", violation.isEmpty());
        violation.ifPresent(v -> snapshot
                .with("refusedBy", v.invariant())
                .with("refusal", v.getMessage()));

        return snapshot.toJson();
    }
}

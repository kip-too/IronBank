package shilingi.agent;

import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.money.Rate;
import shilingi.obligations.Obligation;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the agent read in SPEC.md section 11 steps 1 to 3, as one value.
 *
 * <p>This is what gets copied into the intent's snapshot. It is a value, taken once and not
 * re-read: the agent decides against this, records this, and is judged against this. If it held
 * references to live repositories instead, the log would say what is true now rather than what
 * was true when the decision was made, and PROBLEM.md F6 would be back.
 *
 * @param today            from the injected clock
 * @param horizonEnd       the far edge of the planning horizon
 * @param unfunded         SCHEDULED obligations due on or before {@code horizonEnd}, any currency
 * @param shillingBalance  account 1000
 * @param foreignBalance   account 1100, in dollars
 * @param midRate          the mid-market rate for today, if the feed has one. See ADR-015.
 * @param conversionsInFlight dollars already committed to conversions that have not finished.
 *                         Those dollars are spoken for and the shillings they will produce are
 *                         already on their way - see ADR-022.
 */
public record TreasuryPosition(
        LocalDate today,
        LocalDate horizonEnd,
        List<Obligation> unfunded,
        Money shillingBalance,
        Money foreignBalance,
        Optional<Rate> midRate,
        Money conversionsInFlight) {

    public TreasuryPosition {
        Objects.requireNonNull(today, "today is required, from the injected clock");
        Objects.requireNonNull(horizonEnd, "horizonEnd is required");
        Objects.requireNonNull(shillingBalance, "shillingBalance is required");
        Objects.requireNonNull(foreignBalance, "foreignBalance is required");
        Objects.requireNonNull(midRate, "midRate is required (as an Optional, possibly empty)");
        Objects.requireNonNull(conversionsInFlight, "conversionsInFlight is required");
        unfunded = List.copyOf(Objects.requireNonNull(unfunded, "unfunded is required"));
    }

    /** The unfunded obligations SPEC.md section 11's rule can act on. */
    public List<Obligation> unfundedShillingObligations() {
        return unfunded.stream().filter(o -> o.amount().currency() == Currency.KES).toList();
    }

    /**
     * The unfunded obligations the v1 rule does NOT act on, because converting dollars into
     * shillings does not fund a dollar debt. Exposed so they are visible in the snapshot and on
     * the screen rather than silently dropped. See FundingPlan's header.
     */
    public List<Obligation> unfundedForeignObligations() {
        return unfunded.stream().filter(o -> o.amount().currency() != Currency.KES).toList();
    }

    /**
     * Dollars needed for obligations that are themselves denominated in dollars.
     *
     * <p>Converting these away would fund a shilling debt by making a dollar debt unpayable. They
     * are reserved rather than converted - see ADR-022. This is not the same as acting on them:
     * nothing here pays a dollar obligation, and that gap remains.
     */
    public Money foreignObligationsReserve() {
        Money total = Money.zero(Currency.USDC);
        for (Obligation o : unfundedForeignObligations()) {
            if (o.amount().currency() == Currency.USDC) {
                total = total.plus(o.amount());
            }
        }
        return total;
    }

    public Money unfundedShillingObligationsTotal() {
        Money total = Money.zero(Currency.KES);
        for (Obligation o : unfundedShillingObligations()) {
            total = total.plus(o.amount());
        }
        return total;
    }

    /** Invariant I8's question, asked plainly: is anything inside the horizon unfunded? */
    public boolean anythingUnfundedInHorizon() {
        return !unfunded.isEmpty();
    }
}

package shilingi.obligations;

import shilingi.money.Money;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Something the business must pay, on a date, in an amount (PROBLEM.md section 4). Payroll, rent,
 * a supplier invoice, statutory.
 *
 * <p>PROBLEM.md section 1: these arrive on a calendar you cannot argue with. That is the whole
 * reason the agent exists - income speaks one language and arrives whenever it feels like it,
 * and this column does not.
 *
 * <h2>OVERDUE is worked out, not remembered</h2>
 * {@link #stateOn(LocalDate)} is the only place SPEC.md section 7's fourth state exists. There is
 * no column for it, no sweeper that sets it, and nothing to forget to run - an obligation is
 * overdue the instant its date passes, and it says so the moment anybody asks. See ADR-017.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No partial funding and no partial payment.</b> SPEC.md section 7 has three states and
 *       no fractions. SPEC.md section 17 scenario 16 - "two obligations due the same day, funds
 *       enough for one and a half" - is a question about what the AGENT does with that, not
 *       about splitting an obligation, and it lands on day 8.</li>
 *   <li><b>No link to the funds that funded it, or the payment that paid it.</b> SPEC.md
 *       section 6 gives an obligation five fields and none of them is a reference. The
 *       reconciler (day 13) is what ties money to obligations, through source references.</li>
 *   <li><b>No recurrence.</b> Payroll every month is several obligations, not one repeating one.
 *       Nothing in SPEC.md asks for a schedule.</li>
 *   <li>No history of when it changed state. The journal is where history lives.</li>
 * </ul>
 *
 * @param id       database identity, null until saved
 * @param name     what it is, e.g. "Payroll September"
 * @param amount   what is owed, in its own currency
 * @param dueDate  the day it must be paid
 * @param status   the stored state. Never OVERDUE - see above.
 */
public record Obligation(Long id, String name, Money amount, LocalDate dueDate, ObligationStatus status) {

    public Obligation {
        Objects.requireNonNull(name, "An obligation needs a name");
        Objects.requireNonNull(amount, "An obligation needs an amount");
        Objects.requireNonNull(dueDate, "An obligation needs a due date");
        Objects.requireNonNull(status, "An obligation needs a status");

        if (name.isBlank()) {
            throw new IllegalArgumentException("An obligation needs a name, and a blank one is not a name");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "An obligation must be for a positive amount, but was " + amount);
        }
    }

    /**
     * A new obligation, not yet funded.
     *
     * <p>A due date in the past is accepted. SPEC.md section 17 scenario 13 asks what happens
     * then, and the answer is that it is immediately {@link ObligationState#OVERDUE} and says so.
     * Refusing to record it would be refusing to record something that is true - and hiding a
     * real overdue debt is a worse outcome than having one. See ADR-017.
     */
    public static Obligation scheduled(String name, Money amount, LocalDate dueDate) {
        return new Obligation(null, name, amount, dueDate, ObligationStatus.SCHEDULED);
    }

    /**
     * SPEC.md section 7's machine as observed on a given day.
     *
     * <p>The day must come from the injected clock. Nothing here calls {@code LocalDate.now()},
     * which is what makes overdue behaviour testable by moving the clock rather than by waiting.
     */
    public ObligationState stateOn(LocalDate today) {
        Objects.requireNonNull(today, "today is required, and must come from the injected clock");

        if (status == ObligationStatus.SCHEDULED && dueDate.isBefore(today)) {
            return ObligationState.OVERDUE;
        }
        return switch (status) {
            case SCHEDULED -> ObligationState.SCHEDULED;
            case FUNDED -> ObligationState.FUNDED;
            case PAID -> ObligationState.PAID;
        };
    }

    /**
     * Whether this is the planning failure SPEC.md section 7 describes: past its date with
     * nothing set aside.
     *
     * <p>Due <i>today</i> and unfunded is not overdue - it is urgent, and invariant I8 is what
     * catches it, on day 8.
     */
    public boolean isOverdueOn(LocalDate today) {
        return stateOn(today) == ObligationState.OVERDUE;
    }

    /** Whether funds have been set aside. What invariant I8 is ultimately asking about. */
    public boolean isFunded() {
        return status == ObligationStatus.FUNDED || status == ObligationStatus.PAID;
    }

    public Obligation withStatus(ObligationStatus newStatus) {
        return new Obligation(id, name, amount, dueDate, newStatus);
    }
}

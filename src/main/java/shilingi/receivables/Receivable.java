package shilingi.receivables;

import shilingi.money.Money;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Something the business is owed: a client invoice (PROBLEM.md section 4).
 *
 * <p>PROBLEM.md section 1: irregular timing, irregular amount, one to six clients. The invoice is
 * raised on one day and paid on another, and PROBLEM.md section 5 is emphatic that the gap
 * between those two days is where exchange difference comes from - not from anything the
 * business sold.
 *
 * <h2>There is no due date, and so no overdue</h2>
 * SPEC.md section 6 gives a receivable {@code counterparty, currency, amount, issue_date,
 * status}. No due date. This system therefore cannot say a receivable is late, and does not
 * pretend to. That is a gap in the specification rather than an omission here, and adding a due
 * date would be inventing a payment term nobody stated. See ADR-017.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No partial settlement.</b> Two states, no fractions. A client paying half an invoice
 *       is real and is not modelled; today that money would reach the reconciler as an amount
 *       that does not match, which SPEC.md section 13 says is "an exception, always, regardless
 *       of size". That is a defensible place for it to land and a poor place for it to stay.</li>
 *   <li><b>No link to the journal entry that raised it, or the receipt that settled it.</b>
 *       SPEC.md section 6 gives it five fields and none is a reference. Matching is the
 *       reconciler's job, on day 13, through source references.</li>
 *   <li><b>Nothing here posts anything.</b> PROBLEM.md section 5 day 1 shows Dr Receivable /
 *       Cr Revenue, and building that means deciding which rate a receivable is raised at.
 *       PROBLEM.md says "rate that day" and the mid rate table has it, but SPEC.md states no
 *       rule, so it is not built here. Flagged, not guessed.</li>
 * </ul>
 *
 * @param id           database identity, null until saved
 * @param counterparty who owes it
 * @param amount       what is owed, in its own currency
 * @param issueDate    the day the invoice was raised - the day whose rate the books used
 * @param status       outstanding or settled
 */
public record Receivable(Long id, String counterparty, Money amount, LocalDate issueDate,
                         ReceivableStatus status) {

    public Receivable {
        Objects.requireNonNull(counterparty, "A receivable needs a counterparty");
        Objects.requireNonNull(amount, "A receivable needs an amount");
        Objects.requireNonNull(issueDate, "A receivable needs an issue date");
        Objects.requireNonNull(status, "A receivable needs a status");

        if (counterparty.isBlank()) {
            throw new IllegalArgumentException(
                    "A receivable needs a counterparty, and a blank one is not a counterparty");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "A receivable must be for a positive amount, but was " + amount);
        }
    }

    public static Receivable raised(String counterparty, Money amount, LocalDate issueDate) {
        return new Receivable(null, counterparty, amount, issueDate, ReceivableStatus.OUTSTANDING);
    }

    public boolean isOutstanding() {
        return status == ReceivableStatus.OUTSTANDING;
    }

    /** How long this has been outstanding, in days, as at a date from the injected clock. */
    public long ageInDaysOn(LocalDate today) {
        Objects.requireNonNull(today, "today is required, and must come from the injected clock");
        return java.time.temporal.ChronoUnit.DAYS.between(issueDate, today);
    }

    public Receivable withStatus(ReceivableStatus newStatus) {
        return new Receivable(id, counterparty, amount, issueDate, newStatus);
    }
}

package shilingi.receivables;

/**
 * SPEC.md section 6 gives a receivable a status; SPEC.md section 7 gives it no state machine.
 *
 * <p>These two states are the minimum the documents evidence: PROBLEM.md section 5 raises an
 * invoice on day 1 and settles it on day 12. Nothing more is invented - in particular there is
 * no OVERDUE, because SPEC.md section 6 gives a receivable an {@code issue_date} and no due
 * date, so lateness is not expressible. See ADR-017.
 */
public enum ReceivableStatus {

    /** Raised, and the money has not arrived. */
    OUTSTANDING,

    /** The money arrived and was matched to it. Terminal. */
    SETTLED
}

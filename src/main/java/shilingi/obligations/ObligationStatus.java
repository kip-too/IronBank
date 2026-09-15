package shilingi.obligations;

/**
 * The stored half of SPEC.md section 7's obligation machine.
 *
 * <pre>
 *   SCHEDULED -> FUNDED -> PAID
 * </pre>
 *
 * <p><b>OVERDUE is not here</b>, and the database will not store it either. It is derived - see
 * {@link ObligationState} and ADR-017.
 */
public enum ObligationStatus {

    /** Known about, dated, and nothing set aside for it yet. */
    SCHEDULED,

    /** Funds allocated. Invariant I8 is about getting every near obligation into this state. */
    FUNDED,

    /** Money has gone out. Terminal. */
    PAID
}

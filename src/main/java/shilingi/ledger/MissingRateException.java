package shilingi.ledger;

import shilingi.money.Currency;

/**
 * Invariant I1: no non-KES posting exists without a rate, a rate source and a rate timestamp.
 *
 * <p>PROBLEM.md F1 is rate amnesia - "a foreign amount is recorded in local money with no record
 * of the rate used, its source, or its timestamp", so no figure can be re-derived and therefore
 * no figure can be checked. A rate without a timestamp and a source is not a rate, it is a
 * rumour (PROBLEM.md section 4).
 *
 * <p>Stays a top-level class rather than joining the nested family in
 * {@link LedgerRejection}, because it is thrown by {@link Posting}'s own constructor as
 * well as by the ledger - I1 is enforced at three layers.
 *
 * <p>The database constraint {@code posting_foreign_currency_needs_rate_source_and_timestamp}
 * is the actual mechanism. This exception is the early, legible failure sitting on top of it.
 */
public class MissingRateException extends LedgerRejection {

    private final String accountCode;
    private final Currency currency;

    public MissingRateException(String accountCode, Currency currency) {
        super("Posting to account " + accountCode + " is in " + currency
              + ", so it requires a rate, a rate source and a rate timestamp. "
              + "A rate without a timestamp and a source is not a rate, it is a rumour "
              + "(PROBLEM.md section 4).");
        this.accountCode = accountCode;
        this.currency = currency;
    }

    public String accountCode() {
        return accountCode;
    }

    public Currency currency() {
        return currency;
    }
}

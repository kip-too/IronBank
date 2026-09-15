package shilingi.ledger;

import shilingi.money.Currency;

import java.time.LocalDate;

/**
 * The ledger's job is to refuse things (SPEC.md section 8). This is what a refusal is.
 *
 * <p>SPEC.md section 8 requires <b>a distinct exception per case</b>, so that a caller - and a
 * reviewer reading a log in March - can tell which rule was broken without parsing a message.
 * Each nested class below is one rule.
 *
 * <p>A rejection means <b>nothing was written</b>. {@code LedgerService.post} runs in one
 * database transaction and every check happens before any insert, so a rejected entry leaves no
 * trace in the journal at all.
 *
 * <h2>Why these are nested classes</h2>
 * Seven rules, seven exceptions, one file. Nested keeps them readable as a set - the rules are
 * a family and are best read together - and reads well at the throw site and in a catch:
 * {@code LedgerRejection.UnbalancedInShillings}.
 *
 * <p>{@link MissingRateException} is the exception to that, and stays a top-level class: it is
 * thrown by {@link Posting}'s own constructor as well as by the ledger, because I1 is enforced
 * at three layers - the record, the ledger, and a database check constraint.
 */
public abstract class LedgerRejection extends RuntimeException {

    protected LedgerRejection(String message) {
        super(message);
    }

    /**
     * SPEC.md section 8: "the entry has fewer than two postings".
     *
     * <p>One line is not an entry. Double-entry means something was given and something was
     * received, and a single posting records only half of a claim about the world.
     */
    public static final class TooFewPostings extends LedgerRejection {

        private final int count;

        public TooFewPostings(int count) {
            super("A journal entry needs at least two postings, but this one has " + count
                  + ". One line records only half of what happened.");
            this.count = count;
        }

        public int count() {
            return count;
        }
    }

    /**
     * SPEC.md section 8: "the business date is in the future relative to the injected clock".
     *
     * <p>Note "the injected clock" - SPEC.md section 4 means this is checked against the clock
     * the application was given, never against {@code LocalDate.now()}.
     */
    public static final class FutureBusinessDate extends LedgerRejection {

        private final LocalDate businessDate;
        private final LocalDate today;

        public FutureBusinessDate(LocalDate businessDate, LocalDate today) {
            super("Business date " + businessDate + " is in the future; today is " + today
                  + ". The books do not record things that have not happened yet.");
            this.businessDate = businessDate;
            this.today = today;
        }

        public LocalDate businessDate() {
            return businessDate;
        }

        public LocalDate today() {
            return today;
        }
    }

    /** SPEC.md section 8: "any posting references an account that does not exist". */
    public static final class UnknownAccount extends LedgerRejection {

        private final String accountCode;

        public UnknownAccount(String accountCode) {
            super("No account " + accountCode + " exists in the chart. The chart is seeded by "
                  + "migration and cannot be added to at runtime - an account that can be created "
                  + "on demand is an account that can be invented to make a total agree (I3).");
            this.accountCode = accountCode;
        }

        public String accountCode() {
            return accountCode;
        }
    }

    /**
     * Invariant I5: realised (6100) and unrealised (6110) exchange difference never share an
     * entry. SPEC.md section 9 places this check in the ledger, at post time.
     *
     * <p>PROBLEM.md section 5: one is history and the other is an opinion about today. An entry
     * that touched both would be claiming they are the same kind of fact.
     */
    public static final class RealisedAndUnrealisedTogether extends LedgerRejection {

        public RealisedAndUnrealisedTogether() {
            super("Accounts " + AccountCodes.EXCHANGE_DIFFERENCE_REALISED + " and "
                  + AccountCodes.EXCHANGE_DIFFERENCE_UNREALISED + " appear in the same entry. "
                  + "Realised difference is history; unrealised difference is an opinion about "
                  + "today. Invariant I5 keeps them apart.");
        }
    }

    /**
     * Invariant I2, in the form that is actually true of this specification's own worked
     * example: an entry whose postings are all in one currency must balance in that currency.
     *
     * <p><b>This is deliberately not checked for cross-currency entries.</b> See ADR-013 - not
     * one of the four entries in PROBLEM.md section 5 balances per currency, and the shilling
     * balance is what holds universally.
     */
    public static final class UnbalancedInCurrency extends LedgerRejection {

        private final Currency currency;
        private final long totalMinorUnits;

        public UnbalancedInCurrency(Currency currency, long totalMinorUnits) {
            super("Every posting in this entry is in " + currency + ", so it must balance in "
                  + currency + ", but the postings sum to " + totalMinorUnits
                  + " minor units instead of zero. Nothing will be added to make it agree (I3).");
            this.currency = currency;
            this.totalMinorUnits = totalMinorUnits;
        }

        public Currency currency() {
            return currency;
        }

        public long totalMinorUnits() {
            return totalMinorUnits;
        }
    }

    /**
     * Invariant I2: "postings do not sum to zero in shillings". The universal balance rule -
     * true of every entry in PROBLEM.md section 5 and of every entry this system will ever make.
     */
    public static final class UnbalancedInShillings extends LedgerRejection {

        private final long totalMinorUnits;

        public UnbalancedInShillings(long totalMinorUnits) {
            super("This entry does not balance in shillings: the postings sum to " + totalMinorUnits
                  + " cents instead of zero. A total that does not agree is a finding, not "
                  + "something to adjust (SPEC.md section 1 rule 3).");
            this.totalMinorUnits = totalMinorUnits;
        }

        public long totalMinorUnits() {
            return totalMinorUnits;
        }
    }
}

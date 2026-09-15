package shilingi.demo;

import shilingi.money.Currency;
import shilingi.money.Money;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The spreadsheet, modelled exactly as ADR-023 (open item O8) specifies it.
 *
 * <pre>
 *   1. USDC arrives.  Record nothing - it is not shillings yet.
 *   2. Convert.       The bank credits a shilling amount.
 *   3. Write that shilling amount in the sheet, dated today, against the client.
 *   4. Month end.     Sum the column. Call it revenue.
 * </pre>
 *
 * <h2>This is not a straw man, and nothing here is rigged to lose</h2>
 * SPEC.md section 19: "A demo that beats a deliberately stupid alternative proves nothing, and an
 * audience can smell it." So:
 *
 * <ul>
 *   <li><b>Its arithmetic is correct.</b> {@link #total()} is the exact sum of its rows. No test
 *       asserts it is wrong, because it is not.</li>
 *   <li><b>It is given the same inputs</b>, the same clock and the same conversion outcomes as the
 *       real system. It is not fed worse data.</li>
 *   <li><b>Its method is documented in PROBLEM.md section 2</b> as what actually happens today.
 *       It was not invented here in order to be beaten.</li>
 * </ul>
 *
 * <p>What it cannot do is answer a question. It never fails to balance, never needs a rate table,
 * and produces a number every month - and that number carries no information about where it came
 * from. That is the whole comparison, and it is a fair one.
 *
 * <h2>What it deliberately does not record</h2>
 * <ul>
 *   <li><b>The invoice.</b> Nothing is written when work is billed - only when money lands.</li>
 *   <li><b>The dollars received.</b> Step 1: "it is not money yet."</li>
 *   <li><b>The rate.</b> The bank credited shillings; it did not say at what rate (PROBLEM.md F1).</li>
 *   <li><b>The fee or the spread.</b> Nobody sent an invoice for either (PROBLEM.md F2).</li>
 *   <li><b>The 4,000 dollars still held.</b> They have not become shillings, so they are not in
 *       the sheet at all.</li>
 * </ul>
 */
public class NaiveSheet {

    /** One line of the spreadsheet: a date, a name, and a shilling amount. Nothing else. */
    public record Row(LocalDate on, String against, Money shillings, String note) {
    }

    private final List<Row> rows = new ArrayList<>();

    /**
     * Step 3. The only thing that ever gets written down.
     *
     * @param shillings what the bank said arrived. Not what was converted, not at what rate -
     *                  the bank credited a number and this is that number.
     */
    public void writeDown(LocalDate on, String against, Money shillings, String note) {
        if (shillings.currency() != Currency.KES) {
            throw new IllegalArgumentException(
                    "The sheet only ever holds shillings - that is the whole of its method");
        }
        rows.add(new Row(on, against, shillings, note));
    }

    public List<Row> rows() {
        return List.copyOf(rows);
    }

    /** Step 4. Sum the column. Correct arithmetic, every time. */
    public Money total() {
        Money total = Money.zero(Currency.KES);
        for (Row row : rows) {
            total = total.plus(row.shillings());
        }
        return total;
    }

    /**
     * What the sheet calls this figure at month end.
     *
     * <p>The label is where the damage is. The arithmetic is right; calling cash received
     * "revenue" conflates three different things - what was earned, what the market did, and what
     * the provider took - into one number, and PROBLEM.md section 5 calls booking exchange
     * difference as revenue "the single most common error in a small set of books".
     */
    public String labelForTotal() {
        return "Revenue";
    }

    /**
     * The four questions from ADR-023, answered as far as this method can answer them.
     *
     * <p>Three of the four come back as "cannot say" - not because the sheet is careless, but
     * because the facts needed to answer were discarded before anything was written down. They are
     * not recoverable from these records by anyone, at any later date, however carefully they look.
     */
    public String answerTo(Question question) {
        return switch (question) {
            case WHAT_WAS_EARNED -> total().toString();
            case HOW_MUCH_WAS_THE_DOLLAR_MOVING -> null;
            case WHAT_DID_THE_PROVIDER_CHARGE -> null;
            case CAN_THE_DAY_BE_REPRODUCED -> null;
        };
    }

    public enum Question {
        WHAT_WAS_EARNED("What did the business earn in September?"),
        HOW_MUCH_WAS_THE_DOLLAR_MOVING("How much of that is the dollar moving, not trading?"),
        WHAT_DID_THE_PROVIDER_CHARGE("What did the provider charge, openly and quietly?"),
        CAN_THE_DAY_BE_REPRODUCED("Reproduce the 20th from the records alone.");

        private final String asked;

        Question(String asked) {
            this.asked = asked;
        }

        public String asked() {
            return asked;
        }
    }
}

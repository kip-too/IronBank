package shilingi.demo;

import shilingi.money.Money;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Money, formatted for a person to read.
 *
 * <p>{@code Money.toString()} renders {@code KES 79320000 (793200.00)} - the minor units and the
 * shifted decimal - and its own header says why: "for logs and test failure messages, not for a
 * screen". This is the screen, so the screen does its own formatting.
 *
 * <p>SPEC.md section 5 grants exactly this: "The presentation layer may show them however it
 * likes." That sentence is about signs, and the same principle covers grouping and symbols. What
 * the presentation layer may never do is compute, so nothing here does arithmetic - it reads a
 * stored figure and shifts the decimal point with {@link BigDecimal}, which is exact.
 */
final class Figures {

    private Figures() {
    }

    private static final DecimalFormat GROUPED =
            new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));

    private static final DecimalFormat GROUPED_DOLLARS =
            new DecimalFormat("#,##0.00####", DecimalFormatSymbols.getInstance(Locale.ROOT));

    /** e.g. {@code KES 793,200.00} or {@code USDC 4,000.00}. */
    static String of(Money money) {
        BigDecimal major = BigDecimal.valueOf(money.minorUnits(), money.currency().scale());
        return switch (money.currency()) {
            case KES -> "KES " + GROUPED.format(major);
            case USDC -> "USDC " + GROUPED_DOLLARS.format(major);
        };
    }

    /**
     * The same, with the sign flipped, for showing a credit balance as a positive figure.
     *
     * <p>Internally credits are negative (SPEC.md section 5). Revenue of 1,290,000 is stored as
     * {@code -129,000,000} cents, and showing a reader "Revenue: -1,290,000" would be technically
     * true and actively confusing. The flip happens here, in presentation, and nowhere else.
     */
    static String asPositive(Money money) {
        return of(money.negate());
    }
}

package shilingi.money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * An amount of money, held as a whole number of minor units in a 64-bit integer alongside
 * its currency. SPEC.md section 5: never a decimal type in the database, never a floating
 * point type anywhere.
 *
 * <p>KES 12,900.00 is {@code Money.of(1_290_000L, Currency.KES)}.
 * USDC 10,000.000000 is {@code Money.of(10_000_000_000L, Currency.USDC)}.
 *
 * <h2>Signs</h2>
 * SPEC.md section 5: debits are positive and credits are negative, internally. {@code Money}
 * itself holds either sign without complaint and has no opinion about which is which - that
 * is the ledger's business, not this type's.
 *
 * <h2>Why float construction throws rather than failing to compile</h2>
 * {@code new Money(1.5, KES)} does not compile, because narrowing double to long is not
 * implicit in Java. That is the stronger guard, but a compile error cannot be proven by a
 * test. So {@link #of(double, Currency)} and {@link #of(float, Currency)} exist purely to
 * turn a silent-looking mistake into a loud one that {@code MoneyTest} can demonstrate.
 * They are deprecated so an IDE strikes them through at the call site.
 *
 * <h2>Overflow</h2>
 * Arithmetic uses {@code Math.*Exact}, so overflow throws {@link ArithmeticException} rather
 * than wrapping around. A silently wrapped total is a wrong total that balances, which is the
 * shape of failure this project exists to prevent.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No conversion between currencies.</b> That needs a rate and lands on day 4
 *       (SPEC.md section 15). The multiply-then-round-once rule is not implemented here.</li>
 *   <li><b>No multiplication, and no division other than {@link #split(int)}.</b> Multiplying
 *       money by a rate belongs to {@link Rate#toShillings(Money)}, which is the one place the
 *       round-once rule is applied. Keeping multiplication off this type is what stops a
 *       {@code BigDecimal} and a {@code Money} meeting anywhere else.</li>
 *   <li><b>No parsing from a decimal string.</b> Nothing needs it yet; adding it would invite
 *       a floating point round trip through the back door.</li>
 *   <li>No currency-aware formatting for presentation. {@link #toString()} is for humans
 *       reading test failures and logs, not for a screen.</li>
 * </ul>
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    public Money {
        if (currency == null) {
            throw new IllegalArgumentException("Money requires a currency; an amount without one is not money");
        }
    }

    /** An amount, given as a whole number of this currency's minor units. */
    public static Money of(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    /** Zero in the given currency. Zero still has a currency. */
    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    /**
     * Always throws. Exists so that a floating point amount fails loudly and provably,
     * rather than being quietly truncated or rounded somewhere nobody is looking.
     *
     * @deprecated there is no correct way to build money from a double. Use
     *             {@link #of(long, Currency)} with minor units.
     */
    @Deprecated
    public static Money of(double minorUnits, Currency currency) {
        throw new UnsupportedOperationException(
                "Money cannot be built from a floating point value (got double " + minorUnits + "). "
                + "SPEC.md section 5: no floating point for money, anywhere. "
                + "Pass whole minor units as a long instead.");
    }

    /**
     * Always throws. See {@link #of(double, Currency)}.
     *
     * @deprecated there is no correct way to build money from a float.
     */
    @Deprecated
    public static Money of(float minorUnits, Currency currency) {
        throw new UnsupportedOperationException(
                "Money cannot be built from a floating point value (got float " + minorUnits + "). "
                + "SPEC.md section 5: no floating point for money, anywhere. "
                + "Pass whole minor units as a long instead.");
    }

    /** Sum of two amounts in the same currency. Throws on a currency mismatch or on overflow. */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(this.minorUnits, other.minorUnits), currency);
    }

    /** Difference of two amounts in the same currency. Throws on a currency mismatch or on overflow. */
    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(this.minorUnits, other.minorUnits), currency);
    }

    /**
     * Splits this amount into {@code parts} equal pieces, in the smallest unit the currency has.
     *
     * <h2>The residue rule, at the place the code does it</h2>
     * SPEC.md section 5: <i>"When an amount is split across several destinations and the parts
     * do not sum to the whole, the difference goes to the last part. Document the rule where the
     * code does it. Do not scatter the residue proportionally - that is a second rounding and it
     * reopens the problem."</i>
     *
     * <p>So: every part but the last is {@code minorUnits / parts}, truncated. The last part is
     * whatever is left, computed as the whole minus everything already allocated. The parts
     * always sum back to exactly this amount - that is arithmetic here, not an assertion.
     *
     * <p>SPEC.md section 17 scenario 17 is 1,000 shillings split three ways. In cents that is
     * 100,000, giving 33,333 + 33,333 + <b>33,334</b>. The stray cent is on the last part and
     * nowhere else.
     *
     * <p>Negative amounts split the same way, by magnitude: -100,000 gives
     * -33,333 + -33,333 + <b>-33,334</b>. Integer division in Java truncates towards zero, so
     * the residue keeps the sign of the whole and the parts stay symmetrical with the positive
     * case.
     *
     * <h2>What this does not do</h2>
     * <ul>
     *   <li><b>No weighted or proportional split.</b> Allocating an amount across destinations
     *       in unequal shares needs a rule for how each share is computed before the residue is
     *       placed, and SPEC.md gives none. Not guessed at.</li>
     * </ul>
     *
     * @param parts how many pieces. Must be at least one.
     */
    public List<Money> split(int parts) {
        if (parts < 1) {
            throw new IllegalArgumentException(
                    "An amount cannot be split into " + parts + " parts; at least one is needed");
        }

        long each = minorUnits / parts;
        long allocatedToAllButLast = Math.multiplyExact(each, parts - 1L);
        long last = Math.subtractExact(minorUnits, allocatedToAllButLast);

        List<Money> pieces = new ArrayList<>(parts);
        for (int i = 0; i < parts - 1; i++) {
            pieces.add(new Money(each, currency));
        }
        pieces.add(new Money(last, currency));
        return List.copyOf(pieces);
    }

    /** The same amount with the opposite sign. Turns a debit into a credit and back. */
    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    /**
     * Orders two amounts of the same currency. Throws {@link CurrencyMismatchException} on
     * different currencies: there is no defensible ordering between shillings and dollars
     * without a rate. This means {@code Money} is only consistently comparable within a
     * currency, which is a true statement about money rather than a limitation of this class.
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(this.minorUnits, other.minorUnits);
    }

    private void requireSameCurrency(Money other) {
        if (this.currency != other.currency) {
            throw new CurrencyMismatchException(this.currency, other.currency);
        }
    }

    /**
     * For logs and test failure messages, e.g. {@code KES 1290000 (12900.00)}. The bracketed
     * form is produced with {@link BigDecimal#valueOf(long, int)}, which shifts the decimal
     * point exactly and never goes near a floating point value. Display only - nothing in the
     * system parses this back.
     */
    @Override
    public String toString() {
        return currency + " " + minorUnits + " (" + BigDecimal.valueOf(minorUnits, currency.scale()).toPlainString() + ")";
    }
}

package shilingi.money;

/**
 * Thrown when two {@link Money} values of different currencies are combined or compared.
 *
 * <p>There is no implicit conversion anywhere in this system. Converting requires a rate,
 * and a rate carries a source and a timestamp (PROBLEM.md section 4). Silently converting
 * inside an arithmetic operator would produce exactly the failure F1 names: a local-currency
 * figure whose rate nobody can re-derive.
 */
public class CurrencyMismatchException extends RuntimeException {

    private final Currency left;
    private final Currency right;

    public CurrencyMismatchException(Currency left, Currency right) {
        super("Cannot combine " + left + " with " + right
              + ": there is no implicit conversion, a conversion needs a rate with a source and a timestamp");
        this.left = left;
        this.right = right;
    }

    public Currency left() {
        return left;
    }

    public Currency right() {
        return right;
    }
}

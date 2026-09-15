package shilingi.settlement;

/**
 * The sidecar answered, and the answer was no.
 *
 * <p>Distinct from {@link SettlementUnavailableException} on purpose: this one is an <b>answer</b>,
 * so the outcome is known. A 400 means the request was wrong and nothing was broadcast. The Java
 * side may treat that as a failure, because it is one.
 */
public class SettlementRefusedException extends RuntimeException {

    private final int statusCode;

    public SettlementRefusedException(int statusCode, String body) {
        super("The settlement sidecar refused with HTTP " + statusCode + ": " + body);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}

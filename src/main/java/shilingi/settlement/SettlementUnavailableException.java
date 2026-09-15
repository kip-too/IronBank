package shilingi.settlement;

import java.net.URI;

/**
 * The sidecar did not answer.
 *
 * <p><b>This is not a failure of the payment.</b> It is silence, and silence about a payment that
 * may already have been broadcast is exactly the condition AWAITING_RESOLUTION exists for.
 *
 * <p>A caller that catches this and marks an instruction FAILED has reintroduced F5: the next
 * cycle would see a known failure, retry it, and send the money twice. The correct response is to
 * time the instruction out into AWAITING_RESOLUTION and re-query, which is what the state machine
 * makes easy and the alternative impossible.
 */
public class SettlementUnavailableException extends RuntimeException {

    public SettlementUnavailableException(URI uri, Throwable cause) {
        super("No answer from the settlement sidecar at " + uri + ". This means the outcome is "
              + "UNKNOWN, not that the payment failed - do not retry on the strength of it "
              + "(PROBLEM.md F5, SPEC.md invariant I7).", cause);
    }
}

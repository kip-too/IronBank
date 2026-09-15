package shilingi.settlement;

import shilingi.money.Money;

import java.util.Objects;
import java.util.Optional;

/**
 * The dollar leg (SPEC.md section 12). Three endpoints: send a payment, check a payment's status,
 * get a balance.
 *
 * <p>The Java core never talks to a blockchain. It talks to a small local HTTP service that does,
 * and that service is deliberately thin - no business logic, no knowledge of accounting. This
 * interface is the seam. If the network changes, only the process behind it changes.
 *
 * <h2>Accepted is not settled, and unknown is not failed</h2>
 * Both of the distinctions this project turns on appear in the signatures rather than in
 * documentation somebody has to remember:
 * <ul>
 *   <li>{@link #send} returns an {@link Acceptance}, which carries a transaction hash and a
 *       status of accepted. A hash is not a receipt.</li>
 *   <li>{@link #status} returns an {@link Optional}. Empty means the rail has never heard of this
 *       reference - which is <b>not</b> the same as it having failed, and is precisely the input
 *       that sends an instruction to AWAITING_RESOLUTION rather than to FAILED.</li>
 * </ul>
 *
 * <h2>What this does not offer</h2>
 * There is no {@code resend}, no {@code retry} and no {@code cancel}. Re-querying is safe and
 * available; sending again is a decision the instruction state machine makes, where it is a
 * compile error in the wrong state.
 */
public interface SettlementPort {

    /**
     * Hands a dollar payment to the chain.
     *
     * @param externalRef our reference, carried through so a later query can find it
     * @param to          the receiving address
     * @param amount      dollars, in minor units
     */
    Acceptance send(String externalRef, String to, Money amount);

    /**
     * Asks what became of a payment. Sends nothing.
     *
     * @return empty when the rail has no record of this reference at all
     */
    Optional<Settlement> status(String externalRef);

    /** What the wallet holds, according to the chain. */
    Money balance();

    /** Proof the rail took it. Not proof it happened. */
    record Acceptance(String externalRef, String txHash, boolean simulated) {

        public Acceptance {
            Objects.requireNonNull(externalRef, "externalRef is required");
            Objects.requireNonNull(txHash, "txHash is required");
        }

        /**
         * True when this came from the sidecar's stub mode rather than a chain.
         *
         * <p>Carried all the way through on purpose. REAL_VS_SIMULATED.md has to be able to say
         * which hashes are real, and a flag that stops at the adapter boundary cannot answer that.
         */
        public boolean isSimulated() {
            return simulated;
        }
    }

    /** What the chain says about a payment. */
    record Settlement(String externalRef, SettlementStatus status, String txHash,
                      Optional<String> blockNumber, boolean simulated) {

        public Settlement {
            Objects.requireNonNull(externalRef, "externalRef is required");
            Objects.requireNonNull(status, "status is required");
            Objects.requireNonNull(blockNumber, "blockNumber is required (Optional, possibly empty)");
        }
    }

    enum SettlementStatus {
        /** Broadcast, no receipt yet. In flight. */
        PENDING,
        /** Mined, and the receipt says success. */
        SETTLED,
        /** Mined, and the receipt says it reverted. A known outcome. */
        FAILED
    }
}

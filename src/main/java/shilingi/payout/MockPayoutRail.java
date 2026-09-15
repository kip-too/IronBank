package shilingi.payout;

import org.springframework.stereotype.Component;

import shilingi.intent.Snapshot;
import shilingi.money.Money;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The shilling rail, mocked - but not mocked into being harmless.
 *
 * <p>SPEC.md section 12: <i>"It is not, however, a function that returns success."</i> It accepts
 * and returns "accepted", it delivers outcomes later, and it can be configured to succeed, fail,
 * time out, deliver the callback twice, deliver the callback out of order, and deliver a callback
 * for an instruction it was never given.
 *
 * <p><b>Those last three are the point.</b> SPEC.md section 12: "They are the failures that
 * produce F5, and a mock that cannot produce them cannot prove the system survives them."
 *
 * <h2>Delivery is pumped, not threaded</h2>
 * Callbacks queue, and {@link #deliverPending} hands them over. There is no executor, no timer
 * and no sleeping.
 *
 * <p>That is deliberate. A threaded mock would make "the callback arrives twice, four seconds
 * apart" a test that passes most of the time, and the whole reason these behaviours exist is to
 * prove something about correctness rather than about luck. Pumping makes out-of-order delivery
 * exactly reproducible and lets the demo's scripted clock drive a thirty-day story in ninety
 * seconds. The system under test cannot tell the difference: it receives the same callbacks, in
 * the same orders, with the same repeats.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>It moves no money.</b> PROBLEM.md section 7 - that is the boundary, and it is why this
 *       is an interface with a mock behind it rather than an integration.</li>
 *   <li><b>No network, no HTTP, no retries of its own.</b> SPEC.md section 4 calls this an
 *       in-process mock.</li>
 *   <li><b>No partial success</b>, no fees deducted by the rail, no currency conversion. The rail
 *       either moved the shillings or did not.</li>
 *   <li><b>Not thread-safe.</b> Deliberate, for the reason above.</li>
 * </ul>
 */
@Component
public class MockPayoutRail implements PayoutPort {

    public static final String RAIL = "mock-shilling-rail";

    private RailBehaviour behaviour = RailBehaviour.SUCCEED;

    private final Deque<PayoutCallback> pending = new ArrayDeque<>();
    private final Map<String, PayoutOutcome> known = new HashMap<>();
    private final List<PayoutRequest> accepted = new ArrayList<>();
    private int railRefSequence = 0;

    /** How this rail will behave for everything sent from now on. */
    public MockPayoutRail behaving(RailBehaviour behaviour) {
        this.behaviour = behaviour;
        return this;
    }

    public RailBehaviour behaviour() {
        return behaviour;
    }

    @Override
    public Acceptance send(PayoutRequest request) {
        accepted.add(request);

        String railRef = RAIL + "/" + (++railRefSequence);
        Acceptance acceptance = new Acceptance(request.externalRef(), railRef, request.requestedAt());

        switch (behaviour) {
            case NEVER_REPLY -> {
                // Accepted, and that is all that will ever be heard. The instruction will time out
                // into AWAITING_RESOLUTION, which is the state this whole project turns on.
            }
            case SUCCEED -> queue(callback(railRef, request, PayoutOutcome.SUCCEEDED, request.amount()));
            case FAIL -> queue(callback(railRef, request, PayoutOutcome.FAILED, request.amount()));
            case CALLBACK_TWICE -> {
                // The SAME rail reference twice. A rail re-delivering an event it is unsure we
                // received does exactly this, and it is what the unique constraint catches.
                PayoutCallback once = callback(railRef, request, PayoutOutcome.SUCCEEDED, request.amount());
                queue(once);
                queue(once);
            }
            case CALLBACK_OUT_OF_ORDER ->
                    queue(callback(railRef, request, PayoutOutcome.SUCCEEDED, request.amount()));
            case CALLBACK_FOR_UNKNOWN_INSTRUCTION -> queue(new PayoutCallback(
                    RAIL, railRef, "never-sent/" + railRefSequence, PayoutOutcome.SUCCEEDED,
                    request.amount(), request.requestedAt(),
                    payload(railRef, "never-sent/" + railRefSequence,
                            PayoutOutcome.SUCCEEDED, request.amount(), request.requestedAt())));
            case CALLBACK_WITH_WRONG_AMOUNT -> queue(callback(railRef, request,
                    PayoutOutcome.SUCCEEDED, request.amount().minus(Money.of(1L, request.amount().currency()))));
        }

        if (behaviour != RailBehaviour.NEVER_REPLY && behaviour != RailBehaviour.CALLBACK_FOR_UNKNOWN_INSTRUCTION) {
            known.put(request.externalRef(),
                    behaviour == RailBehaviour.FAIL ? PayoutOutcome.FAILED : PayoutOutcome.SUCCEEDED);
        }

        return acceptance;
    }

    /**
     * Re-query. Safe by construction: it sends nothing, so calling it on an instruction whose
     * outcome is unknown cannot make anything happen twice.
     */
    @Override
    public Optional<PayoutOutcome> status(String externalRef) {
        return Optional.ofNullable(known.get(externalRef));
    }

    /**
     * Hands every queued callback to the sink, and empties the queue.
     *
     * <p>Under {@link RailBehaviour#CALLBACK_OUT_OF_ORDER} they go in reverse - the second payment
     * you sent is reported before the first. SPEC.md section 12 asks for exactly this, and it is a
     * real rail behaviour rather than a contrived one.
     *
     * @return how many were delivered
     */
    public int deliverPending(CallbackSink sink) {
        List<PayoutCallback> batch = new ArrayList<>(pending);
        pending.clear();

        if (behaviour == RailBehaviour.CALLBACK_OUT_OF_ORDER) {
            Collections.reverse(batch);
        }

        batch.forEach(sink::accept);
        return batch.size();
    }

    /** What is queued and not yet delivered. For tests and for the demo screen. */
    public int pendingCount() {
        return pending.size();
    }

    /** Everything this rail was asked to send. Used to prove nothing was sent twice. */
    public List<PayoutRequest> acceptedRequests() {
        return List.copyOf(accepted);
    }

    /** Wipes the rail's memory. For tests that reuse one instance across scenarios. */
    public MockPayoutRail reset() {
        pending.clear();
        known.clear();
        accepted.clear();
        railRefSequence = 0;
        behaviour = RailBehaviour.SUCCEED;
        return this;
    }

    private void queue(PayoutCallback callback) {
        pending.add(callback);
    }

    private PayoutCallback callback(String railRef, PayoutRequest request,
                                    PayoutOutcome outcome, Money amount) {
        return new PayoutCallback(RAIL, railRef, request.externalRef(), outcome, amount,
                request.requestedAt(),
                payload(railRef, request.externalRef(), outcome, amount, request.requestedAt()));
    }

    private static String payload(String railRef, String externalRef, PayoutOutcome outcome,
                                  Money amount, Instant at) {
        return Snapshot.of()
                .with("rail", RAIL)
                .with("railRef", railRef)
                .with("externalRef", externalRef)
                .with("outcome", outcome.name())
                .with("amountMinor", amount.minorUnits())
                .with("currency", amount.currency().name())
                .with("occurredAt", at.toString())
                .toJson();
    }

    /** Where a rail delivers its callbacks. Implemented by the application, not by the rail. */
    @FunctionalInterface
    public interface CallbackSink {
        void accept(PayoutCallback callback);
    }
}

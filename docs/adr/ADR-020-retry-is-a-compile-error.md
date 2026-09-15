# ADR-020 — The retry is a compile error, and SUBMITTED gets an arrow §7 does not draw

**Date:** 2026-09-15 (day 9)
**Status:** accepted — decision 2 is a deliberate deviation from `SPEC.md` §7

## Decision 1 — one type per state, so the retry cannot be written

`SPEC.md` §9 is unusually prescriptive about I7:

> "Do not implement it as an `if` statement that skips the retry. Implement it so that the retry
> method **cannot be called** on an instruction in that state — a different type, or a guard in
> the state machine itself. Rules enforced by discipline get broken at 2am on day 12."

So `Instruction` is a sealed interface with one record per state, and a transition is a method
returning the next type. `retry()` exists on `Failed` and on nothing else.

```java
awaitingResolution.retry(now);   // error: cannot find symbol
                                 //   symbol: method retry(Instant)
```

Not "throws". Not "returns false". **Does not compile** — verified by compiling a probe file,
not asserted from confidence. The same probe shows `failed.retry(now)` compiling cleanly, so the
guarantee is precise rather than blanket.

`Submitted` has no retry either, which matters as much: an instruction in flight also has an
unknown outcome.

Three layers, weakest last:

| Layer | Catches |
|---|---|
| Java types | anything written in Java — at compile time |
| `instruction_transition_must_be_legal()` trigger | migrations, scripts, a `psql` prompt |
| `check` constraint on `state` | a value outside the six |

The trigger refuses two things specifically, because in the data a retry looks like exactly one
of them: the attempt count changing while `AWAITING_RESOLUTION`, or the state going back to
`SUBMITTED`.

**Cost:** reading an instruction back from the database loses the instants of past transitions,
because the table stores current state and attempt count, not a history. Rather than invent
timestamps, the repository supplies `created_at` and says so. What happened and when belongs in
the journal and the settlement records, not in a second version of them.

## Decision 2 — `SUBMITTED → FAILED`, an arrow §7 does not draw

§7 draws `FAILED` only beneath `AWAITING_RESOLUTION`. Taken literally, an instruction the rail
*explicitly rejects* would have to pass through `AWAITING_RESOLUTION` first.

**That blurs the exact distinction the state exists to draw.** `AWAITING_RESOLUTION` is for
outcomes that are **unknown** — §7 calls it "not terminal, and not failure", and PROBLEM.md I7
says "unknown is not failure". A rail reporting rejection has given a *known* outcome. Routing
it through the unknown state would put known and unknown failures in the same bucket, and the
whole value of the state is that they are not the same bucket.

So `Submitted.rejected(...)` returns `Failed` directly, and the trigger permits that arrow.

This is a deviation made by Claude, not by Kurgat. My reading is that §7's diagram is focused on
the timeout path and the arrow is an omission rather than a prohibition — but **if it was
deliberate, this is the thing to reverse**, and it is one method and one line of the trigger.

## Decision 3 — `MANUAL_REVIEW` has no exit

§7 draws none, so none is built. An instruction that reaches it stays there as far as this system
is concerned.

That is unsatisfying and it is deliberate: what a human does after looking is not specified
anywhere, and inventing a resolution path would be inventing the one rule this state exists to
hand to a person. **Flagged as a gap needing Kurgat's answer** — without one, the demo can reach
a state it cannot leave.

## What is deliberately not built

- **No re-query mechanism.** §7 says an unknown instruction "is re-queried"; asking the rail
  again is the settlement adapter's job (day 11) and the payout adapter's (day 10). What comes
  back is evidence, and `resolvedAsSettled` / `resolvedAsFailed` are where it lands.
- **No optimistic locking on transitions.** §17 scenario 5 — two threads creating the same
  external reference — is handled by the unique constraint. Two threads *transitioning* the same
  instruction at once is a different race and is **not addressed**. Noted rather than assumed
  away.
- **No link to a settlement record.** `Settlement` is its own entity in §6 and arrives with the
  reconciler.

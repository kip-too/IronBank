# ADR-004 — Why `AWAITING_RESOLUTION` exists and is never retried

**Date:** 2026-09-15 · **Status:** accepted

## The failure it prevents

`PROBLEM.md` **F5**: *"A payment is retried because its outcome was unclear, and goes out twice.
Money leaves and does not come back."*

`SPEC.md` §7 is blunt about why the state is unusual: *"this is the state that most systems do not
have, and its absence is why they double-pay."*

## Decision

An instruction whose outcome is **unknown** is not failed. It goes to `AWAITING_RESOLUTION`, which
is neither terminal nor failure, and the only ways out are **evidence** or **a person**.

```
SUBMITTED --timeout--> AWAITING_RESOLUTION --evidence--> SETTLED | FAILED
                                           --a person--> MANUAL_REVIEW
```

## How the rule is enforced — three layers, weakest last

| Layer | Catches |
|---|---|
| **The Java type system** | anything written in Java, **at compile time** |
| A database trigger | migrations, scripts, a `psql` prompt |
| A check constraint | any state outside the six |

`retry()` exists on `Failed` and on **no other state type**. So
`awaitingResolution.retry(now)` does not throw — **it does not compile**, and that was verified by
compiling a probe rather than asserted from confidence. `SPEC.md` §9 asked for precisely this:
*"Rules enforced by discipline get broken at 2am on day 12."*

`Submitted` has no retry either, which matters as much: in flight is also an unknown outcome.

In the data a retry looks like exactly one of two things — the attempt count moving while
`AWAITING_RESOLUTION`, or the state going back to `SUBMITTED` — and the trigger refuses both by
name.

## Why retrying a `FAILED` instruction is safe

F5 is about retrying an **unknown**. A failure the rail reported is *known*, so sending it again is
ordinary operations. That distinction is the entire point of the state, and collapsing the two is
the mistake it exists to make impossible.

## Where the rule reaches beyond the state machine

- `SettlementUnavailableException` — a sidecar timeout — carries the message *"do not retry on the
  strength of it"*. Catching it and marking an instruction FAILED would reintroduce F5 directly.
- `MANUAL_REVIEW` cannot be retried from either: a person resolves it to FAILED first, putting
  their name to *"this did not happen"*, and retries from there
  ([ADR-021](ADR-021-manual-review-needs-an-exit.md)).
- The agent counts anything `AWAITING_RESOLUTION` as still **in flight**, so its dollars are not
  converted again while the outcome is unknown ([ADR-022](ADR-022-in-flight-money-and-reserved-dollars.md)).

## Details and one deviation

[ADR-020](ADR-020-retry-is-a-compile-error.md), including an arrow added that §7 does not draw,
and the argument for it.

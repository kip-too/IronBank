# ADR-024 — The payout mock: O4 answered no, and delivery is pumped rather than threaded

**Date:** 2026-09-15 (day 10)
**Status:** accepted

## Decision 1 — O4 resolves to **no**

`SPEC.md` O4 asks whether the payout mock should mirror a real mobile money API's request and
callback format, and answers its own question conditionally:

> "Yes, **if the format can be verified from current documentation.** Verify the actual format
> from current documentation before shaping it; do not write the field names from memory."

It could not be verified. The Safaricom developer portal did not respond on 2026-09-15:

```
developer.safaricom.co.ke/APIs/BusinessToCustomer   http=408
developer.safaricom.co.ke/                          http=408
developer.safaricom.co.ke/Documentation             http=408
```

`CLAUDE.md` rule 1 forbids writing an API's field names from memory, and O4 forbids it twice over.
So `PayoutCallback` uses this project's own plainly-named fields, and `rawPayload` carries what
the rail actually sent, verbatim, so nothing is lost in translation.

**What this costs:** the eventual swap to a real rail is a mapping class rather than nothing. That
is a smaller cost than a mock shaped around field names somebody half-remembered, which would look
authentic and be wrong — and would be discovered only when the real integration failed.

**How to revisit:** when the portal is reachable, read the B2C result format, write the mapping,
and record the field names here with the date read.

## Decision 2 — callbacks are pumped, not threaded

`MockPayoutRail` queues callbacks, and `deliverPending(sink)` hands them over. No executor, no
timer, no sleeping.

A threaded mock would make "the callback arrives twice, four seconds apart" a test that passes
*most* of the time. These behaviours exist to prove something about correctness, and a flaky proof
of correctness is not one. Pumping makes out-of-order delivery exactly reproducible, and it is what
lets the demo's scripted clock run a thirty-day story in ninety seconds.

**The system under test cannot tell the difference**: it receives the same callbacks, in the same
orders, with the same repeats. What it loses is a rehearsal of genuine concurrency — two callbacks
arriving on two threads at once is **not** covered, and neither is `SPEC.md` §17 scenario 5's two
threads creating the same external reference. The unique constraint is what would catch the
second; nothing yet demonstrates it under real contention.

## Decision 3 — a seventh behaviour

`SPEC.md` §12 lists six. A seventh, `CALLBACK_WITH_WRONG_AMOUNT`, is added because §17 scenario 4
asks for it and §13 states the rule it tests — *"amounts differ → exception, always, regardless of
size"*. A mock that could not produce a wrong amount could not prove that rule.

## Decision 4 — five dispositions, and only one changes anything

| Disposition | Meaning |
|---|---|
| `APPLIED` | matched an instruction and moved it |
| `ALREADY_SEEN` | a repeat, caught by the unique constraint on `(rail, rail_ref)`. Changes nothing |
| `UNMATCHED` | no instruction carries that reference — goes to reconciliation |
| `AMOUNT_MISMATCH` | right reference, wrong amount. Settled on **neither** figure |
| `NOT_APPLICABLE` | the instruction was in no state to receive it |

The `AMOUNT_MISMATCH` row is worth reading twice: the instruction stays in `SUBMITTED`. It is not
settled at the rail's figure and not at ours. §13 says exception, always, and an exception that
quietly picked one of the two numbers would not be one.

## Decision 5 — recorded first, in its own transaction

`CallbackIngest.record` runs `REQUIRES_NEW`, so the evidence that a callback arrived survives
whatever happens next. If processing throws, the row remains with a null `processed_at` — an event
that arrived and was not dealt with, which is itself a reconciliation item.

Processing first and recording after would lose exactly the events worth keeping: the ones handled
badly.

The table is write-once at the database: what a callback *said* can never be edited, and the
disposition columns can be written **once**. A second attempt to process the same row is refused
with its own message.

## What is deliberately not built

- **Nothing posts to the ledger.** A settled payout should produce journal entries, and what those
  entries are depends on an allocation rule `SPEC.md` does not give. The instruction moves; the
  books do not, yet. **This is the largest remaining gap before the demo.**
- **No signature or authenticity check.** A real rail signs its callbacks; a mock cannot
  meaningfully rehearse verifying that.
- **No reconciliation screen.** `CallbackIngest.unresolved()` is the query day 13 builds on.

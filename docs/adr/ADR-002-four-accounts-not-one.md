# ADR-002 — Exchange difference, spread and fee are four accounts, not one

**Date:** 2026-09-15 · **Status:** accepted (the project's central claim)

## Decision

```
6100  Exchange difference, realised     what the market did, and it happened
6110  Exchange difference, unrealised   what the market did, and we are still holding
6200  Conversion spread                 what the provider took quietly, inside the rate
6210  Conversion fee                    what the provider took openly
```

Four accounts. They never combine, and invariant I5 refuses an entry touching both 6100 and 6110.

## Why

This is failure **F2** in `PROBLEM.md` §3 — *"exchange difference, provider spread and explicit
fee all collapse into one number"* — and it is the reason the project exists. `PROBLEM.md` §5 puts
it plainly: *"Collapse them and you get one figure that answers no question. Keep them apart and
the business can act."*

Each has a different cause and therefore a different remedy:

| Account | Cause | What you do about it |
|---|---|---|
| 6100 | the dollar moved between two dates | chase the client faster, or accept it |
| 6110 | the dollar moved and you are still holding | convert sooner, or accept the exposure |
| 6200 | your provider's rate is worse than mid | renegotiate, or change provider |
| 6210 | your provider charges a fee | renegotiate, or change provider |

Blended into one number, none of those four questions can be asked.

## The one that is easy to get wrong

**Exchange difference is not revenue.** `PROBLEM.md` §5 calls booking it as revenue *"the single
most common error in a small set of books"* — it overstates the top line and hides an FX position.
The demonstration screen shows both figures side by side for exactly this reason.

## The subtlety that took a decision

Spread is **not** additional money leaving — nobody invoices for it. It is already inside the
exchange difference, so `Dr 6200 / Cr 6100` **reclassifies** rather than adds. That is what lets
the memo entry balance without inventing an account, and it is set out in full in
[ADR-016](ADR-016-the-memo-entry-balances.md).

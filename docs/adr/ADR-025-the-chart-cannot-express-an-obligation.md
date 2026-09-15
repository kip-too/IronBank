# ADR-025 — The chart of accounts cannot express an obligation, so payouts are not posted

**Date:** 2026-09-15
**Status:** accepted — **this is a finding about `SPEC.md` §6, not a design choice**

## What was found

Closing the "nothing posts to the ledger" gap meant asking what a payout posting looks like.
Paying payroll properly is two entries:

```
accrue:   Dr <payroll expense>    Cr 2000 Payables
pay:      Dr 2000 Payables        Cr 1000 Bank
```

`SPEC.md` §6's chart, in full, is:

```
1000 Bank    1100 Wallet    1200 Receivables    1900 Suspense
2000 Payables    4000 Revenue
6100 exdiff realised    6110 exdiff unrealised    6200 spread    6210 fee
```

**There is no expense account.** Not for payroll, rent, suppliers or statutory — the four things
`PROBLEM.md` §1 names as the entire outgoing side of this business. The accrual has no debit side
that exists.

## The decision

**Payouts are not posted.** Invoices, receipts and revaluations are.

Posting only the payment half would leave account 2000 carrying a **debit** balance — a figure
that is wrong, in an account that should never hold it, and which somebody would eventually
"correct" with an adjustment. That is a plug with a respectable name, and I3 forbids it.
Inventing an expense account is forbidden by rule 1 and by I3's own test, which asserts the chart
contains nothing of the kind.

So the honest options were: invent an account, write a wrong number, or say so. This says so.

## Why this costs less than it sounds

`PROBLEM.md` §5 **never posts a payout either.** Its day 20 is the *conversion* that funds
payroll, not the payroll payment. The worked example — the acceptance test §10 says the engine
must satisfy — is complete without it, and it now posts end to end from business events:

| Day | Entry | Posted by |
|---|---|---|
| 1 | Dr 1200 / Cr 4000 at the issue-date rate | `Bookkeeper.recordInvoice` |
| 12 | Dr 1100 / Cr 1200 / Cr 6100 | `Bookkeeper.recordReceipt` |
| 20 | Dr 1000 / Cr 1100 / Cr 6100, plus spread and fee | `FxEngine` |
| 30 | Dr 6110 / Cr 1100, no dollars moved | `Bookkeeper.revalueWallet` |

## What Kurgat has to decide

Whether §6's chart is a *minimum* that may be extended, or a *complete* set. §6 calls it
"minimum set, and each one exists because of a rule in §9", which reads like the former — but
every account in it is justified by an invariant, and "payroll expense" is justified by no
invariant at all.

**If the chart may grow**, adding `5000 Operating expenses` (or four accounts, one per category)
makes payouts postable, and the accrual/payment pair becomes ordinary bookkeeping. That is one
migration and one method.

**If the chart is complete**, then this system deliberately keeps the *treasury* books and not
the *whole* books, and `REAL_VS_SIMULATED.md` should say that plainly. That is a defensible scope
— the project is about exchange difference, spread and fee, not about expense classification.

Either answer is fine. Guessing between them is not.

## Also decided here: which rate each business event uses

None of these is invented; each is read from `PROBLEM.md` §5 and stated in `Bookkeeper`:

- **An invoice** uses the mid rate **on its issue date** — day 1: *"Rate that day: 129.00"*.
- **A receipt** uses the mid rate **on the day the money arrived** for what comes in, and the
  **receivable's own issue-date rate** for what is cleared. The difference between those two is
  the exchange difference, and it is the whole of day 12.
- **A revaluation** uses the closing mid rate against weighted-average carrying value.

**No rate is ever carried forward.** A business event on a day the feed has no quote for is
refused with `NoRateForDateException`, consistently with ADR-015 and ADR-019. The event is real
and can be posted the moment a rate exists — a data problem with an obvious fix, unlike a stale
figure that looks exactly as authoritative as a fresh one.

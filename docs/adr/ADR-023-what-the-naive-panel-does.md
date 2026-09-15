# ADR-023 — What the demo's naive panel does (O8)

**Date:** 2026-09-15
**Status:** accepted
**Answers:** `SPEC.md` O8, which §20 marks *"the one to think about hardest"*

## The problem

`SPEC.md` O8: *"What the demo's naive panel actually does, precisely, to be a fair comparison
rather than a straw man."* And §19: *"A demo that beats a deliberately stupid alternative proves
nothing, and an audience can smell it."*

The temptation is to build something that loses. The panel has to be something a **competent**
person would actually do.

## The decision

The naive panel does exactly this, and nothing worse:

```
1. USDC arrives.  Record nothing - it is not shillings yet.
2. Convert.       The bank credits a shilling amount.
3. Write that shilling amount in the sheet, dated today, against the client's name.
4. Month end.     Sum the column. Call it revenue.
```

Four steps. Every one defensible. This is what `PROBLEM.md` §2 describes a founder doing, and it
is **not stupid** — it is the cheapest thing that produces a number, it never fails to balance,
and it never needs a rate table.

**The naive panel is also never wrong on its own terms.** It does not double-count, lose money,
or make arithmetic errors. The demo must not pretend otherwise. Its sums are right.

## What the comparison actually shows

Both panels run the same thirty-day story on the same scripted clock. At the end they are asked
the same four questions, and the difference is **not** that one is wrong — it is that one cannot
answer.

| Question | Naive panel | This system |
|---|---|---|
| What did the business earn in September? | one figure | Revenue 1,290,000 — the invoice, at the rate on the day it was raised |
| How much of that figure is the dollar moving? | *cannot say* | 6,600 in 6100, measured against mid |
| What did the provider charge? | *cannot say* | 2,400 spread (6200) and the fee (6210), separately |
| Reproduce the 20th from the records | *cannot* — the rate is gone | replay, from the journal alone |

The naive column says **"cannot say"** three times out of four. That is the demo, and it lands
harder than a wrong number would, because the audience can check that it is fair: the naive
figure is right, it just carries no information.

## The single strongest moment

Both panels show one total for September. **They differ**, and the naive one is not obviously
wrong — because it books the exchange difference as revenue. `PROBLEM.md` §5 calls that "the
single most common error in a small set of books", and the naive panel makes it the way real
books make it: not by miscalculating, but by having nowhere else to put the number.

So the screen shows the two revenue figures side by side, and then shows where the difference
went. One panel has one account. The other has four.

## Why this is a fair comparison and not a straw man

- The naive panel uses the **same inputs**, the same clock, the same conversion outcomes. It is
  not given worse data.
- Its arithmetic is **correct**. No test asserts that it is wrong, because it is not.
- Its method is **documented in `PROBLEM.md` §2** as what actually happens today, not invented
  here to lose.
- It would survive an audit of its own totals. What it cannot survive is a question.

If the audience concludes the naive panel is reasonable, the demo has still worked — **that is
the point**. `PROBLEM.md` §3 names the failures as things that happen to careful people.

## What this commits day 12 to building

- Two panels, same story, same scripted clock, side by side.
- A row of four questions with the naive column answering "—" three times.
- The two September revenue figures, and the four accounts that explain the gap.
- No animation of the naive panel failing, no error states, no red. It is not a villain.

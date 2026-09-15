# ADR-026 — The demo: one opening balance, and a second hole in the chart

**Date:** 2026-09-15 (day 12)
**Status:** accepted

## Decision 1 — the opening bank balance is KES 84,400, and that is the only free variable

The demo plays PROBLEM.md §5's story through the real system. For an audience to check the output
against the document they were handed, the agent has to convert **6,000 dollars** on the 20th —
and the agent's rule is not negotiable.

The rule, run unchanged: `cover 880,000 − have 84,400 = 795,600 short`, and
`795,600 / 132.60 = 6,000.0` **exactly**, before the ceiling is even applied.

A demo has to pick an opening balance anyway. Picking the one that makes the output checkable is
worth more than picking a round number. **Nothing is special-cased** — and
`nothing_is_hardcoded_to_match_the_document` reproduces the arithmetic from the documented inputs
and asserts the agent agrees, so this cannot quietly become a special case later.

## Decision 2 — the opening balance is credited to Payables, not Revenue

Found by rendering the page and reading it. The screen said:

```
4000 Revenue ... KES 1,374,400
```

when the demo's entire claim is *"revenue is the invoice and only the invoice"* — 1,290,000. The
extra 84,400 was the opening balance, which the first version posted `Dr Bank / Cr Revenue`.

**Cash brought forward is not something the business earned this month.** Crediting it to 4000
inflated the exact figure the demo argues about, and an audience would have been right to call it.

The textbook credit is **equity or retained earnings**, and §6's chart has neither. That is the
same finding as [ADR-025](ADR-025-the-chart-cannot-express-an-obligation.md)'s missing expense
account, and it is now two instances of one gap:

> **§6's chart expresses the treasury cleanly and cannot express the rest of a set of books.**
> No expense account, so an obligation cannot be accrued. No equity account, so a balance cannot
> be brought forward.

Both are recorded rather than papered over. Crediting Payables is sign-correct and coherent —
cash held against amounts owed — and the screen says so in words rather than letting the number
imply something tidier.

**This strengthens ADR-025's question:** is §6's chart a minimum that may be extended, or a
complete set? Two independent needs have now hit the same wall.

## Decision 3 — no template engine

`spring-boot-starter-web` is needed to serve anything. Thymeleaf is not: §3's technology table
names no template engine and rule 6 makes every library a decision. One page does not justify one,
so `DemoController` writes HTML in Java 21 text blocks. Ugly at fifty screens; reasonable at one.

## Decision 4 — presentation formats money; the domain does not

`Money.toString()` renders `KES 79320000 (793200.00)` and its own header says it is "for logs and
test failure messages, not for a screen". I then put it on a screen. `Figures` fixes that in the
demo package, where §5 explicitly allows it: *"The presentation layer may show them however it
likes."*

It also flips the sign on accounts that naturally carry a credit. Internally credits are negative;
showing a reader **"Revenue: −1,290,000"** would be technically true and actively confusing. The
flip happens in presentation and nowhere else — no stored figure changes.

## What the screen deliberately does not do

- **The naive panel is not styled as a failure.** No red, no warnings, no animation of it going
  wrong. The screen says out loud that its sum is correct, because ADR-023's fairness argument
  only works if the audience can see it is fair.
- **It does not hide what is missing.** A closing paragraph states that payroll is funded and not
  paid, that no currency is converted, and that the dollar-leg hash is simulated — with pointers
  to where each is explained.

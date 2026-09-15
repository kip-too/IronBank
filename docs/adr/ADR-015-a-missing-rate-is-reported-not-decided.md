# ADR-015 — A missing rate is reported, not decided

**Date:** 2026-09-14 (day 4)
**Status:** accepted — and it deliberately leaves a question open for Kurgat

## The problem

`SPEC.md` §17 scenario 7: *"Payroll falls due on a day when the rate feed has no entry."*

The fixed rate table has four rows, for the four dates in `PROBLEM.md` §5's worked example.
Every other date in history has no quote. So the adapter must answer the question: what is the
rate on 13 September?

## The decision

`RatePort.midRateOn(LocalDate)` returns `Optional<Rate>`. Empty means the feed has no entry. The
adapter does not carry yesterday's rate forward, does not interpolate, does not find the nearest
date, and does not throw.

**The gaps in the seeded table are left as gaps.** `V5`'s header says so explicitly, so that
nobody fills them later to make a test convenient and quietly removes the only way scenario 7 is
reachable.

## Why the port declines to decide

What to do about a missing rate is a **financial** decision with different consequences per
answer:

- *Carry yesterday's forward* — the books get a figure that looks exactly as fresh as a real
  one. Nothing in the record distinguishes "quoted today at 131.50" from "nobody quoted, so we
  reused Friday". That is F1 again, and it is the most tempting option because it never blocks
  anything.
- *Refuse to act* — honest, and it means payroll does not get funded on a day the feed is down.
  That may be correct and it may be unacceptable; it is not Claude's call.
- *Escalate to a human* — needs a mechanism that does not exist yet.

`CLAUDE.md` rule 2 forbids picking one. Returning `Optional` is how the code declines without
pretending the question does not exist: **every caller is forced to face the empty case at the
point it matters**, and when Kurgat rules, the rule goes in the caller — the agent, on day 8 —
where it can be given its own named test.

## What this costs

- **Callers carry an `Optional` around.** Mild, and the alternative shapes are worse: a `null`
  that gets dereferenced somewhere distant, or an exception that the caller catches and turns
  into whatever behaviour was convenient at 2am.
- **Nothing is resolved.** This ADR records a decision not to decide, which is only legitimate
  because the question is genuinely Kurgat's and because the code makes it impossible to ignore.
  **If day 8 arrives with no ruling, the agent has to do something**, and that something will
  need its own ADR.

## Related, and deliberately separate

The **rate table holds mid-market quotes only** — it is named `mid_rate` for that reason.
`SPEC.md` §12 says the adapter returns a mid-market rate. An executed rate is not a feed quote;
it is what a conversion actually got, and it is already recorded where it belongs, on the
posting. Giving the table a `kind` column and seeding one kind would imply a second source of
truth for executed rates that does not exist. Recorded in `V5__mid_rates.sql`.

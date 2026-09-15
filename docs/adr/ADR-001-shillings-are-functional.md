# ADR-001 — Shillings are the functional currency

**Date:** 2026-09-15 · **Status:** accepted (records a decision `PROBLEM.md` §4 already made)

## Decision

KES is the functional currency. Every posting carries a shilling value, and that value is stored
on the posting rather than computed on read.

## Why

`PROBLEM.md` §4 defines it: *"The one language the books are kept in."* The business is Kenyan,
its obligations are shilling obligations on a calendar it cannot argue with, and its statutory
reporting is in shillings. Income arrives in dollars and is a **position**, not the unit of
account.

The alternative — keeping books in dollars because that is what is earned — would make every
payroll, rent and statutory payment a foreign-currency transaction, and would put the exchange
exposure on the side of the business that has no choice about timing.

## What follows from it, structurally

- `Posting.functional_amount_minor` is **stored**, never recomputed. The shilling value of a
  posting is a fact about the moment it was made; recomputing it later with today's rate silently
  rewrites history.
- Every entry must balance **in shillings**, always. Per-currency balance is a narrower rule — see
  [ADR-013](ADR-013-balance-per-currency-only-for-single-currency-entries.md), where the literal
  version of that rule turned out to reject the specification's own worked example.
- A KES posting needs no rate. A posting in anything else cannot exist without one (invariant I1,
  enforced in three places).

## What it costs

One functional currency is an assumption in the schema, not a configuration value. `PROBLEM.md`
§8 question 7 asks what breaks if a business thinks in two, and the honest answer is: the
`functional_amount_minor` column, the balance rule, and every report. `SPEC.md` §2 puts a second
functional currency out of scope permanently, and this is what that costs.

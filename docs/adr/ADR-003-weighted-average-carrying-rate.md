# ADR-003 — Weighted average, not first-in-first-out (O2)

**Date:** 2026-09-15 · **Status:** accepted as the assumed default — **Kurgat's call to overturn**

## Decision

The wallet carries one blended rate, derived from the journal on every read. `SPEC.md` §10 leaves
this open and assumes weighted average until ruled otherwise; that assumption stands, and the
method sits behind `CarryingValuePolicy` so the answer can change without touching anything else —
which is exactly what §10 requires.

## Why weighted average, for now

- **It is derivable from the journal alone.** Carrying value is the sum of
  `functional_amount_minor` over postings to 1100; the balance is the sum of `amount_minor`.
  Nothing is stored that could drift, and replay needs no extra records.
- **First-in-first-out needs lot tracking.** Which dollars left first, and how much of each lot
  remains, is state the journal does not carry. It is a table and a rule about ordering, and
  neither is specified anywhere.

## What it costs

Weighted average smooths. A business that received dollars at 129.00 and at 140.00 has a wallet
carrying something in between, and no entry anywhere says *"the 129 dollars are the ones that
left"*. For tax treatment, or for a policy that deliberately realises particular gains, that
distinction can matter. `SPEC.md` §19 puts tax treatment permanently out of scope, which is part
of why this is affordable.

## The rounding decision underneath it

Worth reading even if the FIFO question is settled the other way. The carrying value of dollars
leaving is computed **proportionally** — `round(carryingValue × leaving / balance)` — and not by
deriving a blended rate and multiplying by it.

Deriving a rate rounds twice, and at the boundary it can strand a cent of shilling value on a
wallet holding **zero dollars**: a number with no meaning, which somebody would later remove with
an invented entry. The proportional rule makes emptying the wallet exact by arithmetic rather than
by luck. Full reasoning and the test that pins it:
[ADR-016](ADR-016-the-memo-entry-balances.md), decision 5.

## To change it

Write a second `CarryingValuePolicy` and change one bean definition. Nothing else in the system
asks about carrying value.

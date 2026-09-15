# ADR-014 — A rate with more than eight decimals is refused, not rounded

**Date:** 2026-09-14 (day 4)
**Status:** accepted

## The problem

`SPEC.md` §5 fixes the rate scale at 8. `SPEC.md` §17 scenario 18 asks what happens when
something hands the system *"a rate with more decimal places than the rate scale allows"* — and
nowhere answers it.

Two obvious answers: round the quote to 8 places, or refuse it.

## The decision

**Refuse.** `Rate`'s constructor throws `RatePrecisionException` when the value carries more
than 8 *significant* decimal places. Fewer is widened to 8, which is exact and loses nothing.

Significance is measured after `stripTrailingZeros()`, so `131.500000000000` is accepted — it
carries one significant decimal, not twelve. Refusing that would be refusing a rate that fits
perfectly well.

## Why

A rounded rate **is not the rate anybody quoted.** If the feed says `131.123456789` and the
books store `131.12345679`, then every figure derived from it disagrees slightly with the source
and nobody can tell why — the stored rate looks exactly as authoritative as an exact one. That
is failure **F1, rate amnesia**, arriving by the back door: not a missing rate, but a subtly
wrong one that cannot be checked against its source.

Refusing puts the decision where it can be seen. If a live feed genuinely quotes 10 decimals,
the **rate adapter** has to decide what to do about the extra precision, explicitly, in code
somebody reviews. That is a boundary decision, and boundaries are where decisions belong.

## What this costs

- **A live adapter cannot simply pass a feed's quote through.** It has to handle
  `RatePrecisionException`, and the handling will be a decision Kurgat has to make. That cost is
  real and it is deferred, not avoided — `RatePort`'s only implementation today reads a
  `numeric(20,8)` column, where PostgreSQL has already done the rounding on write, so the
  exception cannot fire. **The first live adapter is where this bites.**
- **It is strict about something that is usually harmless.** Most FX feeds quote 4 to 6
  decimals, so in practice this fires only on malformed data — which is an argument for
  refusing, not against.

## The alternative, and when it would be right

Rounding to 8 `HALF_UP` at ingestion would be defensible **if the rounded value were stored
alongside the original quote**, so the audit trail keeps both. That needs a column for the raw
quote and a decision about which one postings cite. It is more work and more schema, and it buys
nothing until there is a live feed. Worth revisiting at that point rather than now.

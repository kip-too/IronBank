# ADR-011 — Money is `bigint` minor units; a rate is `numeric(20,8)`

**Date:** 2026-09-14 (day 2)
**Status:** accepted

## The problem

`SPEC.md` §5 says money is *"stored as whole minor units in a 64-bit integer"* and *"never a
decimal type in the database, never a floating point type anywhere."* The same section says
*"a rate is stored with a scale of 8."*

Read carelessly, `posting.rate_value numeric(20,8)` looks like a violation of the first rule.

## The decision

- **Money:** `bigint`, whole minor units, always. `amount_minor`, `functional_amount_minor`.
- **Rates:** `numeric(20,8)`. Exact decimal, never `float8`/`double precision`/`real`.

The "no decimal type" rule governs **amounts**. That is the sentence it sits under, and the
reason behind it is that money must not carry a fractional minor unit — a half-cent that exists
in the database is a rounding decision nobody made. A rate is not money. It has no minor unit,
and `SPEC.md` fixes it at scale 8 in the same breath.

## Why not store the rate as a scaled `bigint` too

Storing `132.20` as `13220000000` would be more uniform, and it was the tempting option.

Rejected for one reason: **a reviewer running `select * from posting` should see `132.20000000`.**
This project exists so that a person in March can read September. A column that reads
`13220000000` and requires knowing the implied scale to interpret makes every raw-SQL audit an
exercise in mental arithmetic, which is exactly the friction that stops people auditing.

`numeric` is exact. It is not floating point, it does not drift, and PostgreSQL will reject a
value that does not fit the declared precision. The uniformity argument is real but it buys
nothing, and it costs readability in the one place readability is the product.

## What this costs

- **`BigDecimal` in the Java code.** `Posting.rateValue` is a `BigDecimal`, and `BigDecimal`
  carries the usual trap: `equals` compares scale, so `131.5` and `131.50000000` are not equal
  even though they compare equal. Tests use `isEqualByComparingTo`, and
  `JournalStoreTest.the_stored_rate_keeps_its_scale` pins the scale that comes back from the
  database at 8.
- **A `BigDecimal` near money is a live hazard.** Nothing yet stops someone multiplying an
  amount by a rate in `BigDecimal` and rounding twice on the way. That is what the round-once
  rule exists to prevent, it lands on day 4, and until it does there is a gap here rather than
  a guard.
- **`numeric` arithmetic is slower than integer arithmetic.** Irrelevant at this volume.

## The boundary to hold

`Money` has no multiplication and no division, on purpose — see its class header. Every
operation that combines a rate with an amount has to go through day 4's conversion, which
rounds once, `HALF_UP`, at the end. Keeping multiplication off `Money` entirely is what stops a
`BigDecimal` and a `Money` meeting anywhere except in that one audited place.

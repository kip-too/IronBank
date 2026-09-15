# ADR-007 — The residue rule: the difference goes to the last part

**Date:** 2026-09-15 · **Status:** accepted (records `SPEC.md` §5's rule and where it is applied)

## The rule

`SPEC.md` §5: *"When an amount is split across several destinations and the parts do not sum to
the whole, the difference goes to the **last** part. Document the rule where the code does it. Do
not scatter the residue proportionally — that is a second rounding and it reopens the problem."*

Implemented in `Money.split(int)`, documented at the line that applies it, as §5 asks.

```
KES 1,000 split three ways = 100,000 cents / 3
    33,333 + 33,333 + 33,334
```

`SPEC.md` §17 scenario 17, and there is a test named for it.

## Why the last part, rather than spreading it

Spreading a residue proportionally is a **second rounding**. The first rounding created the
difference; a second one applied to the difference creates another, smaller one, and the problem
recurses. Putting the whole residue in one place ends it in one step.

Choosing *the last* part rather than the first or the largest is arbitrary — what matters is that
it is **fixed and written down**, so two runs of the same split agree and a reviewer can predict
the answer without running the code.

## The property that makes it worth having

The parts always sum back to the whole. Exactly, for any amount and any number of parts — by
arithmetic, because the last part is computed as *whole minus everything already allocated* rather
than as a share.

That is what makes it a no-plug rule: **no split ever needs an adjustment to make it agree.** A
property test covers seven amounts across thirteen part-counts.

Negatives split symmetrically: `-100,000` gives `-33,333 + -33,333 + -33,334`. Credits are
negative internally (§5), so this is not a corner case.

## The same idea elsewhere

Two other places apply "round once, at the end" rather than compounding roundings, and both are
recorded where they happen:

- **Conversion** (`Rate.toShillings`) multiplies at full precision and rounds once, `HALF_UP`.
  The test that proves it is the one where rounding twice gives a *different* answer — asserting
  10,000 × 131.50 proves nothing, since it comes out the same either way.
- **Carrying value** (`WeightedAverageCarryingValue`) takes the proportion directly rather than
  deriving a rate and multiplying, so emptying the wallet leaves exactly zero
  ([ADR-016](ADR-016-the-memo-entry-balances.md), decision 5).

## What is not built

**No weighted or proportional split.** Allocating an amount across destinations in unequal shares
needs a rule for computing each share *before* the residue is placed, and `SPEC.md` gives none.
Not guessed at — and it is the thing `SPEC.md` §17 scenario 16 ("funds enough for one and a half")
would need.

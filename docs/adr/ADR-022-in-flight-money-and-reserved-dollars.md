# ADR-022 — Money in flight, and dollars owed in dollars

**Date:** 2026-09-15
**Status:** accepted — fixes two defects flagged on day 8

## Defect 1 — the agent converted the same gap twice

Before this, `FundingPlan` compared what was due against the shilling balance and nothing else.
So a second decision cycle, run before the first conversion settled, saw the same shortfall and
proposed the whole of it again:

```
cycle 1   short 780,000  ->  convert 5,883 USDC
cycle 2   short 780,000  ->  convert 5,883 USDC     (the shillings have not arrived yet)
```

Roughly **twice what was needed**, converted. Not a missing feature — a defect. Day 8 flagged it
and day 9's instruction states made it fixable.

### The rule

Conversions in `CREATED`, `SUBMITTED` or `AWAITING_RESOLUTION` are **in flight**, and:

1. **their dollars are not available to convert again**, and
2. **the shillings they will produce are subtracted from the gap**, valued at the same mid rate
   the plan uses to size a conversion.

Point 2 needed a rule, because the executed rate is not known yet. Valuing at the current mid is
the self-consistent choice: if the rule says *"5,883 dollars will cover this 780,000 gap"*, then
5,883 dollars in flight cover it. The arithmetic that sized the conversion is the arithmetic that
retires it.

### Why it self-corrects

A conversion that reaches `FAILED` is **no longer in flight**. The dollars come back, the gap
reopens, and the next cycle sees it — with nothing resetting any state by hand. There is a test
for exactly that path.

`AWAITING_RESOLUTION` **does** count as in flight, which is the safe direction: the outcome is
unknown, the dollars may or may not be gone, and converting them again is precisely the
double-spend that state exists to prevent.

### What it costs

Between instructing and settling, the agent is conservative — it assumes the in-flight conversion
will land. If it silently never lands and never fails (a rail that simply forgets), the gap stays
closed in the agent's arithmetic while being open in reality. **That is what the ageing of
`AWAITING_RESOLUTION` items is for**, and it is the reconciler's job on day 13, not the agent's.

## Defect 2 — dollar obligations were invisible to the arithmetic

An obligation denominated in USDC was excluded from `needed` (correctly — §11's rule compares
against the KES balance) but was **not** protected. So the agent would happily convert away the
dollars needed to pay a dollar debt, funding a shilling obligation by making a dollar one
unpayable.

### The rule

Unfunded foreign-currency obligations **reserve** their dollars. Available to convert is:

```
available = wallet balance − in flight − reserved       (never negative)
```

A large dollar obligation can therefore cap or eliminate a conversion, and holding is then
permitted even with a shilling shortfall — correctly, because **the same dollars cannot be spent
twice**, and the shortfall is genuinely unfundable. It is recorded and visible either way.

### What it does not do

**Reserving is not paying.** Nothing in this system pays a dollar obligation, and the agent's
reasoning text says so in as many words: *"Their dollars are reserved, but nothing here pays
them."* Paying one needs an allocation rule that `SPEC.md` does not give — §17 scenario 16's
question — so the gap is narrowed, not closed.

## Also built: step 7

`TreasuryService.act(Decision)` — §11's *"only then create the instruction"*. Two properties
worth naming:

- **A refused decision produces nothing at all.** Not an instruction marked refused, not one in a
  skippable state. A rejection that leaves a row is a rejection somebody can undo by changing one
  column.
- **The external reference is `convert/<intent id>`**, derived rather than generated. Acting on
  the same decision twice therefore produces the same reference and hits the unique constraint —
  §13's mechanism doing the work instead of an application check. It is also traceable: a
  reference in a rail's records leads straight back to the intent that explains it, which is what
  F6 asks for.

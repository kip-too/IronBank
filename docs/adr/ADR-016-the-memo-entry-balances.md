# ADR-016 — The memo entry balances: spread against 6100, fee against Payables

**Date:** 2026-09-14 (day 5)
**Status:** accepted — resolves the three questions that were blocking day 5
**Decided by:** Claude, under Kurgat's instruction to decide and log rather than ask

## The problem

`SPEC.md` §10 says the FX engine outputs *"one balanced entry plus a separate memo entry"*, and
that the memo entry carries spread (6200) and fee (6210). `PROBLEM.md` §5 day 20 shows it as:

```
Memo posting — cost of conversion
    Spread (implicit)                  2,400
    Fee (explicit)                     7,139
```

Two debits, 9,539 in total, **and no credit side**. Invariant I2 says every entry balances in
shillings, with no exceptions. The chart in §6 contains no contra or memo account to absorb it.
So either the memo entry is exempt from I2, or something must be on the other side.

Three questions had to be answered before the engine could be written:

1. What is the credit side of the memo entry?
2. Spread and fee are different in kind — spread is not cash, and the fee is. Should one entry
   really carry both?
3. `PROBLEM.md` prints a fee of 7,139 where 0.9% of 793,200 is 7,138.80. Which is it?

## Decision 1 — the spread's other side is 6100, and it is a reclassification

**The spread is not additional money leaving. Nobody bills for it.** It is already inside the
exchange difference.

The business received shillings at 132.20 when the market middle was 132.60. The difference it
booked against carrying value — 4,200 — is therefore already 2,400 smaller than the market alone
would explain. Posting `Dr 6200 / Cr 6100` adds no cost. It **moves** one that was already
there, out of *what the market did to us* and into *what our provider took quietly*.

After both entries:

| Account | Holds | Means |
|---|---|---|
| 6100 | 6,600 credit | what the market did, measured honestly against mid |
| 6200 | 2,400 debit | what the provider took inside the rate |
| **net** | **4,200** | what actually happened |

That is exactly the separation `PROBLEM.md` §5 argues for — *"two different causes, two
different fixes"* — and it needs no account that §6's chart does not already have. Crucially it
also means **the memo entry balances like any other entry**, so I2 needs no exception.

## Decision 2 — the fee's other side is 2000 Payables

The fee **is** real money, unlike the spread. `PROBLEM.md` §5 has the bank receive the full
793,200 with no deduction, so the fee was not netted off the proceeds — it is owed to the
provider separately. `Dr 6210 / Cr 2000`.

**If a provider instead deducts its fee from the proceeds, that is a different fact and needs a
different entry**: the bank leg would be 793,200 − 7,138.80 = 786,061.20 and the credit would be
the bank, not payables. That is a property of the provider, not of this engine, and when a real
payout rail is modelled (day 10) it may well be the common case. Flagged rather than assumed.

Spread and fee still share one entry, as §10 says — but in four postings across four accounts,
so I4 holds: they are never combined into one number.

## Decision 3 — the fee is taken as charged, never derived

`SPEC.md` §10 lists the inputs and says **"Fee = as charged"**. So the fee is data, supplied by
whoever knows what the provider billed. The engine computes no percentage of anything, and the
7,139-versus-7,138.80 question **dissolves**: whatever the provider charges is what gets
recorded.

For the record, though, since §10 insists 7,139 must *"fall out of the engine"*: in cents,
0.9% of 79,320,000 is exactly **713,880**, with no rounding at all. That is KES 7,138.80.
**7,139 is reachable only by rounding to whole shillings, and §5 fixes KES at scale 2.** The
example's figure appears to be a display rounding. Nothing in the engine rounds a fee, because
nothing in it derives one.

## Decision 4 — spread is `(value at mid) − (value at executed)`

`SPEC.md` §10 defines spread as `(mid − executed) × dollars converted`. The engine instead
subtracts two round-once conversions. Arithmetically these agree to within a cent — and that
cent is the reason:

```
exchangeDifference + spread  ==  atMid − carryingValue
```

is true **exactly**, by construction, under this definition. Under the literal one it is true
*usually*, and the occasional stray cent would have nowhere to go except an invented entry.
A plug. Same class of reasoning as ADR-013: where the specification's literal formula and its
first principle disagree, the first principle wins, and the departure gets written down.

## Decision 5 — carrying value is proportional, not rate-then-multiply

In `WeightedAverageCarryingValue`. To find what 6,000 of 10,000 held dollars are carried at:

- **Rejected:** derive a blended rate, round it to scale 8, then multiply. Two roundings. Its
  real failure is at the boundary — when the *whole* wallet is converted, the result need not
  equal the carrying value, so emptying the wallet of dollars can leave a stray cent of shilling
  value behind it. A carrying value on a wallet holding nothing is a number with no meaning, and
  removing it later requires inventing an entry.
- **Chosen:** `round(carryingValue × leaving / balance)`. One rounding. When
  `leaving == balance` this is exactly `carryingValue`, **by arithmetic rather than by luck**.

Honesty about magnitude: at this business's scale the two methods agree almost always — the
divergence needs amounts around 10⁸ dollars to reach a single cent. The argument for the
proportional rule is not that it visibly differs day to day; it is that it makes the zero-out
exact by construction, so the failure cannot happen at all rather than happening rarely.
`FxEngineTest.emptying_the_wallet_leaves_nothing_behind` pins it with a non-terminating blend.

**Consequence, recorded:** the rate stored on the wallet posting is the weighted-average
carrying rate at scale 8, while the shilling figure comes from the proportional calculation. On
an awkward blend those two can differ by a cent — `functional_amount_minor` need not equal
`round(amount × rate_value)`. That is the accepted cost of never stranding carrying value, and
it is the lesser of the two evils.

## How to reverse any of this

- Spread's contra: change `FxEngine.costOfConversionEntry`. If it should not be a
  reclassification, the credit needs a new account, which needs a migration and an answer to
  what that account means.
- Fee netted rather than billed: change the bank leg in `movementEntry` and the fee's contra.
- FIFO instead of weighted average (O2): write a second `CarryingValuePolicy` and change one
  bean. Nothing else in the system asks about carrying value.

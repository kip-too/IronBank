# ADR-013 — Per-currency balance applies only to single-currency entries

**Date:** 2026-09-14 (day 3)
**Status:** accepted — **this one overrides the literal text of `SPEC.md` §8 and I2**
**Decided by:** Claude, under Kurgat's instruction to decide and log rather than ask

## The problem

`SPEC.md` §8 lists as the first thing the ledger must reject:

> postings do not sum to zero within any single currency

and I2 states: *"Entries balance per currency and in shillings."*

Implemented literally, that rule **rejects every single entry in `PROBLEM.md` §5.** Not one of
the four — it rejects all four.

| Entry | USDC sums to | KES sums to | Shillings sum to |
|---|---|---|---|
| Day 1, invoice raised | **+10,000** | **−1,290,000** | 0 |
| Day 12, money arrives | 0 | **−25,000** | 0 |
| Day 20, payroll conversion | **−6,000** | **+789,000** | 0 |
| Day 30, month-end revaluation | 0 | **+2,800** | 0 |

The shilling column is zero every time. The per-currency columns are almost never zero, and
they cannot be: **a cross-currency entry exists precisely because value crosses currencies.**
Day 1 books a dollar receivable against shilling revenue. Day 20 turns dollars into shillings.
Neither can balance within a currency without inventing a clearing account to absorb the other
side — and §6's chart has no such account, so creating one would violate I3 and rule 1 at once.

`PROBLEM.md` §6 says I10 is the one to keep coming back to. The worked example is the acceptance
test (`SPEC.md` §10 says so outright: *"If one does not, the engine is wrong, not the example."*).
When a rule and the example contradict each other, the example is the evidence about what the
business actually does.

## The decision

I2 is implemented as two rules, not one:

1. **The shilling balance is universal.** Every entry, without exception, must sum to zero in
   `functional_amount_minor`. Enforced in `LedgerService` (`UnbalancedInShillings`) and again at
   commit by the `entry_must_balance()` constraint trigger in `V4`.

2. **The per-currency balance applies only when the entry is wholly in one currency.** A
   single-currency entry must sum to zero in that currency. A cross-currency entry is required
   to balance in shillings only. Enforced in `LedgerService` (`UnbalancedInCurrency`) and in
   `V4`.

## Why the second rule is kept at all rather than dropped

It would have been simpler to delete the per-currency check and keep only the shilling balance,
which is the universal accounting rule. It is kept because **it does work the shilling check
cannot**: two USDC postings can cancel in dollars while disagreeing in shillings, and the
reverse. `LedgerServiceTest.rejects_a_dollar_entry_that_balances_in_shillings_but_not_in_dollars`
is exactly that case — +10,000 USDC against −9,000 USDC, both legs carrying shilling values that
sum to zero. Only the per-currency check catches it.

So I2 keeps both halves, and both halves are live. What changed is the scope of the first.

## What this costs

- **It is a departure from the specification's literal text**, made by Claude rather than by
  Kurgat, and that is the real cost. It is recorded here, in `LedgerService`'s header, in
  `LedgerRejection.UnbalancedInCurrency`'s javadoc, and in `V4__entry_must_balance.sql`, so
  nobody meets the behaviour without meeting the reasoning.
- **A cross-currency entry can have a nonsense dollar leg and still post**, so long as the
  shilling values balance. Nothing today checks that a posting's `functional_amount_minor`
  follows from its own `rate_value` — that is the round-once conversion rule, day 4. Until day 4
  lands, this is a genuine hole and not a theoretical one.
- **The rule is now conditional**, and conditional rules are harder to hold in your head than
  absolute ones. Mitigated by naming: the two exceptions are different types, and each says in
  its message which case it is about.

## The alternative that was rejected, and why

**Route every cross-currency entry through an FX clearing account**, so that each currency does
balance:

```
Dr Bank KES 793,200        Cr FX clearing KES 793,200
Dr FX clearing USDC 6,000  Cr Wallet USDC 6,000
```

This is what several real systems do, and it would have preserved I2 word for word. Rejected
because the clearing account is not in `SPEC.md` §6's chart, and `CLAUDE.md` rule 1 forbids
inventing one. It would also restructure every entry in `PROBLEM.md` §5 away from how the
document writes them, which makes the worked example unusable as the acceptance test.

**If Kurgat prefers that shape, it is a real option** — it is more orthodox than what is built
here. It costs a new account, a migration, and rewriting the worked example's entries.

## How to reverse it

`LedgerService.rejectUnbalancedWithinASingleCurrency` has one early return
(`if (totals.size() != 1) return;`). Removing it restores the literal rule — and immediately
fails `LedgerServiceTest.AcceptsTheWorkedExample`, all three of them. That failure is the
evidence for this ADR, and it is reproducible in one line.

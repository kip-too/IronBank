# ADR-008 — Account 1200 is denominated USDC, not USD

**Date:** 2026-09-14 (day 2)
**Status:** accepted, and the one on this list most worth overturning
**Decided by:** Claude, under Kurgat's instruction to decide and log rather than ask

## The problem

`SPEC.md` §6 gives account 1200 Receivables the currency **USD**. `SPEC.md` §5 defines a minor
unit for **KES** and **USDC** only — there is no scale for USD anywhere in the specification.

That gap is not cosmetic. Invariant I2 requires every entry to balance *per currency* as well as
in shillings. The worked example in `PROBLEM.md` §5, day 12, is:

```
Dr  USDC wallet        1,315,000
    Cr  Receivable                1,290,000
    Cr  Exchange difference          25,000
```

In shillings that balances. Per currency, if USD and USDC are two codes, it does not: USDC is
+10,000 and USD is −10,000, and neither sums to zero. **As literally written, I2 rejects the
specification's own worked example.** One of the two has to give.

## The decision

The ledger has exactly two currency codes, **KES** and **USDC**. Account 1200 is denominated
USDC at scale 6. `SPEC.md` §6's "USD" for account 1200 is read as a label, not a third currency.

The deciding text is `PROBLEM.md` §4, which defines the foreign currency as: *"For us: **USD**,
held as **USDC**."* One foreign currency. USDC is the form it is held in, not a different money.

## Why not the alternatives

**Two codes, with an exception to I2 that treats USD and USDC as the same balance currency.**
Rejected. I2 is one of ten invariants and this project's argument is that the invariants are
absolute. Carving an exception into the balance check — the check that makes double-entry mean
anything — to accommodate a naming inconsistency is a bad trade at any price. It also puts a
special case in the hottest path in the system, where every future reader has to hold it in
their head.

**Add USD as a third currency with scale 2.** Rejected harder. The scale would be invented, and
`CLAUDE.md` rule 2 forbids exactly that. It would also make every receivable settlement a
cross-currency event needing a USD→USDC rate that nobody quotes and no adapter supplies.

## What this costs

**The real cost:** if the business is ever paid an invoice by bank wire in actual US dollars
rather than in USDC, this collapses. Two holding forms of the same currency would then exist,
and calling the bank balance "USDC" would be a lie in the chart of accounts.

**The escape route, if that happens:** the *account* distinguishes the form, not the currency
code. A future `1150 Bank — USD` would carry the same currency code and the same scale, with
the account name carrying the distinction. That is already how 1000 and 1100 differ. So the fix
is a migration adding an account, not a redesign — which is the main reason this is affordable.

**The smaller cost:** anyone reading `SPEC.md` §6 and then the chart finds a discrepancy. It is
recorded in `V2__chart_of_accounts.sql`, in `Currency`, and here.

## How to reverse it

`CurrencyTest.only_the_two_currencies_spec_section_5_defines_a_minor_unit_for_exist` asserts the
enum has exactly two values, so adding a currency breaks a test rather than slipping through. If
Kurgat rules the other way, the change is: add the enum constant with its scale, update that
test, update the `account_currency_known` and `posting_currency_known` check constraints by
migration, and write the I2 exception explicitly with its own test.

**This is the decision most likely to be wrong, because it resolves a contradiction in the
specification rather than filling a gap in it.**

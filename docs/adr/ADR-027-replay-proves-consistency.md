# ADR-027 — Replay proves consistency, not correctness — and says so

**Date:** 2026-09-15 (day 13)
**Status:** accepted — **answers `PROBLEM.md` §8 question 8**

## The problem with §14 as written

`SPEC.md` §14: *"reads the journal from the beginning and reconstructs balances, then compares
with the stored balances."*

**There are no stored balances.** `Balances` derives every figure by summing postings, deliberately
(ADR-009): *"a stored balance is a second answer to a question that already has one"*, and the
second answer is the one that goes wrong quietly.

So §14 taken literally would have replay compare `sum(postings)` with `sum(postings)` and call the
tautology a pass. A green tick meaning nothing is worse than no tick.

## What replay does instead, and what each part is worth

1. **An entry-by-entry fold, checked against the aggregate query.** A Java fold against a
   PostgreSQL `sum()` — two genuinely different readers of the same rows. Weak on its own, but a
   real disagreement if it ever fires.
2. **Every entry re-checked rather than trusted.** Balance in shillings, and per currency where
   single-currency. The ledger checked these when they were written; this asks the same question
   of what is *stored now*, which is a different question. The test that proves it means something
   disables the append-only trigger as a superuser — the one route ADR-010 records as still open —
   corrupts a posting, and watches replay name the entry.
3. **The strong one: proof the answer depends on nothing outside the journal.** `Replay` reads
   `journal_entry` and `posting` and no other table.
   `the_rate_feed_can_be_deleted_and_replay_is_unchanged` deletes every rate and replays to the
   same figures. §14: *"The moment replay reaches outside the records, it stops proving anything."*
   The only way to prove it does not is to take the outside away.

## The answer to §8 question 8

> *"Replay reproduces the numbers. Does replay prove correctness, or only consistency?"*

**Consistency and reproducibility. Not correctness.**

Replay cannot tell you the rate on the 12th was really 131.50. It can tell you that whatever rate
was used is still on the posting, that the entry balanced then and balances now, that nothing has
been edited since, and that September's closing position can be produced in March without anyone
remembering anything.

Correctness of the *inputs* is what the rate source, the reconciler and a person are for. That is
a smaller claim than "replay proves the books are right", and it is the one that is true.

## Two decisions in the reconciler worth recording

**`ON CONFLICT DO NOTHING`, not a caught exception.** The obvious way to make reconciliation
idempotent is to catch `DuplicateKeyException` and carry on. **On PostgreSQL that does not work
inside a transaction**: the violation aborts the whole transaction and every later statement fails
with `25P02 current transaction is aborted`. The second run poisoned itself on its first
already-known item and reported nothing about anything after it. Found by a test.

`ON CONFLICT` keeps the unique constraint as the mechanism — the database still decides — while
leaving the transaction usable. The affected-row count is the answer.

**Ageing is derived and `first_seen` is immutable at the database.** Same reasoning as ADR-017's
OVERDUE: a stored age is true until the next day and then false, silently. And if re-running
reconciliation could re-date an item, **an item could stay young for ever by being looked at
often** — which would defeat the ageing precisely for the items it exists to expose. V11's trigger
refuses to move `first_seen`.

**Business days exclude weekends; Kenyan public holidays are not modelled.** O3 says "2 business
days" and a holiday calendar cannot be written from memory (rule 1). The effect is that an item
can cross the threshold a day or two early around a holiday — erring towards showing somebody a
question sooner, which is the safe direction.

## What is deliberately not built

- **No automatic resolution.** `resolve` takes a name and an explanation and refuses a blank for
  either. An item that closed itself would be an item nobody answered.
- **No matching of receipts to invoices.** §13's diagram is about instructions — money out.
  Money in has its own three-way match and it is not built.
- **A foreign-currency unmatched item raises a ReconItem but posts nothing to suspense**, because
  account 1900 is a shilling account. Real gap, recorded in the code.
- **Replay holds the whole journal in memory.** Fine at this size; a longer journal needs a
  cursor, and the fold itself would not change.

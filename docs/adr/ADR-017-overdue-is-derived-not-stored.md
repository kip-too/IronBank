# ADR-017 — OVERDUE is derived, not stored

**Date:** 2026-09-14 (day 6)
**Status:** accepted

## The problem

`SPEC.md` §6 gives an obligation a `status` column. `SPEC.md` §7 draws four states:

```
   SCHEDULED → FUNDED → PAID
        │
        └────→ OVERDUE   (due date passed, no funds allocated — this is a bug
                          in the agent's planning, and must be visible as one)
```

The obvious reading is four values in one column. The parenthesis is what argues against it.

## The decision

**Three states are stored — `SCHEDULED`, `FUNDED`, `PAID`. `OVERDUE` is worked out.**

`Obligation.stateOn(LocalDate)` returns the full four-state machine; the date comes from the
injected clock. `ObligationRepository.overdue()` is a `where status = 'SCHEDULED' and due_date <
?` query. The database `check` constraint **refuses to store the string `OVERDUE` at all**, and
there is a test proving it.

Two enums rather than one: `ObligationStatus` is what is stored and what transitions,
`ObligationState` is what is true when you look. An enum holding a value that can never be
written is a trap for whoever writes the next repository method.

## Why

**A stored status is only true once something has run and set it.** So a stored `OVERDUE` is
absent exactly when the sweeper that sets it has failed — which is the precise moment §7 says the
bug "must be visible as one". The design would hide the failure it exists to expose.

Derived, it cannot go stale, there is no job to forget to run, and an obligation is overdue the
instant its date passes. It is also testable by moving the clock rather than by waiting, which
is the entire reason §4 makes the clock an adapter.

## Details that fell out of §7's own diagram

- **The `OVERDUE` arrow comes off `SCHEDULED`, not `FUNDED`.** A funded, unpaid obligation past
  its date is an operational matter; unfunded and past due is a planning failure. Followed
  literally, and there is a test for it.
- **Due *today* is not overdue.** §7 says "due date passed". Due today and unfunded is urgent,
  and invariant I8 is what catches it on day 8.

## What this costs

- **You cannot `select ... where status = 'OVERDUE'`.** Every question about overdue has to go
  through the repository or carry the date. Worth it, and it forces the caller to say *as at
  when*, which is the right question.
- **No record of when something became overdue**, because there is no event. If that history is
  ever needed it is a genuinely new thing, not a column.

## Three smaller decisions made the same day

**Receivable has two states, `OUTSTANDING → SETTLED`.** §7 gives it no state machine at all, so
these are the minimum the documents evidence: `PROBLEM.md` §5 raises an invoice on day 1 and
settles it on day 12. **There is deliberately no `OVERDUE` for a receivable** — §6 gives it an
`issue_date` and no due date, so lateness is not expressible. Adding one would be inventing a
payment term nobody stated. What this system can honestly say about an invoice is its age.

**A due date in the past is accepted** (§17 scenario 13). It is immediately `OVERDUE` and says
so. Refusing would refuse to record something true, and hiding a real overdue debt is worse than
having one.

**Only `status` may change.** The database trigger rejects any update to amount, due date, name
or currency. The agent plans against those numbers; changing them underneath it would make the
intent log a record of a decision about figures that no longer exist.

## What is deliberately not built

- **No partial funding or partial payment.** Three states, no fractions. §17 scenario 16 — "two
  obligations due the same day, funds enough for one and a half" — is a question about what the
  *agent* does, and lands on day 8.
- **No link from an obligation to the funds that funded it**, or from a receivable to the entry
  that raised it. §6 gives these records five fields each and none is a reference. Matching is
  the reconciler's job on day 13, through source references.
- **Nothing posts to the ledger.** `PROBLEM.md` §5 day 1 shows `Dr Receivable / Cr Revenue`, and
  building that means deciding which rate a receivable is raised at. `PROBLEM.md` says "rate that
  day" and the mid-rate table has it, but `SPEC.md` states no rule — so it is flagged, not
  guessed. **The demo on day 12 will need this wiring.**
- **No status history.** The journal is where history lives.

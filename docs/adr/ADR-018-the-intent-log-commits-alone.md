# ADR-018 — The intent log commits in its own transaction, and stores `json` not `jsonb`

**Date:** 2026-09-14 (day 7)
**Status:** accepted

## Decision 1 — `append()` runs in `REQUIRES_NEW`

`SPEC.md` §11 steps 6 and 7: *"Write the intent … **and commit it**. Only then create the
instruction. Steps 6 and 7 are in that order and the order is not negotiable."*

The obvious implementation — an agent method annotated `@Transactional` doing both — **defeats
the requirement entirely.** Both writes would join one transaction, so a failure creating the
instruction rolls the intent back too, and the system is left with no record that anything was
ever decided. That is the exact failure the ordering exists to prevent.

So `IntentLog.append` declares `Propagation.REQUIRES_NEW`. It commits on its own, whatever the
caller is doing. A crash between steps 6 and 7 then leaves **a decision with no action:
recoverable and visible.** The reverse — an action nobody can explain — is F6.

**Cost:** the intent is durable even when the action it proposed never happens, so the log will
contain decisions with no consequence. That is the intended shape, not litter, and
`a_decision_with_no_action_is_a_legitimate_end_state` pins it.

## Decision 2 — the snapshot columns are `json`, not `jsonb`

`jsonb` is the usual default and is the wrong choice here. It decomposes the document, **reorders
its keys and drops duplicates** — it stores what the JSON *means*.

This is an evidence log. The point of a snapshot is to support the claim *"this is what the agent
saw"*, byte for byte — not *"this is something equivalent to what the agent saw"*. `json` stores
the exact text it was given and still validates well-formedness, so malformed evidence is refused
by the database rather than stored unreadable.

**Cost:** no GIN index, and querying inside the document is slower. Nothing queries inside these
documents — they are read whole, by a person, when a decision is being questioned.

## Decision 3 — `instruction` is created on day 7, deliberately incomplete

Day 7's gate is *"intent cannot be written after its instruction"*, which is an inherently
relational claim: it cannot be proven without the other table. `SPEC.md` §9 names the mechanism
precisely — *"a foreign key that is NOT NULL"*.

So `V7` creates `instruction` with only the columns its two constraints need:

- `intent_id NOT NULL REFERENCES intent(id)` — invariant **I9**. An instruction cannot be
  inserted until its intent row is committed, so the ordering is structural rather than a
  convention somebody follows.
- `external_ref UNIQUE` — **F5**. §13 calls this *"the single most important constraint in the
  schema and … what makes silent double payment impossible rather than unlikely."*

Type, amount, currency, state and attempts arrive on day 9 with the state machine, and the
migration says so in its header.

**This is in tension with `SPEC.md` §1 rule 5, "one module per turn, finished."** A table that
needs five more columns is not finished. The trade was: prove day 7's stated guarantee today
against the real constraint, or defer the day's headline to day 9 and only assert it. Proving it
won. The tension is recorded rather than smoothed over.

## Decision 4 — `?::json` casts rather than the driver's `PGobject`

A plain `String` parameter will not go into a `json` column. The two ways out are the
PostgreSQL driver's own `PGobject`, or an SQL cast.

`PGobject` was tried first and does not compile: the driver is `runtime` scoped in `pom.xml`, and
that scoping is correct — application code should not import driver classes. Widening the scope
to make one insert convenient would have been the wrong repair. The cast keeps both the driver
and its types out of the application, and the column still validates the JSON.

## What is deliberately not built

- **No link from an intent to its outcome.** §17 scenario 6 asks what the record should say when
  the reasoning was wrong but the outcome was good. This design answers by keeping them apart:
  the intent says what was believed, the journal says what happened, and the reconciler puts them
  side by side on day 13. Neither is allowed to edit the other.
- **No deserialisation of snapshots.** Turning evidence back into live objects invites the "just
  re-read the data" shortcut that the copy exists to prevent.
- **No schema for what a snapshot contains.** That is the agent's business, on day 8. The log
  guarantees only that the evidence is well-formed, stable and complete.
- **No retention policy.** The log grows forever, which is right for an audit record.

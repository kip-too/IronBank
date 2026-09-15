# ADR-010 — Immutability by a trigger that raises, not by a PostgreSQL RULE

**Date:** 2026-09-14 (day 2)
**Status:** accepted

## The problem

`SPEC.md` §6: *"The database enforces this with a rule that rejects updates on the table, not
with a code convention."* PostgreSQL has a feature literally called `CREATE RULE`. Taking the
sentence at its word would mean:

```sql
CREATE RULE posting_no_update AS ON UPDATE TO posting DO INSTEAD NOTHING;
```

## The decision

A `BEFORE UPDATE OR DELETE ... FOR EACH ROW` trigger that raises an exception, plus a
`BEFORE TRUNCATE ... FOR EACH STATEMENT` trigger that does the same. Both on `posting` **and**
on `journal_entry`.

## Why not the RULE

`DO INSTEAD NOTHING` **discards the UPDATE silently**. The caller is told the statement
succeeded, the row is unchanged, and nothing anywhere records that someone tried to rewrite
history. A project whose first principle is *"a total that does not agree is a finding"*
(`SPEC.md` §1 rule 3) cannot have its most important integrity rule work by quietly swallowing
writes. The refusal has to be audible.

The trigger raises with `errcode = restrict_violation` and a message that names the table, the
operation, and what to do instead: *"A correction is a new entry that reverses and re-posts,
never an edit."* A refusal that only says no teaches nothing at 2am on day 12.

## Why TRUNCATE is blocked too

**`TRUNCATE` does not fire row-level `DELETE` triggers.** A table protected against only UPDATE
and DELETE is still erasable in its entirety by one statement. Blocking UPDATE and DELETE while
leaving TRUNCATE open would be a lock on a door standing in an open field.

## Why `journal_entry` is locked too, though the spec only names `Posting`

`business_date` and `source_ref` live on the entry, and `business_date` decides which month
every one of that entry's postings falls into. Freezing the amounts while leaving the date
editable protects the part that is hard to get wrong and exposes the part that matters at
month end.

**Cost:** a typo in `description` can never be fixed in place, only by a reversing entry. That
is the same discipline the postings already live under, so it is consistent rather than merely
strict.

## What this costs in the tests

`TRUNCATE` was how the test database was going to be cleaned — `SPEC.md` §3 says so explicitly.
It cannot be any more. `AbstractDatabaseTest` therefore runs `Flyway.clean()` then `migrate()`,
which is DDL and so is allowed.

Slower than a truncate, and it runs per test rather than per class.

**Measured on day 9**, with nine migrations and 217 tests:

```
clean = 210-700 ms      migrate = 424-1116 ms      total = 635-1814 ms per reset
```

That is roughly three of the suite's five minutes. Two things were tried and reported rather than
quietly assumed:

- **Disabling Flyway's checksum validation on the test profile** (`validate-on-migrate: false`)
  made almost no difference - 658 ms against 635 ms. The cost is the DDL itself, not Flyway's
  bookkeeping. Reverted, because it weakens a real check for no gain.
- **Resetting once per test class instead of per test** would cut ~217 resets to ~15 and save
  most of those three minutes. **Not taken.** Many tests assert absolute counts (`count() == 0`,
  `containsExactly` over a whole table), so sharing a database between tests in a class would
  make them order-dependent - trading a property that catches real bugs for build speed.

So the cost stands, with numbers attached, and **the rule remains: if this becomes the reason the
build is slow, the fix is a faster clean, never a softer trigger.** The next lever worth trying
is a template database (`CREATE DATABASE ... TEMPLATE`), which needs the connection pool closed
between tests and may well cost more than it saves.

The alternative was letting test code call `ALTER TABLE ... DISABLE TRIGGER`. Rejected: that
puts the ability to switch off immutability into the codebase, where someone eventually finds it
and uses it in anger.

## Known limits, recorded rather than hidden

- **A superuser can `ALTER TABLE ... DISABLE TRIGGER`.** This application currently connects as
  the `postgres` superuser, so that route is open. Closing it means running as a role that does
  not own these tables, plus `REVOKE UPDATE, DELETE, TRUNCATE`. Deferred, not done.
- **A `BEFORE UPDATE ... FOR EACH ROW` trigger only fires for rows the statement matches**, so
  an `UPDATE ... WHERE` that matches nothing returns 0 rows affected rather than an error. It
  changes nothing, because it changes nothing — but it is asserted in
  `PostingImmutabilityTest.update_matching_nothing_is_still_refused_when_rows_exist` so that the
  behaviour is a recorded property and not a surprise.
- **`DROP TABLE` is not blocked**, and cannot usefully be. Schema change is Flyway's job.

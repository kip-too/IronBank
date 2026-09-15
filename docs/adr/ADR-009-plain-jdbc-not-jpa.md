# ADR-009 — Plain JDBC, not JPA or Hibernate

**Date:** 2026-09-14 (day 2)
**Status:** accepted

## The problem

`SPEC.md` §3 names PostgreSQL and Flyway but no persistence style. Day 2 needs one.

## The decision

`JdbcTemplate` with hand-written SQL. No JPA, no Hibernate, no Spring Data repositories.
`spring-boot-starter-jdbc` only — which is not a new dependency decision under `SPEC.md` §1 rule
6, since it ships inside Spring Boot.

## Why

**Hibernate's central mechanism is the thing this schema forbids.** It manages mutable entities
and issues UPDATE statements on its own initiative when dirty checking notices a change at flush
time. Postings are immutable, and migration V3 has the database reject UPDATE outright. An ORM
whose default behaviour triggers a database-level refusal is fighting the design, and the fights
would surface as `restrict_violation` at flush — far from the code that caused them.

**The constraints are the product.** This project's claim is that correctness lives in the
database: the unique constraint on `external_ref`, the I1 check, the append-only trigger, the
composite foreign key on `(account_code, currency)`. A first-level cache and a flush queue sit
between the code and those constraints and decide *when* they are checked. Here, "when" should
always be "now, at the statement I wrote".

**Auditability is the feature.** `I10` asks that a reviewer reproduce a day's closing position
from the records alone. Every statement this system issues is visible in the source. Nothing is
generated.

## What this costs

- **Boilerplate.** Row mappers and column lists by hand. `JournalStore` is longer than the
  equivalent annotated entity would be, and every new column is edited in three places.
- **No lazy loading, no identity map.** `JournalStore.findAll()` currently issues one query per
  entry for its postings — an N+1 that an ORM would have solved for free. It is fine at ten
  entries and wrong at ten thousand. Replay (day 14) will need a single joined query with a
  cursor, and that is noted in the class header rather than left to be discovered.
- **No free schema validation.** Nothing checks at startup that the Java types still match the
  columns. The integration tests are what catches drift, so they have to keep running against
  the real database.

## What would change this

If entity graphs get deep enough that hand-mapping becomes the bug source rather than the safety
feature. Nothing in `SPEC.md` §2's scope suggests they will — there are ten accounts, and the
biggest object is an entry with four lines.

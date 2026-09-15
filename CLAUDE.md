# CLAUDE.md

Read `PROBLEM.md` then `SPEC.md` before writing any code. Everything below is a summary of those two, kept short because it is loaded into every session.

## What this is

A treasury and bookkeeping layer for a Kenyan business that earns dollars and spends shillings. Shillings are the functional currency. The point of the project is that exchange difference, provider spread and explicit fee are three separate facts that most systems blend into one meaningless number.

## The split

Kurgat decides the financial rules and writes the adversarial tests. You write the code and its unit tests. If a rule you need is not in `SPEC.md`, **ask — do not choose one.**

## Seven rules

1. **Never invent an identifier.** Library methods, API endpoints, contract addresses, chain IDs, config keys, table names. Unverified things get marked `TODO[unverified]` in place with a note on how to confirm them.
2. **Never invent a financial rule.** Rounding, ordering, signs, which account to post to. If `SPEC.md` does not say, stop and ask.
3. **No plugs.** Nothing exists to make a total agree. A total that does not agree is a finding.
4. **No floating point for money.** Integer minor units everywhere — calculations, DTOs, JSON, test fixtures.
5. **One module per turn, finished.** Code, tests and migration together. Then stop.
6. **Ask before adding a dependency.**
7. **End every turn with what is incomplete, assumed, or unverified.**

## Things that are easy to get wrong here

- Round once, at the end, `HALF_UP`. Never round an intermediate value.
- Residue from a split goes to the last part, never spread proportionally.
- A posting's shilling value is stored, not recomputed on read.
- `AWAITING_RESOLUTION` is not failure and is never retried. Make the retry unrepresentable in that state, not merely unused.
- The intent is written and committed **before** the instruction is created.
- Nothing calls `now()`. The clock is injected.
- Postings are immutable, enforced at the database.
- Uniqueness of an instruction's external reference is a database constraint. Application checks sit on top of it, never instead of it.

## Current state

Day 0. Nothing built. Start at `SPEC.md` §15, day 1.

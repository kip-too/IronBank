# ADR-021 — MANUAL_REVIEW gets an exit, through a named person

**Date:** 2026-09-15
**Status:** accepted — closes the gap ADR-020 flagged

## The problem

`SPEC.md` §7 draws `MANUAL_REVIEW` with arrows in and **no arrow out**. Day 9 built that
literally, on the grounds that inventing a resolution path would be inventing the one rule the
state exists to hand to a person.

That was the wrong call, and ADR-020 flagged it as a gap the same day. Built literally, the state
is a **trap**: anything reaching it stays there for ever, including on demo day, and §7's own
words for it — *"a human must look"* — imply that looking leads somewhere.

## The decision

Two exits, the same two `AWAITING_RESOLUTION` has, because a resolution is a resolution however
it is reached:

- `resolvedByHumanAsSettled(at, who, evidence)` → `SETTLED`
- `resolvedByHumanAsFailed(at, who, evidence)` → `FAILED`

Both **require a name and evidence**, and refuse a blank for either:

> *"A manual resolution needs a name against it. The whole reason this state exists is that a
> person took responsibility for the answer."*

That is the part worth keeping. The state is not "the software gave up" — it is "a person
decided", and a decision with nobody's name on it is the same anonymous record that F6 is about.

## There is still no retry from MANUAL_REVIEW

Deliberately. A person who wants the payment sent again resolves it to `FAILED` first, stating
that it did not happen, and retries from there.

**Somebody has to put their name to "this did not happen" before anything is sent a second
time.** That is the same reasoning as I7: the danger is never retrying a known failure, it is
retrying an unknown one, and `MANUAL_REVIEW` is by definition unknown until a person resolves it.

The database enforces it too — `V9` refuses an attempt-count change on a `MANUAL_REVIEW` row with
its own message, separate from the `AWAITING_RESOLUTION` one, so the refusal says which rule
fired.

## What this costs

- **It is a second deviation from §7's diagram**, after ADR-020's `SUBMITTED → FAILED`. Both were
  made by Claude. The difference is that this one closes a state that cannot be left, which is a
  defect rather than a design choice.
- **`who` is a free-text string.** There are no user accounts — §2 puts them out of scope — so
  nothing verifies the name. It is a record of a claim, not an authenticated identity, and
  `REAL_VS_SIMULATED.md` should say so rather than let the field imply more than it is.

# ADR-012 — The business-date zone is configuration, and it is `Africa/Nairobi`

**Date:** 2026-09-14 (day 1, recorded day 2)
**Status:** accepted, needs Kurgat's ratification

## The problem

`ClockPort.businessDate()` turns an instant into the calendar day a transaction belongs to. That
needs a zone, and **`SPEC.md` does not state one.**

It is not a technical detail. `2026-09-30T22:30:00Z` is 30 September in UTC and 1 October three
hours east — either side of a month-end revaluation. The zone decides which month a posting
falls in, which decides which figures a month-end close produces.

## The decision

- **No default anywhere in Java.** `ClockPort` requires the zone in the constructor;
  `ClockConfig` reads the required property `shilingi.clock.zone`; a missing value stops
  application startup.
- **`application.yml` carries `Africa/Nairobi`**, with a comment saying it is a decision and not
  a default.

## Why the split

A hardcoded fallback would be a financial rule nobody chose, sitting invisibly in a config
class. Putting the value in `application.yml` makes it a line somebody can read, review and
change, and makes its absence a loud failure rather than a quiet substitution.

`Africa/Nairobi` is the obvious value: the business is Kenyan, the functional currency is the
shilling, payroll and statutory deadlines are Kenyan calendar dates. It is recorded as a
decision anyway, because "obvious" is how unexamined rules get into books.

## What this costs

- **A missing property is a startup failure**, not a degraded mode. Intended.
- **`ZoneId.of` is called on a string from configuration**, so a typo fails at startup with
  `ZoneRulesException`. Also intended, and better than the alternative of silently falling back.
- **Every date in the system now depends on one configuration value.** Changing it later would
  silently re-date nothing already stored — `business_date` is a stored `date` column, and
  migration V3 makes it immutable — but it would change how *new* instants are dated. There is
  no test that would catch the discrepancy, because both old and new rows are individually
  correct. **A change to this value after go-live is a data event, not a config tweak.**

## What is deliberately not built

- **No business-day calendar.** Weekends and Kenyan public holidays are not modelled.
  `SPEC.md` O3 assumes a reconciliation ageing threshold of *"2 business days"*, which needs
  that calendar. It is not guessed at; `ClockPort`'s header records the gap.
- **No handling of the clock moving backwards.** `MutableClock` permits it so that `SPEC.md`
  §17 scenario 15 stays testable, but nothing yet reacts to it.

## Verification

`ClockPortTest.the_configured_zone_id_resolves_on_this_jvm` asserts only that the identifier is
real on this JVM's tz database. It deliberately makes no claim about the offset, and it is not a
ratification of the choice. The business-date tests use a fixed `ZoneOffset.ofHours(3)` so they
test the derivation rule rather than smuggling in a claim about any country.

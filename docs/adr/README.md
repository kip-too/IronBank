# Architecture decision records

One page each. What was decided, why, and what it costs — including the decisions that are
probably wrong, which are marked as such rather than smoothed over.

## Written

| ADR | Decision | Day | Status |
|-----|----------|-----|--------|
| [001](ADR-001-shillings-are-functional.md) | Shillings are the functional currency | 14 | accepted |
| [002](ADR-002-four-accounts-not-one.md) | Exchange difference, spread and fee are four accounts | 14 | accepted — the central claim |
| [003](ADR-003-weighted-average-carrying-rate.md) | Weighted average, not FIFO (O2) | 14 | assumed default — **Kurgat's to overturn** |
| [004](ADR-004-awaiting-resolution.md) | Why AWAITING_RESOLUTION exists and is never retried | 14 | accepted |
| [005](ADR-005-settlement-is-a-separate-process.md) | The settlement leg is a separate process, in another language | 14 | accepted |
| [006](ADR-006-the-payout-leg-is-mocked.md) | The payout leg is mocked — the regulatory boundary | 14 | accepted — a design constraint |
| [007](ADR-007-the-residue-rule.md) | The residue rule: the difference goes to the last part | 14 | accepted |
| [008](ADR-008-one-foreign-currency-code.md) | Account 1200 is denominated USDC, not USD | 2 | accepted — **most likely to be wrong** |
| [009](ADR-009-plain-jdbc-not-jpa.md) | Plain JDBC, not JPA or Hibernate | 2 | accepted |
| [010](ADR-010-immutability-by-raising-trigger.md) | Immutability by a raising trigger, not a PostgreSQL RULE | 2 | accepted |
| [011](ADR-011-rates-are-numeric-money-is-bigint.md) | Money is `bigint` minor units; a rate is `numeric(20,8)` | 2 | accepted |
| [012](ADR-012-business-date-zone.md) | The business-date zone is configuration, `Africa/Nairobi` | 1 | accepted — needs ratification |
| [013](ADR-013-balance-per-currency-only-for-single-currency-entries.md) | Per-currency balance applies only to single-currency entries | 3 | accepted — **overrides §8/I2 as written** |
| [014](ADR-014-over-precise-rates-are-refused.md) | A rate with more than eight decimals is refused, not rounded | 4 | accepted |
| [015](ADR-015-a-missing-rate-is-reported-not-decided.md) | A missing rate is reported, not decided | 4 | accepted — question answered by 019 |
| [016](ADR-016-the-memo-entry-balances.md) | The memo entry balances: spread against 6100, fee against Payables | 5 | accepted — **five decisions, resolves the day-5 blockers** |
| [017](ADR-017-overdue-is-derived-not-stored.md) | OVERDUE is derived, not stored | 6 | accepted |
| [018](ADR-018-the-intent-log-commits-alone.md) | The intent log commits alone; `json` not `jsonb` | 7 | accepted — **one item in tension with rule 5** |
| [019](ADR-019-a-missing-rate-does-not-excuse-an-unfunded-obligation.md) | A missing rate does not excuse an unfunded obligation | 8 | accepted — **answers ADR-015's open question** |
| [020](ADR-020-retry-is-a-compile-error.md) | The retry is a compile error; SUBMITTED gains an arrow §7 omits | 9 | accepted — **one deliberate deviation from §7** |
| [021](ADR-021-manual-review-needs-an-exit.md) | MANUAL_REVIEW gets an exit, through a named person | 9+ | accepted — closes ADR-020's gap |
| [022](ADR-022-in-flight-money-and-reserved-dollars.md) | Money in flight, and dollars owed in dollars | 9+ | accepted — **fixes two defects** |
| [023](ADR-023-what-the-naive-panel-does.md) | What the demo's naive panel does (O8) | 9+ | accepted — answers §20's hardest open item |
| [024](ADR-024-payout-mock-shape-and-delivery.md) | The payout mock: O4 answered no; delivery is pumped | 10 | accepted |
| [025](ADR-025-the-chart-cannot-express-an-obligation.md) | The chart cannot express an obligation, so payouts are not posted | 11 | accepted — **a finding needing Kurgat's answer** |
| [026](ADR-026-the-demo.md) | The demo: one opening balance, and a second hole in the chart | 12 | accepted |
| [027](ADR-027-replay-proves-consistency.md) | Replay proves consistency, not correctness — and says so | 13 | accepted — **answers PROBLEM.md §8 q8** |

## Two findings that need Kurgat's answer

Neither is a design choice. Both are gaps in `SPEC.md` §6's chart of accounts, found by trying to
post real entries:

1. **No expense account**, so an obligation cannot be accrued and a payout cannot be posted —
   [ADR-025](ADR-025-the-chart-cannot-express-an-obligation.md).
2. **No equity account**, so a balance cannot be brought forward —
   [ADR-026](ADR-026-the-demo.md), decision 2.

The question both turn on: is §6's chart a **minimum that may be extended**, or a **complete set**?
§6 calls it a "minimum set", which reads like the former — but every account in it is justified by
an invariant, and neither of these is. Either answer is fine. Guessing between them is not.

## Smaller decisions, logged where they are made rather than here

Some choices are not worth a page but still should not be silent. They are recorded as comments
at the point of the decision:

- **Account code as primary key**, no surrogate id — `V2__chart_of_accounts.sql`
- **`created_at` has no `DEFAULT now()`**, because a default is the database calling `now()` on
  the application's behalf — `V3__journal.sql`
- **A posting must match its account's currency**, enforced by a composite foreign key rather
  than a trigger — `V3__journal.sql`
- **`source_ref` is nullable**, because `SPEC.md` §6 lists it without requiring it —
  `V3__journal.sql`
- **`bigint generated always as identity`** rather than UUIDs, so the journal has a stable total
  order for replay — `V3__journal.sql`
- **The rate is three flat fields on `Posting`, not a `Rate` object**, following `SPEC.md` §6
  literally — `Posting.java`
- **Single-segment Maven `groupId`**, claiming no domain nobody owns — `pom.xml`
- **Maven over Gradle** (O1), because `SPEC.md` §15 and §16 both define done as `mvn test` green
- **The order the ledger checks rules in**, fixed so a bad entry always reports the same rule:
  meaningless before wrong — `LedgerService.java`
- **I2 enforced again at commit by a deferred constraint trigger**, so no route into the table
  can write an unbalanced entry — `V4__entry_must_balance.sql`
- **The test clock is pinned**, so a fixture dated 30 September is in the past in March too —
  `PinnedClockTestConfig.java`
- **Seven exceptions in one file as nested classes**, because the rules are a family and read
  best together — `LedgerRejection.java`
- **A rate converts in one direction only** (`toShillings`), because SPEC.md §4 defines a rate
  as shillings per dollar and the inverse needs a second rounding rule — `Rate.java`
- **The residue rule is documented at the line that applies it**, as §5 asks — `Money.split`
- **The seeded rate table has gaps on purpose**, because filling them removes the only route to
  §17 scenario 7 — `V5__mid_rates.sql`
- **Obligations and receivables are mutable where the journal is not**, because they have state
  machines; the triggers allow status to move and nothing else — `V6__receivables_and_obligations.sql`
- **Two enums for the obligation machine** (stored vs observed), so no enum holds a value that
  cannot be written — `ObligationStatus` / `ObligationState`
- **`Snapshot` sorts its keys**, so the same facts always serialise to the same bytes and a
  reviewer diffing two intents sees only real differences — `Snapshot.java`
- **The inverse conversion lives in the agent, not on `Rate`**, because "round up to whole
  dollars" is §11's planning rule rather than a fact about rates — `FundingPlan.java`
- **The guard runs before the intent is written**, so the intent records the refusal as well as
  the proposal — `TreasuryAgent.judgeAndRecord`
- **Horizon and buffer are configuration** (O6: 35 days, 10%), with no Java fallback, for the
  same reason as the clock zone — `application.yml`

# SPEC.md — build specification

**Read `PROBLEM.md` first. This document is meaningless without it.**

**Project working name:** `shilingi` (rename before first public commit — that decision is not made yet)
**Window:** 27 September – 10 October 2026, fourteen days.
**Licence:** to be decided before the repo goes public. Default assumption: MIT.

---

## 0. How this document is used

Kurgat writes the thinking and the tests. Claude writes the code.

That split is deliberate and it is the reason this document is so specific. If Claude has to guess at a financial rule, the split has failed — the guess will be plausible, wrong in a small way, and expensive to find later. So: **everything Claude needs to decide has been decided here, and everything that has not been decided is marked `OPEN`.**

| Who | Does |
|-----|------|
| Kurgat | Decides the financial rules. Writes the adversarial test scenarios. Breaks things. Answers `OPEN` items. Reviews every posting the system produces against hand-worked figures. |
| Claude | Writes production code and unit tests. Refuses to invent rules. Raises a question instead of guessing. Keeps the build green. |

---

## 1. Hard rules for Claude

These outrank every other instruction in this file, including the schedule.

1. **Never invent an identifier.** Not a class name from a library, not a method on an SDK, not an API endpoint, not a config key, not a contract address, not a chain ID. If it was not read from actual documentation or actual source in this session, write it as `TODO[unverified]` with a one-line note on how to confirm it, and keep going around it.
2. **Never invent a financial rule.** If the rounding, the ordering, the sign, or the account to post to is not written in this document, stop and ask. A plausible rule that nobody chose is the most dangerous thing you can add to this codebase.
3. **No plugs.** Nothing in this codebase exists to make a total agree. If a total does not agree, that is a finding, and it surfaces as a failure, not as an adjustment.
4. **No floating point for money.** Ever. Not in a calculation, not in a DTO, not in a test fixture, not in JSON.
5. **One module per turn, finished.** Working code plus its unit tests plus the migration, then stop. Do not sketch six modules.
6. **Ask before adding a dependency.** Every new library is a decision, and this project deliberately has very few.
7. **Say what you did not do.** End every turn with what is incomplete, what is assumed, and what is unverified. An honest gap is worth more than a complete-looking file.
8. **Failing behaviour is written as a test first**, when the behaviour is a rule from §9. The rule exists to be proven, not to be described in a comment.

---

## 2. Scope

### In

- Double-entry ledger, two currencies, shillings functional.
- Recording receivables and obligations with due dates.
- An agent that decides when and how much to convert, and writes down why.
- Settlement of the dollar leg on a real test network, with real transaction hashes.
- A shilling payout leg through a mocked adapter that behaves like a real asynchronous rail.
- The three-way split: exchange difference, spread, fee.
- Three-way reconciliation: intent ↔ settlement ↔ ledger, with suspense and ageing.
- Replay: reproduce any day's closing position from records alone.
- One demonstration screen.

### Out — do not build these, even if they seem quick

Multi-tenancy. User accounts and login. KYC or identity checks. A real currency conversion. More than one functional currency. More than one foreign currency. Hedge accounting. Tax treatment. Yield or interest on idle balances. Mobile anything. A second screen. Notifications. An admin panel. Docker. Kubernetes. Kafka. Redis. Microservices.

If something on that list appears to be needed, that is a finding to raise, not a task to start.

---

## 3. Technology

| Layer | Choice | Why |
|-------|--------|-----|
| Core language | Java 21 (Amazon Corretto 21) | Already installed, no admin rights needed, compounds with the capstone work |
| Framework | Spring Boot 3.x | Same as the capstone. One framework, not three |
| Database | PostgreSQL, local install | No Docker available on this machine |
| Migrations | Flyway | Schema is versioned from commit one |
| Tests | JUnit 5 + AssertJ | No Testcontainers — Docker is unavailable |
| Settlement side | Node 20 + viem, run as a small separate process | The tooling for this network is JavaScript-first. Fighting that in Java costs days we do not have |
| Build | Maven or Gradle — `OPEN`, pick on day 1 and never revisit | |

**On the two-process design.** The Java core never talks to a blockchain. It talks to a small local HTTP service that does. That service is the *settlement adapter*, and it is deliberately thin: three endpoints, no business logic, no knowledge of accounting. If the network changes, only that process changes. This is the same discipline as keeping rail-specific logic behind an adapter — the rail here just happens to need a different language.

**Test database:** a separate local Postgres database, migrated by Flyway on the test profile, truncated between test classes. No in-memory substitute — the ledger constraints are the point, and they must be the real database's constraints.

---

## 4. Architecture

```
                    ┌──────────────────────────────────────┐
                    │            DEMO SCREEN               │
                    │   (one page, server-rendered)        │
                    └──────────────────┬───────────────────┘
                                       │
  ┌────────────────────────────────────┼────────────────────────────────────┐
  │  JAVA CORE                         │                                    │
  │                                                                         │
  │   ┌──────────────┐   ┌──────────────┐   ┌──────────────────────────┐    │
  │   │   AGENT      │──▶│   TREASURY   │──▶│        LEDGER            │    │
  │   │  decides     │   │  orchestrates│   │  double-entry, immutable │    │
  │   │  writes      │   │  instructions│   │  refuses bad postings    │    │
  │   │  intent      │   │              │   └──────────────────────────┘    │
  │   └──────────────┘   └──────┬───────┘                 ▲                 │
  │          │                  │                         │                 │
  │          ▼                  ▼                         │                 │
  │   ┌──────────────┐   ┌──────────────┐   ┌─────────────┴────────────┐    │
  │   │ INTENT LOG   │   │  FX ENGINE   │   │     RECONCILER           │    │
  │   │ append-only  │   │ splits diff  │   │  intent ↔ settle ↔ books │    │
  │   └──────────────┘   │ spread  fee  │   │  suspense + ageing       │    │
  │                      └──────────────┘   └──────────────────────────┘    │
  │                                                                         │
  │        ┌───────────────┬──────────────────┬───────────────┐             │
  │        ▼               ▼                  ▼               ▼             │
  │   ┌─────────┐   ┌────────────┐   ┌─────────────┐   ┌────────────┐       │
  │   │  RATE   │   │ SETTLEMENT │   │   PAYOUT    │   │   CLOCK    │       │
  │   │ adapter │   │  adapter   │   │   adapter   │   │  adapter   │       │
  │   └─────────┘   └─────┬──────┘   └──────┬──────┘   └────────────┘       │
  └───────────────────────┼─────────────────┼───────────────────────────────┘
                          │ HTTP            │ in-process mock
                          ▼                 ▼
                ┌──────────────────┐   ┌──────────────────────────┐
                │ NODE SIDECAR     │   │  MOCK SHILLING RAIL      │
                │ viem → test net  │   │  async, callback-shaped, │
                │ real tx hashes   │   │  configurable to fail    │
                └──────────────────┘   └──────────────────────────┘
```

Everything below the adapter line is replaceable. Everything above it must not know which rail it is talking to.

**The clock is an adapter.** Nothing anywhere calls `now()` directly. Time is injected. Without this, none of the ageing, due-date or month-end behaviour can be tested, and demo day becomes a hostage to the actual date.

---

## 5. Money, rates and rounding

This section is law. Every rule here gets a unit test that proves it.

**Amounts.** Stored as whole minor units in a 64-bit integer, alongside a currency code. Never a decimal type in the database, never a floating point type anywhere.

| Currency | Minor unit | Scale |
|----------|-----------|-------|
| KES | cent | 2 |
| USDC | micro-dollar | 6 |

**Rates.** A rate is stored with a scale of 8 and always carries four things: the value, the source, the timestamp, and whether it is mid-market or executed. A rate object missing any of those cannot be constructed — enforce this in the constructor, not in a validator that somebody can forget to call.

**Conversion.** To convert an amount:

1. Take the amount in minor units.
2. Multiply by the rate at full precision.
3. Adjust for the scale difference between the two currencies.
4. Round once, at the end, `HALF_UP`, to the target currency's minor unit.

Round once and only once. Rounding an intermediate value and then rounding again produces a different answer, and the difference is small enough to survive review and large enough to matter across a year.

**Residue.** When an amount is split across several destinations and the parts do not sum to the whole, the difference goes to the **last** part. Document the rule where the code does it. Do not scatter the residue proportionally — that is a second rounding and it reopens the problem.

**Signs.** Debits positive, credits negative, internally. The presentation layer may show them however it likes. Nothing else in the system gets an opinion about signs.

---

## 6. Domain model

Proposed names. **Freeze this at the end of Day 3** — after that, renaming costs more than it is worth.

```
Account            the chart of accounts. code, name, type, currency, is_suspense
JournalEntry       a balanced set of postings. business_date, description,
                   source_ref, created_at
Posting            one line. entry_id, account_id, amount_minor, currency,
                   rate_value, rate_source, rate_timestamp, functional_amount_minor
Receivable         what a client owes. counterparty, currency, amount, issue_date,
                   status
Obligation         what we owe. name, currency, amount, due_date, status
Rate               value, source, timestamp, kind (MID | EXECUTED)
Intent             the agent's decision, written before acting. created_at,
                   trigger, reasoning, inputs_snapshot, proposed_action
Instruction        a thing to be done. intent_id, type, amount, currency,
                   external_ref, state, attempts
Settlement         what the rail reported back. instruction_id, rail, rail_ref,
                   reported_amount, reported_at, raw_payload
ReconItem          an unmatched thing. kind, first_seen, age_days, state
```

Notes that are not optional:

- `Posting` is **immutable**. No update, no delete. A correction is a new entry that reverses and re-posts. The database enforces this with a rule that rejects updates on the table, not with a code convention.
- `Posting.functional_amount_minor` is stored, not computed on read. The shilling value of a posting is a fact about the moment it was made, and recomputing it later with today's rate silently rewrites history.
- `Intent.inputs_snapshot` stores what the agent could see when it decided. Not a reference to the data — a copy. The data will have changed by the time anyone reads the log.
- `Instruction.external_ref` has a **unique constraint**. This is the single most important constraint in the schema and it is what makes F5 (silent double payment) impossible rather than unlikely.

**Chart of accounts** — minimum set, and each one exists because of a rule in §9:

```
1000  Bank — KES                    asset,  KES
1100  Wallet — USDC                 asset,  USDC
1200  Receivables                   asset,  USD
1900  Suspense — unexplained        asset,  KES     (is_suspense = true)
2000  Payables                      liability, KES
4000  Revenue                       income, KES
6100  Exchange difference realised    other, KES
6110  Exchange difference unrealised  other, KES
6200  Conversion spread             expense, KES
6210  Conversion fee                expense, KES
```

6100, 6110, 6200 and 6210 are four separate accounts for the reason given in `PROBLEM.md` §5. Do not let them become one.

---

## 7. State machines

**Instruction** — the important one.

```
   CREATED
      │  submitted to rail
      ▼
   SUBMITTED ──────────────┐
      │                    │  rail says nothing, timeout expires
      │ rail confirms      ▼
      │              AWAITING_RESOLUTION   ← not terminal, and not failure
      │                    │
      │                    │  later evidence arrives
      │       ┌────────────┼────────────┐
      ▼       ▼            ▼            ▼
   SETTLED  SETTLED      FAILED   MANUAL_REVIEW
                                   (a human must look)
```

`AWAITING_RESOLUTION` is the state that most systems do not have, and its absence is why they double-pay. An instruction in this state is **never retried**. It is re-queried. The only exits are evidence or a human.

**Obligation**

```
   SCHEDULED → FUNDED → PAID
        │
        └────→ OVERDUE   (due date passed, no funds allocated — this is a bug
                          in the agent's planning, and must be visible as one)
```

**Intent** is append-only. It has no state machine. It happened.

---

## 8. The ledger

The ledger's job is to refuse things.

`LedgerService.post(JournalEntry)` must reject, with a distinct exception per case:

- postings do not sum to zero within any single currency
- postings do not sum to zero in shillings
- any posting lacks a rate, rate source or rate timestamp when its currency is not KES
- any posting references an account that does not exist
- the entry has fewer than two postings
- the business date is in the future relative to the injected clock

Rejection means nothing is written. One database transaction, all or nothing.

There is no `update` and no `delete` on the posting table. Add a database-level rule that rejects them, and a test that proves the rule fires.

---

## 9. The rules, as enforceable checks

Each maps to `PROBLEM.md` §6. Each gets a test named after it.

| ID | Rule | Enforced where |
|----|------|----------------|
| I1 | No non-KES posting without rate, source, timestamp | Ledger, at post time |
| I2 | Entries balance per currency and in shillings | Ledger, at post time |
| I3 | No account and no code path exists to force agreement | Reviewed, plus a test asserting the chart has no "adjustment" account |
| I4 | Exchange difference, spread and fee post to 6100/6110, 6200, 6210 and never to each other | FX engine tests |
| I5 | Realised (6100) and unrealised (6110) never share an entry | Ledger, at post time |
| I6 | Unexplained receipts land in 1900 with a first-seen date | Reconciler |
| I7 | `AWAITING_RESOLUTION` never transitions to a retry | Instruction state machine — make the transition unrepresentable, not merely unused |
| I8 | Every `SCHEDULED` obligation with a due date inside the planning horizon has funds allocated, or the agent must refuse to hold dollars | Agent, before every decision |
| I9 | No instruction is created without an intent already persisted | Treasury service, enforced by a foreign key that is `NOT NULL` |
| I10 | Replay of the journal reproduces the closing position exactly | Replay test |

I7 deserves a design note: do not implement it as an `if` statement that skips the retry. Implement it so that the retry method cannot be called on an instruction in that state — a different type, or a guard in the state machine itself. Rules enforced by discipline get broken at 2am on day 12.

---

## 10. The FX engine

Given a conversion that has happened, produce postings. Inputs:

- amount converted, in dollars
- executed rate
- mid-market rate at the same moment
- explicit fee charged
- the carrying rate at which those dollars sit in the books

Outputs, as one balanced entry plus a separate memo entry:

1. **Exchange difference** = (shillings received at executed rate) − (carrying value of the dollars given up). Account 6100.
2. **Spread** = (mid-market rate − executed rate) × dollars converted. Account 6200. If this is negative — you beat the mid — record it as negative and do not hide it.
3. **Fee** = as charged. Account 6210.

Carrying rate uses **weighted average cost**. When dollars arrive at different rates, the wallet carries one blended rate, recalculated on each receipt. `OPEN`: whether to use weighted average or first-in-first-out. Weighted average is assumed until Kurgat rules otherwise; the method must sit behind a single interface so the answer can change without touching anything else.

**Month-end revaluation** is a separate operation that touches only 6110, never 6100, and never moves any actual money.

The worked example in `PROBLEM.md` §5 is the acceptance test. Every figure in it — 25,000 / 4,200 / 2,400 / 7,139 / 2,800 — must fall out of the engine with no adjustment. If one does not, the engine is wrong, not the example.

---

## 11. The agent

The agent does one thing: decides **how many dollars to convert, and when**.

Each decision cycle:

1. Read the obligation calendar for the planning horizon (default 35 days).
2. Read current balances, in both currencies.
3. Read the current mid-market rate.
4. Check I8 — is any obligation inside the horizon unfunded?
5. Decide.
6. **Write the intent, with reasoning and a snapshot of everything read in steps 1 to 3, and commit it.**
7. Only then create the instruction.

Steps 6 and 7 are in that order and the order is not negotiable. If the process dies between them, we have a decision with no action — recoverable and visible. The reverse leaves an action nobody can explain, which is F6.

The agent's reasoning may come from a language model. Its *constraints* may not. The invariants are checked in Java, before the instruction is created, and a proposed action that violates one is rejected regardless of how good the reasoning sounds. This distinction is the demo's strongest single moment — show a model proposing something sensible-sounding that the system refuses.

**First version of the decision rule** — keep it dull and legible:

```
needed  = obligations due within horizon, not yet funded
cover   = needed + buffer (buffer default 10%)
have    = KES balance
short   = max(0, cover - have)
convert = short, converted at the current mid rate, rounded up to whole dollars
```

No cleverness about rate timing in version one. Rate-aware timing is a stretch goal, and only once the boring version is proven.

---

## 12. Adapters

### Rate adapter
Returns a mid-market rate with a source and timestamp. First implementation reads a fixed table of dated rates from the database so tests and the demo are deterministic. A live source is optional and must not become a dependency of any test.

### Settlement adapter (real)
Java calls the Node sidecar over local HTTP. Three endpoints: send a payment, check a payment's status, get a balance. The sidecar returns a real transaction hash from the test network.

`TODO[unverified]` — network name, chain ID, RPC endpoint, testnet token contract address, faucet location, and the exact viem function signatures. **None of these may be written from memory.** Read them from the current documentation on day 1 and record them in `docs/network.md` with the date read.

### Payout adapter (mocked, behaving like the real thing)
The shilling leg is mocked. It is not, however, a function that returns success. It must:

- accept an instruction and return immediately with "accepted", not "done"
- deliver the outcome later, asynchronously, through a callback
- be configurable to: succeed, fail, time out, deliver the callback twice, deliver the callback out of order, and deliver a callback for an instruction it was never given
- carry an external reference through the whole round trip

Those last three are not decoration. They are the failures that produce F5, and a mock that cannot produce them cannot prove the system survives them.

`OPEN` — whether to shape this on a real mobile money payout API's request and callback format. Doing so makes the mock more honest and the eventual swap smaller. Verify the actual format from current documentation before shaping it; do not write the field names from memory.

### Clock adapter
Injected everywhere. Advanced explicitly in tests. The demo runs on a scripted clock so that a thirty-day story fits in ninety seconds.

---

## 13. Idempotency and reconciliation

**Every instruction carries an external reference that is unique in the database.** A duplicate submission fails on the constraint, at the database, not in application logic. This is the mechanism, and the constraint is the mechanism — application checks are an optimisation on top of it, never a substitute.

**Every inbound callback is recorded before it is processed**, keyed on the rail's own reference, with a unique constraint. A repeated callback is detected as already-seen and ignored. A callback for an unknown instruction is not an error to swallow — it goes to reconciliation as an unmatched item.

**The reconciler** runs on demand and matches three records of the same event:

```
   INTENT          what we meant to do
      │
      ▼
   SETTLEMENT      what the rail says happened
      │
      ▼
   LEDGER          what the books say

   all three agree            → matched
   settlement without intent  → unmatched inbound  → suspense 1900
   intent without settlement  → in flight, or stale if older than threshold
   amounts differ             → exception, always, regardless of size
```

Unmatched items age. Below the threshold they are operational noise. Above it they are exceptions that appear on the screen. `OPEN` — what the threshold is. Assume 2 business days until Kurgat decides.

---

## 14. Replay

A command that reads the journal from the beginning and reconstructs balances, then compares with the stored balances. Any difference is a failure with the specific entry identified.

Replay must not read anything outside the journal. Not the wallet, not the rail, not the rate feed's current value. If replay needs a rate, it takes the rate stored on the posting. The moment replay reaches outside the records, it stops proving anything.

---

## 15. Build order

Fourteen days. Each day's item is finished — code, tests, migration — before the next begins. If a day slips, the last items are cut, in reverse order. The demo is built on day 12 whatever happens.

| Day | Build | Done when |
|-----|-------|-----------|
| 1 | Project skeleton, Postgres, Flyway, clock adapter, money type with its unit tests. Read network documentation and write `docs/network.md` | `mvn test` green, money type rejects float construction |
| 2 | Chart of accounts, `Account`, `JournalEntry`, `Posting`, immutability rule at database level | Update on posting table is rejected by the database, proven by a test |
| 3 | Ledger post and its six rejections | All six rejection tests pass. **Domain model freezes today** |
| 4 | Rate type, rate adapter with the fixed table, conversion and rounding rules | Round-once rule proven; residue rule proven |
| 5 | FX engine | Every figure in the worked example falls out unaided |
| 6 | Receivables and obligations, and their state machines | Overdue is reachable and visible |
| 7 | Intent log, append-only, with snapshot | Intent cannot be written after its instruction |
| 8 | Agent, dull version, with invariant checks including I8 | A proposed action violating I8 is refused |
| 9 | Instruction state machine including `AWAITING_RESOLUTION` | Retry is unrepresentable in that state, proven |
| 10 | Payout mock with all six behaviours, and the inbound callback record | Duplicate callback changes nothing; unknown callback reaches reconciliation |
| 11 | Node sidecar, real test-network settlement, real hash stored | A real transaction hash appears in the database |
| 12 | Demo screen: two panels, naive versus correct, thirty-day story on a scripted clock | Runs start to finish without a human touching anything |
| 13 | Reconciler, suspense, ageing, replay command | Replay reproduces the closing position |
| 14 | `REAL_VS_SIMULATED.md`, README, architecture decision records, submission | Somebody who has never seen the repo can run it from the README |

Month-end revaluation, and rate-aware timing in the agent, are stretch goals. They are built only if a day comes in early. They are never built by borrowing from day 12 or 13.

---

## 16. Definition of done

A module is done when all of these are true, and not before:

- it does what this specification says, and nothing this specification does not say
- its rules from §9 have tests named after the rule they prove
- failure paths have tests, not just the happy path
- amounts are integers of minor units throughout
- no identifier in it was invented — anything unverified is marked in place
- what it does not handle is written down in the module's own comment header
- `mvn test` is green

---

## 17. Testing

Claude writes unit tests for every module as it is built. Those prove the code does what it was told.

**Kurgat writes the scenarios that try to break it.** That is the more interesting half and it is deliberately not delegated. Starter list — each one is a question about the design as much as a test:

1. A callback arrives twice, four seconds apart.
2. A callback arrives twice, four hours apart, and in between the instruction was marked `AWAITING_RESOLUTION`.
3. A callback arrives for an instruction that was never created.
4. A callback arrives with the right reference and the wrong amount.
5. Two instructions are created with the same external reference by two threads at the same instant.
6. The rail times out, then succeeds an hour later, after the agent has already planned around the failure.
7. Payroll falls due on a day when the rate feed has no entry.
8. Dollars arrive at three different rates in one week, then half are converted. What is the carrying rate, and does the exchange difference survive a hand check?
9. A conversion where the executed rate beats the mid-market rate.
10. A fee larger than the exchange difference.
11. A conversion of the entire wallet, leaving zero. Then a revaluation.
12. An obligation due today, with no dollars and no shillings.
13. An obligation created with a due date in the past.
14. The month ends between the instruction and its callback.
15. The clock moves backwards.
16. Two obligations due the same day, funds enough for one and a half.
17. An amount that does not divide cleanly — 1,000 shillings split three ways.
18. A rate with more decimal places than the rate scale allows.
19. Replay after a correcting entry has reversed and re-posted an earlier mistake.
20. Replay of a day on which an item sat in suspense and was never resolved.

Scenarios 8, 14 and 19 are where this design is most likely to be wrong. Start there.

---

## 18. Deliverables

- Public repository, MIT unless decided otherwise, with the regulatory boundary from `PROBLEM.md` §7 stated in the README's first screen.
- `REAL_VS_SIMULATED.md` — a blunt table of what actually runs against a real network and what is mocked, with no softening. This is a strength, not an admission.
- `docs/network.md` — every network fact, with the date it was read from documentation.
- Architecture decision records, short, one page each:
  - ADR-001 why shillings are the functional currency
  - ADR-002 why exchange difference, spread and fee are separate accounts
  - ADR-003 weighted average versus first-in-first-out for carrying rate
  - ADR-004 why `AWAITING_RESOLUTION` exists and is never retried
  - ADR-005 why the settlement leg is a separate process in another language
  - ADR-006 why the shilling payout leg is mocked (the regulatory boundary)
  - ADR-007 the residue rule
- A recorded demonstration: the thirty-day story, the refusal moment from §11, a real transaction hash, and a replay that reproduces the closing position.

---

## 19. Risks, stated honestly

- **Exchange-difference accounting is a rabbit hole.** One functional currency, one foreign currency, realised and unrealised split, nothing else. If hedge accounting or tax treatment appears in a conversation, it is out of scope, permanently.
- **Judges may not feel this pain.** Many have never closed a set of books. The two-panel demo is the answer and it is why day 12 is protected.
- **Fourteen days against seven other active tracks.** This is only affordable if it is capstone work done under a deadline rather than an eighth thing. If it stops being that, stop.
- **The network tooling may not cooperate.** Day 11 is late on purpose: everything except the real settlement leg must already work by then, so that a bad day on the sidecar costs a feature and not the project.
- **The agent is the smallest part of this and will attract the most attention.** Resist. The refusal is more interesting than the reasoning, and the refusal lives in Java.

---

## 20. Open items

Answer these as they come up. Do not let Claude guess them.

| ID | Question | Assumed until decided |
|----|----------|-----------------------|
| O1 | Maven or Gradle | Decide day 1 |
| O2 | Weighted average or first-in-first-out carrying rate | Weighted average |
| O3 | Reconciliation ageing threshold | 2 business days |
| O4 | Whether the payout mock mirrors a real mobile money API's format | Yes, if the format can be verified from current documentation |
| O5 | Repository name and licence | `shilingi`, MIT |
| O6 | Planning horizon and buffer | 35 days, 10% |
| O7 | Whether spread is an expense or a reduction of income | Expense, account 6200 |
| O8 | What the demo's naive panel actually does, precisely, to be a fair comparison rather than a straw man | Undecided — this one matters |

O8 is the one to think about hardest. A demo that beats a deliberately stupid alternative proves nothing, and an audience can smell it.

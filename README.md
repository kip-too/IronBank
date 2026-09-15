# shilingi

A treasury and bookkeeping layer for a Kenyan business that earns dollars and spends shillings.

Exchange difference, provider spread and explicit fee are **three separate facts**. Most systems
blend them into one number that answers no question. This one refuses to.

---

## What this is, and what it is not

> **This is an open-source reference implementation of accounting and orchestration logic. It does
> not convert currency. It does not hold customer money. It does not move real value.** The
> conversion leg is a mocked adapter that assumes a licensed counterparty performs the actual
> conversion.

That is not a disclaimer bolted on the end. It is a design boundary that decides where the code
stops, and it is why the payout adapter is an interface with a mock behind it rather than an
integration.

Kenya regulates this space properly: the Virtual Asset Service Providers Act commenced 4 November
2025 and the VASP Regulations 2026 were gazetted 22 July 2026 under Legal Notice 134. Converting
virtual assets into fiat requires separate CBK authorisation. **The exemption for genuine software
providers turns on what the software actually does**, not on what a README claims — so the
boundary is real in the code, and [ADR-006](docs/adr/ADR-006-the-payout-leg-is-mocked.md) explains
where it sits.

[`REAL_VS_SIMULATED.md`](REAL_VS_SIMULATED.md) states, without softening, exactly which parts run
against something real and which do not.

---

## The problem, in one example

A client pays a USD 10,000 invoice. Days later the money arrives. You convert some of it because
payroll is on Friday. A shilling amount lands in the bank, and you write **that** in a spreadsheet.

Four things were destroyed between the invoice and the spreadsheet:

- **the rate** — the bank credited shillings, it did not say at what rate
- **the cost** — the provider charged no fee; it gave a worse rate, and that cost is now
  unrecoverable from the records
- **the link** — the credit carries no invoice number
- **the date basis** — three different days, three different rates, one number

This system keeps all four. The demonstration screen shows both sets of books side by side and
asks each of them the same four questions.

---

## Running it

### You need

| | |
|---|---|
| **Java 21** | Amazon Corretto 21 or equivalent |
| **Maven 3.9+** | |
| **PostgreSQL 16+** | running locally, with a `postgres` superuser |
| **Node 20+** | only for the settlement sidecar |

No Docker. Nothing to install beyond those four.

### 1. Database

```powershell
./scripts/start-db.ps1     # only if PostgreSQL is not already running
./scripts/setup-db.ps1     # creates shilingi, shilingi_test, shilingi_demo
```

Flyway creates every table on first run. There is no schema to load by hand.

> If PostgreSQL keeps stopping, read [`scripts/start-db.ps1`](scripts/start-db.ps1) — it diagnoses
> why and names the permanent fix.

### 2. Tests

```bash
mvn test
```

**324 tests.** They run against the real PostgreSQL, deliberately — `SPEC.md` §3 forbids an
in-memory substitute, because the ledger's constraints are the point and they must be the real
database's constraints. Expect about five minutes; the suite drops and rebuilds the schema between
tests so that no test can see another's data.

### 3. The demonstration screen

```bash
mvn -DskipTests package

SPRING_PROFILES_ACTIVE=demo \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/shilingi_demo \
SPRING_FLYWAY_CLEAN_DISABLED=false \
SERVER_PORT=8090 \
java -jar target/shilingi-0.1.0-SNAPSHOT.jar
```
```bash
java -jar target\shilingi-0.1.0-SNAPSHOT.jar --spring.profiles.active=demo --spring.datasource.url=jdbc:postgresql://localhost:5432/shilingi_demo --spring.flyway.clean-disabled=false --server.port=8090
```

Then open **<http://localhost:8090/>**.

It plays a thirty-day story start to finish with nothing to click — no start button, no pauses. It
resets its own database first, so it is repeatable rather than a first run. Every figure on it can
be checked against `PROBLEM.md` §5.

The screen runs on a **scripted clock** (`SPEC.md` §12), which is why a story ending on 30
September works on any day of the year.

### 4. The settlement sidecar (optional)

```bash
cd sidecar
npm install
npm run stub     # no network needed
```

`npm start` runs it live against Base Sepolia and needs `SHILINGI_PRIVATE_KEY` plus a network that
does not block testnet RPC endpoints — **this one does**, and
[`docs/network.md`](docs/network.md) §4 diagnoses exactly why.

The Java side talks to it over `127.0.0.1:8787`. `SidecarContractTest` starts the real process, so
the contract between the two is proven whether or not a chain is reachable.

---

## How it is put together

```
   AGENT ─────▶ TREASURY ─────▶ LEDGER            double-entry, immutable,
   decides      orchestrates    refuses bad       refuses six things by name
   writes       instructions    postings
   intent           │               ▲
       │            ▼               │
   INTENT LOG   FX ENGINE      RECONCILER
   append-only  splits diff    intent ↔ settle ↔ books
                spread  fee    suspense + ageing

   RATE    SETTLEMENT     PAYOUT     CLOCK        every one an adapter
   adapter  adapter       adapter    adapter
              │              │
         Node sidecar   in-process mock
         viem → testnet  async, hostile
```

Everything below the adapter line is replaceable. Everything above it does not know which rail it
is talking to.

### Where to look first

| If you want to see | Read |
|---|---|
| the rules, and what happens when you break them | `LedgerService`, `LedgerRejection` |
| why exchange difference is not revenue | `FxEngine` |
| a rule made unbreakable by the type system | `Instruction` — the retry is a **compile error** |
| a decision recorded before it was acted on | `IntentLog`, `TreasuryAgent` |
| the system refusing a well-argued proposal | `TreasuryInvariants` |
| whether the books still agree with themselves | `Replay` |

---

## The documents

| | |
|---|---|
| [`PROBLEM.md`](PROBLEM.md) | why this exists. **Read this first** — the rest is meaningless without it |
| [`SPEC.md`](SPEC.md) | what was to be built, and the rules it must not break |
| [`REAL_VS_SIMULATED.md`](REAL_VS_SIMULATED.md) | what actually runs against something real |
| [`docs/TEST_AND_OPERATIONS.md`](docs/TEST_AND_OPERATIONS.md) | **deploying and running this on Ubuntu, and testing it end to end** |
| [`docs/network.md`](docs/network.md) | every network fact, with the date it was read |
| [`docs/adr/`](docs/adr/README.md) | **27 decision records** — what was decided, why, and what it cost |

The ADRs are the honest part. They include the places this deviates from `SPEC.md` and the
argument for each, the decisions most likely to be wrong (marked as such), and two findings about
the specification itself that need answering before this could be called finished.

---

## What is not built, and why

Stated here rather than discovered later:

- **No real transaction hash has been captured.** The sidecar is written and tested; this network
  blocks every testnet RPC endpoint. `docs/network.md` §4.
- **Payouts are not posted to the ledger.** `SPEC.md` §6's chart has no expense account, so an
  obligation cannot be accrued without inventing one — raised as a finding rather than plugged.
  [ADR-025](docs/adr/ADR-025-the-chart-cannot-express-an-obligation.md).
- **No partial settlement**, no credit notes, no multi-tenancy, no login, no second functional
  currency, no hedge accounting, no tax treatment. `SPEC.md` §2 puts every one of those out of
  scope, permanently.

---

## Licence

MIT. See [`LICENSE`](LICENSE).

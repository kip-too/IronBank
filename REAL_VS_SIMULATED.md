# REAL_VS_SIMULATED.md

What actually runs against something real, and what does not. `SPEC.md` §18 asks for this blunt
and unsoftened, and calls it a strength rather than an admission. Nothing below is rounded up.

**Status as at day 14 of 14 — the end of the build window.**

---

## Real

| Thing | What makes it real |
|---|---|
| **The ledger** | PostgreSQL 18.4. Every constraint, trigger and rejection runs in the database, and the test suite runs against a live one - no in-memory substitute, per `SPEC.md` §3 |
| **Immutability** | `UPDATE`, `DELETE` and `TRUNCATE` on postings, entries, intents and callbacks are refused by the database, not by a code convention |
| **The balance rule** | Enforced at commit by a deferred constraint trigger. Raw SQL that bypasses the application still cannot write an unbalanced entry |
| **Double-payment protection** | A unique constraint. Proven under real contention: 16 threads racing one external reference, exactly one wins |
| **The money arithmetic** | Integer minor units end to end - in Java, in the database, in JSON, across the process boundary to Node |
| **The sidecar contract** | The Java↔Node agreement is tested against the **real sidecar process**, started by the test, not against a Java-side fake |
| **viem** | 2.56.5, installed. Chain id, RPC and contract address read from viem's and Circle's own sources, never from memory |
| **Replay** | Reads `journal_entry` and `posting` and no other table. Proven by deleting the entire rate feed and replaying to identical figures |
| **Reconciliation ageing** | `first_seen` is immutable at the database, so re-running reconciliation cannot re-date an item and make it young again |

## Simulated

| Thing | What is simulated, and how honestly |
|---|---|
| **The shilling payout rail** | Entirely mocked. **This is a design boundary, not unfinished work** - see `PROBLEM.md` §7. The mock is not a function that returns success: it can succeed, fail, never reply, deliver a callback twice, deliver callbacks out of order, call back about something it never received, and report a wrong amount |
| **Callback delivery timing** | Pumped, not threaded. Deterministic on purpose (ADR-024). **Genuine concurrent callback arrival is not rehearsed** |
| **Currency conversion** | Never performed. The FX engine records a conversion that a licensed counterparty is assumed to have done |
| **The rate feed** | A fixed table of four dated rates. Deliberately full of gaps, so "no rate for this date" stays reachable |
| **Manual resolution identity** | `who` is unverified free text. There are no user accounts (`SPEC.md` §2), so it records a claim, not an authenticated person |
| **Callback authenticity** | Not checked. A real rail signs its callbacks; the mock has nothing meaningful to verify |

## Not done at all

- **No real transaction hash has been captured.** See below - this is the largest gap.
- **Payouts are not posted to the ledger.** `SPEC.md` §6's chart has no expense account, so an
  obligation cannot be accrued. Raised as a finding rather than worked around (ADR-025).
- **Nothing marks obligations FUNDED**, and nothing pays one denominated in dollars.
- **No matching of receipts to invoices.** SPEC.md §13's three-way match is about money out;
  money in has its own and it is not built.
- **A foreign-currency unmatched item raises a recon item but posts nothing to suspense**, because
  account 1900 is a shilling account.
- **No partial settlement, no credit notes.** A part payment reaches the reconciler as an amount
  that does not match, which §13 says is an exception, always.

---

## The one that matters: no real transaction hash

`SPEC.md` §15 makes day 11 done when *"a real transaction hash appears in the database"*. **That
has not happened, and the reason is not the code.**

This network runs **Cisco Umbrella**, which blocks every testnet RPC endpoint tried - six of them,
from four providers. The certificate presented for `sepolia.base.org` is issued by
`CN=Cisco Umbrella Secondary SubCA jnb-SG`, and reading past it returns `HTTP 303` to a block
page. Full diagnosis, including what was ruled out, is in [docs/network.md](docs/network.md)
section 4.

**What this does and does not cost:**

- The sidecar is **written, installed and tested**. Both modes share one HTTP contract, and the
  contract is proven against the real process.
- Switching to live is `SHILINGI_MODE=live` plus a funded key. No code changes.
- Every simulated hash is flagged `simulated: true` from the sidecar, through the adapter, into
  the `Acceptance` record. **A simulated hash can never be mistaken for a real one**, which is why
  the flag is carried the whole way rather than dropped at the boundary.

To close it, on a connection not behind that policy:

```bash
cd sidecar
SHILINGI_MODE=live SHILINGI_PRIVATE_KEY=0x... npm start
```

Two things still need verifying at the same time, and they are recorded as unverified rather than
assumed: a **gas faucet** for Base Sepolia (the Circle faucet gives USDC, not ETH, and a wallet
with USDC and no gas can do nothing), and the deployed contract's **`decimals()`**, which
`docs/network.md` explains the contract source cannot answer.

Until then this project has a settlement adapter that is complete and unproven against a chain,
and says so.

---

## What replay does and does not prove

Worth stating here because a passing replay test invites a bigger claim than it earns.

Replay proves **consistency and reproducibility**. It cannot tell you the rate on the 12th was
really 131.50. It can tell you that whatever rate was used is still on the posting, that the entry
balanced then and balances now, that nothing has been edited since, and that September's closing
position can be produced in March with nobody remembering anything.

Correctness of the *inputs* is what the rate source, the reconciler and a person are for. The full
argument, and the answer to `PROBLEM.md` §8 question 8, is in
[ADR-027](docs/adr/ADR-027-replay-proves-consistency.md).

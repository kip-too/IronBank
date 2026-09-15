# ADR-005 — The settlement leg is a separate process, in another language

**Date:** 2026-09-15 · **Status:** accepted

## Decision

The Java core never talks to a blockchain. It talks to a small Node process over local HTTP, and
that process talks to the chain with viem. Three endpoints: send a payment, check a payment's
status, get a balance.

## Why not in Java

`SPEC.md` §3 states the reason and it is a scheduling one, not an architectural preference: *"The
tooling for this network is JavaScript-first. Fighting that in Java costs days we do not have."*

In fourteen days, with the real settlement leg deliberately scheduled late so a bad day costs a
feature and not the project (§19), fighting an ecosystem is the wrong place to spend the budget.

## Why it is a good boundary anyway

This is the same discipline as keeping rail-specific logic behind an adapter — the rail here just
happens to need a different language.

- **The sidecar knows nothing about accounting.** No postings, no invariants, no chart of
  accounts. If it did, the accounting rules would live in two languages.
- **It has no opinion about retrying.** Retrying is a decision the instruction state machine makes,
  under rules the sidecar cannot see — and in which a retry from the wrong state is a compile
  error. A sidecar that retried on its own could defeat that from outside.
- **If the network changes, only this process changes.** Chain id, RPC URL, contract address and
  ABI are all on one side of an HTTP call.

## The two decisions inside it

**Two modes, one contract.** `stub` answers from memory and touches no network; `live` uses viem
against Base Sepolia. The HTTP contract is identical, so everything except "did a real chain accept
it" is proven without a network — which matters here more than it should, because this network
blocks every testnet RPC endpoint (`docs/network.md` §4).

**The contract test starts the real sidecar process.** Testing against a Java-side fake would have
proven the adapter can talk to a Java-side fake. `SidecarContractTest` launches `node server.mjs`
and proves the two processes agree.

## What it costs

- **Two runtimes to install and two things to start.** The README says so plainly.
- **Money crosses the boundary as a string.** JSON numbers are doubles, and `SPEC.md` §5 forbids
  floating point for money *"not in a calculation, not in a DTO, not in a test fixture, not in
  JSON"*. Minor units go over the wire as strings, and there is a test using a value one past the
  largest integer a double holds exactly.
- **A timeout is ambiguous by nature.** `SettlementUnavailableException` exists to keep "no answer"
  from being read as "failed" — see [ADR-004](ADR-004-awaiting-resolution.md).

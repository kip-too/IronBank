# ADR-006 — The shilling payout leg is mocked: the regulatory boundary

**Date:** 2026-09-15 · **Status:** accepted — **a design constraint, not unfinished work**

## Decision

`PayoutPort` has exactly one implementation, and it is a mock. This project does not convert
currency, does not hold customer money, and does not move real value.

## Why this is not a shortcut

`PROBLEM.md` §7 sets it out, and the last sentence is the operative one:

> *"That is not a disclaimer bolted on at the end. It is a design boundary that decides where the
> code stops, and it is why the conversion adapter is an interface rather than an implementation.
> The exemption for genuine software providers turns on what the software actually does, not on
> what the README claims — so the boundary has to be real in the code."*

Kenya regulates this properly now: the Virtual Asset Service Providers Act commenced 4 November
2025, the VASP Regulations 2026 were gazetted 22 July 2026 under Legal Notice 134, and converting
virtual assets into fiat needs separate CBK authorisation. A reference implementation that
*actually* converted would need a licence. One that describes conversions a licensed counterparty
performed does not.

**So the boundary is load-bearing.** It is why `PayoutPort` is an interface with a mock behind it
rather than an integration with a provider, and why the FX engine is told what happened rather
than making it happen.

## The mock is not a function that returns success

`SPEC.md` §12 is emphatic, and the mock does all of it: accept and return **accepted** rather than
done; deliver outcomes later; and be configurable to succeed, fail, never reply, deliver the
callback twice, deliver callbacks out of order, and call back about an instruction it was never
given. A seventh — right reference, wrong amount — covers §17 scenario 4.

§12 on why the last three matter: *"They are the failures that produce F5, and a mock that cannot
produce them cannot prove the system survives them."*

A mock this hostile is more useful than a real integration would have been at this stage. A real
rail succeeds nearly always, so the paths that matter would almost never run.

## What the mock cannot rehearse, recorded rather than glossed

- **Genuine concurrency.** Delivery is pumped rather than threaded, on purpose
  ([ADR-024](ADR-024-payout-mock-shape-and-delivery.md)) — a flaky proof of correctness is not one.
- **Callback authenticity.** A real rail signs its callbacks; there is nothing meaningful to verify
  here.
- **A real provider's field names.** O4 asked for them *if verifiable from current documentation*.
  They were not — the Safaricom developer portal returned 408 — and rule 1 forbids writing API
  field names from memory. The mock uses its own names and keeps the raw payload verbatim.

All of this is in `REAL_VS_SIMULATED.md`, stated without softening, because `SPEC.md` §18 asks for
it that way and because the boundary is a strength rather than an admission.

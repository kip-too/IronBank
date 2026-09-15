# ADR-019 — A missing rate does not excuse an unfunded obligation

**Date:** 2026-09-14 (day 8)
**Status:** accepted — this is ADR-015's deferred question, now answered
**Decided by:** Claude, under Kurgat's instruction to decide and log rather than ask

## How this was found

Writing day 8's tests, one of them asserted `refused() == false` while the comment above it
claimed I8 refused the action. The assertion was correct about the code. **The code was wrong.**

```
no rate published  →  requiredDollars = empty
                   →  mustConvert     = 0
                   →  conversionIsRequired() = false
                   →  I8 permits the hold
```

A rate-feed outage silently permitted holding dollars while payroll went unfunded. **I8 was
bypassed not by an argument but by an absence** — which is the worse failure of the two, because
nothing announces it. No exception, no refusal, just a routine-looking "nothing to do today".

## The decision

`FundingPlan` now carries `held`, the wallet balance, so the guard can distinguish two situations
that both leave `mustConvert` at zero:

| | `mustConvert` | I8 says |
|---|---|---|
| No dollars held at all | 0 | **permitted** — the agent cannot fix this by converting |
| Dollars held, but no rate to price them | 0 | **refused** — a human must look |

`FundingPlan.cannotBeWorkedOut()` is the second case. The refusal message says so in words:
*"…no mid-market rate is published for today, so how many dollars would cover it cannot be worked
out. The obligation is unfunded and nothing can act on it: a human must look."*

## Why this, and not the alternatives

This answers the question ADR-015 deliberately left open — *"what should happen when the feed has
no entry?"* — at the layer ADR-015 said it belonged: the caller, not the port.

- **Carry yesterday's rate forward.** Rejected, for the reason ADR-015 gave: the books get a
  figure that looks exactly as fresh as a real one, and nothing in the record distinguishes them.
  That is F1.
- **Permit the hold and log it.** This is what the code did by accident, and it is the option
  that reads most reasonably until you notice that a rate outage during payroll week is
  indistinguishable from a quiet day.
- **Refuse, and make it an exception.** Chosen. §7 says an unfunded obligation past its date "must
  be visible as one". A rate outage that will *leave* one unfunded deserves the same treatment,
  and a refusal is the mechanism this system already has for making something visible.

## What this costs

- **The agent can be left with no permitted action at all.** When the feed is down and dollars
  are held, every proposal is refused. That is not a deadlock to be engineered around — it is the
  system saying it cannot act safely and needs a person. It is honest, and it is loud.
- **A refused decision still writes an intent.** So a prolonged rate outage produces one refusal
  per cycle in the log. That is a correct record of a real, ongoing problem, not noise.
- **It makes the rate feed a availability dependency for acting**, which it was always going to
  be — this only makes the dependency visible instead of implicit.

## The related decision the same day: the guard cannot see the reasoning

`TreasuryInvariants` is handed an action and a set of facts, and nothing else. It has no access
to the reasoning, by construction.

§11: *"The agent's reasoning may come from a language model. Its constraints may not… a proposed
action that violates one is rejected regardless of how good the reasoning sounds."* That sentence
can only be true in practice if the check is structurally unable to be persuaded, and
`the_guard_cannot_see_the_reasoning` pins it: the same action argued well and argued badly gets
the same answer.

`TreasuryAgent.decideOn` is the seam where a language model would attach. **No model is wired in**
— §2 does not list one, and rule 6 requires asking before adding a dependency — and the demo's
refusal moment works through that seam without one.

## Known gaps in the agent, recorded rather than hidden

- **Dollar-denominated obligations are excluded from the shortfall.** §11's rule compares against
  "KES balance", so it is about shilling obligations; converting dollars does not fund a dollar
  debt, and which rate to compare at is a rule nobody stated. They appear in the snapshot, in the
  reasoning text and in `unfundedForeignObligations()`, so they are visible — **but nothing acts
  on them. This is a real gap.**
- **No account of money already in flight.** A conversion instructed but not yet settled is not
  subtracted from the shortfall, so two cycles in quick succession would each propose the whole
  gap. Day 9's instruction states are what fix this.
- **Over-converting is permitted.** I8 is about failing to convert, not about converting too
  much. `PROBLEM.md` §8 question 3 asks where the line between treasury decision and bet sits
  without answering it, so no rule is invented here.
- **No scheduling.** A cycle happens when something asks for one. §11 describes a decision cycle,
  not a daemon.

# PROBLEM.md

**Project:** a treasury and bookkeeping layer for a Kenyan business that earns dollars and spends shillings.
**Written for:** anyone joining this repo, human or model, who needs to understand *why* before touching *what*.
**Status:** problem statement. No code decisions here. Those live in `SPEC.md`.

---

## 1. The business we are building for

Picture a small Nairobi company. A dev shop, a BPO, an export trader, a design studio — the exact trade does not matter. What matters is the shape of its money:

```
  MONEY IN                          MONEY OUT
  ────────                          ─────────
  USD invoices                      KES payroll        (fixed date, monthly)
  irregular timing                  KES rent           (fixed date, monthly)
  irregular amount                  KES suppliers      (30-day terms)
  1 to 6 clients                    KES statutory      (fixed date, monthly)
                                    KES M-Pesa float   (small, constant)
```

Income speaks one language and arrives whenever it feels like it. Spending speaks another language and arrives on a calendar you cannot argue with.

Somebody, every month, has to stand between those two columns and make them meet. Today that somebody is a founder with a spreadsheet.

---

## 2. A day in the life, as it actually happens

1. A client pays a USD invoice.
2. Days pass. The money arrives somewhere — a bank, a payment provider, a wallet.
3. The founder converts some of it to shillings because payroll is on Friday.
4. A shilling amount lands in the bank.
5. The founder writes that shilling amount in a spreadsheet.

Step 5 is where the damage is done. Look at what was thrown away between step 1 and step 5:

- **The rate is gone.** The bank credited shillings. It did not say at what rate.
- **The cost is invisible.** The provider did not charge a fee. It gave a worse rate. The cost is real and is now unrecoverable from the records.
- **The link is gone.** The shilling credit carries no invoice number. Matching it to the invoice is done by eye, by memory, at month end.
- **The date basis is muddled.** The invoice was raised on one day, the money arrived on another, the conversion happened on a third. Three different days, three different rates, one number in the spreadsheet.
- **The leftovers have nowhere to go.** When the month-end totals don't agree, the difference gets forced into a miscellaneous line so the sheet balances.

That last one has a name in every core banking system: a **plug**. A number invented to make two sides agree. Every plug is a question nobody answered.

---

## 3. The six failures, named

These are the things this project exists to stop. Each is stated as a failure, not as a feature.

| # | Failure | What it looks like | Why it matters |
|---|---------|--------------------|----------------|
| F1 | **Rate amnesia** | A foreign amount is recorded in local money with no record of the rate used, its source, or its timestamp | You cannot re-derive any figure, so you cannot check any figure |
| F2 | **Blended cost** | Exchange difference, provider spread and explicit fee all collapse into one number | The business cannot tell whether it lost money to the market or to its provider |
| F3 | **Orphan credits** | Money arrives with no link to the obligation it settles | Matching becomes a monthly memory exercise, and old items are quietly abandoned |
| F4 | **The plug** | An invented figure forces a total to agree | The books balance and mean nothing |
| F5 | **Silent double payment** | A payment is retried because its outcome was unclear, and goes out twice | Money leaves and does not come back |
| F6 | **Unexplainable decisions** | Somebody (or something) decided to convert 6,000 on a Tuesday. Nobody can say why | No review is possible, so no trust is possible |

F5 and F6 become much worse the moment a software agent is making the timing decisions instead of a human. A human converting money remembers roughly what they did. A program does not remember anything it was not told to write down.

---

## 4. The words we will use

Plain definitions. These are fixed for the whole project — if a document uses one of these words, it means this and nothing else.

**Functional currency.** The one language the books are kept in. For us: **KES**. Everything in the accounts is ultimately a shilling number.

**Foreign currency.** Any other money the business holds or is owed. For us: **USD**, held as **USDC**.

**Obligation.** Something the business must pay, on a date, in an amount. Payroll. Rent. A supplier invoice.

**Receivable.** Something the business is owed. A client invoice.

**Rate.** How many shillings one dollar is worth, at a stated moment, from a stated source. A rate without a timestamp and a source is not a rate, it is a rumour.

**Mid-market rate.** The reference rate, the middle of the market. Nobody actually trades at it. It exists so you can measure how far from it you traded.

**Executed rate.** The rate you actually got.

**Spread.** The gap between the mid-market rate and the executed rate, expressed as money. This is a cost that never appears as a fee.

**Fee.** A cost charged openly, as a separate amount.

**Exchange difference.** The change in shilling value of the same foreign money between two points in time. Not a cost. Not income. Its own thing.

**Realised** — the difference crystallised because the money actually moved or converted.
**Unrealised** — the difference exists only because we re-measured money we are still holding.

**Suspense.** A holding account for money we have seen but cannot yet explain. Suspense is not a bin. Every item in it is a question with a date attached, and it gets older and more embarrassing until somebody answers it.

**Plug.** A number invented to force agreement. Forbidden. The entire project is an argument against plugs.

---

## 5. One worked example, carried all the way through

All figures are invented but internally consistent. Anybody should be able to reproduce every line with a calculator. This example is the acceptance test for whether the system is right.

Functional currency KES. Foreign currency USD held as USDC.

### Day 1 — invoice raised

USD 10,000 invoiced. Rate that day: **129.00**.

```
Dr  Receivable — Client A          1,290,000
    Cr  Revenue                              1,290,000
```

The receivable is now a shilling number *and* a dollar number. Both are true. Both are stored.

### Day 12 — the money arrives

USDC 10,000 received. Rate that day: **131.50** → 1,315,000 shillings of value.

```
Dr  USDC wallet                    1,315,000
    Cr  Receivable — Client A                1,290,000
    Cr  Exchange difference (realised)          25,000
```

**The 25,000 is not revenue.** The business did not sell anything extra. The dollar simply strengthened between the day the invoice was raised and the day it was paid. Recording it as revenue overstates the top line and hides an FX position. This is the single most common error in a small set of books.

### Day 20 — payroll

Payroll of KES 800,000 falls due. The agent converts USDC 6,000.

- Executed rate: **132.20** → KES **793,200** received.
- Those 6,000 units were carried in the books at the Day 12 rate of 131.50 → KES **789,000**.
- Difference: **4,200**.

```
Dr  Bank — KES                       793,200
    Cr  USDC wallet                            789,000
    Cr  Exchange difference (realised)           4,200
```

Now the part almost nobody records. Mid-market that morning was **132.60**. We got **132.20**. That gap of 0.40 per dollar, over 6,000 dollars, is **KES 2,400 of spread** — a genuine cost that appears nowhere on any statement, because it was baked into the rate.

And separately, the provider charged an explicit fee of 0.9%.

```
Memo posting — cost of conversion
    Spread (implicit)                  2,400
    Fee (explicit)                     7,139   ← 0.9% of 793,200
```

Two different costs, two different causes, two different fixes. Blending them tells you nothing. Splitting them tells you whether to renegotiate with your provider or change your timing.

### Day 30 — month end

USDC 4,000 still held. Closing rate: **130.80**.

- Carried at 4,000 × 131.50 = **526,000**
- Now worth 4,000 × 130.80 = **523,200**

```
Dr  Exchange difference (unrealised)     2,800
    Cr  USDC wallet                              2,800
```

Nothing moved. Nobody paid anybody. The books simply told the truth about what the remaining dollars are worth today. Kept separate from the realised figures, because one is history and the other is an opinion about today.

### What the example proves

Four distinct kinds of number appeared, and each has its own account:

```
  revenue          ─── what we earned
  exchange diff    ─── what the market did to us      (realised / unrealised)
  spread           ─── what our provider took quietly
  fee              ─── what our provider took openly
```

Collapse them and you get one figure that answers no question. Keep them apart and the business can act: chase the client faster, change provider, convert at a different time, or accept it.

---

## 6. What "correct" means here

These are the promises the system makes. They are written as things that must never happen, because that is how you test them.

- **I1** No amount is ever recorded in the books without its currency, its rate, the rate's source and the rate's timestamp. If any of those is missing, the posting is refused.
- **I2** Every journal entry balances — in each currency separately, and in shillings.
- **I3** Nothing invented. There is no line in the system whose purpose is to make a total agree.
- **I4** Exchange difference, spread and fee are three separate accounts and are never combined.
- **I5** Realised and unrealised are never mixed.
- **I6** Money that has arrived but cannot be explained goes to suspense, with a date, and ages visibly.
- **I7** A payment instruction whose outcome is unknown is never retried. Unknown is not failure.
- **I8** No obligation with a due date is left without funds because the agent decided to hold dollars.
- **I9** Every decision the agent makes is written down *before* it acts, including what it believed at the time.
- **I10** Any day's closing position can be reproduced from the records alone, by replay, with no human memory required.

I10 is the one to keep coming back to. It is the whole point. A reviewer in March should be able to replay September and get the same numbers.

---

## 7. What this project is not

Kenya now regulates this space properly. The Virtual Asset Service Providers Act commenced on 4 November 2025, and the VASP Regulations 2026 were gazetted on 22 July 2026 under Legal Notice 134. The Central Bank of Kenya supervises wallet providers, payment processors and stablecoin issuers; the Capital Markets Authority supervises exchanges and token platforms. Businesses converting virtual assets into fiat need separate CBK authorisation. Existing operators must be licensed by 4 November 2026.

So, stated plainly and permanently:

> This is an **open-source reference implementation of accounting and orchestration logic**. It does not convert currency. It does not hold customer money. It does not move real value. The conversion leg is a **mocked adapter** that assumes a licensed counterparty performs the actual conversion.

That is not a disclaimer bolted on at the end. It is a design boundary that decides where the code stops, and it is why the conversion adapter is an interface rather than an implementation. The exemption for genuine software providers turns on what the software actually does, not on what the README claims — so the boundary has to be real in the code.

---

## 8. Questions worth arguing about

Not settled. These are for thinking, not for coding around. Each one changes the design if answered differently.

1. When the dollar strengthens between invoice and payment, who earned that money — the business, or nobody? Does the answer change if the business chose to delay conversion?
2. Is spread a cost of doing business, or a loss? Would the books look different if we treated it as each?
3. The agent holds dollars instead of converting. Is that a treasury decision or a bet? Where is the line, and can a system enforce it?
4. If a payout's outcome is unknown for six hours, what is true about the books during those six hours? What is the honest posting?
5. Suspense items age. At what age does an unexplained item stop being an operational annoyance and become a statement about the business?
6. An agent writes down its reasoning before acting. If it later turns out the reasoning was wrong but the outcome was good, what should the record say?
7. Everything here assumes one functional currency. What genuinely breaks if a business thinks in two?
8. Replay reproduces the numbers. Does replay prove correctness, or only consistency?

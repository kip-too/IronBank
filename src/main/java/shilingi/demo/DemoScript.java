package shilingi.demo;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import shilingi.agent.Decision;
import shilingi.agent.ProposedAction;
import shilingi.agent.TreasuryAgent;
import shilingi.agent.TreasuryService;
import shilingi.books.Bookkeeper;
import shilingi.clock.MutableClock;
import shilingi.fx.ConversionFacts;
import shilingi.fx.ConversionPostings;
import shilingi.fx.FxEngine;
import shilingi.instruction.Instruction;
import shilingi.instruction.InstructionRepository;
import shilingi.ledger.AccountCodes;
import shilingi.ledger.Balances;
import shilingi.ledger.JournalEntry;
import shilingi.ledger.LedgerService;
import shilingi.ledger.Posting;
import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.money.Rate;
import shilingi.payout.CallbackIngest;
import shilingi.payout.MockPayoutRail;
import shilingi.payout.PayoutCallback;
import shilingi.payout.PayoutOutcome;
import shilingi.recon.ReconItem;
import shilingi.recon.Reconciler;
import shilingi.obligations.Obligation;
import shilingi.obligations.ObligationRepository;
import shilingi.receivables.Receivable;
import shilingi.receivables.ReceivableRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The thirty-day story, played start to finish with nobody touching anything.
 *
 * <p>SPEC.md section 15 day 12. It is PROBLEM.md section 5's worked example, run through the real
 * system - the real ledger, the real agent, the real FX engine - alongside {@link NaiveSheet}
 * doing what a founder with a spreadsheet does.
 *
 * <h2>Every figure can be checked against PROBLEM.md section 5</h2>
 * That is deliberate, and it took one decision to arrange: <b>the opening bank balance is
 * KES 84,400.</b>
 *
 * <p>With payroll at 800,000 and the buffer at 10%, the agent's own rule works out
 * {@code cover 880,000 - have 84,400 = 795,600 short}, and {@code 795,600 / 132.60} is
 * <b>exactly 6,000 dollars</b> - the amount PROBLEM.md converts on day 20. Nothing is
 * special-cased: the rule runs unchanged, and the opening balance is the one free variable a demo
 * has to pick anyway. Picking it so an audience can check the output against the document they
 * were given is worth more than picking a round number.
 *
 * <p>Change the opening balance and the agent converts a different amount, correctly. There is a
 * test that asserts exactly that, so this cannot quietly become a special case.
 *
 * <h2>The refusal</h2>
 * SPEC.md section 11 calls it "the demo's strongest single moment - show a model proposing
 * something sensible-sounding that the system refuses". Day 20 does it twice over: a plausible,
 * well-argued proposal to hold dollars is refused by invariant I8, the refusal is written into the
 * intent log, <b>no instruction is created</b>, and then the dull rule runs and is permitted.
 *
 * <h2>What this does not do</h2>
 * <ul>
 *   <li><b>It resets the database before it runs.</b> Demo profile only. A story that cannot be
 *       replayed from the start is not a demo, it is a first run.</li>
 *   <li><b>No payout is posted</b>, because the chart cannot express one - ADR-025. The story
 *       funds payroll; it does not pay it, and the screen says so rather than implying otherwise.</li>
 *   <li><b>The settlement leg is not called.</b> The conversion is recorded as having happened,
 *       which is the regulatory boundary in PROBLEM.md section 7, and the sidecar has its own
 *       proof in SidecarContractTest.</li>
 * </ul>
 */
@Service
@Profile("demo")
public class DemoScript {

    /** The one free variable, chosen so the agent's rule lands on PROBLEM.md's 6,000. */
    public static final Money OPENING_BANK_BALANCE = Money.of(8_440_000L, Currency.KES);

    private static final LocalDate DAY_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate DAY_12 = LocalDate.of(2026, 9, 12);
    private static final LocalDate DAY_20 = LocalDate.of(2026, 9, 20);
    private static final LocalDate DAY_25 = LocalDate.of(2026, 9, 25);
    private static final LocalDate DAY_30 = LocalDate.of(2026, 9, 30);

    /** Small, and unexplained. PROBLEM.md F3 does not need to be large to be a problem. */
    private static final Money UNEXPLAINED = Money.of(1_250_000L, Currency.KES);

    private final Flyway flyway;
    private final MutableClock clock;
    private final LedgerService ledger;
    private final Bookkeeper books;
    private final FxEngine fx;
    private final TreasuryAgent agent;
    private final TreasuryService treasury;
    private final InstructionRepository instructions;
    private final ReceivableRepository receivables;
    private final ObligationRepository obligations;
    private final Balances balances;
    private final CallbackIngest ingest;
    private final Reconciler reconciler;

    public DemoScript(Flyway flyway, MutableClock clock, LedgerService ledger, Bookkeeper books,
                      FxEngine fx, TreasuryAgent agent, TreasuryService treasury,
                      InstructionRepository instructions, ReceivableRepository receivables,
                      ObligationRepository obligations, Balances balances,
                      CallbackIngest ingest, Reconciler reconciler) {
        this.flyway = flyway;
        this.clock = clock;
        this.ledger = ledger;
        this.books = books;
        this.fx = fx;
        this.agent = agent;
        this.treasury = treasury;
        this.instructions = instructions;
        this.receivables = receivables;
        this.obligations = obligations;
        this.balances = balances;
        this.ingest = ingest;
        this.reconciler = reconciler;
    }

    /** One step of the story, as both columns saw it. */
    public record Act(LocalDate on, String what, String correct, String naive) {
    }

    public record Story(List<Act> acts, NaiveSheet sheet, Decision refusal, Decision permitted,
                        Money convertedDollars, String instructionRef,
                        List<ReconItem> unanswered, List<ReconItem> exceptions) {
    }

    /**
     * Plays the whole thing. No arguments, no prompts, no pauses.
     *
     * <p>SPEC.md section 15: done when it "runs start to finish without a human touching anything".
     */
    public Story play() {
        reset();

        List<Act> acts = new ArrayList<>();
        NaiveSheet sheet = new NaiveSheet();

        openTheBooks(acts);
        Receivable invoice = dayOne(acts);
        dayTwelve(acts, invoice);
        DayTwentyResult twenty = dayTwenty(acts, sheet);
        dayTwentyFive(acts);
        dayThirty(acts);

        return new Story(acts, sheet, twenty.refusal(), twenty.permitted(),
                twenty.converted(), twenty.instructionRef(),
                reconciler.open(), reconciler.exceptions());
    }

    private void reset() {
        // Demo profile only. Flyway's clean is disabled by default and enabled on this profile
        // for exactly this reason.
        flyway.clean();
        flyway.migrate();
        clock.setTo(DemoClockConfig.OPENS_AT);
    }

    private void openTheBooks(List<Act> acts) {
        // The opening balance has to enter the books somehow, and it enters through the ledger
        // like everything else - balanced, dated, and refused if it were not.
        //
        // IT IS CREDITED TO PAYABLES, NOT TO REVENUE, and that is not a detail.
        //
        // Cash brought forward is not something the business earned this month. Crediting it to
        // 4000 would inflate the exact figure this demo is arguing about - an audience would be
        // right to call that - so the opening cash is shown as held against what is owed.
        //
        // The textbook credit is equity or retained earnings, and SPEC.md section 6's chart has
        // neither. That is the SAME finding as ADR-025's missing expense account: the chart
        // expresses the treasury cleanly and cannot express the rest of a set of books. Recorded
        // rather than papered over.
        ledger.post(JournalEntry.of(DAY_1, "Opening cash, brought forward", "demo/opening",
                clock.instant(),
                List.of(Posting.inShillings(AccountCodes.BANK_KES, OPENING_BANK_BALANCE),
                        Posting.inShillings(AccountCodes.PAYABLES, OPENING_BANK_BALANCE.negate()))));

        obligations.save(Obligation.scheduled("Payroll September",
                Money.of(80_000_000L, Currency.KES), DAY_20));

        acts.add(new Act(DAY_1, "The month opens",
                "Bank " + Figures.of(OPENING_BANK_BALANCE) + " brought forward, held against "
                + "amounts owed - not counted as revenue. Payroll of KES 800,000 scheduled for "
                + "the 20th, unfunded and visible as such.",
                "Nothing to write down yet."));
    }

    private Receivable dayOne(List<Act> acts) {
        moveTo(DAY_1);

        Receivable invoice = receivables.save(Receivable.raised(
                "Client A", Money.of(10_000_000_000L, Currency.USDC), DAY_1));
        books.recordInvoice(invoice);

        acts.add(new Act(DAY_1, "USD 10,000 invoiced to Client A, rate that day 129.00",
                "Dr 1200 Receivables 10,000 USDC at 129.00 = KES 1,290,000 / Cr 4000 Revenue. "
                + "The receivable is a shilling number AND a dollar number, and both are stored.",
                "Nothing. No money has moved, so nothing goes in the sheet."));
        return invoice;
    }

    private void dayTwelve(List<Act> acts, Receivable invoice) {
        moveTo(DAY_12);

        books.recordReceipt(invoice.id(), DAY_12);

        acts.add(new Act(DAY_12, "USDC 10,000 arrives, rate that day 131.50",
                "Dr 1100 Wallet KES 1,315,000 / Cr 1200 Receivables KES 1,290,000 / "
                + "Cr 6100 Exchange difference KES 25,000. The 25,000 is NOT revenue - the dollar "
                + "strengthened between the invoice and the payment.",
                "Nothing. Dollars are not shillings yet, so there is nothing to write."));
    }

    private record DayTwentyResult(Decision refusal, Decision permitted, Money converted,
                                   String instructionRef) {
    }

    private DayTwentyResult dayTwenty(List<Act> acts, NaiveSheet sheet) {
        moveTo(DAY_20);

        // ---- the refusal -------------------------------------------------------------------
        // A proposal of exactly the kind a language model would make: specific, plausible, and
        // arguing for the thing that would leave payroll unfunded.
        Decision refusal = agent.decideOn(
                "payroll due today",
                new ProposedAction.Hold("waiting for a better rate"),
                "The shilling has weakened three sessions running and the mid has moved from "
                + "131.50 to 132.60 in eight days. Holding the dollars another week should get a "
                + "materially better conversion and improve the month's result.");

        acts.add(new Act(DAY_20, "A proposal to hold dollars, well argued",
                "REFUSED by invariant I8, in Java, before any instruction exists. "
                + refusal.violation().orElseThrow().getMessage()
                + " The refusal is written into the intent log and no instruction is created.",
                "No such check exists. Holding would simply have happened, and payroll would have "
                + "been short with nothing recording why."));

        // ---- the dull rule -----------------------------------------------------------------
        Decision permitted = agent.runCycle("payroll due today");
        Instruction.Created created = treasury.act(permitted).orElseThrow();
        Money converted = created.amount();

        acts.add(new Act(DAY_20, "The agent's own rule runs",
                "needed 800,000, cover 880,000, have 84,400, short 795,600, and at the mid of "
                + "132.60 that is exactly " + Figures.of(converted) + ", rounded up to whole dollars. "
                + "Intent committed first, instruction created second, reference " + created.externalRef() + ".",
                "A number is chosen. Nothing records how, or against what."));

        // ---- the conversion happens, and is recorded ----------------------------------------
        Instruction.Submitted submitted = created.submit(clock.instant());
        instructions.transitionTo(submitted);

        Rate executed = Rate.executed("132.20", "provider", clock.instant());
        Rate mid = Rate.mid("132.60", "worked-example", clock.instant());
        Money fee = Money.of(713_880L, Currency.KES);   // 0.9% of 793,200, exactly

        ConversionPostings postings = fx.postingsFor(new ConversionFacts(
                DAY_20, converted, executed, mid, fee, created.externalRef()), clock.instant());
        postings.all().forEach(ledger::post);

        instructions.transitionTo(submitted.confirmed(clock.instant(), "demo/settled"));

        // The naive method, step 3: write down the shilling amount the bank credited.
        Money landed = executed.toShillings(converted);
        sheet.writeDown(DAY_20, "Client A", landed, "converted");

        acts.add(new Act(DAY_20, "6,000 converted at 132.20; mid was 132.60; fee 0.9%",
                "Dr 1000 Bank KES 793,200 / Cr 1100 Wallet KES 789,000 (what the books carried "
                + "those dollars at) / Cr 6100 KES 4,200. Then, kept separate: "
                + "Dr 6200 Spread KES 2,400 / Cr 6100, and Dr 6210 Fee KES 7,138.80 / Cr 2000 Payables.",
                "Write 793,200 in the sheet against Client A. The rate, the spread and the fee are "
                + "not recorded anywhere, because nobody sent an invoice for them."));

        return new DayTwentyResult(refusal, permitted, converted, created.externalRef());
    }

    /**
     * PROBLEM.md F3, and the thing the naive method has no answer to at all.
     *
     * <p>The rail reports a payment this system never instructed. It is not an error to swallow:
     * it is money that moved for a reason nobody here knows, so it is recorded, posted to suspense
     * with a date on it, and it starts ageing.
     */
    private void dayTwentyFive(List<Act> acts) {
        moveTo(DAY_25);

        PayoutCallback fromNowhere = new PayoutCallback(
                MockPayoutRail.RAIL, "rail/unexpected-88", "payout/never-instructed",
                PayoutOutcome.SUCCEEDED, UNEXPLAINED, clock.instant(),
                shilingi.intent.Snapshot.of()
                        .with("rail", MockPayoutRail.RAIL)
                        .with("note", "reported by the provider; no instruction carries this reference")
                        .toJson());

        ingest.accept(fromNowhere);
        Reconciler.Report report = reconciler.reconcile();

        acts.add(new Act(DAY_25, "The rail reports " + Figures.of(UNEXPLAINED)
                + " nobody instructed",
                "Recorded before it was judged, then raised as an unmatched item and posted "
                + "Dr 1900 Suspense / Cr 1000 Bank with the date it was first seen. "
                + report.raised() + " question now ageing. It is not an error to swallow: it is "
                + "money that moved for a reason nobody here knows.",
                "The bank statement shows a payment. It is reconciled by eye at month end, or it "
                + "is not."));
    }

    private void dayThirty(List<Act> acts) {
        moveTo(DAY_30);

        books.revalueWallet(DAY_30).orElseThrow();

        acts.add(new Act(DAY_30, "Month end. USDC 4,000 still held, closing rate 130.80",
                "Dr 6110 Exchange difference (unrealised) KES 2,800 / Cr 1100 Wallet KES 2,800. "
                + "Nothing moved and nobody paid anybody - the books simply told the truth about "
                + "what the remaining dollars are worth today. 6110, never 6100: I5 would refuse "
                + "an entry that touched both.",
                "Sum the column. One number: " + "KES 793,200" + ", and call it revenue."));
    }

    private void moveTo(LocalDate day) {
        clock.setTo(day.atTime(6, 0).atZone(DemoClockConfig.ZONE).toInstant());
    }

    // --- what the two columns ended up with ---------------------------------------------

    public Money balanceOf(String accountCode) {
        return balances.carryingValueOf(accountCode);
    }

    public Money walletDollars() {
        return balances.balanceOf(AccountCodes.WALLET_USDC, Currency.USDC);
    }

    public Instant now() {
        return clock.instant();
    }
}

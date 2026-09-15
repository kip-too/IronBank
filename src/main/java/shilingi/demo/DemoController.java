package shilingi.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import shilingi.ledger.AccountCodes;
import shilingi.money.Money;

import java.util.List;

/**
 * The one demonstration screen (SPEC.md section 2 and section 4).
 *
 * <p>Two panels, the same thirty-day story, side by side. Hit {@code /} and it plays itself -
 * SPEC.md section 15 says done means "runs start to finish without a human touching anything", so
 * there is no start button and nothing to click.
 *
 * <h2>No template engine</h2>
 * SPEC.md section 3's technology table names none, and rule 6 makes every library a decision. One
 * page does not justify Thymeleaf, so this writes HTML with Java 21 text blocks. Ugly in a
 * codebase with fifty screens; entirely reasonable in one with a single screen whose whole job is
 * to be read once.
 *
 * <h2>What the screen is careful about</h2>
 * The naive panel is not styled as a failure. No red, no warnings, no animation of it going wrong.
 * Its arithmetic is correct and the screen says so out loud, because ADR-023's fairness argument
 * only works if the audience can see it is fair. What the panel cannot do is answer a question,
 * and the four-question table is where that lands.
 */
@RestController
@Profile("demo")
public class DemoController {

    private final DemoScript demo;

    public DemoController(DemoScript demo) {
        this.demo = demo;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String screen() {
        DemoScript.Story story = demo.play();
        return page(story);
    }

    private String page(DemoScript.Story story) {
        return """
                <!-- shilingi: one screen, server-rendered, no framework -->
                <title>shilingi — thirty days, two sets of books</title>
                <style>
                  :root { color-scheme: light dark; }
                  body { font: 15px/1.55 ui-sans-serif, system-ui, -apple-system, Segoe UI, sans-serif;
                         margin: 0; padding: 32px 20px 64px; max-width: 1180px; margin-inline: auto; }
                  h1 { font-size: 1.5rem; margin: 0 0 4px; letter-spacing: -0.01em; }
                  .sub { opacity: .68; margin: 0 0 28px; }
                  h2 { font-size: 1.05rem; margin: 34px 0 10px; }
                  table { border-collapse: collapse; width: 100%%; margin: 10px 0 4px; font-size: 14px; }
                  th, td { text-align: left; padding: 9px 12px; border-bottom: 1px solid rgba(128,128,128,.28);
                           vertical-align: top; }
                  th { font-weight: 600; opacity: .72; font-size: 12.5px; text-transform: uppercase;
                       letter-spacing: .04em; }
                  td.num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
                  .cols { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
                  @media (max-width: 860px) { .cols { grid-template-columns: 1fr; } }
                  .panel { border: 1px solid rgba(128,128,128,.34); border-radius: 10px; padding: 16px 18px; }
                  .panel h3 { margin: 0 0 2px; font-size: 1rem; }
                  .panel .who { opacity: .66; font-size: 13px; margin: 0 0 12px; }
                  .cannot { opacity: .5; font-style: italic; }
                  .refusal { border-left: 3px solid #b8860b; padding: 12px 16px; margin: 14px 0;
                             background: rgba(184,134,11,.07); border-radius: 0 8px 8px 0; }
                  .note { opacity: .7; font-size: 13.5px; }
                  code { font: 13px ui-monospace, SFMono-Regular, Menlo, monospace; }
                </style>

                <h1>shilingi</h1>
                <p class="sub">Thirty days of one small Nairobi business, kept two ways.
                   Every figure below matches <code>PROBLEM.md</code> §5, so you can check it.</p>

                <h2>What happened</h2>
                <table>
                  <tr><th style="width:92px">Date</th><th style="width:30%%">Event</th>
                      <th>This system</th><th>A spreadsheet</th></tr>
                  %s
                </table>

                <div class="refusal">
                  <strong>The 20th, before anything was converted.</strong><br>
                  A proposal arrived to hold the dollars, argued like this:
                  <em>“%s”</em><br><br>
                  <strong>Refused.</strong> %s<br><br>
                  <span class="note">The reasoning was never read by the check. Invariant I8 is
                  handed an action and a set of facts, and nothing else — so a well-argued proposal
                  and a badly-argued one that amount to the same action get the same answer. The
                  refusal is in the intent log; <strong>no instruction was created.</strong></span>
                </div>

                <div class="cols">
                  <div class="panel">
                    <h3>This system</h3>
                    <p class="who">Four accounts, because four different things happened.</p>
                    <table>
                      <tr><th>Account</th><th class="num">Balance</th></tr>
                      %s
                    </table>
                    <p class="note">Still holding %s, carried at what it was worth when it arrived.</p>
                  </div>

                  <div class="panel">
                    <h3>A spreadsheet</h3>
                    <p class="who">One column, summed at month end.</p>
                    <table>
                      <tr><th>Date</th><th>Against</th><th class="num">Shillings</th></tr>
                      %s
                      <tr><th>%s</th><th></th><th class="num">%s</th></tr>
                    </table>
                    <p class="note"><strong>This sum is correct.</strong> It is exactly what the
                    bank credited, added up without error. Nothing here is a straw man — it is the
                    method <code>PROBLEM.md</code> §2 describes, and it never fails to balance.</p>
                  </div>
                </div>

                <h2>The same four questions, asked of both</h2>
                <table>
                  <tr><th style="width:44%%">Question</th><th>This system</th><th>A spreadsheet</th></tr>
                  %s
                </table>
                <p class="note">Three of four come back blank on the right — not through
                   carelessness, but because the rate, the spread and the fee were discarded before
                   anything was written down. They are not recoverable from those records by
                   anyone, at any later date, however carefully they look.</p>

                <h2>Questions nobody has answered yet</h2>
                <p class="note">PROBLEM.md §4 on suspense: <em>“not a bin. Every item in it is a
                   question with a date attached, and it gets older and more embarrassing until
                   somebody answers it.”</em> The age is worked out, never stored — an age that is
                   stored is true until the next day and then false, silently.</p>
                <table>
                  <tr><th>Raised</th><th style="width:34%%">What</th><th class="num">Business days</th>
                      <th class="num">Amount</th></tr>
                  %s
                </table>
                <p class="note">Suspense (account 1900) stands at <strong>%s</strong>. It is on the
                   balance sheet, it is ageing, and it cannot be closed without somebody putting
                   their name to an explanation.</p>

                <h2>What this screen is not showing you</h2>
                <p class="note">
                  The payroll itself is <strong>funded, not paid</strong>: <code>SPEC.md</code> §6's
                  chart has no expense account, so an obligation cannot be accrued without inventing
                  one — raised as a finding in ADR-025 rather than plugged. The conversion is
                  recorded as having happened; this system does not convert currency
                  (<code>PROBLEM.md</code> §7). And the transaction hash on the dollar leg is
                  simulated — see <code>REAL_VS_SIMULATED.md</code>, which says why in full.
                </p>
                """
                .formatted(
                        acts(story),
                        escape(story.refusal().intent().reasoning()),
                        escape(story.refusal().violation().orElseThrow().getMessage()),
                        accounts(),
                        Figures.of(demo.walletDollars()),
                        sheetRows(story),
                        story.sheet().labelForTotal(),
                        Figures.of(story.sheet().total()),
                        questions(story),
                        reconItems(story),
                        Figures.of(demo.balanceOf(AccountCodes.SUSPENSE)));
    }

    private String acts(DemoScript.Story story) {
        StringBuilder rows = new StringBuilder();
        for (DemoScript.Act act : story.acts()) {
            rows.append("<tr><td>").append(act.on())
                    .append("</td><td>").append(escape(act.what()))
                    .append("</td><td>").append(escape(act.correct()))
                    .append("</td><td>").append(escape(act.naive()))
                    .append("</td></tr>");
        }
        return rows.toString();
    }

    private String accounts() {
        record Line(String code, String name) {
        }
        List<Line> lines = List.of(
                new Line(AccountCodes.REVENUE, "4000 Revenue — what we earned"),
                new Line(AccountCodes.EXCHANGE_DIFFERENCE_REALISED,
                        "6100 Exchange difference, realised — what the market did"),
                new Line(AccountCodes.EXCHANGE_DIFFERENCE_UNREALISED,
                        "6110 Exchange difference, unrealised — an opinion about today"),
                new Line(AccountCodes.CONVERSION_SPREAD,
                        "6200 Spread — what the provider took quietly"),
                new Line(AccountCodes.CONVERSION_FEE,
                        "6210 Fee — what the provider took openly"),
                new Line(AccountCodes.PAYABLES, "2000 Payables — opening cash held, plus the fee owed"),
                new Line(AccountCodes.BANK_KES, "1000 Bank — KES"),
                new Line(AccountCodes.WALLET_USDC, "1100 Wallet — USDC, at carrying value"));

        StringBuilder rows = new StringBuilder();
        for (Line line : lines) {
            rows.append("<tr><td>").append(escape(line.name()))
                    .append("</td><td class=\"num\">").append(present(line.code()))
                    .append("</td></tr>");
        }
        return rows.toString();
    }

    private String sheetRows(DemoScript.Story story) {
        StringBuilder rows = new StringBuilder();
        for (NaiveSheet.Row row : story.sheet().rows()) {
            rows.append("<tr><td>").append(row.on())
                    .append("</td><td>").append(escape(row.against()))
                    .append("</td><td class=\"num\">").append(Figures.of(row.shillings()))
                    .append("</td></tr>");
        }
        return rows.toString();
    }

    /**
     * Credits are negative internally (SPEC.md section 5). Showing a reader "Revenue: -1,290,000"
     * would be technically true and actively confusing, so the four accounts that naturally carry
     * a credit balance are flipped here, in presentation, and nowhere else.
     */
    private String present(String accountCode) {
        Money balance = demo.balanceOf(accountCode);
        boolean naturallyCredit = accountCode.equals(AccountCodes.REVENUE)
                || accountCode.equals(AccountCodes.EXCHANGE_DIFFERENCE_REALISED)
                || accountCode.equals(AccountCodes.PAYABLES);
        return naturallyCredit ? Figures.asPositive(balance) : Figures.of(balance);
    }

    private String reconItems(DemoScript.Story story) {
        if (story.unanswered().isEmpty()) {
            return "<tr><td colspan=\"4\" class=\"cannot\">Nothing unexplained.</td></tr>";
        }

        java.time.LocalDate today = java.time.LocalDate.ofInstant(demo.now(), DemoClockConfig.ZONE);
        StringBuilder rows = new StringBuilder();

        for (var item : story.unanswered()) {
            long age = item.ageInBusinessDaysOn(today);
            boolean past = story.exceptions().stream().anyMatch(e -> e.id().equals(item.id()));

            rows.append("<tr><td>").append(item.firstSeen())
                    .append("</td><td>").append(escape(item.kind().name().replace('_', ' ').toLowerCase()))
                    .append("<br><span class=\"note\">").append(escape(item.detail())).append("</span>")
                    .append("</td><td class=\"num\">").append(age)
                    .append(past ? " <strong>— exception</strong>" : " <span class=\"note\">— noise</span>")
                    .append("</td><td class=\"num\">")
                    .append(item.amount().map(Figures::of).orElse("—"))
                    .append("</td></tr>");
        }
        return rows.toString();
    }

    private String questions(DemoScript.Story story) {
        Money realised = demo.balanceOf(AccountCodes.EXCHANGE_DIFFERENCE_REALISED);
        Money spread = demo.balanceOf(AccountCodes.CONVERSION_SPREAD);
        Money fee = demo.balanceOf(AccountCodes.CONVERSION_FEE);

        record Row(NaiveSheet.Question question, String ours) {
        }
        List<Row> rows = List.of(
                new Row(NaiveSheet.Question.WHAT_WAS_EARNED,
                        Figures.asPositive(demo.balanceOf(AccountCodes.REVENUE))
                        + " — the invoice, at the rate on the day it was raised"),
                new Row(NaiveSheet.Question.HOW_MUCH_WAS_THE_DOLLAR_MOVING,
                        Figures.asPositive(realised) + " realised in 6100, measured against mid, plus "
                        + Figures.of(demo.balanceOf(AccountCodes.EXCHANGE_DIFFERENCE_UNREALISED))
                        + " unrealised in 6110"),
                new Row(NaiveSheet.Question.WHAT_DID_THE_PROVIDER_CHARGE,
                        Figures.of(spread) + " of spread and " + Figures.of(fee)
                        + " of fee — separately"),
                new Row(NaiveSheet.Question.CAN_THE_DAY_BE_REPRODUCED,
                        "Yes — every posting carries the rate, its source and its timestamp"));

        StringBuilder html = new StringBuilder();
        for (Row row : rows) {
            String theirs = story.sheet().answerTo(row.question());
            if (theirs != null) {
                theirs = Figures.of(story.sheet().total());
            }
            html.append("<tr><td>").append(escape(row.question().asked()))
                    .append("</td><td>").append(escape(row.ours()))
                    .append("</td><td>")
                    .append(theirs == null
                            ? "<span class=\"cannot\">cannot say</span>"
                            : escape(theirs))
                    .append("</td></tr>");
        }
        return html.toString();
    }

    /** Everything rendered here is this system's own text, but escaping it is free and correct. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

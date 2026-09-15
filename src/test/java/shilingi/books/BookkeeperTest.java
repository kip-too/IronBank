package shilingi.books;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import shilingi.ledger.AccountCodes;
import shilingi.ledger.Balances;
import shilingi.ledger.JournalEntry;
import shilingi.ledger.LedgerRejection;
import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.platform.AbstractDatabaseTest;
import shilingi.receivables.Receivable;
import shilingi.receivables.ReceivableRepository;
import shilingi.receivables.ReceivableStatus;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * PROBLEM.md section 5, now posted to the books by business events rather than by hand-built
 * fixtures. The rates come from V5's seeded table, so the dates below are the worked example's.
 *
 * <p>All figures in CENTS: PROBLEM.md writes "1,290,000 shillings" and KES has scale 2.
 */
class BookkeeperTest extends AbstractDatabaseTest {

    @Autowired
    private Bookkeeper books;

    @Autowired
    private ReceivableRepository receivables;

    @Autowired
    private Balances balances;

    private static final LocalDate DAY_1 = LocalDate.of(2026, 9, 1);    // mid 129.00
    private static final LocalDate DAY_12 = LocalDate.of(2026, 9, 12);  // mid 131.50
    private static final LocalDate DAY_30 = LocalDate.of(2026, 9, 30);  // mid 130.80

    private static Money usdc(long dollars) {
        return Money.of(dollars * 1_000_000L, Currency.USDC);
    }

    private static Money kes(long cents) {
        return Money.of(cents, Currency.KES);
    }

    private Receivable invoiceForTenThousand() {
        return receivables.save(Receivable.raised("Client A", usdc(10_000), DAY_1));
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("PROBLEM.md section 5 day 1: invoice raised")
    class DayOne {

        @Test
        @DisplayName("USD 10,000 at 129.00 is a shilling number and a dollar number, both stored")
        void the_invoice_posts_at_the_issue_date_rate() {
            Receivable invoice = invoiceForTenThousand();

            JournalEntry entry = books.recordInvoice(invoice);

            assertThat(entry.postings()).hasSize(2);
            assertThat(balances.carryingValueOf(AccountCodes.RECEIVABLES)).isEqualTo(kes(129_000_000L));
            assertThat(balances.balanceOf(AccountCodes.RECEIVABLES, Currency.USDC)).isEqualTo(usdc(10_000));
            assertThat(balances.carryingValueOf(AccountCodes.REVENUE)).isEqualTo(kes(-129_000_000L));
        }

        @Test
        @DisplayName("a day the feed has no rate for is refused, not guessed at")
        void no_rate_means_no_posting() {
            // The 13th is deliberately absent from V5's table - see that migration's header.
            Receivable invoice = receivables.save(
                    Receivable.raised("Client A", usdc(1_000), LocalDate.of(2026, 9, 13)));

            assertThatExceptionOfType(NoRateForDateException.class)
                    .isThrownBy(() -> books.recordInvoice(invoice))
                    .withMessageContaining("looks exactly like a fresh one");

            assertThat(balances.carryingValueOf(AccountCodes.RECEIVABLES)).isEqualTo(Money.zero(Currency.KES));
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("PROBLEM.md section 5 day 12: the money arrives")
    class DayTwelve {

        @Test
        @DisplayName("the 25,000 goes to 6100 and nowhere near revenue")
        void the_exchange_difference_is_not_revenue() {
            Receivable invoice = invoiceForTenThousand();
            books.recordInvoice(invoice);

            books.recordReceipt(invoice.id(), DAY_12);

            assertThat(balances.carryingValueOf(AccountCodes.WALLET_USDC))
                    .as("10,000 at 131.50")
                    .isEqualTo(kes(131_500_000L));
            assertThat(balances.carryingValueOf(AccountCodes.RECEIVABLES))
                    .as("cleared at what it was carried at")
                    .isEqualTo(Money.zero(Currency.KES));
            assertThat(balances.carryingValueOf(AccountCodes.EXCHANGE_DIFFERENCE_REALISED))
                    .as("25,000 shillings of realised difference, as a credit")
                    .isEqualTo(kes(-2_500_000L));

            assertThat(balances.carryingValueOf(AccountCodes.REVENUE))
                    .as("revenue is still exactly the invoice - the strengthening dollar added nothing")
                    .isEqualTo(kes(-129_000_000L));
        }

        @Test
        void the_receivable_is_settled() {
            Receivable invoice = invoiceForTenThousand();
            books.recordInvoice(invoice);
            books.recordReceipt(invoice.id(), DAY_12);

            assertThat(receivables.findById(invoice.id()).orElseThrow().status())
                    .isEqualTo(ReceivableStatus.SETTLED);
            assertThat(receivables.outstanding()).isEmpty();
        }

        @Test
        @DisplayName("a second receipt against the same invoice is refused")
        void one_receipt_cannot_explain_two_invoices() {
            Receivable invoice = invoiceForTenThousand();
            books.recordInvoice(invoice);
            books.recordReceipt(invoice.id(), DAY_12);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> books.recordReceipt(invoice.id(), DAY_12))
                    .withMessageContaining("F3");
        }

        @Test
        @DisplayName("a weakening dollar produces a debit to 6100, and nothing else changes shape")
        void the_difference_works_in_both_directions() {
            // Invoiced on the 12th at 131.50, received on the 30th at 130.80 - the dollar fell.
            Receivable invoice = receivables.save(
                    Receivable.raised("Client B", usdc(10_000), DAY_12));
            books.recordInvoice(invoice);

            books.recordReceipt(invoice.id(), DAY_30);

            assertThat(balances.carryingValueOf(AccountCodes.EXCHANGE_DIFFERENCE_REALISED))
                    .as("10,000 x (130.80 - 131.50) = -7,000 shillings, a debit")
                    .isEqualTo(kes(700_000L));
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("PROBLEM.md section 5 day 30: month-end revaluation")
    class DayThirty {

        @Test
        @DisplayName("nothing moved, and the books still told the truth")
        void the_wallet_is_revalued_without_moving_a_dollar() {
            // Get to day 30's opening position the way the story does: invoice, receive, and hold.
            Receivable invoice = receivables.save(Receivable.raised("Client A", usdc(4_000), DAY_1));
            books.recordInvoice(invoice);
            books.recordReceipt(invoice.id(), DAY_12);

            assertThat(balances.carryingValueOf(AccountCodes.WALLET_USDC))
                    .as("4,000 carried at 131.50 = 526,000")
                    .isEqualTo(kes(52_600_000L));

            JournalEntry revaluation = books.revalueWallet(DAY_30).orElseThrow();

            assertThat(balances.carryingValueOf(AccountCodes.EXCHANGE_DIFFERENCE_UNREALISED))
                    .as("4,000 x (131.50 - 130.80) = 2,800 shillings, a debit")
                    .isEqualTo(kes(280_000L));
            assertThat(balances.carryingValueOf(AccountCodes.WALLET_USDC))
                    .as("now worth 4,000 x 130.80 = 523,200")
                    .isEqualTo(kes(52_320_000L));
            assertThat(balances.balanceOf(AccountCodes.WALLET_USDC, Currency.USDC))
                    .as("and not one dollar moved")
                    .isEqualTo(usdc(4_000));

            assertThat(revaluation.postings())
                    .filteredOn(p -> p.accountCode().equals(AccountCodes.WALLET_USDC))
                    .singleElement()
                    .satisfies(p -> assertThat(p.amount()).isEqualTo(Money.zero(Currency.USDC)));
        }

        @Test
        @DisplayName("I5: a revaluation touches 6110 and never 6100")
        void realised_and_unrealised_stay_apart() {
            Receivable invoice = receivables.save(Receivable.raised("Client A", usdc(4_000), DAY_1));
            books.recordInvoice(invoice);
            books.recordReceipt(invoice.id(), DAY_12);

            JournalEntry revaluation = books.revalueWallet(DAY_30).orElseThrow();

            assertThat(revaluation.postings()).extracting(shilingi.ledger.Posting::accountCode)
                    .contains(AccountCodes.EXCHANGE_DIFFERENCE_UNREALISED)
                    .doesNotContain(AccountCodes.EXCHANGE_DIFFERENCE_REALISED);
        }

        @Test
        void an_empty_wallet_is_not_revalued() {
            assertThat(books.revalueWallet(DAY_30)).isEmpty();
        }

        @Test
        @DisplayName("a wallet already carried at today's rate produces no entry of zeroes")
        void nothing_to_say_means_nothing_is_said() {
            Receivable invoice = receivables.save(Receivable.raised("Client A", usdc(4_000), DAY_1));
            books.recordInvoice(invoice);
            books.recordReceipt(invoice.id(), DAY_30);

            assertThat(books.revalueWallet(DAY_30))
                    .as("an entry full of zeroes says nothing and still has to be read")
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("the whole story: every figure in PROBLEM.md section 5 is in the books")
    void the_worked_example_end_to_end() {
        Receivable invoice = invoiceForTenThousand();
        books.recordInvoice(invoice);
        books.recordReceipt(invoice.id(), DAY_12);

        // Revenue is the invoice, and only the invoice.
        assertThat(balances.carryingValueOf(AccountCodes.REVENUE)).isEqualTo(kes(-129_000_000L));
        // Exchange difference is the market, and only the market.
        assertThat(balances.carryingValueOf(AccountCodes.EXCHANGE_DIFFERENCE_REALISED))
                .isEqualTo(kes(-2_500_000L));
        // And the wallet holds what it should, at what it should.
        assertThat(balances.balanceOf(AccountCodes.WALLET_USDC, Currency.USDC)).isEqualTo(usdc(10_000));
        assertThat(balances.carryingValueOf(AccountCodes.WALLET_USDC)).isEqualTo(kes(131_500_000L));
    }

    @Test
    @DisplayName("every entry the bookkeeper makes survives the ledger's own seven checks")
    void nothing_here_bypasses_the_ledger() {
        // The bookkeeper posts through LedgerService like everything else. If any entry did not
        // balance, or broke I1 or I5, it would have been refused rather than written.
        Receivable invoice = invoiceForTenThousand();

        assertThat(books.recordInvoice(invoice).id()).isNotNull();
        assertThat(books.recordReceipt(invoice.id(), DAY_12).id()).isNotNull();
        assertThat(books.revalueWallet(DAY_30).orElseThrow().id()).isNotNull();

        assertThatExceptionOfType(LedgerRejection.class).isThrownBy(() -> {
            throw new LedgerRejection.TooFewPostings(1);
        });
    }
}

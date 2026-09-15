package shilingi.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import shilingi.money.Currency;
import shilingi.platform.AbstractDatabaseTest;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChartOfAccountsTest extends AbstractDatabaseTest {

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("the chart is exactly the ten accounts SPEC.md section 6 names, and no others")
    void chart_matches_the_specification() {
        List<Account> all = accounts.findAll();

        assertThat(all).extracting(Account::code, Account::name, Account::type, Account::currency)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("1000", "Bank — KES", AccountType.ASSET, Currency.KES),
                        org.assertj.core.groups.Tuple.tuple("1100", "Wallet — USDC", AccountType.ASSET, Currency.USDC),
                        org.assertj.core.groups.Tuple.tuple("1200", "Receivables", AccountType.ASSET, Currency.USDC),
                        org.assertj.core.groups.Tuple.tuple("1900", "Suspense — unexplained", AccountType.ASSET, Currency.KES),
                        org.assertj.core.groups.Tuple.tuple("2000", "Payables", AccountType.LIABILITY, Currency.KES),
                        org.assertj.core.groups.Tuple.tuple("4000", "Revenue", AccountType.INCOME, Currency.KES),
                        org.assertj.core.groups.Tuple.tuple("6100", "Exchange difference realised", AccountType.OTHER, Currency.KES),
                        org.assertj.core.groups.Tuple.tuple("6110", "Exchange difference unrealised", AccountType.OTHER, Currency.KES),
                        org.assertj.core.groups.Tuple.tuple("6200", "Conversion spread", AccountType.EXPENSE, Currency.KES),
                        org.assertj.core.groups.Tuple.tuple("6210", "Conversion fee", AccountType.EXPENSE, Currency.KES));
    }

    @Test
    @DisplayName("I4: exchange difference, spread and fee are four separate accounts")
    void the_four_cost_accounts_are_four_accounts() {
        // PROBLEM.md section 5: collapse them and you get one figure that answers no question.
        assertThat(accounts.findByCode("6100")).isPresent();
        assertThat(accounts.findByCode("6110")).isPresent();
        assertThat(accounts.findByCode("6200")).isPresent();
        assertThat(accounts.findByCode("6210")).isPresent();

        assertThat(List.of("6100", "6110", "6200", "6210"))
                .allSatisfy(code -> assertThat(accounts.findByCode(code)).isPresent());
    }

    @Test
    @DisplayName("I5: realised and unrealised exchange difference are different accounts")
    void realised_and_unrealised_are_not_the_same_account() {
        Account realised = accounts.findByCode("6100").orElseThrow();
        Account unrealised = accounts.findByCode("6110").orElseThrow();

        assertThat(realised.code()).isNotEqualTo(unrealised.code());
        assertThat(realised.name()).contains("realised");
        assertThat(unrealised.name()).contains("unrealised");
    }

    @Test
    @DisplayName("I3: the chart contains no account whose purpose would be to force a total to agree")
    void no_account_exists_to_make_a_total_agree() {
        // PROBLEM.md F4: a plug is a number invented to make two sides agree. The cheapest way
        // to build one is to give it an account and a respectable name, so the names are checked.
        // "Suspense" is deliberately NOT in this list: suspense is a question with a date
        // attached, not a plug (PROBLEM.md section 4), and 1900 exists because of invariant I6.
        List<String> forcingWords =
                List.of("adjustment", "adjustments", "plug", "misc", "miscellaneous",
                        "balancing", "sundry", "rounding", "reconciling", "unallocated", "other income");

        for (Account account : accounts.findAll()) {
            String name = account.name().toLowerCase(Locale.ROOT);
            assertThat(forcingWords)
                    .as("account %s (%s) is named like a plug", account.code(), account.name())
                    .noneSatisfy(word -> assertThat(name).contains(word));
        }
    }

    @Test
    @DisplayName("1900 is the only suspense account, and it is marked as one")
    void suspense_is_declared_not_implied() {
        assertThat(accounts.findSuspenseAccounts())
                .extracting(Account::code)
                .containsExactly("1900");
    }

    @Test
    @DisplayName("there is no code path that creates an account at runtime")
    void the_repository_cannot_create_an_account() {
        // I3, enforced by the shape of the API rather than by a rule somebody must remember.
        assertThat(AccountRepository.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .allSatisfy(name -> assertThat(name).doesNotStartWith("save")
                        .doesNotStartWith("insert")
                        .doesNotStartWith("create")
                        .doesNotStartWith("update")
                        .doesNotStartWith("delete"));
    }

    @Test
    void an_account_in_an_unknown_currency_is_rejected_by_the_database() {
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                jdbc.update("insert into account (code, name, type, currency) values (?, ?, ?, ?)",
                        "9999", "Sterling account", "ASSET", "GBP"));
    }

    @Test
    void an_account_with_an_unknown_type_is_rejected_by_the_database() {
        assertThatThrownBy(() ->
                jdbc.update("insert into account (code, name, type, currency) values (?, ?, ?, ?)",
                        "9998", "Something", "MYSTERY", "KES"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

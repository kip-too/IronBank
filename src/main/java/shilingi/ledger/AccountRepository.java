package shilingi.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import shilingi.money.Currency;

import java.util.List;
import java.util.Optional;

/**
 * Reads the chart of accounts. Read-only on purpose: there is no {@code insert}, no
 * {@code update} and no {@code delete}.
 *
 * <p>Invariant I3 says no code path exists to force a total to agree. The shortest such path
 * would be creating an account at runtime and posting the difference into it, so the capability
 * simply does not exist. The chart arrives by migration V2 and changes by migration only.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li>No caching. Ten rows, read rarely; a cache would be a second source of truth about the
 *       chart for no gain.</li>
 * </ul>
 */
@Repository
public class AccountRepository {

    private static final RowMapper<Account> MAPPER = (rs, rowNum) -> new Account(
            rs.getString("code"),
            rs.getString("name"),
            AccountType.valueOf(rs.getString("type")),
            Currency.valueOf(rs.getString("currency")),
            rs.getBoolean("is_suspense"));

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every account, in code order. */
    public List<Account> findAll() {
        return jdbc.query("select code, name, type, currency, is_suspense from account order by code", MAPPER);
    }

    public Optional<Account> findByCode(String code) {
        return jdbc.query(
                        "select code, name, type, currency, is_suspense from account where code = ?",
                        MAPPER, code)
                .stream()
                .findFirst();
    }

    /** The suspense accounts. PROBLEM.md section 4: money seen but not yet explained. */
    public List<Account> findSuspenseAccounts() {
        return jdbc.query(
                "select code, name, type, currency, is_suspense from account where is_suspense order by code",
                MAPPER);
    }
}

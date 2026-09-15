package shilingi.receivables;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import shilingi.money.Currency;
import shilingi.money.Money;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Stores receivables.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No delete.</b> An invoice that was raised in error is corrected by a credit note,
 *       which is a thing this system does not model yet, not by removing the record.</li>
 *   <li>No paging. One to six clients (PROBLEM.md section 1).</li>
 * </ul>
 */
@Repository
public class ReceivableRepository {

    private static final RowMapper<Receivable> MAPPER = (rs, rowNum) -> new Receivable(
            rs.getLong("id"),
            rs.getString("counterparty"),
            Money.of(rs.getLong("amount_minor"), Currency.valueOf(rs.getString("currency"))),
            rs.getObject("issue_date", LocalDate.class),
            ReceivableStatus.valueOf(rs.getString("status")));

    private static final String COLUMNS = "id, counterparty, currency, amount_minor, issue_date, status";

    private final JdbcTemplate jdbc;

    public ReceivableRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Receivable save(Receivable receivable) {
        if (receivable.id() != null) {
            throw new IllegalArgumentException(
                    "Receivable " + receivable.id() + " already exists; use settle to move it");
        }

        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into receivable (counterparty, currency, amount_minor, issue_date, status) "
                    + "values (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, receivable.counterparty());
            ps.setString(2, receivable.amount().currency().name());
            ps.setLong(3, receivable.amount().minorUnits());
            ps.setObject(4, receivable.issueDate());
            ps.setString(5, receivable.status().name());
            return ps;
        }, keys);

        long id = ((Number) keys.getKeys().get("id")).longValue();
        return new Receivable(id, receivable.counterparty(), receivable.amount(),
                receivable.issueDate(), receivable.status());
    }

    /**
     * Marks a receivable settled. The only transition there is.
     *
     * <p>Settling an already-settled receivable is refused rather than ignored: PROBLEM.md F3 is
     * orphan credits, and quietly accepting a second settlement of the same invoice is how one
     * receipt ends up explaining two invoices.
     */
    public Receivable settle(long id) {
        Receivable current = findById(id).orElseThrow(() ->
                new IllegalArgumentException("No receivable " + id));

        if (current.status() == ReceivableStatus.SETTLED) {
            throw new IllegalStateException(
                    "Receivable " + id + " is already settled. A second receipt against it is a "
                    + "reconciliation exception, not a repeat settlement.");
        }

        jdbc.update("update receivable set status = ? where id = ?",
                ReceivableStatus.SETTLED.name(), id);
        return current.withStatus(ReceivableStatus.SETTLED);
    }

    public Optional<Receivable> findById(long id) {
        return jdbc.query("select " + COLUMNS + " from receivable where id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** Everything, oldest invoice first. */
    public List<Receivable> findAll() {
        return jdbc.query("select " + COLUMNS + " from receivable order by issue_date, id", MAPPER);
    }

    /** What is still owed to the business. Oldest first, because age is the interesting part. */
    public List<Receivable> outstanding() {
        return jdbc.query("select " + COLUMNS + " from receivable "
                          + "where status = 'OUTSTANDING' order by issue_date, id", MAPPER);
    }
}

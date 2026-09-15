package shilingi.obligations;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import shilingi.clock.ClockPort;
import shilingi.money.Currency;
import shilingi.money.Money;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Stores obligations and answers the questions the agent and the demo screen need to ask.
 *
 * <h2>Overdue is a query, not a column</h2>
 * {@link #overdue()} is a {@code where} clause over status and due date, evaluated against the
 * injected clock. There is no {@code status = 'OVERDUE'} anywhere because the database will not
 * store that value - see V6 and ADR-017. The consequence worth having: this answer cannot be
 * stale, and it cannot be wrong because a job did not run.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No delete.</b> An obligation that turned out not to be owed is a finding, and
 *       deleting it removes the evidence. Nothing in SPEC.md asks for one.</li>
 *   <li><b>No general update.</b> Only {@link #updateStatus} - the database trigger rejects a
 *       change to any other field anyway.</li>
 *   <li>No paging. A month of a small business's obligations is a short list.</li>
 * </ul>
 */
@Repository
public class ObligationRepository {

    private static final RowMapper<Obligation> MAPPER = (rs, rowNum) -> new Obligation(
            rs.getLong("id"),
            rs.getString("name"),
            Money.of(rs.getLong("amount_minor"), Currency.valueOf(rs.getString("currency"))),
            rs.getObject("due_date", LocalDate.class),
            ObligationStatus.valueOf(rs.getString("status")));

    private static final String COLUMNS = "id, name, currency, amount_minor, due_date, status";

    private final JdbcTemplate jdbc;
    private final ClockPort clock;

    public ObligationRepository(JdbcTemplate jdbc, ClockPort clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Obligation save(Obligation obligation) {
        if (obligation.id() != null) {
            throw new IllegalArgumentException(
                    "Obligation " + obligation.id() + " already exists; use updateStatus to move it");
        }

        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into obligation (name, currency, amount_minor, due_date, status) "
                    + "values (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, obligation.name());
            ps.setString(2, obligation.amount().currency().name());
            ps.setLong(3, obligation.amount().minorUnits());
            ps.setObject(4, obligation.dueDate());
            ps.setString(5, obligation.status().name());
            return ps;
        }, keys);

        long id = ((Number) keys.getKeys().get("id")).longValue();
        return new Obligation(id, obligation.name(), obligation.amount(), obligation.dueDate(),
                obligation.status());
    }

    /**
     * Moves an obligation along SPEC.md section 7's arrows.
     *
     * <p>The legality check here is the readable one; the database trigger is the one that
     * cannot be bypassed.
     */
    public Obligation updateStatus(long id, ObligationStatus to) {
        Obligation current = findById(id).orElseThrow(() ->
                new IllegalArgumentException("No obligation " + id));

        if (!isLegal(current.status(), to)) {
            throw new IllegalTransitionException(current.status(), to);
        }

        jdbc.update("update obligation set status = ? where id = ?", to.name(), id);
        return current.withStatus(to);
    }

    private static boolean isLegal(ObligationStatus from, ObligationStatus to) {
        return (from == to)
               || (from == ObligationStatus.SCHEDULED && to == ObligationStatus.FUNDED)
               || (from == ObligationStatus.FUNDED && to == ObligationStatus.PAID);
    }

    public Optional<Obligation> findById(long id) {
        return jdbc.query("select " + COLUMNS + " from obligation where id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** Everything, soonest due first - the order the agent reads the calendar in. */
    public List<Obligation> findAll() {
        return jdbc.query("select " + COLUMNS + " from obligation order by due_date, id", MAPPER);
    }

    /**
     * The planning failures: past due with nothing set aside, as at the injected clock's today.
     *
     * <p>SPEC.md section 7 says this "must be visible as one", so it has its own method and the
     * demo screen has something to show.
     */
    public List<Obligation> overdue() {
        return overdueOn(clock.businessDate());
    }

    /** As {@link #overdue()}, for an explicit date. Used by tests that move the clock. */
    public List<Obligation> overdueOn(LocalDate today) {
        return jdbc.query("select " + COLUMNS + " from obligation "
                          + "where status = 'SCHEDULED' and due_date < ? order by due_date, id",
                MAPPER, today);
    }

    /**
     * Obligations falling due on or before a date and not yet funded. This is what invariant I8
     * is checked against on day 8: every one of these inside the planning horizon must have funds
     * allocated, or the agent must refuse to hold dollars.
     *
     * <p>Includes ones already past due, deliberately: an overdue obligation is still unfunded
     * and still needs money.
     */
    public List<Obligation> unfundedDueBy(LocalDate date) {
        return jdbc.query("select " + COLUMNS + " from obligation "
                          + "where status = 'SCHEDULED' and due_date <= ? order by due_date, id",
                MAPPER, date);
    }
}

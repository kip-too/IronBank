package shilingi.intent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The append-only record of every decision the agent has made.
 *
 * <p>There is no update method, no delete method, and no way to write an intent that refers to
 * an action already taken. Those are not omissions to be filled in later - they are the point.
 *
 * <h2>append() commits on its own</h2>
 * SPEC.md section 11 step 6 says to write the intent "and commit it", then step 7 creates the
 * instruction. {@link #append(Intent)} therefore runs in its own transaction
 * ({@code REQUIRES_NEW}) rather than joining a caller's.
 *
 * <p>Without that, an agent doing both inside one transaction would roll the intent back when
 * the instruction failed - leaving no record that anything was ever decided, which is precisely
 * the failure the ordering exists to prevent. With it, a crash between the two steps leaves
 * <b>a decision with no action: recoverable and visible.</b> The reverse leaves an action nobody
 * can explain, which is F6.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No search inside the snapshot.</b> The column is {@code json} and is read whole, by a
 *       person. See V7 for why it is not {@code jsonb}.</li>
 *   <li><b>No retention or archival.</b> The log grows forever. That is correct for an audit
 *       record and will eventually be a size question, not a correctness one.</li>
 *   <li>No paging on {@link #all()}. Day 13's screen will need it.</li>
 * </ul>
 *
 * <h2>The {@code ?::json} casts</h2>
 * The snapshot columns are PostgreSQL {@code json}, and a plain string parameter will not go
 * into one. The alternative to the cast is the driver's own {@code PGobject}, which would mean
 * putting the PostgreSQL driver on the compile classpath - it is deliberately {@code runtime}
 * scoped - and a driver class in application code. The cast keeps both out, and the column type
 * still validates the JSON, so malformed evidence is refused by the database rather than stored
 * as something unreadable.
 */
@Repository
public class IntentLog {

    private static final RowMapper<Intent> MAPPER = (rs, rowNum) -> new Intent(
            rs.getLong("id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("trigger"),
            rs.getString("reasoning"),
            rs.getString("inputs_snapshot"),
            rs.getString("proposed_action"));

    private static final String COLUMNS =
            "id, created_at, trigger, reasoning, inputs_snapshot, proposed_action";

    private final JdbcTemplate jdbc;

    public IntentLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes a decision and commits it, before anything acts on it.
     *
     * @return the intent as stored, with its identity - which is what an instruction will need
     *         to reference, and cannot be created without
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Intent append(Intent intent) {
        if (intent.id() != null) {
            throw new IllegalArgumentException(
                    "Intent " + intent.id() + " has already been written. The log is append-only: "
                    + "a decision that was revised is a new decision, with its own reasoning.");
        }

        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into intent (created_at, trigger, reasoning, inputs_snapshot, proposed_action) "
                    + "values (?, ?, ?, ?::json, ?::json)", Statement.RETURN_GENERATED_KEYS);
            ps.setTimestamp(1, Timestamp.from(intent.createdAt()));
            ps.setString(2, intent.trigger());
            ps.setString(3, intent.reasoning());
            ps.setString(4, intent.inputsSnapshot());
            ps.setString(5, intent.proposedAction());
            return ps;
        }, keys);

        long id = ((Number) keys.getKeys().get("id")).longValue();

        return new Intent(id, intent.createdAt(), intent.trigger(), intent.reasoning(),
                intent.inputsSnapshot(), intent.proposedAction());
    }

    public Optional<Intent> findById(long id) {
        return jdbc.query("select " + COLUMNS + " from intent where id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** Every decision, oldest first. The order they were made in. */
    public List<Intent> all() {
        return jdbc.query("select " + COLUMNS + " from intent order by created_at, id", MAPPER);
    }

    /** How many decisions have been recorded. Used by tests that care that nothing was written. */
    public long count() {
        Long count = jdbc.queryForObject("select count(*) from intent", Long.class);
        return count == null ? 0L : count;
    }
}

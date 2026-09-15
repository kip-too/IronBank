package shilingi.instruction;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import shilingi.money.Currency;
import shilingi.money.Money;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Stores instructions and reads them back into the right state type.
 *
 * <h2>Reading loses the transition history, and that is fine</h2>
 * The table stores the current state and the attempt count, not the instants each transition
 * happened at. So an instruction read back from the database gets the timestamps it can honestly
 * supply - {@code created_at} - rather than invented ones. What happened and when belongs in the
 * journal and in the settlement records, not in a column that would be a second version of them.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No general update.</b> {@link #transitionTo} writes state and attempts, and the
 *       database trigger refuses a change to anything else anyway.</li>
 *   <li><b>No delete.</b> An instruction that should not have existed is a finding.</li>
 *   <li><b>No optimistic locking.</b> SPEC.md section 17 scenario 5 - two threads creating the
 *       same external reference at once - is handled by the unique constraint, which is the
 *       point. Two threads TRANSITIONING the same instruction at once is a different race and is
 *       not yet addressed; noted rather than assumed away.</li>
 * </ul>
 */
@Repository
public class InstructionRepository {

    private static final String COLUMNS =
            "id, intent_id, external_ref, type, amount_minor, currency, created_at, attempts, state";

    private static final RowMapper<Instruction> MAPPER = (rs, rowNum) -> {
        Instruction.Details details = new Instruction.Details(
                rs.getLong("id"),
                rs.getLong("intent_id"),
                rs.getString("external_ref"),
                InstructionType.valueOf(rs.getString("type")),
                Money.of(rs.getLong("amount_minor"), Currency.valueOf(rs.getString("currency"))),
                rs.getTimestamp("created_at").toInstant(),
                rs.getInt("attempts"));

        Instant createdAt = details.createdAt();

        return switch (InstructionState.valueOf(rs.getString("state"))) {
            case CREATED -> new Instruction.Created(details);
            case SUBMITTED -> new Instruction.Submitted(details, createdAt);
            case AWAITING_RESOLUTION -> new Instruction.AwaitingResolution(
                    details, createdAt, "read from the database");
            case SETTLED -> new Instruction.Settled(details, createdAt, "read from the database");
            case FAILED -> new Instruction.Failed(details, createdAt, "read from the database");
            case MANUAL_REVIEW -> new Instruction.ManualReview(
                    details, createdAt, "read from the database");
        };
    };

    private final JdbcTemplate jdbc;

    public InstructionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes a new instruction in CREATED.
     *
     * <p>The insert will fail if {@code intentId} names no committed intent (I9) or if
     * {@code externalRef} has been used before (F5). Both are database constraints, and both
     * failures are the system working.
     */
    public Instruction.Created create(long intentId, String externalRef, InstructionType type,
                                      Money amount, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into instruction (intent_id, external_ref, type, amount_minor, currency, "
                    + "created_at, attempts, state) values (?, ?, ?, ?, ?, ?, 0, 'CREATED')",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, intentId);
            ps.setString(2, externalRef);
            ps.setString(3, type.name());
            ps.setLong(4, amount.minorUnits());
            ps.setString(5, amount.currency().name());
            ps.setTimestamp(6, Timestamp.from(createdAt));
            return ps;
        }, keys);

        long id = ((Number) keys.getKeys().get("id")).longValue();
        return new Instruction.Created(
                new Instruction.Details(id, intentId, externalRef, type, amount, createdAt, 0));
    }

    /**
     * Persists a transition that the type system has already permitted.
     *
     * <p>The caller cannot construct an illegal transition - that is what {@link Instruction}'s
     * types are for - so this method sees only legal ones. The database trigger checks anyway,
     * for everything that does not come through Java.
     */
    public Instruction transitionTo(Instruction instruction) {
        int updated = jdbc.update("update instruction set state = ?, attempts = ? where id = ?",
                instruction.state().name(), instruction.attempts(), instruction.id());

        if (updated != 1) {
            throw new IllegalStateException(
                    "No instruction " + instruction.id() + " to move to " + instruction.state());
        }
        return instruction;
    }

    public Optional<Instruction> findById(long id) {
        return jdbc.query("select " + COLUMNS + " from instruction where id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<Instruction> findByExternalRef(String externalRef) {
        return jdbc.query("select " + COLUMNS + " from instruction where external_ref = ?",
                MAPPER, externalRef).stream().findFirst();
    }

    /**
     * Everything whose outcome is unknown. SPEC.md section 7: these are re-queried, never
     * retried, and SPEC.md section 13 wants them ageing visibly.
     */
    public List<Instruction> awaitingResolution() {
        return jdbc.query("select " + COLUMNS + " from instruction "
                          + "where state = 'AWAITING_RESOLUTION' order by created_at, id", MAPPER);
    }

    /**
     * Dollars committed to conversions that have not finished: CREATED, SUBMITTED, or
     * AWAITING_RESOLUTION.
     *
     * <p>These dollars are spoken for. The agent must not propose converting them again, and the
     * shillings they will produce must not be counted twice in the funding gap. See ADR-022 -
     * without this, two decision cycles before a settlement each propose the whole shortfall.
     *
     * <p>A conversion that reaches FAILED is deliberately NOT in flight any more: the dollars are
     * back, the gap reopens, and the next cycle sees it. That is the self-correcting half.
     */
    public Money conversionsInFlight() {
        Long total = jdbc.queryForObject(
                "select coalesce(sum(amount_minor), 0) from instruction "
                + "where type = 'CONVERSION' and currency = 'USDC' "
                + "and state in ('CREATED', 'SUBMITTED', 'AWAITING_RESOLUTION')",
                Long.class);
        return Money.of(total == null ? 0L : total, Currency.USDC);
    }

    public List<Instruction> findAll() {
        return jdbc.query("select " + COLUMNS + " from instruction order by id", MAPPER);
    }
}

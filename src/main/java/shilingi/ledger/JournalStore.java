package shilingi.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import shilingi.money.Currency;
import shilingi.money.Money;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Writes entries to the journal and reads them back. Insert and select only - there is no
 * update and no delete, here or in the database.
 *
 * <h2>This class does not validate anything, and must not be called directly</h2>
 * The six rejections in SPEC.md section 8 - balance per currency, balance in shillings, rate
 * present, account exists, at least two postings, business date not in the future - are day 3's
 * work and live in the ledger service that will sit in front of this class. Until that exists,
 * the only callers are tests.
 *
 * <p>What stops a bad entry today is the database: the composite foreign key on
 * (account_code, currency), the I1 check constraint, and the foreign key to journal_entry. Those
 * catch a subset. They do not catch an unbalanced entry, which is precisely why day 3 exists.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li>No paging on {@link #findAll()}. Replay (day 14) will need a cursor rather than a list
 *       once the journal is longer than memory.</li>
 *   <li>No batch insert. One statement per posting. Entries here have two to four lines.</li>
 * </ul>
 */
@Repository
public class JournalStore {

    private final JdbcTemplate jdbc;

    public JournalStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes an entry and all of its postings in one database transaction. All or nothing:
     * SPEC.md section 8 requires that a rejection writes nothing at all.
     *
     * @return the entry as stored, with database identities filled in.
     */
    @Transactional
    public JournalEntry append(JournalEntry entry) {
        KeyHolder keys = new GeneratedKeyHolder();

        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into journal_entry (business_date, description, source_ref, created_at) "
                    + "values (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, entry.businessDate());
            ps.setString(2, entry.description());
            ps.setString(3, entry.sourceRef());
            ps.setTimestamp(4, Timestamp.from(entry.createdAt()));
            return ps;
        }, keys);

        long entryId = ((Number) keys.getKeys().get("id")).longValue();

        List<Posting> stored = new ArrayList<>(entry.postings().size());
        for (Posting p : entry.postings()) {
            stored.add(insertPosting(entryId, p));
        }

        return new JournalEntry(entryId, entry.businessDate(), entry.description(),
                entry.sourceRef(), entry.createdAt(), stored);
    }

    private Posting insertPosting(long entryId, Posting p) {
        KeyHolder keys = new GeneratedKeyHolder();

        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into posting (entry_id, account_code, amount_minor, currency, "
                    + "rate_value, rate_source, rate_timestamp, functional_amount_minor) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, entryId);
            ps.setString(2, p.accountCode());
            ps.setLong(3, p.amount().minorUnits());
            ps.setString(4, p.amount().currency().name());
            if (p.rateValue() == null) {
                ps.setNull(5, Types.NUMERIC);
            } else {
                ps.setBigDecimal(5, p.rateValue());
            }
            ps.setString(6, p.rateSource());
            if (p.rateTimestamp() == null) {
                ps.setNull(7, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(7, Timestamp.from(p.rateTimestamp()));
            }
            ps.setLong(8, p.functionalAmount().minorUnits());
            return ps;
        }, keys);

        long postingId = ((Number) keys.getKeys().get("id")).longValue();

        return new Posting(postingId, entryId, p.accountCode(), p.amount(), p.rateValue(),
                p.rateSource(), p.rateTimestamp(), p.functionalAmount());
    }

    public Optional<JournalEntry> findById(long id) {
        List<JournalEntry> found = jdbc.query(
                "select id, business_date, description, source_ref, created_at "
                + "from journal_entry where id = ?",
                (rs, rowNum) -> new JournalEntry(
                        rs.getLong("id"),
                        rs.getObject("business_date", java.time.LocalDate.class),
                        rs.getString("description"),
                        rs.getString("source_ref"),
                        rs.getTimestamp("created_at").toInstant(),
                        findPostings(rs.getLong("id"))),
                id);
        return found.stream().findFirst();
    }

    public List<Posting> findPostings(long entryId) {
        return jdbc.query(
                "select id, entry_id, account_code, amount_minor, currency, rate_value, "
                + "rate_source, rate_timestamp, functional_amount_minor "
                + "from posting where entry_id = ? order by id",
                POSTING_MAPPER, entryId);
    }

    /** Every entry, oldest first. Ordered by business date then identity, which is the order replay needs. */
    public List<JournalEntry> findAll() {
        return jdbc.query(
                "select id, business_date, description, source_ref, created_at "
                + "from journal_entry order by business_date, id",
                (rs, rowNum) -> new JournalEntry(
                        rs.getLong("id"),
                        rs.getObject("business_date", java.time.LocalDate.class),
                        rs.getString("description"),
                        rs.getString("source_ref"),
                        rs.getTimestamp("created_at").toInstant(),
                        findPostings(rs.getLong("id"))));
    }

    private static final RowMapper<Posting> POSTING_MAPPER = (rs, rowNum) -> {
        Currency currency = Currency.valueOf(rs.getString("currency"));
        BigDecimal rateValue = rs.getBigDecimal("rate_value");
        Timestamp rateTimestamp = rs.getTimestamp("rate_timestamp");
        Instant rateInstant = rateTimestamp == null ? null : rateTimestamp.toInstant();

        return new Posting(
                rs.getLong("id"),
                rs.getLong("entry_id"),
                rs.getString("account_code"),
                Money.of(rs.getLong("amount_minor"), currency),
                rateValue,
                rs.getString("rate_source"),
                rateInstant,
                Money.of(rs.getLong("functional_amount_minor"), Currency.KES));
    };
}

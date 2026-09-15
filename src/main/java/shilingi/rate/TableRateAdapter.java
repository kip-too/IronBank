package shilingi.rate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import shilingi.money.Rate;
import shilingi.money.RateKind;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Reads mid-market rates from the fixed {@code mid_rate} table (SPEC.md section 12).
 *
 * <p>Deterministic by construction: the same date returns the same rate on every run, in every
 * test, on demo day, in March. Nothing here reaches the network.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No write path.</b> Rates arrive by migration. An adapter that could insert a rate
 *       could insert a convenient one.</li>
 *   <li><b>No caching.</b> A handful of rows, and a cache would be a second answer to the
 *       question "what was the rate on the 12th".</li>
 *   <li>Over-precise quotes cannot occur here, because the column is {@code numeric(20,8)} and
 *       PostgreSQL rounds on write. A live adapter would have to face
 *       {@link shilingi.money.RatePrecisionException} and decide explicitly - see ADR-014.</li>
 * </ul>
 */
@Component
public class TableRateAdapter implements RatePort {

    private final JdbcTemplate jdbc;

    public TableRateAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Rate> midRateOn(LocalDate businessDate) {
        return jdbc.query(
                        "select value, source, quoted_at from mid_rate where rate_date = ?",
                        (rs, rowNum) -> new Rate(
                                rs.getBigDecimal("value"),
                                rs.getString("source"),
                                rs.getTimestamp("quoted_at").toInstant(),
                                RateKind.MID),
                        businessDate)
                .stream()
                .findFirst();
    }
}

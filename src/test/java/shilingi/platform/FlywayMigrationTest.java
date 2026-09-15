package shilingi.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import shilingi.clock.ClockPort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the day 1 infrastructure is real: a live PostgreSQL database, migrated by Flyway,
 * and a single injected clock in the context.
 *
 * <p>SPEC.md section 3: no in-memory substitute. The ledger constraints are the point of this
 * project and they must be the real database's constraints, so this test requires a running
 * PostgreSQL and fails loudly without one. That is the intended behaviour, not a fragility.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li>No truncation between test classes yet. SPEC.md section 3 requires it; there are no
 *       tables to truncate until day 2, so the harness for it is not built.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ClockPort clock;

    @Test
    void flyway_has_applied_the_baseline_migration_to_a_real_postgres() {
        String product = jdbc.queryForObject("select version()", String.class);
        assertThat(product).contains("PostgreSQL");

        List<String> applied = jdbc.queryForList(
                "select version from flyway_schema_history where success = true order by installed_rank",
                String.class);

        assertThat(applied).contains("1");
    }

    @Test
    void the_context_supplies_exactly_one_clock_and_it_carries_the_configured_zone() {
        assertThat(clock).isNotNull();
        assertThat(clock.zone().getId()).isEqualTo("Africa/Nairobi");
        assertThat(clock.businessDate()).isNotNull();
    }
}

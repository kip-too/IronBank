package shilingi.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for tests that touch the database. Runs against a real local PostgreSQL, per
 * SPEC.md section 3 - no in-memory substitute, because the ledger's constraints are the point
 * and they must be the real database's constraints.
 *
 * <h2>Why this cleans by dropping rather than truncating</h2>
 * SPEC.md section 3 asks for the test database to be truncated between test classes. It cannot
 * be. Migration V3 blocks TRUNCATE on {@code posting} and {@code journal_entry} on purpose:
 * TRUNCATE does not fire row-level DELETE triggers, so a journal that blocked only UPDATE and
 * DELETE would still be erasable by a single statement.
 *
 * <p>Rather than weaken the production rule to suit the tests, the tests pay for it -
 * {@code Flyway.clean()} drops the objects and {@code migrate()} rebuilds them, which DDL is
 * allowed to do. The alternative would have been to let test code call
 * {@code ALTER TABLE ... DISABLE TRIGGER}, which puts the ability to switch off immutability
 * into the repository, where someone would eventually find it. See ADR-010.
 *
 * <h2>The clock is pinned</h2>
 * Every subclass runs on {@link PinnedClockTestConfig}'s fixed clock, so that a fixture dated
 * 30 September is in the past in March as well as in October. See that class for why.
 *
 * <p><b>Trade-off:</b> this is slower than a truncate, and it runs per test rather than per
 * class, so the cost grows with the suite. It is measured in hundreds of milliseconds today.
 * If it becomes the reason the build is slow, the fix is a faster clean, never a softer trigger.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PinnedClockTestConfig.class)
public abstract class AbstractDatabaseTest {

    @Autowired
    private Flyway flyway;

    @BeforeEach
    void resetSchema() {
        flyway.clean();
        flyway.migrate();
    }
}

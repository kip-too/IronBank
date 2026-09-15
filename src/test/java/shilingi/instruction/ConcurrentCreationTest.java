package shilingi.instruction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import shilingi.intent.Intent;
import shilingi.intent.IntentLog;
import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.platform.AbstractDatabaseTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC.md section 17 scenario 5: <i>"Two instructions are created with the same external reference
 * by two threads at the same instant."</i>
 *
 * <p>Until now that scenario had only an untested constraint behind it. The unique index on
 * {@code external_ref} was asserted against two sequential inserts, which proves the constraint
 * exists and proves nothing about a race - and F5, silent double payment, is a race.
 *
 * <p>This is the one place in the suite that uses real threads. SPEC.md section 13 is explicit
 * about why the mechanism has to be the database:
 *
 * <blockquote>
 * "A duplicate submission fails on the constraint, at the database, not in application logic.
 * This is the mechanism, and the constraint is the mechanism - application checks are an
 * optimisation on top of it, never a substitute."
 * </blockquote>
 *
 * <p>An application check would lose this race. Two threads both look, both find nothing, both
 * insert. Only the database can decide, because only the database sees both.
 */
class ConcurrentCreationTest extends AbstractDatabaseTest {

    private static final int THREADS = 16;

    @Autowired
    private InstructionRepository instructions;

    @Autowired
    private IntentLog intents;

    @Autowired
    private JdbcTemplate jdbc;

    private static final Instant AT = Instant.parse("2026-09-20T06:00:00Z");

    @Test
    @DisplayName("sixteen threads racing on one external reference produce exactly one instruction")
    void only_one_thread_wins() throws Exception {
        long intentId = intents.append(Intent.proposing(AT, "race", "because", "{}", "{}")).id();

        // Every thread waits on the same latch, so they are released together rather than
        // trickling in one after another and racing nothing.
        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<Void>> attempts = new ArrayList<>(THREADS);
            for (int i = 0; i < THREADS; i++) {
                attempts.add(() -> {
                    startTogether.await();
                    try {
                        instructions.create(intentId, "convert/contested",
                                InstructionType.CONVERSION,
                                Money.of(6_000_000_000L, Currency.USDC), AT);
                        succeeded.incrementAndGet();
                    } catch (RuntimeException refusedByTheDatabase) {
                        refused.incrementAndGet();
                    }
                    return null;
                });
            }

            List<Future<Void>> running = new ArrayList<>(THREADS);
            attempts.forEach(a -> running.add(pool.submit(a)));

            startTogether.countDown();
            for (Future<Void> f : running) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get())
                .as("exactly one thread may create the instruction")
                .isEqualTo(1);
        assertThat(refused.get())
                .as("and every other one is refused by the database, not by a check that raced")
                .isEqualTo(THREADS - 1);

        assertThat(jdbc.queryForObject(
                "select count(*) from instruction where external_ref = ?", Long.class,
                "convert/contested"))
                .as("F5: one external reference, one instruction, one payment")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("different references under the same contention all succeed, so the constraint is not just blocking")
    void distinct_references_are_unaffected() {
        // The previous test would also pass if the table refused everything after the first row.
        // This is the control: same contention, distinct references, all of them land.
        long intentId = intents.append(Intent.proposing(AT, "race", "because", "{}", "{}")).id();

        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> running = new ArrayList<>(THREADS);
            for (int i = 0; i < THREADS; i++) {
                int n = i;
                running.add(pool.submit(() -> {
                    try {
                        startTogether.await();
                        instructions.create(intentId, "convert/distinct/" + n,
                                InstructionType.CONVERSION,
                                Money.of(1_000_000L, Currency.USDC), AT);
                        succeeded.incrementAndGet();
                    } catch (Exception e) {
                        // counted by omission
                    }
                }));
            }
            startTogether.countDown();
            running.forEach(f -> {
                try {
                    f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).isEqualTo(THREADS);
    }
}

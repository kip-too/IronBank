package shilingi.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import shilingi.money.Currency;
import shilingi.money.Money;
import shilingi.obligations.Obligation;
import shilingi.obligations.ObligationRepository;
import shilingi.obligations.ObligationStatus;
import shilingi.platform.AbstractDatabaseTest;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 7 is done when "intent cannot be written after its instruction" (SPEC.md section 15).
 *
 * <p>That is proven here against the real constraint rather than described: an instruction row
 * cannot be inserted unless its intent already exists and is committed, because
 * {@code instruction.intent_id} is a {@code NOT NULL} foreign key - the exact mechanism SPEC.md
 * section 9 names for invariant I9.
 */
class IntentLogTest extends AbstractDatabaseTest {

    @Autowired
    private IntentLog log;

    @Autowired
    private ObligationRepository obligations;

    @Autowired
    private JdbcTemplate jdbc;

    private static final Instant DECIDED_AT = Instant.parse("2026-09-20T05:30:00Z");

    /**
     * V8 added type, amount, currency, state and attempts to this table. These tests insert in
     * raw SQL on purpose - they prove the DATABASE refuses, not that a repository is careful -
     * so the column list lives here rather than being borrowed from InstructionRepository.
     */
    private static final String INSERT_INSTRUCTION =
            "insert into instruction (intent_id, external_ref, created_at, type, amount_minor, "
            + "currency, state, attempts) values (?, ?, ?, 'CONVERSION', 6000000000, 'USDC', "
            + "'CREATED', 0)";

    private Intent aDecision() {
        return Intent.proposing(DECIDED_AT,
                "payroll due in 5 days",
                "KES balance covers 62% of what is due inside the horizon; converting the shortfall "
                + "plus the 10% buffer at today's mid rate.",
                Snapshot.of()
                        .with("kesBalanceMinor", 50_000_000L)
                        .with("obligationsDueMinor", 80_000_000L)
                        .with("midRate", "132.60000000")
                        .toJson(),
                Snapshot.of()
                        .with("action", "CONVERT")
                        .with("amountUsdcMinor", 6_000_000_000L)
                        .toJson());
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 11: the intent is committed before the instruction exists")
    class TheOrdering {

        @Test
        @DisplayName("an instruction cannot be created without an intent that is already there")
        void an_instruction_needs_an_intent_first() {
            // I9, and the whole of day 7's gate. There is no id 999 in the intent table.
            assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                    jdbc.update(INSERT_INSTRUCTION, 999L, "ref-1", Timestamp.from(DECIDED_AT)));

            assertThat(jdbc.queryForObject("select count(*) from instruction", Long.class)).isZero();
        }

        @Test
        @DisplayName("an instruction cannot be created with no intent at all")
        void intent_id_cannot_be_null() {
            assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                    jdbc.update(INSERT_INSTRUCTION, null, "ref-1", Timestamp.from(DECIDED_AT)));
        }

        @Test
        @DisplayName("with the intent written first, the instruction goes in")
        void the_right_order_works() {
            Intent decision = log.append(aDecision());

            jdbc.update(INSERT_INSTRUCTION, decision.id(), "convert/2026-09-20/1",
                    Timestamp.from(DECIDED_AT));

            assertThat(jdbc.queryForObject(
                    "select intent_id from instruction where external_ref = ?", Long.class,
                    "convert/2026-09-20/1")).isEqualTo(decision.id());
        }

        @Test
        @DisplayName("F5: the same external reference cannot be used twice")
        void a_duplicate_external_reference_is_refused_by_the_database() {
            // SPEC.md section 13: "the single most important constraint in the schema".
            Intent decision = log.append(aDecision());

            jdbc.update(INSERT_INSTRUCTION, decision.id(), "convert/2026-09-20/1",
                    Timestamp.from(DECIDED_AT));

            assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                    jdbc.update(INSERT_INSTRUCTION, decision.id(), "convert/2026-09-20/1",
                            Timestamp.from(DECIDED_AT)));

            assertThat(jdbc.queryForObject("select count(*) from instruction", Long.class)).isEqualTo(1L);
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 7: it happened, so it cannot be un-happened")
    class AppendOnly {

        @Test
        @DisplayName("the reasoning cannot be rewritten once the outcome is known")
        void an_intent_cannot_be_updated() {
            // The failure this prevents: convert, watch it go badly, then improve the
            // explanation. A log that permits that looks like evidence and is a reconstruction.
            Intent written = log.append(aDecision());

            assertThatThrownBy(() ->
                    jdbc.update("update intent set reasoning = ? where id = ?",
                            "I always thought that was a bad idea", written.id()))
                    .hasMessageContaining("append-only");

            assertThat(log.findById(written.id()).orElseThrow().reasoning())
                    .contains("converting the shortfall");
        }

        @Test
        void an_intent_cannot_be_deleted_or_truncated() {
            Intent written = log.append(aDecision());

            assertThatThrownBy(() -> jdbc.update("delete from intent where id = ?", written.id()))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> jdbc.execute("truncate table intent cascade"))
                    .hasMessageContaining("append-only");

            assertThat(log.count()).isEqualTo(1L);
        }

        @Test
        void the_log_offers_no_way_to_revise_a_decision() {
            assertThat(IntentLog.class.getDeclaredMethods())
                    .extracting(java.lang.reflect.Method::getName)
                    .allSatisfy(name -> assertThat(name).doesNotStartWith("update")
                            .doesNotStartWith("delete")
                            .doesNotStartWith("revise")
                            .doesNotStartWith("amend"));
        }

        @Test
        void re_appending_an_already_written_intent_is_refused() {
            Intent written = log.append(aDecision());

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> log.append(written))
                    .withMessageContaining("append-only");
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 6: the snapshot is a copy, not a reference")
    class TheSnapshot {

        @Test
        @DisplayName("what the agent saw stays what the agent saw, after the world moves on")
        void the_snapshot_does_not_change_when_the_data_does() {
            // This is the test that makes the word "copy" mean something.
            Obligation payroll = obligations.save(Obligation.scheduled(
                    "Payroll September", Money.of(80_000_000L, Currency.KES),
                    LocalDate.of(2026, 9, 25)));

            Intent written = log.append(Intent.proposing(DECIDED_AT,
                    "payroll due in 5 days",
                    "one obligation unfunded inside the horizon",
                    Snapshot.of()
                            .with("obligationId", payroll.id())
                            .with("obligationStatus", payroll.status().name())
                            .with("obligationAmountMinor", payroll.amount().minorUnits())
                            .toJson(),
                    Snapshot.of().with("action", "CONVERT").toJson()));

            // The world moves on: the obligation gets funded.
            obligations.updateStatus(payroll.id(), ObligationStatus.FUNDED);
            assertThat(obligations.findById(payroll.id()).orElseThrow().status())
                    .isEqualTo(ObligationStatus.FUNDED);

            // The intent still says what was true when the decision was made.
            assertThat(log.findById(written.id()).orElseThrow().inputsSnapshot())
                    .as("a reference would now read FUNDED and the decision would look absurd")
                    .contains("\"obligationStatus\":\"SCHEDULED\"");
        }

        @Test
        @DisplayName("the stored text is byte-for-byte what was written, not a re-rendering")
        void json_not_jsonb_means_the_exact_text_survives() {
            // jsonb would reorder keys and drop duplicates. For evidence, "equivalent to what the
            // agent saw" is not the same claim as "what the agent saw".
            String exact = "{\"z\":1,\"a\":2,\"nested\":{\"b\":[1,2,3]}}";

            Intent written = log.append(Intent.proposing(DECIDED_AT, "t", "r", exact, "{}"));

            assertThat(log.findById(written.id()).orElseThrow().inputsSnapshot())
                    .isEqualTo(exact);
        }

        @Test
        @DisplayName("a snapshot that is not valid JSON is refused by the database")
        void malformed_evidence_is_not_stored() {
            assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                    log.append(Intent.proposing(DECIDED_AT, "t", "r", "{not json", "{}")));

            assertThat(log.count()).isZero();
        }

        @Test
        @DisplayName("Snapshot sorts its keys, so the same facts always produce the same bytes")
        void snapshots_are_reproducible() {
            String one = Snapshot.of().with("b", 2).with("a", 1).with("c", 3).toJson();
            String two = Snapshot.of().with("c", 3).with("a", 1).with("b", 2).toJson();

            assertThat(one).isEqualTo(two).isEqualTo("{\"a\":1,\"b\":2,\"c\":3}");
        }

        @Test
        void snapshot_keeps_money_as_minor_units_and_rates_as_text() {
            // No floating point anywhere near the evidence, same as everywhere else.
            String json = Snapshot.of()
                    .with("balanceMinor", Money.of(131_500_000L, Currency.KES).minorUnits())
                    .with("midRate", "132.60000000")
                    .toJson();

            assertThat(json).isEqualTo("{\"balanceMinor\":131500000,\"midRate\":\"132.60000000\"}");
        }

        @Test
        void a_snapshot_that_cannot_be_written_fails_loudly() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    Snapshot.of().with("unserialisable", new Object()).toJson())
                    .withMessageContaining("could not be explained later");
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("an intent that explains nothing is not an intent")
    class Refusals {

        @Test
        void reasoning_is_required() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    Intent.proposing(DECIDED_AT, "trigger", "   ", "{}", "{}"))
                    .withMessageContaining("F6");
        }

        @Test
        void a_trigger_is_required() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    Intent.proposing(DECIDED_AT, "  ", "reasoning", "{}", "{}"));
        }

        @Test
        @DisplayName("the timestamp must be supplied, because nothing calls now()")
        void the_clock_is_injected() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    Intent.proposing(null, "trigger", "reasoning", "{}", "{}"))
                    .withMessageContaining("injected clock");
        }

        @Test
        void the_database_refuses_blank_reasoning_too() {
            assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                    jdbc.update("insert into intent (created_at, trigger, reasoning, inputs_snapshot, "
                                + "proposed_action) values (?, ?, ?, ?::json, ?::json)",
                            Timestamp.from(DECIDED_AT), "trigger", "   ", "{}", "{}"));
        }
    }

    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("decisions read back in the order they were made")
    void the_log_reads_oldest_first() {
        log.append(Intent.proposing(DECIDED_AT, "first", "r", "{}", "{}"));
        log.append(Intent.proposing(DECIDED_AT.plusSeconds(60), "second", "r", "{}", "{}"));
        log.append(Intent.proposing(DECIDED_AT.plusSeconds(120), "third", "r", "{}", "{}"));

        assertThat(log.all()).extracting(Intent::trigger).containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("an intent survives even when whatever it was for never happens")
    void a_decision_with_no_action_is_a_legitimate_end_state() {
        // SPEC.md section 11: "If the process dies between them, we have a decision with no
        // action - recoverable and visible."
        Intent orphan = log.append(aDecision());

        assertThat(log.findById(orphan.id())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from instruction where intent_id = ?",
                Long.class, orphan.id())).isZero();
    }

    @Test
    @DisplayName("a snapshot can be built from a map as well as fluently")
    void snapshot_from_a_map() {
        assertThat(Snapshot.json(Map.of("b", 2, "a", List.of(1, 2))))
                .isEqualTo("{\"a\":[1,2],\"b\":2}");
    }
}

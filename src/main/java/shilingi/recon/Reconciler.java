package shilingi.recon;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import shilingi.clock.ClockPort;
import shilingi.ledger.AccountCodes;
import shilingi.ledger.JournalEntry;
import shilingi.ledger.LedgerService;
import shilingi.ledger.Posting;
import shilingi.money.Currency;
import shilingi.money.Money;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SPEC.md section 13's three-way match, run on demand.
 *
 * <pre>
 *   INTENT          what we meant to do
 *   SETTLEMENT      what the rail says happened
 *   LEDGER          what the books say
 *
 *   all three agree            -> matched
 *   settlement without intent  -> unmatched inbound -> suspense 1900
 *   intent without settlement  -> in flight, or stale if older than threshold
 *   amounts differ             -> exception, always, regardless of size
 * </pre>
 *
 * <h2>Running it twice must change nothing</h2>
 * Reconciliation is on demand and may run many times a day. Every item is raised against a unique
 * {@code (kind, subject)}, so a second run finds the same problems and adds nothing. That matters
 * more than it sounds: without it, {@code first_seen} would reset on every run and <b>an item
 * could stay young for ever by being looked at often</b>, which would defeat the ageing entirely.
 * The database enforces it; {@code V11}'s trigger also refuses to let {@code first_seen} move.
 *
 * <h2>The threshold</h2>
 * SPEC.md O3 is open and assumes two business days. It is configuration, not a constant, for the
 * same reason as the clock zone - a threshold nobody chose is a rule nobody chose.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No automatic resolution.</b> Items are raised and aged; closing one is
 *       {@link #resolve} and takes a person's words. Nothing here decides a question has been
 *       answered.</li>
 *   <li><b>No matching of receipts to invoices.</b> That is a different three-way match - money
 *       in, rather than money out - and SPEC.md section 13's diagram is about instructions.</li>
 *   <li><b>No holiday calendar.</b> See {@link ReconItem#ageInBusinessDaysOn}.</li>
 * </ul>
 */
@Service
public class Reconciler {

    private static final RowMapper<ReconItem> MAPPER = (rs, rowNum) -> {
        Long amountMinor = rs.getObject("amount_minor", Long.class);
        String currency = rs.getString("currency");

        return new ReconItem(
                rs.getLong("id"),
                ReconKind.valueOf(rs.getString("kind")),
                rs.getString("subject_kind"),
                rs.getString("subject_ref"),
                rs.getObject("first_seen", LocalDate.class),
                ReconItem.ReconState.valueOf(rs.getString("state")),
                amountMinor == null || currency == null
                        ? Optional.empty()
                        : Optional.of(Money.of(amountMinor, Currency.valueOf(currency))),
                rs.getString("detail"));
    };

    private static final String COLUMNS =
            "id, kind, subject_kind, subject_ref, first_seen, state, amount_minor, currency, detail";

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final ClockPort clock;
    private final int staleAfterBusinessDays;

    public Reconciler(JdbcTemplate jdbc, LedgerService ledger, ClockPort clock,
                      @Value("${shilingi.recon.stale-after-business-days}") int staleAfterBusinessDays) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.clock = clock;
        this.staleAfterBusinessDays = staleAfterBusinessDays;
    }

    /** What a run found. */
    public record Report(int raised, int alreadyKnown, List<ReconItem> open, List<ReconItem> exceptions) {
    }

    /**
     * Runs the match.
     *
     * @return everything open, and separately everything past the threshold - which SPEC.md
     *         section 13 says is the difference between operational noise and an exception
     */
    @Transactional
    public Report reconcile() {
        LocalDate today = clock.businessDate();
        int raised = 0;
        int alreadyKnown = 0;

        // --- settlement without intent -------------------------------------------------------
        // PROBLEM.md F3. The worst thing to do with money nobody can explain is nothing, quietly.
        for (Map<String, Object> row : jdbc.queryForList("""
                select id, external_ref, amount_minor, currency, received_at
                  from inbound_callback
                 where disposition = 'UNMATCHED'
                """)) {
            long callbackId = ((Number) row.get("id")).longValue();
            Money amount = Money.of(((Number) row.get("amount_minor")).longValue(),
                    Currency.valueOf((String) row.get("currency")));

            boolean isNew = raise(ReconKind.UNMATCHED_INBOUND, "callback", Long.toString(callbackId),
                    today, amount,
                    "The rail reported " + amount + " against reference '" + row.get("external_ref")
                    + "', which no instruction carries. Nothing in this system asked for it.");

            if (isNew) {
                raised++;
                postToSuspense(callbackId, amount, today);
            } else {
                alreadyKnown++;
            }
        }

        // --- amounts differ ------------------------------------------------------------------
        for (Map<String, Object> row : jdbc.queryForList("""
                select c.id, c.external_ref, c.amount_minor, c.currency, i.amount_minor as expected
                  from inbound_callback c
                  join instruction i on i.id = c.matched_instruction_id
                 where c.disposition = 'AMOUNT_MISMATCH'
                """)) {
            long callbackId = ((Number) row.get("id")).longValue();
            Money reported = Money.of(((Number) row.get("amount_minor")).longValue(),
                    Currency.valueOf((String) row.get("currency")));
            long expected = ((Number) row.get("expected")).longValue();

            if (raise(ReconKind.AMOUNT_MISMATCH, "callback", Long.toString(callbackId), today, reported,
                    "Reference '" + row.get("external_ref") + "' was for " + expected
                    + " minor units and the rail reported " + reported.minorUnits()
                    + ". SPEC.md section 13: an exception, always, regardless of size.")) {
                raised++;
            } else {
                alreadyKnown++;
            }
        }

        // --- callbacks that could not be applied ----------------------------------------------
        for (Map<String, Object> row : jdbc.queryForList("""
                select id, external_ref, amount_minor, currency
                  from inbound_callback
                 where disposition = 'NOT_APPLICABLE' or processed_at is null
                """)) {
            long callbackId = ((Number) row.get("id")).longValue();
            Money amount = Money.of(((Number) row.get("amount_minor")).longValue(),
                    Currency.valueOf((String) row.get("currency")));

            if (raise(ReconKind.CALLBACK_NOT_APPLIED, "callback", Long.toString(callbackId), today,
                    amount,
                    "A callback for '" + row.get("external_ref") + "' arrived and could not be "
                    + "acted on. It is recorded; it has changed nothing.")) {
                raised++;
            } else {
                alreadyKnown++;
            }
        }

        // --- intent without settlement --------------------------------------------------------
        // In flight is normal. In flight for too long is not, and only the clock can tell them
        // apart - which is why the clock is injected.
        for (Map<String, Object> row : jdbc.queryForList("""
                select id, external_ref, amount_minor, currency, state, created_at
                  from instruction
                 where state in ('CREATED', 'SUBMITTED', 'AWAITING_RESOLUTION')
                """)) {
            long instructionId = ((Number) row.get("id")).longValue();
            LocalDate createdOn = ((Timestamp) row.get("created_at")).toInstant()
                    .atZone(clock.zone()).toLocalDate();
            Money amount = Money.of(((Number) row.get("amount_minor")).longValue(),
                    Currency.valueOf((String) row.get("currency")));

            ReconItem asIf = new ReconItem(null, ReconKind.IN_FLIGHT_STALE, "instruction",
                    Long.toString(instructionId), createdOn, ReconItem.ReconState.OPEN,
                    Optional.of(amount), "");

            if (asIf.ageInBusinessDaysOn(today) < staleAfterBusinessDays) {
                continue;
            }

            if (raise(ReconKind.IN_FLIGHT_STALE, "instruction", Long.toString(instructionId),
                    createdOn, amount,
                    "Instruction '" + row.get("external_ref") + "' has been " + row.get("state")
                    + " since " + createdOn + " - more than " + staleAfterBusinessDays
                    + " business days. It is re-queried, never retried.")) {
                raised++;
            } else {
                alreadyKnown++;
            }
        }

        List<ReconItem> open = open();
        return new Report(raised, alreadyKnown, open, olderThanThreshold(open, today));
    }

    /**
     * Invariant I6: unexplained money lands in 1900 with a first-seen date.
     *
     * <p>The rail says money left that this system never asked for. Either the rail is wrong or
     * somebody else instructed it, and until a person says which, the books record what is known:
     * shillings left the bank and the reason is a question. Suspense is an asset because the
     * question has value - somebody owes an explanation, and possibly the money.
     */
    private void postToSuspense(long callbackId, Money amount, LocalDate today) {
        if (amount.currency() != Currency.KES) {
            // Account 1900 is a shilling account. A foreign unmatched item is still raised as a
            // ReconItem above; it simply has no suspense posting, and that gap is real.
            return;
        }

        ledger.post(JournalEntry.of(today,
                "Unexplained movement reported by the rail, callback " + callbackId,
                "recon/callback/" + callbackId,
                clock.instant(),
                List.of(
                        Posting.inShillings(AccountCodes.SUSPENSE, amount),
                        Posting.inShillings(AccountCodes.BANK_KES, amount.negate()))));
    }

    /**
     * Raises an item, unless it has been raised before.
     *
     * <h2>Why {@code ON CONFLICT DO NOTHING} rather than catching the exception</h2>
     * The obvious implementation catches {@code DuplicateKeyException} and carries on. <b>On
     * PostgreSQL that does not work inside a transaction:</b> a constraint violation aborts the
     * whole transaction, and every subsequent statement fails with
     * {@code 25P02 current transaction is aborted}. A second reconciliation run would poison
     * itself on its first already-known item and report nothing about anything after it.
     *
     * <p>{@code ON CONFLICT DO NOTHING} keeps the unique constraint as the mechanism - the
     * database is still what decides - while leaving the transaction usable. The affected-row
     * count is the answer: one means new, zero means already known and its {@code first_seen}
     * stays exactly where it was.
     *
     * @return true when this is the first time the item has been raised
     */
    private boolean raise(ReconKind kind, String subjectKind, String subjectRef, LocalDate firstSeen,
                          Money amount, String detail) {
        int inserted = jdbc.update("""
                insert into recon_item
                    (kind, subject_kind, subject_ref, first_seen, state, amount_minor, currency, detail)
                values (?, ?, ?, ?, 'OPEN', ?, ?, ?)
                on conflict (kind, subject_kind, subject_ref) do nothing
                """,
                kind.name(), subjectKind, subjectRef, firstSeen,
                amount == null ? null : amount.minorUnits(),
                amount == null ? null : amount.currency().name(),
                detail);

        return inserted == 1;
    }

    /** Somebody answered the question. Takes their name and their words. */
    @Transactional
    public void resolve(long itemId, String who, String what) {
        if (who == null || who.isBlank() || what == null || what.isBlank()) {
            throw new IllegalArgumentException(
                    "Resolving a reconciliation item takes a name and an explanation. An item that "
                    + "closed itself would be an item nobody answered.");
        }

        int updated = jdbc.update(
                "update recon_item set state = 'RESOLVED', resolved_at = ?, resolution = ? "
                + "where id = ? and state = 'OPEN'",
                Timestamp.from(clock.instant()), who + ": " + what, itemId);

        if (updated != 1) {
            throw new IllegalStateException("No open reconciliation item " + itemId);
        }
    }

    public List<ReconItem> open() {
        return jdbc.query("select " + COLUMNS + " from recon_item where state = 'OPEN' "
                          + "order by first_seen, id", MAPPER);
    }

    public List<ReconItem> all() {
        return jdbc.query("select " + COLUMNS + " from recon_item order by first_seen, id", MAPPER);
    }

    /**
     * The ones that have stopped being operational noise.
     *
     * <p>PROBLEM.md section 8 question 5 asks at what age an unexplained item "stops being an
     * operational annoyance and becomes a statement about the business". This does not answer
     * that - it applies the threshold O3 sets and leaves the larger question where it belongs.
     */
    public List<ReconItem> exceptions() {
        return olderThanThreshold(open(), clock.businessDate());
    }

    private List<ReconItem> olderThanThreshold(List<ReconItem> items, LocalDate today) {
        return items.stream()
                .filter(item -> item.ageInBusinessDaysOn(today) >= staleAfterBusinessDays)
                .toList();
    }
}

package shilingi.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A set of postings recorded together (SPEC.md section 6).
 *
 * <p>Like {@link Posting}, an entry is append-only in the database. Migration V3 rejects UPDATE,
 * DELETE and TRUNCATE on {@code journal_entry} as well as on {@code posting}, even though
 * SPEC.md section 6 only names Posting - see ADR-010. The reason is {@code businessDate}: it
 * decides which month every one of the entry's postings falls in, so leaving it editable would
 * leave the postings editable in the way that matters most.
 *
 * <h2>createdAt is not now()</h2>
 * SPEC.md section 4: nothing anywhere calls {@code now()}. The caller supplies {@code createdAt}
 * from the injected clock, and the database column has no {@code DEFAULT now()} for the same
 * reason - a default would just be the database making the call instead.
 *
 * <p>{@code businessDate} and {@code createdAt} are two different facts and are both kept.
 * The business date is the day the transaction belongs to; {@code createdAt} is the moment the
 * record was written. They differ whenever anything is recorded late, which is most of the time.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>Nothing here checks that the entry balances</b>, that it has at least two postings,
 *       or that its business date is not in the future. Those are three of the six rejections
 *       in SPEC.md section 8 and they belong to the ledger service on day 3. This type is a
 *       record of what was posted, not the gate it passed through.</li>
 *   <li><b>Nothing here enforces I5</b> (realised 6100 and unrealised 6110 never sharing an
 *       entry). Also day 3.</li>
 *   <li>No reversal helper yet.</li>
 * </ul>
 *
 * @param id           database identity, null until inserted.
 * @param businessDate the day this transaction belongs to.
 * @param description  what happened, in words, for whoever reads this in March.
 * @param sourceRef    a link back to whatever caused this entry. Nullable - SPEC.md section 6
 *                     lists it without saying it is required.
 * @param createdAt    when the record was written, from the injected clock.
 * @param postings     the lines. Held in the order they were given; the ledger does not reorder them.
 */
public record JournalEntry(
        Long id,
        LocalDate businessDate,
        String description,
        String sourceRef,
        Instant createdAt,
        List<Posting> postings) {

    public JournalEntry {
        Objects.requireNonNull(businessDate, "businessDate is required");
        Objects.requireNonNull(description, "description is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(postings, "postings is required");
        postings = List.copyOf(postings);
    }

    public static JournalEntry of(LocalDate businessDate, String description, String sourceRef,
                                  Instant createdAt, List<Posting> postings) {
        return new JournalEntry(null, businessDate, description, sourceRef, createdAt, postings);
    }
}

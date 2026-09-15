package shilingi.fx;

import shilingi.ledger.JournalEntry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the FX engine produces for one conversion: SPEC.md section 10's "one balanced entry plus
 * a separate memo entry".
 *
 * <p>Both are ordinary journal entries and both balance. Neither is written by the engine - the
 * caller posts them through {@code LedgerService}, which is the only way into the journal.
 *
 * @param movement        the real movement of money: shillings in, dollars out, and the realised
 *                        exchange difference between the two.
 * @param costOfConversion what the conversion cost, split into spread and fee. Empty when there
 *                        was neither - a conversion exactly at mid with no fee charged produces
 *                        no cost entry rather than an entry full of zeroes.
 */
public record ConversionPostings(JournalEntry movement, Optional<JournalEntry> costOfConversion) {

    public ConversionPostings {
        Objects.requireNonNull(movement, "movement is required");
        Objects.requireNonNull(costOfConversion, "costOfConversion is required");
    }

    /** Both entries, in the order they must be posted. */
    public List<JournalEntry> all() {
        return costOfConversion.map(cost -> List.of(movement, cost)).orElseGet(() -> List.of(movement));
    }
}

package shilingi.recon;

import shilingi.money.Money;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * An unmatched thing, and how long it has been unmatched (SPEC.md section 6).
 *
 * <p>PROBLEM.md section 4 on suspense, which applies to every item here: "Suspense is not a bin.
 * Every item in it is a question with a date attached, and it gets older and more embarrassing
 * until somebody answers it."
 *
 * <h2>Age is worked out, not stored</h2>
 * Same reasoning as OVERDUE in ADR-017. A stored age is true until the next day and then false,
 * silently, with nothing announcing it. {@link #ageInBusinessDaysOn} derives it from
 * {@code firstSeen} against a date the caller must supply from the injected clock.
 */
public record ReconItem(Long id, ReconKind kind, String subjectKind, String subjectRef,
                        LocalDate firstSeen, ReconState state, Optional<Money> amount,
                        String detail) {

    public ReconItem {
        Objects.requireNonNull(kind, "kind is required");
        Objects.requireNonNull(subjectKind, "subjectKind is required");
        Objects.requireNonNull(subjectRef, "subjectRef is required");
        Objects.requireNonNull(firstSeen, "firstSeen is required");
        Objects.requireNonNull(state, "state is required");
        Objects.requireNonNull(amount, "amount is required (Optional, possibly empty)");
        Objects.requireNonNull(detail, "detail is required");
    }

    public enum ReconState {
        /** Nobody has answered it yet. */
        OPEN,
        /** Somebody has. */
        RESOLVED
    }

    /**
     * How many business days old this is.
     *
     * <p><b>Weekends are excluded; Kenyan public holidays are not.</b> SPEC.md O3 says "2 business
     * days" and a holiday calendar cannot be written from memory - CLAUDE.md rule 1 - so this
     * counts weekdays and says what it does not know. The effect is that an item can cross the
     * threshold a day or two early around a holiday, which errs towards showing somebody a
     * question sooner. That is the safe direction for this to be wrong in.
     *
     * @param today from the injected clock. Never {@code LocalDate.now()}.
     */
    public long ageInBusinessDaysOn(LocalDate today) {
        Objects.requireNonNull(today, "today is required, and must come from the injected clock");

        if (!today.isAfter(firstSeen)) {
            return 0L;
        }

        long days = 0L;
        for (LocalDate day = firstSeen.plusDays(1); !day.isAfter(today); day = day.plusDays(1)) {
            switch (day.getDayOfWeek()) {
                case SATURDAY, SUNDAY -> {
                }
                default -> days++;
            }
        }
        return days;
    }

    /** Plain calendar age, for anything that wants it without the business-day question. */
    public long ageInDaysOn(LocalDate today) {
        return ChronoUnit.DAYS.between(firstSeen, today);
    }

    public boolean isOpen() {
        return state == ReconState.OPEN;
    }
}

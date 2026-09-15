-- V4: an unbalanced entry cannot exist in this database, by any route.
--
-- SPEC.md section 8 requires the LEDGER to reject an unbalanced entry with a distinct exception,
-- and LedgerService does. This migration is the layer underneath: CLAUDE.md's standing rule is
-- that the database constraint is the mechanism and the application check sits on top of it,
-- never instead of it. Without this, an unbalanced entry is impossible only for callers who go
-- through LedgerService - a script, a migration, a future service or a psql prompt could still
-- write one.
--
-- DECISION: a DEFERRABLE INITIALLY DEFERRED constraint trigger, checked at COMMIT.
--   Postings are inserted one row at a time, so an entry is unbalanced in the middle of being
--   written. A check that fired per statement would reject every entry ever made. Deferring to
--   commit is what makes the check possible at all.
--   Trade-off 1: the failure surfaces at commit, not at the offending statement, and arrives as
--     a generic integrity violation rather than one of the seven distinct exceptions. That is
--     why LedgerService checks first - it produces the readable failure, and this catches
--     everything that did not go through it.
--   Trade-off 2: FOR EACH ROW means the aggregate runs once per posting in the entry. Entries
--     here have two to four lines, so this is a handful of index lookups at commit.
--   Trade-off 3: an entry with NO postings at all is not caught, because the trigger hangs off
--     posting and never fires. LedgerService rejects it (TooFewPostings). Recorded, not hidden.

create or replace function entry_must_balance() returns trigger
    language plpgsql
as $$
declare
    shilling_total  bigint;
    line_count      integer;
    currency_count  integer;
    currency_total  bigint;
    only_currency   text;
begin
    select coalesce(sum(functional_amount_minor), 0),
           count(*),
           count(distinct currency)
      into shilling_total, line_count, currency_count
      from posting
     where entry_id = new.entry_id;

    -- SPEC.md section 8: "the entry has fewer than two postings"
    if line_count < 2 then
        raise exception
            'Journal entry % has % posting(s). An entry needs at least two: one line records '
            'only half of what happened.',
            new.entry_id, line_count
            using errcode = 'integrity_constraint_violation';
    end if;

    -- Invariant I2, universal half: every entry balances in shillings.
    if shilling_total <> 0 then
        raise exception
            'Journal entry % does not balance in shillings: its postings sum to % cents, not '
            'zero. Nothing will be added to make it agree - a total that does not agree is a '
            'finding (SPEC.md section 1 rule 3).',
            new.entry_id, shilling_total
            using errcode = 'integrity_constraint_violation';
    end if;

    -- Invariant I2, single-currency half. Deliberately NOT applied to cross-currency entries:
    -- not one of the four entries in PROBLEM.md section 5 balances per currency. See ADR-013.
    if currency_count = 1 then
        select coalesce(sum(amount_minor), 0), min(currency)
          into currency_total, only_currency
          from posting
         where entry_id = new.entry_id;

        if currency_total <> 0 then
            raise exception
                'Journal entry % is wholly in %, so it must balance in %, but its postings sum '
                'to % minor units, not zero.',
                new.entry_id, only_currency, only_currency, currency_total
                using errcode = 'integrity_constraint_violation';
        end if;
    end if;

    return null;
end;
$$;

create constraint trigger posting_entry_must_balance
    after insert on posting
    deferrable initially deferred
    for each row execute function entry_must_balance();

comment on function entry_must_balance() is
    'Invariant I2 and the two-posting minimum, enforced at commit. LedgerService checks the same '
    'rules first and produces the readable exception; this exists so that no other route into '
    'the table can write an entry that does not balance.';

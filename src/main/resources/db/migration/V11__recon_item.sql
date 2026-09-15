-- V11: the things that do not match, and how long they have not matched for.
--
-- SPEC.md section 6: ReconItem - kind, first_seen, age_days, state.
-- SPEC.md section 13: unmatched items age. "Below the threshold they are operational noise.
--   Above it they are exceptions that appear on the screen."
--
-- DECISION: age_days is NOT a column, for the same reason OVERDUE is not one (ADR-017).
--   An age that is stored is only true until the next day, and it becomes false silently. It is
--   derived from first_seen against the injected clock, so it cannot go stale and there is no
--   job to forget to run.

create table recon_item (
    id            bigint      generated always as identity,
    kind          text        not null,
    subject_kind  text        not null,
    subject_ref   text        not null,
    first_seen    date        not null,
    state         text        not null,
    amount_minor  bigint,
    currency      text,
    detail        text        not null,
    resolved_at   timestamptz,
    resolution    text,

    constraint recon_item_pk primary key (id),

    constraint recon_item_kind_known check (kind in (
        'UNMATCHED_INBOUND',     -- a settlement with no intent behind it (PROBLEM.md F3)
        'AMOUNT_MISMATCH',       -- right reference, wrong amount. An exception, always
        'IN_FLIGHT_STALE',       -- an intent with no settlement, older than the threshold
        'CALLBACK_NOT_APPLIED'   -- arrived, recorded, and could not be acted on
    )),
    constraint recon_item_state_known check (state in ('OPEN', 'RESOLVED')),
    constraint recon_item_currency_known check (currency is null or currency in ('KES', 'USDC')),
    constraint recon_item_has_detail check (length(trim(detail)) > 0),

    -- Reconciliation runs on demand and may run many times a day. Without this, every run would
    -- raise the same items again and the ageing would reset each time - which would hide exactly
    -- the old items the ageing exists to expose.
    constraint recon_item_is_raised_once unique (kind, subject_kind, subject_ref)
);

create index recon_item_open on recon_item (state, first_seen);

comment on column recon_item.first_seen is
    'The day this was first noticed. Never updated - re-running reconciliation must not reset the '
    'age, or an item could stay young for ever by being looked at often.';

comment on table recon_item is
    'SPEC.md section 13. An item here is a question with a date attached, and it gets older and '
    'more embarrassing until somebody answers it (PROBLEM.md section 4).';

-- first_seen must never move, or the ageing means nothing. Everything else about an item is
-- fixed at the moment it is raised; only the resolution may be written, and only once.
create or replace function recon_item_first_seen_is_fixed() returns trigger
    language plpgsql
as $$
begin
    if (new.kind, new.subject_kind, new.subject_ref, new.first_seen, new.amount_minor, new.currency)
       is distinct from
       (old.kind, old.subject_kind, old.subject_ref, old.first_seen, old.amount_minor, old.currency) then
        raise exception
            'What a reconciliation item is, and when it was first seen, cannot change. An item '
            'that could be re-dated would stay young for ever by being looked at often.'
            using errcode = 'restrict_violation';
    end if;

    if old.state = 'RESOLVED' and new.state <> 'RESOLVED' then
        raise exception
            'Reconciliation item % is resolved. Re-opening it would lose the record of what was '
            'decided; a new question is a new item.',
            old.id
            using errcode = 'restrict_violation';
    end if;

    return new;
end;
$$;

create trigger recon_item_ageing_is_honest
    before update on recon_item
    for each row execute function recon_item_first_seen_is_fixed();

create trigger recon_item_no_delete
    before delete on recon_item
    for each row execute function reject_mutation_of_append_only_table();

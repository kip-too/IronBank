-- V10: every callback the system is handed, recorded before it is acted on.
--
-- SPEC.md section 13: "Every inbound callback is recorded BEFORE it is processed, keyed on the
-- rail's own reference, with a unique constraint. A repeated callback is detected as already-seen
-- and ignored. A callback for an unknown instruction is not an error to swallow - it goes to
-- reconciliation as an unmatched item."
--
-- The order in that sentence is the whole design. Recorded first, processed second, so that a
-- crash or a bug in the processing leaves evidence that the callback arrived. A system that
-- processes first and records afterwards loses the events it handled badly, which are exactly
-- the events worth having.

create table inbound_callback (
    id                     bigint      generated always as identity,
    rail                   text        not null,
    rail_ref               text        not null,
    external_ref           text        not null,
    outcome                text        not null,
    amount_minor           bigint      not null,
    currency               text        not null,
    occurred_at            timestamptz not null,
    received_at            timestamptz not null,
    raw_payload            json        not null,

    -- Filled in when the callback is processed. Null means recorded but not yet acted on, which
    -- is itself a reconciliation item rather than a lost event.
    processed_at           timestamptz,
    matched_instruction_id bigint,
    disposition            text,

    constraint inbound_callback_pk primary key (id),

    -- SPEC.md section 13's mechanism for a repeated callback. The rail's own reference, not ours:
    -- a rail re-delivering the same event uses the same reference for it, and this is what turns
    -- "we should check whether we have seen this" into "we cannot record it twice".
    constraint inbound_callback_rail_ref_is_unique unique (rail, rail_ref),

    constraint inbound_callback_outcome_known check (outcome in ('SUCCEEDED', 'FAILED')),
    constraint inbound_callback_currency_known check (currency in ('KES', 'USDC')),
    constraint inbound_callback_has_a_rail_ref check (length(trim(rail_ref)) > 0),
    constraint inbound_callback_matches_an_instruction
        foreign key (matched_instruction_id) references instruction (id),

    constraint inbound_callback_disposition_known check (disposition is null or disposition in (
        'APPLIED',            -- matched an instruction and moved it
        'ALREADY_SEEN',       -- a repeat; changed nothing
        'UNMATCHED',          -- no instruction has this external reference
        'AMOUNT_MISMATCH',    -- right reference, wrong amount - an exception, always
        'NOT_APPLICABLE'      -- the instruction was in no state to receive it
    ))
);

create index inbound_callback_by_external_ref on inbound_callback (external_ref);
create index inbound_callback_unresolved on inbound_callback (disposition) where processed_at is null;

comment on table inbound_callback is
    'Recorded before processing, keyed on the rail''s own reference. SPEC.md section 13.';

comment on column inbound_callback.raw_payload is
    'What the rail actually sent, verbatim. json rather than jsonb for the reason in V7: this is '
    'evidence, and evidence is kept byte for byte rather than re-rendered.';

comment on column inbound_callback.disposition is
    'What was done about it. Null with a null processed_at means it arrived and nothing has acted '
    'on it - which reconciliation must see, not swallow.';

-- Append-only, same as the journal and the intent log. A callback record that could be edited
-- after the fact is not evidence that a callback arrived; it is a note saying somebody thinks one
-- did. The disposition columns are the exception - they are written once, by the processing step,
-- and the trigger below permits exactly that and nothing else.

create or replace function inbound_callback_is_write_once() returns trigger
    language plpgsql
as $$
begin
    if (new.rail, new.rail_ref, new.external_ref, new.outcome, new.amount_minor, new.currency,
        new.occurred_at, new.received_at, new.raw_payload::text)
       is distinct from
       (old.rail, old.rail_ref, old.external_ref, old.outcome, old.amount_minor, old.currency,
        old.occurred_at, old.received_at, old.raw_payload::text) then
        raise exception
            'What a callback said cannot be changed. Only how it was dealt with may be recorded, '
            'and only once.'
            using errcode = 'restrict_violation';
    end if;

    if old.processed_at is not null then
        raise exception
            'Callback % has already been dealt with (%). Acting on it a second time is exactly '
            'the double-processing the unique constraint on rail_ref exists to prevent.',
            old.id, old.disposition
            using errcode = 'restrict_violation';
    end if;

    return new;
end;
$$;

create trigger inbound_callback_write_once
    before update on inbound_callback
    for each row execute function inbound_callback_is_write_once();

create trigger inbound_callback_no_delete
    before delete on inbound_callback
    for each row execute function reject_mutation_of_append_only_table();

create trigger inbound_callback_no_truncate
    before truncate on inbound_callback
    for each statement execute function reject_mutation_of_append_only_table();

-- V6: what we are owed, and what we owe.
--
-- SPEC.md section 6:
--   Receivable   counterparty, currency, amount, issue_date, status
--   Obligation   name, currency, amount, due_date, status
--
-- These are NOT append-only, unlike the journal. A posting is a fact about a moment and can
-- never change; an obligation is a thing with a life, and SPEC.md section 7 gives it a state
-- machine. The difference is deliberate and the triggers below draw the line: status may move,
-- along the arrows and only along them, and nothing else about the record may move at all.

create table receivable (
    id            bigint generated always as identity,
    counterparty  text   not null,
    currency      text   not null,
    amount_minor  bigint not null,
    issue_date    date   not null,
    status        text   not null,

    constraint receivable_pk primary key (id),
    constraint receivable_currency_known check (currency in ('KES', 'USDC')),
    constraint receivable_amount_is_positive check (amount_minor > 0),
    constraint receivable_has_a_counterparty check (length(trim(counterparty)) > 0),

    -- SPEC.md section 7 gives a state machine for Obligation and for Instruction, and none for
    -- Receivable. These two states are the minimum the documents actually evidence: PROBLEM.md
    -- section 5 raises an invoice on day 1 and settles it on day 12. See ADR-017.
    constraint receivable_status_known check (status in ('OUTSTANDING', 'SETTLED'))
);

comment on table receivable is
    'What a client owes. Note there is no due_date: SPEC.md section 6 gives a receivable an '
    'issue_date only, so this system has no notion of a receivable being late. That is a gap in '
    'the specification, not an omission here.';

create table obligation (
    id            bigint generated always as identity,
    name          text   not null,
    currency      text   not null,
    amount_minor  bigint not null,
    due_date      date   not null,
    status        text   not null,

    constraint obligation_pk primary key (id),
    constraint obligation_currency_known check (currency in ('KES', 'USDC')),
    constraint obligation_amount_is_positive check (amount_minor > 0),
    constraint obligation_has_a_name check (length(trim(name)) > 0),

    -- OVERDUE IS DELIBERATELY NOT IN THIS LIST, and the database will refuse to store it.
    --
    -- SPEC.md section 7: OVERDUE means "due date passed, no funds allocated - this is a bug in
    -- the agent's planning, and must be visible as one". A stored status only becomes true when
    -- something runs and sets it, so a stored OVERDUE is invisible exactly when the sweeper that
    -- sets it has failed - which is precisely the moment the bug most needs to be visible.
    --
    -- OVERDUE is therefore DERIVED, from the status and the due date against the injected clock.
    -- It is true the instant the date passes, it cannot go stale, and there is no job to forget
    -- to run. See ADR-017.
    constraint obligation_status_known check (status in ('SCHEDULED', 'FUNDED', 'PAID'))
);

comment on column obligation.status is
    'SCHEDULED, FUNDED or PAID - the stored half of SPEC.md section 7''s machine. OVERDUE is not '
    'stored and cannot be: it is derived from this column and due_date against the injected '
    'clock. See Obligation.stateOn.';

create index obligation_by_due_date on obligation (due_date);
create index obligation_by_status on obligation (status);

-- ---------------------------------------------------------------------------------------
-- The state machines, at the database
-- ---------------------------------------------------------------------------------------
--
-- The application enforces these too, with named exceptions. As everywhere else in this
-- codebase, the constraint is the mechanism and the application check sits on top of it.

create or replace function obligation_transition_must_be_legal() returns trigger
    language plpgsql
as $$
begin
    if (new.name, new.currency, new.amount_minor, new.due_date)
       is distinct from (old.name, old.currency, old.amount_minor, old.due_date) then
        raise exception
            'Only the status of an obligation may change. Changing its amount or due date '
            'silently would rewrite what the agent was planning against.'
            using errcode = 'restrict_violation';
    end if;

    -- SPEC.md section 7:  SCHEDULED -> FUNDED -> PAID.  No skipping, no going back.
    if old.status = new.status then
        return new;
    elsif old.status = 'SCHEDULED' and new.status = 'FUNDED' then
        return new;
    elsif old.status = 'FUNDED' and new.status = 'PAID' then
        return new;
    end if;

    raise exception
        'Obligation % cannot move from % to %. SPEC.md section 7 allows SCHEDULED to FUNDED to '
        'PAID, and nothing else - paying something never funded would record money leaving that '
        'was never set aside.',
        old.id, old.status, new.status
        using errcode = 'restrict_violation';
end;
$$;

create trigger obligation_state_machine
    before update on obligation
    for each row execute function obligation_transition_must_be_legal();

create or replace function receivable_transition_must_be_legal() returns trigger
    language plpgsql
as $$
begin
    if (new.counterparty, new.currency, new.amount_minor, new.issue_date)
       is distinct from (old.counterparty, old.currency, old.amount_minor, old.issue_date) then
        raise exception
            'Only the status of a receivable may change. An invoice that can be quietly '
            'restated is an invoice nobody can reconcile against.'
            using errcode = 'restrict_violation';
    end if;

    if old.status = new.status or (old.status = 'OUTSTANDING' and new.status = 'SETTLED') then
        return new;
    end if;

    raise exception
        'Receivable % cannot move from % to %. A settled receivable does not become outstanding '
        'again; money that came back is its own event.',
        old.id, old.status, new.status
        using errcode = 'restrict_violation';
end;
$$;

create trigger receivable_state_machine
    before update on receivable
    for each row execute function receivable_transition_must_be_legal();

-- Deliberately NOT built: no history of status changes. SPEC.md section 6 gives these records a
-- status, not a status log, and the journal is where history lives. If it later matters WHEN an
-- obligation became funded, that is a new table and a new decision, not a column added quietly.

-- V8: completes the instruction table V7 started, and enforces SPEC.md section 7's machine.
--
-- V7 created this table with only the two constraints the intent log needed: the NOT NULL
-- foreign key to intent (I9) and the unique external reference (F5). Everything else is here.

alter table instruction
    add column type          text    not null default 'CONVERSION',
    add column amount_minor  bigint  not null default 0,
    add column currency      text    not null default 'USDC',
    add column state         text    not null default 'CREATED',
    add column attempts      integer not null default 0;

-- The defaults existed only so the columns could be added to a table that may already have rows
-- in a running database. New rows must say what they are, not inherit a guess.
alter table instruction
    alter column type drop default,
    alter column amount_minor drop default,
    alter column currency drop default,
    alter column state drop default;

alter table instruction
    add constraint instruction_type_known check (type in ('CONVERSION', 'PAYOUT')),
    add constraint instruction_currency_known check (currency in ('KES', 'USDC')),
    add constraint instruction_amount_is_positive check (amount_minor > 0),
    add constraint instruction_attempts_not_negative check (attempts >= 0),
    add constraint instruction_state_known check (state in (
        'CREATED', 'SUBMITTED', 'AWAITING_RESOLUTION', 'SETTLED', 'FAILED', 'MANUAL_REVIEW'));

create index instruction_by_state on instruction (state);

-- ---------------------------------------------------------------------------------------
-- Invariant I7, at the database
-- ---------------------------------------------------------------------------------------
--
-- The Java types make a retry from AWAITING_RESOLUTION fail to COMPILE (see Instruction).
-- That is the strong guarantee and it is the one SPEC.md section 9 asks for. This trigger is
-- the second layer, because a type system does not protect a psql prompt, a migration or a
-- future service written by somebody in a hurry.
--
-- In the data, a retry looks like exactly one thing: going back to SUBMITTED, and/or the
-- attempt count going up. Both are refused for an instruction that is AWAITING_RESOLUTION.

create or replace function instruction_transition_must_be_legal() returns trigger
    language plpgsql
as $$
begin
    if (new.intent_id, new.external_ref, new.type, new.amount_minor, new.currency)
       is distinct from (old.intent_id, old.external_ref, old.type, old.amount_minor, old.currency) then
        raise exception
            'Only the state and the attempt count of an instruction may change. Changing what it '
            'instructs, or which intent explains it, would break the link that makes it accountable.'
            using errcode = 'restrict_violation';
    end if;

    -- I7. The whole reason AWAITING_RESOLUTION exists.
    if old.state = 'AWAITING_RESOLUTION' and new.attempts <> old.attempts then
        raise exception
            'Instruction % is AWAITING_RESOLUTION and must never be retried. Its outcome is '
            'unknown, and unknown is not failure - retrying it is how money leaves twice and does '
            'not come back (PROBLEM.md F5). Re-query it, or escalate it to a human.',
            old.id
            using errcode = 'restrict_violation';
    end if;

    if old.state = new.state then
        return new;
    end if;

    -- SPEC.md section 7's arrows, plus SUBMITTED -> FAILED. See ADR-020 for why that one is
    -- added: a rail that explicitly rejects an instruction has given a KNOWN outcome, and
    -- routing it through the state reserved for unknown outcomes blurs the distinction.
    if (old.state = 'CREATED' and new.state = 'SUBMITTED')
       or (old.state = 'SUBMITTED' and new.state in ('SETTLED', 'AWAITING_RESOLUTION', 'FAILED'))
       or (old.state = 'AWAITING_RESOLUTION' and new.state in ('SETTLED', 'FAILED', 'MANUAL_REVIEW'))
       or (old.state = 'FAILED' and new.state = 'SUBMITTED') then
        return new;
    end if;

    raise exception
        'Instruction % cannot move from % to %. SPEC.md section 7 draws no such arrow.',
        old.id, old.state, new.state
        using errcode = 'restrict_violation';
end;
$$;

create trigger instruction_state_machine
    before update on instruction
    for each row execute function instruction_transition_must_be_legal();

comment on column instruction.state is
    'SPEC.md section 7. AWAITING_RESOLUTION is not failure and is never retried - the Java types '
    'make that a compile error, and instruction_transition_must_be_legal() makes it a database '
    'error for everything that is not Java.';

comment on column instruction.attempts is
    'Incremented only by a submit. An instruction that is AWAITING_RESOLUTION cannot have this '
    'changed at all, which is what a retry would look like in the data.';

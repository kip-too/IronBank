-- V9: a way out of MANUAL_REVIEW.
--
-- SPEC.md section 7 draws no arrow out of MANUAL_REVIEW. Built literally on day 9, that made it
-- a trap: anything that reached it stayed there for ever, including on demo day. See ADR-021.
--
-- The two exits added are the same two AWAITING_RESOLUTION has, and for the same reason - a
-- resolution is somebody saying what happened. In Java they require a name and evidence.
--
-- There is still NO arrow from MANUAL_REVIEW to SUBMITTED. A human who wants the payment sent
-- again resolves it to FAILED first, stating that it did not happen, and retries from there.
-- Somebody has to put their name to "this did not happen" before anything is sent a second time.

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

    -- I7. Unchanged, and still the reason AWAITING_RESOLUTION exists.
    if old.state = 'AWAITING_RESOLUTION' and new.attempts <> old.attempts then
        raise exception
            'Instruction % is AWAITING_RESOLUTION and must never be retried. Its outcome is '
            'unknown, and unknown is not failure - retrying it is how money leaves twice and does '
            'not come back (PROBLEM.md F5). Re-query it, or escalate it to a human.',
            old.id
            using errcode = 'restrict_violation';
    end if;

    -- The same protection for MANUAL_REVIEW: a human resolves it, a human does not re-send from it.
    if old.state = 'MANUAL_REVIEW' and new.attempts <> old.attempts then
        raise exception
            'Instruction % is in MANUAL_REVIEW and cannot be retried from there. Resolve it to '
            'FAILED first - somebody has to say that it did not happen - and retry from FAILED.',
            old.id
            using errcode = 'restrict_violation';
    end if;

    if old.state = new.state then
        return new;
    end if;

    if (old.state = 'CREATED' and new.state = 'SUBMITTED')
       or (old.state = 'SUBMITTED' and new.state in ('SETTLED', 'AWAITING_RESOLUTION', 'FAILED'))
       or (old.state = 'AWAITING_RESOLUTION' and new.state in ('SETTLED', 'FAILED', 'MANUAL_REVIEW'))
       or (old.state = 'MANUAL_REVIEW' and new.state in ('SETTLED', 'FAILED'))
       or (old.state = 'FAILED' and new.state = 'SUBMITTED') then
        return new;
    end if;

    raise exception
        'Instruction % cannot move from % to %. SPEC.md section 7 draws no such arrow.',
        old.id, old.state, new.state
        using errcode = 'restrict_violation';
end;
$$;

-- V7: the intent log, and the link that makes it mean something.
--
-- SPEC.md section 6: Intent is "the agent's decision, written before acting".
-- SPEC.md section 7: "Intent is append-only. It has no state machine. It happened."
-- SPEC.md section 11, steps 6 and 7: write the intent and COMMIT IT, and only then create the
--   instruction. "If the process dies between them, we have a decision with no action -
--   recoverable and visible. The reverse leaves an action nobody can explain, which is F6."

create table intent (
    id               bigint      generated always as identity,
    created_at       timestamptz not null,
    trigger          text        not null,
    reasoning        text        not null,
    inputs_snapshot  json        not null,
    proposed_action  json        not null,

    constraint intent_pk primary key (id),
    constraint intent_has_a_trigger check (length(trim(trigger)) > 0),
    constraint intent_has_reasoning check (length(trim(reasoning)) > 0)
);

-- DECISION: json, not jsonb.
--   jsonb decomposes the document, reorders its keys and drops duplicates - it stores what the
--   JSON MEANS. The json type stores the exact text it was given. For an evidence log those are
--   different requirements, and this is an evidence log: the point of a snapshot is to be able
--   to say "this is what the agent saw", byte for byte, not "this is something equivalent to
--   what the agent saw". json still validates well-formedness, so nothing malformed gets in.
--   Trade-off: no GIN index, and querying inside the document is slower. Nothing queries inside
--   these documents; they are read whole, by a person, when a decision is being questioned.
comment on column intent.inputs_snapshot is
    'A COPY of what the agent could see when it decided, not a reference to it (SPEC.md section '
    '6). The obligations, balances and rates behind it will have changed by the time anyone '
    'reads this, which is exactly why it is copied.';

comment on column intent.created_at is
    'From the injected clock. No DEFAULT now() - see SPEC.md section 4 and V3.';

create index intent_by_created_at on intent (created_at);

-- Append-only, using the same function V3 installed for the journal. An intent that could be
-- edited after the fact is worthless: the whole value of writing the reasoning down BEFORE
-- acting is lost the moment it can be rewritten once the outcome is known. That is F6 with
-- extra steps.
create trigger intent_is_append_only
    before update or delete on intent
    for each row execute function reject_mutation_of_append_only_table();

create trigger intent_cannot_be_truncated
    before truncate on intent
    for each statement execute function reject_mutation_of_append_only_table();

-- ---------------------------------------------------------------------------------------
-- instruction: created here DELIBERATELY INCOMPLETE
-- ---------------------------------------------------------------------------------------
--
-- This table gets its type, amount, currency, state and attempt columns on day 9, with the
-- state machine. What it has today are the two constraints that are not day 9's business:
--
--   intent_id NOT NULL REFERENCES intent(id)
--     Invariant I9, and SPEC.md section 9 names this exact mechanism: "enforced by a foreign key
--     that is NOT NULL". It is what makes SPEC.md section 11's ordering structural rather than a
--     convention - an instruction cannot be inserted unless its intent row is already committed,
--     so the intent can never be written after its instruction.
--
--   external_ref UNIQUE
--     SPEC.md section 13: "the single most important constraint in the schema and it is what
--     makes F5 (silent double payment) impossible rather than unlikely."
--
-- Both belong with the intent log, because they are what the intent log is FOR. Building them on
-- day 9 instead would mean day 7 could only claim its guarantee rather than prove it.

create table instruction (
    id           bigint      generated always as identity,
    intent_id    bigint      not null,
    external_ref text        not null,
    created_at   timestamptz not null,

    constraint instruction_pk primary key (id),

    -- I9. The reason this is a foreign key and not a check in application code.
    constraint instruction_needs_an_intent
        foreign key (intent_id) references intent (id),

    -- F5. The reason this is a database constraint and not an application lookup.
    constraint instruction_external_ref_is_unique unique (external_ref),

    constraint instruction_has_an_external_ref check (length(trim(external_ref)) > 0)
);

comment on table instruction is
    'INCOMPLETE until day 9, which adds type, amount, currency, state and attempts along with '
    'the state machine from SPEC.md section 7. What is here is only what invariants I9 and F5 '
    'need, because those are what make the intent log meaningful.';

-- V3: the journal, and the rule that makes it permanent.
--
-- SPEC.md section 6: "Posting is immutable. No update, no delete. A correction is a new entry
-- that reverses and re-posts. The database enforces this with a rule that rejects updates on
-- the table, not with a code convention."

create table journal_entry (
    id             bigint      generated always as identity,
    business_date  date        not null,
    description    text        not null,
    source_ref     text,
    created_at     timestamptz not null,

    constraint journal_entry_pk primary key (id)
);

-- DECISION: created_at has NO default of now().
--   SPEC.md section 4 says nothing anywhere calls now(), and a DEFAULT now() in DDL is the
--   database calling now() on the application's behalf - the same failure wearing a different
--   hat. The application supplies created_at from the injected clock, every time.
--   Trade-off: an insert that forgets created_at fails loudly instead of silently getting a
--   plausible timestamp. That is the intended direction of failure.
comment on column journal_entry.created_at is
    'Supplied by the application from the injected clock. Deliberately has no DEFAULT now() - '
    'see SPEC.md section 4.';

comment on column journal_entry.source_ref is
    'Nullable. SPEC.md section 6 lists it but does not say it is required, so it is not made '
    'required here. Reconciliation (day 13) may show that it should be.';

create table posting (
    id                       bigint         generated always as identity,
    entry_id                 bigint         not null,
    account_code             text           not null,
    amount_minor             bigint         not null,
    currency                 text           not null,
    rate_value               numeric(20, 8),
    rate_source              text,
    rate_timestamp           timestamptz,
    functional_amount_minor  bigint         not null,

    constraint posting_pk primary key (id),

    constraint posting_belongs_to_an_entry
        foreign key (entry_id) references journal_entry (id),

    -- A posting must be denominated in its own account's currency. Enforced with a composite
    -- foreign key rather than a trigger, so it is declarative and cannot be skipped.
    -- Trade-off: an account cannot hold two currencies. SPEC.md section 2 puts multi-currency
    -- accounts out of scope, so this constraint spends nothing that was going to be used.
    constraint posting_matches_its_accounts_currency
        foreign key (account_code, currency) references account (code, currency),

    constraint posting_currency_known
        check (currency in ('KES', 'USDC')),

    -- Invariant I1, at the database. SPEC.md section 8 also requires the ledger to reject this
    -- at post time with a distinct exception (day 3). The application check sits on top of this
    -- constraint, never instead of it.
    constraint posting_foreign_currency_needs_rate_source_and_timestamp
        check (
            currency = 'KES'
            or (rate_value is not null and rate_source is not null and rate_timestamp is not null)
        ),

    -- A shilling posting is its own functional value. Nothing to convert, nothing to disagree.
    constraint posting_kes_is_its_own_functional_value
        check (currency <> 'KES' or functional_amount_minor = amount_minor),

    constraint posting_rate_is_positive
        check (rate_value is null or rate_value > 0)
);

comment on column posting.functional_amount_minor is
    'The shilling value of this posting AS AT THE MOMENT IT WAS MADE. Stored, never recomputed '
    'on read - recomputing it later with today''s rate silently rewrites history. '
    'SPEC.md section 6.';

comment on column posting.rate_value is
    'numeric, scale 8, per SPEC.md section 5. Exact decimal, not floating point. Money stays '
    'in bigint minor units; a rate is not money. See ADR-011.';

create index posting_by_entry on posting (entry_id);
create index journal_entry_by_business_date on journal_entry (business_date);

-- ---------------------------------------------------------------------------------------
-- Append-only, enforced by the database
-- ---------------------------------------------------------------------------------------
--
-- DECISION: a trigger that raises, not a PostgreSQL RULE.
--   The obvious reading of "add a database-level rule" is CREATE RULE ... DO INSTEAD NOTHING.
--   That is the wrong mechanism here, because it discards the UPDATE SILENTLY - the caller is
--   told it succeeded and nothing changed. A system built to refuse plugs must not quietly
--   swallow writes. See ADR-010.
--
-- TRUNCATE is blocked too. TRUNCATE does not fire row-level DELETE triggers, so blocking only
-- UPDATE and DELETE would leave the whole journal erasable by one statement.

create or replace function reject_mutation_of_append_only_table() returns trigger
    language plpgsql
as $$
begin
    raise exception
        'Table % is append-only: % is not permitted. A correction is a new entry that reverses '
        'and re-posts, never an edit. See SPEC.md section 6.',
        tg_table_name, tg_op
        using errcode = 'restrict_violation';
end;
$$;

create trigger posting_is_append_only
    before update or delete on posting
    for each row execute function reject_mutation_of_append_only_table();

create trigger posting_cannot_be_truncated
    before truncate on posting
    for each statement execute function reject_mutation_of_append_only_table();

-- DECISION: journal_entry is locked down too, though SPEC.md section 6 only says Posting is
--   immutable. business_date and source_ref live on the entry and are financially material -
--   business_date decides which month a posting falls in. Freezing the postings while leaving
--   their date editable would be a hole with a lock on it.
--   Trade-off: a typo in description can never be corrected in place, only by a reversing
--   entry. That is the same discipline the postings already live under.

create trigger journal_entry_is_append_only
    before update or delete on journal_entry
    for each row execute function reject_mutation_of_append_only_table();

create trigger journal_entry_cannot_be_truncated
    before truncate on journal_entry
    for each statement execute function reject_mutation_of_append_only_table();

-- KNOWN LIMIT, recorded rather than hidden: a superuser can ALTER TABLE ... DISABLE TRIGGER,
-- and this application currently connects as the postgres superuser. Running as a role that
-- does not own these tables would close that, and is deferred rather than done. Noted in
-- ADR-010.

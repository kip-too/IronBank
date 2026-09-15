-- V2: the chart of accounts.
--
-- The ten accounts below are SPEC.md section 6's minimum set. Each exists because of a rule
-- in section 9; none is decorative and none may be merged with another.
--
-- DECISION: the account code is the primary key, not a surrogate id.
--   Why: a posting row then reads as "6100" rather than "account_id 7", and this project's
--   whole claim is that a reviewer in March can read September's records. A surrogate key
--   would push every audit query through a join.
--   Trade-off: renumbering an account becomes a data migration rather than an update. Charts
--   of accounts do not get renumbered casually, so that cost is close to theoretical here.

create table account (
    code         text    not null,
    name         text    not null,
    type         text    not null,
    currency     text    not null,
    is_suspense  boolean not null default false,

    constraint account_pk primary key (code),

    -- Lets posting declare a composite foreign key on (account_code, currency), which is how
    -- "a posting must be in its account's currency" is enforced declaratively in V3.
    constraint account_code_currency_unique unique (code, currency),

    constraint account_type_known
        check (type in ('ASSET', 'LIABILITY', 'INCOME', 'EXPENSE', 'OTHER')),

    -- Only the two currencies SPEC.md section 5 defines a minor unit for. See ADR-008 for why
    -- account 1200 is USDC here and not USD.
    constraint account_currency_known
        check (currency in ('KES', 'USDC')),

    constraint account_code_is_four_digits
        check (code ~ '^[0-9]{4}$')
);

comment on table account is
    'The chart of accounts. Seeded by migration, not by application code: the chart is part of '
    'the schema, and an account that can be created at runtime is an account that can be '
    'invented to make a total agree.';

insert into account (code, name, type, currency, is_suspense) values
    ('1000', 'Bank — KES',                     'ASSET',     'KES',  false),
    ('1100', 'Wallet — USDC',                  'ASSET',     'USDC', false),
    ('1200', 'Receivables',                    'ASSET',     'USDC', false),
    ('1900', 'Suspense — unexplained',         'ASSET',     'KES',  true),
    ('2000', 'Payables',                       'LIABILITY', 'KES',  false),
    ('4000', 'Revenue',                        'INCOME',    'KES',  false),
    ('6100', 'Exchange difference realised',   'OTHER',     'KES',  false),
    ('6110', 'Exchange difference unrealised', 'OTHER',     'KES',  false),
    ('6200', 'Conversion spread',              'EXPENSE',   'KES',  false),
    ('6210', 'Conversion fee',                 'EXPENSE',   'KES',  false);

-- 6100, 6110, 6200 and 6210 are four accounts and not one, for the reason given in
-- PROBLEM.md section 5: exchange difference, spread and fee have three different causes and
-- three different fixes. Invariant I4 forbids them from ever posting to each other.
--
-- 1900 is the only suspense account. Suspense is not a plug: an item in 1900 is a question
-- with a date attached (PROBLEM.md section 4), and ChartOfAccountsTest asserts that no
-- account exists whose purpose would be to force agreement.

-- V5: the fixed table of dated mid-market rates.
--
-- SPEC.md section 12: "First implementation reads a fixed table of dated rates from the database
-- so tests and the demo are deterministic. A live source is optional and must not become a
-- dependency of any test."
--
-- DECISION: this table holds MID-MARKET quotes only, and is named for it.
--   SPEC.md section 12 says the rate adapter "returns a mid-market rate". An executed rate is
--   not a quote from a feed - it is the rate a conversion actually got, and it is already
--   recorded where it belongs, on the posting (rate_value, rate_source, rate_timestamp).
--   Giving this table a kind column and seeding only one kind would suggest a second source of
--   truth for executed rates that does not exist.
--   Trade-off: if a future requirement needs executed quotes from a feed, this needs a kind
--   column and a migration. That is a smaller cost than an ambiguous table now.

create table mid_rate (
    rate_date  date           not null,
    value      numeric(20, 8) not null,
    source     text           not null,
    quoted_at  timestamptz    not null,

    constraint mid_rate_pk primary key (rate_date),
    constraint mid_rate_is_positive check (value > 0),
    constraint mid_rate_has_a_source check (length(trim(source)) > 0)
);

comment on table mid_rate is
    'Mid-market KES per one unit of foreign currency, by business date. Nobody trades at the '
    'mid; it exists so the spread can be measured against it (PROBLEM.md section 4).';

comment on column mid_rate.quoted_at is
    'When the quote was made. A rate without a timestamp and a source is not a rate, it is a '
    'rumour (PROBLEM.md section 4).';

-- The four rates from the worked example in PROBLEM.md section 5.
--
-- PROBLEM.md numbers its days relatively ("Day 1", "Day 12") and gives no calendar dates. The
-- mapping to September 2026 below is a fixture choice made so the demo and the tests have
-- concrete dates - it is not a fact from the specification.
--
-- Note what is deliberately ABSENT: there is no row for any other date. SPEC.md section 17
-- scenario 7 is "payroll falls due on a day when the rate feed has no entry", and a table with
-- gaps is what makes that scenario reachable. Do not fill the gaps to make anything convenient.
insert into mid_rate (rate_date, value, source, quoted_at) values
    ('2026-09-01', 129.00000000, 'worked-example', '2026-09-01T06:00:00Z'),
    ('2026-09-12', 131.50000000, 'worked-example', '2026-09-12T06:00:00Z'),
    ('2026-09-20', 132.60000000, 'worked-example', '2026-09-20T06:00:00Z'),
    ('2026-09-30', 130.80000000, 'worked-example', '2026-09-30T06:00:00Z');

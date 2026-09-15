# Creates the three databases this project uses. Safe to run more than once.
#
#   shilingi        the application
#   shilingi_test   the test suite, which runs against a REAL PostgreSQL by design
#                   (SPEC.md section 3: no in-memory substitute - the ledger's constraints
#                   are the point, and they must be the real database's constraints)
#   shilingi_demo   the demonstration screen, which resets itself on every run
#
# Requires a running PostgreSQL. If it is not up, run scripts/start-db.ps1 first - and read
# that file, because it explains why it keeps stopping.

$ErrorActionPreference = 'Stop'

$psql = (Get-Command psql -ErrorAction SilentlyContinue).Source
if (-not $psql) {
    throw "psql is not on PATH. Install PostgreSQL 16 or later and try again."
}

& (Join-Path (Split-Path $psql) 'pg_isready.exe') | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "PostgreSQL is not accepting connections. Run scripts/start-db.ps1 first."
}

foreach ($db in @('shilingi', 'shilingi_test', 'shilingi_demo')) {
    $exists = & $psql -U postgres -tAc "select 1 from pg_database where datname = '$db'"
    if ($exists -eq '1') {
        Write-Output "$db already exists"
    } else {
        & $psql -U postgres -c "create database $db" | Out-Null
        Write-Output "$db created"
    }
}

Write-Output ""
Write-Output "Done. Flyway creates every table on first run - there is no schema to load by hand."

# Test and Operations Manual

**shilingi** — a treasury and bookkeeping layer for a Kenyan business that earns dollars and
spends shillings.

**Audience:** a system administrator deploying and running this on Ubuntu, and a tester validating
it. Both are assumed to have never seen the system before. No prior knowledge of the codebase is
required; some comfort with Linux, `systemd` and `psql` is.

**Version covered:** `0.1.0-SNAPSHOT` · 327 automated tests · 11 database migrations
**Document date:** 2026-09-15

---

# PART 0 — Read this before you do anything

Two facts about this build determine everything else in this manual. Neither is a bug; both are
consequences of what was and was not in scope. If you skip this section you will misconfigure the
system.

## 0.1 ⚠️ The `demo` profile DESTROYS the database on every page load

The demonstration screen calls `Flyway.clean()` — it **drops every table and all data** — and then
rebuilds and replays a scripted story. It does this on *every single HTTP request to `/`*.

This is correct behaviour for a demonstration (a story you cannot replay from the start is not a
demonstration, it is a first run) and catastrophic anywhere else.

**Therefore:**

| Rule | Why |
|---|---|
| **Never** set `SPRING_PROFILES_ACTIVE=demo` on a server holding real data | It will be erased |
| **Never** set `spring.flyway.clean-disabled=false` outside the demo | This is the safety catch |
| Give the demo **its own database** (`shilingi_demo`) and its own service unit | Blast radius of one |

Spring Boot defaults `spring.flyway.clean-disabled` to `true`. The demo profile is the only thing
that turns it off. **Leave that default alone everywhere else.**

## 0.2 ⚠️ Outside the demo profile, the application has no HTTP surface at all

There is exactly one endpoint in the entire codebase: `GET /`, and it exists only under the `demo`
profile. Verified by inspection, not assumed.

That means:

- **There is no operator interface.** You cannot raise an invoice, record a receipt, run the agent
  or reconcile through a UI or an API. Those operations exist as Java services with automated
  tests, and nothing exposes them.
- **There is no `/health` endpoint.** Spring Boot Actuator is not on the classpath. Health checks
  in this manual are therefore process-level and database-level, not HTTP-level.
- **A production-profile instance starts, binds a port, and serves nothing.**

**What this system currently is:** a proven accounting core, a demonstration, and a test suite.
**What it is not yet:** a deployable service that an operator can use to run a business.

Section 1.19 lists exactly what would have to be added to change that. Deploy it now for
demonstration, evaluation and acceptance testing — which is what this manual covers — and read
1.19 before planning anything more.

## 0.3 The regulatory boundary is load-bearing

This software **does not convert currency, hold customer money, or move real value.** The shilling
payout leg is a mock; the dollar leg describes a transfer a licensed counterparty performs.

Do not deploy anything that changes that without legal advice. Kenya's Virtual Asset Service
Providers Act commenced 4 November 2025 and the VASP Regulations 2026 were gazetted 22 July 2026
under Legal Notice 134; converting virtual assets into fiat requires CBK authorisation. See
`REAL_VS_SIMULATED.md` and `docs/adr/ADR-006-the-payout-leg-is-mocked.md`.

---

# PART 1 — Operations on Ubuntu Server

Written against **Ubuntu Server 24.04 LTS**. 22.04 works with the Java note in 1.2.

## 1.1 What you are deploying

| Component | What it is | Runs as | Listens on |
|---|---|---|---|
| **shilingi** | Spring Boot application, one fat JAR | `systemd` service `shilingi` | `127.0.0.1:8080` |
| **sidecar** | Node process, settlement adapter | `systemd` service `shilingi-sidecar` | `127.0.0.1:8787` |
| **PostgreSQL** | the ledger. This is the system of record | `postgresql.service` | `127.0.0.1:5432` |

Nothing needs to listen on a public interface. If you expose the demonstration screen, put nginx
in front of it (1.11).

## 1.2 Server requirements

**Minimum for demonstration / UAT:** 2 vCPU, 4 GB RAM, 20 GB disk.
**Recommended:** 2 vCPU, 8 GB RAM, 50 GB disk — the JVM plus PostgreSQL plus a test run is
comfortable at 8 GB and tight at 4 GB.

| Software | Version | Note |
|---|---|---|
| Java | **21** | The build targets 21. Java 17 will not compile it |
| PostgreSQL | **16 or later** | 24.04 ships 16. Tested against 18.4 |
| Node.js | **20 or later** | Ubuntu 24.04's `nodejs` package is 18 — use NodeSource (1.3) |
| Maven | 3.9+ | Only if building on the server |

## 1.3 Install the prerequisites

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk-headless postgresql postgresql-contrib maven git curl ca-certificates gnupg

# Node 20 from NodeSource. Ubuntu 24.04's own nodejs package is 18, which is too old.
sudo mkdir -p /etc/apt/keyrings
curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
  | sudo gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg
echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" \
  | sudo tee /etc/apt/sources.list.d/nodesource.list
sudo apt update && sudo apt install -y nodejs
```

Verify every one before continuing. **If any version is lower than the table above, stop and fix
it** — the failures later are obscure.

```bash
java -version      # expect 21.x
mvn -v             # expect 3.9+
psql --version     # expect 16+
node -v            # expect v20+
```

## 1.4 Create the service account and directories

The application must not run as `root` and must not run as your login user.

```bash
sudo useradd --system --home /opt/shilingi --shell /usr/sbin/nologin shilingi
sudo mkdir -p /opt/shilingi/{bin,config,sidecar,logs}
sudo chown -R shilingi:shilingi /opt/shilingi
sudo chmod 750 /opt/shilingi/config          # config holds the database password
```

## 1.5 Database setup — two roles, and why it matters

This is the most important section in Part 1. Read the reasoning; the commands alone are not the
point.

### The problem with one role

The ledger's integrity rests on database triggers: postings cannot be updated or deleted,
`AWAITING_RESOLUTION` instructions cannot be retried, a reconciliation item's age cannot be reset.

**A role that owns a table can disable that table's triggers.** `ALTER TABLE ... DISABLE TRIGGER`
needs table ownership, and a superuser can always do it. If the application connects as the table
owner, every one of those guarantees is one statement away from being switched off by the
application itself — or by anything that gets hold of its credentials.

This is recorded in the codebase as a known limitation
(`docs/adr/ADR-010-immutability-by-raising-trigger.md`). **The two-role setup below closes it**,
and is the single biggest hardening step you can take.

### The two roles

| Role | Owns the schema | Used by | Can disable triggers |
|---|---|---|---|
| `shilingi_migrator` | yes | Flyway, at startup, for migrations only | yes — but nothing long-running uses it |
| `shilingi_app` | **no** | the application's connection pool | **no** |

### Create them

Choose two strong, different passwords first.

```bash
sudo -u postgres psql
```

```sql
-- Substitute your own passwords.
CREATE ROLE shilingi_migrator LOGIN PASSWORD 'CHANGE-ME-MIGRATOR';
CREATE ROLE shilingi_app      LOGIN PASSWORD 'CHANGE-ME-APP';

CREATE DATABASE shilingi OWNER shilingi_migrator;

\connect shilingi

-- The migrator owns the schema; the app may only use what is in it.
ALTER SCHEMA public OWNER TO shilingi_migrator;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT  USAGE ON SCHEMA public TO shilingi_app;
GRANT  CONNECT ON DATABASE shilingi TO shilingi_app;
```

### Grant the application exactly what it needs

Run this **after** the first startup has created the tables (1.9), because you cannot grant on
tables that do not exist yet.

```sql
\connect shilingi

-- Read everything.
GRANT SELECT ON ALL TABLES IN SCHEMA public TO shilingi_app;

-- Append-only tables: insert, never change.
GRANT INSERT ON journal_entry, posting, intent TO shilingi_app;

-- Tables with state machines: the state may move along its arrows.
GRANT INSERT, UPDATE ON obligation, receivable, instruction, inbound_callback, recon_item
  TO shilingi_app;

-- Identity columns need their sequences.
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO shilingi_app;

-- Reference data arrives by migration only.
REVOKE INSERT, UPDATE, DELETE ON account, mid_rate FROM shilingi_app;

-- Belt and braces. The triggers refuse these anyway; this means the attempt never reaches them.
REVOKE UPDATE, DELETE ON journal_entry, posting, intent FROM shilingi_app;
REVOKE DELETE ON ALL TABLES IN SCHEMA public FROM shilingi_app;
```

### Verify the hardening actually took

Do not skip this. It is four commands and it is the difference between a hardened deployment and
one you believe is hardened.

```bash
sudo -u postgres psql -d shilingi -c "SET ROLE shilingi_app; UPDATE posting SET amount_minor = 1;"
# EXPECT: ERROR - permission denied for table posting

sudo -u postgres psql -d shilingi -c "SET ROLE shilingi_app; ALTER TABLE posting DISABLE TRIGGER posting_is_append_only;"
# EXPECT: ERROR - must be owner of table posting

sudo -u postgres psql -d shilingi -c "SET ROLE shilingi_app; DELETE FROM intent;"
# EXPECT: ERROR - permission denied for table intent

sudo -u postgres psql -d shilingi -c "SET ROLE shilingi_app; SELECT count(*) FROM posting;"
# EXPECT: a number
```

If any of the first three **succeeds**, the grants have not applied. Fix it before going further.

### Databases you will need

| Database | Purpose | Create it? |
|---|---|---|
| `shilingi` | the real one | yes |
| `shilingi_demo` | the demonstration, **wiped constantly** | only if you run the demo |
| `shilingi_test` | the automated suite, **wiped constantly** | only on a build machine |

```sql
CREATE DATABASE shilingi_demo OWNER shilingi_migrator;
CREATE DATABASE shilingi_test OWNER shilingi_migrator;
```

## 1.6 Build the artifact

Build on a build machine if you have one; building on the server needs Maven and the internet.

```bash
git clone <repository-url> /tmp/shilingi-build
cd /tmp/shilingi-build

mvn -DskipTests clean package        # the JAR
cd sidecar && npm ci --omit=dev      # the sidecar's dependencies
```

`mvn clean package` **with** tests requires a live PostgreSQL and takes about five minutes — see
Part 2. On a server without a test database, `-DskipTests` is correct.

### Deploy the files

```bash
sudo install -o shilingi -g shilingi -m 640 \
  /tmp/shilingi-build/target/shilingi-0.1.0-SNAPSHOT.jar /opt/shilingi/bin/shilingi.jar

sudo cp -r /tmp/shilingi-build/sidecar/{server.mjs,package.json,node_modules} /opt/shilingi/sidecar/
sudo chown -R shilingi:shilingi /opt/shilingi
```

## 1.7 Configuration

Configuration is supplied as environment variables in a file only the service account can read.

```bash
sudo tee /opt/shilingi/config/shilingi.env > /dev/null <<'EOF'
# ---- database -------------------------------------------------------------
# The application pool uses the RESTRICTED role.
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/shilingi
SPRING_DATASOURCE_USERNAME=shilingi_app
SPRING_DATASOURCE_PASSWORD=CHANGE-ME-APP

# Flyway uses the OWNER role, for migrations only. Spring Boot supports separate
# credentials for exactly this purpose.
SPRING_FLYWAY_USER=shilingi_migrator
SPRING_FLYWAY_PASSWORD=CHANGE-ME-MIGRATOR

# THE SAFETY CATCH. Never set this to false outside the demo database.
SPRING_FLYWAY_CLEAN_DISABLED=true

# ---- business configuration -----------------------------------------------
# The zone business dates are decided in. This determines which calendar day -
# and so which MONTH - a posting belongs to. It is a financial decision.
SHILINGI_CLOCK_ZONE=Africa/Nairobi

# Planning horizon and buffer for the agent (SPEC.md O6).
SHILINGI_AGENT_HORIZON_DAYS=35
SHILINGI_AGENT_BUFFER=0.10

# How long an unmatched item is noise before it becomes an exception (SPEC.md O3).
# Counted in business days: weekends excluded, public holidays NOT modelled.
SHILINGI_RECON_STALE_AFTER_BUSINESS_DAYS=2

# ---- settlement sidecar ---------------------------------------------------
SHILINGI_SETTLEMENT_BASE_URL=http://127.0.0.1:8787
SHILINGI_SETTLEMENT_TIMEOUT_SECONDS=10

# ---- server ---------------------------------------------------------------
SERVER_PORT=8080
SERVER_ADDRESS=127.0.0.1
EOF

sudo chown shilingi:shilingi /opt/shilingi/config/shilingi.env
sudo chmod 600 /opt/shilingi/config/shilingi.env
```

### Every setting, and what happens if it is wrong

| Variable | Required | Effect of getting it wrong |
|---|---|---|
| `SHILINGI_CLOCK_ZONE` | **yes — no default** | The application refuses to start. This is deliberate: a guessed zone silently misdates every month-end |
| `SHILINGI_AGENT_HORIZON_DAYS` | **yes — no default** | Refuses to start |
| `SHILINGI_AGENT_BUFFER` | **yes — no default** | Refuses to start |
| `SHILINGI_RECON_STALE_AFTER_BUSINESS_DAYS` | **yes — no default** | Refuses to start |
| `SHILINGI_SETTLEMENT_BASE_URL` | **yes — no default** | Refuses to start |
| `SHILINGI_SETTLEMENT_TIMEOUT_SECONDS` | **yes — no default** | Refuses to start |

**There are no Java-side defaults for any of these, on purpose.** A financial rule nobody chose is
worse than an application that will not start. If the service fails on boot with
`Could not resolve placeholder`, a variable is missing — the message names it.

## 1.8 systemd unit — the sidecar

Start the sidecar first; the application expects it.

```bash
sudo tee /etc/systemd/system/shilingi-sidecar.service > /dev/null <<'EOF'
[Unit]
Description=shilingi settlement sidecar (Node)
Documentation=file:/opt/shilingi/README.md
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=shilingi
Group=shilingi
WorkingDirectory=/opt/shilingi/sidecar
ExecStart=/usr/bin/node /opt/shilingi/sidecar/server.mjs
Restart=on-failure
RestartSec=5

# stub = answers from memory, touches no network.
# live = real testnet transactions; needs SHILINGI_PRIVATE_KEY and an unblocked network.
Environment=SHILINGI_MODE=stub
Environment=SHILINGI_PORT=8787

# Hardening
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/shilingi/logs
RestrictAddressFamilies=AF_INET AF_INET6
MemoryMax=512M

StandardOutput=append:/opt/shilingi/logs/sidecar.log
StandardError=append:/opt/shilingi/logs/sidecar.log

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now shilingi-sidecar
sudo systemctl status shilingi-sidecar --no-pager
```

Confirm it:

```bash
curl -s http://127.0.0.1:8787/health
# EXPECT: {"status":"UP","mode":"stub","chain":"baseSepolia","token":"0x036CbD..."}
```

> **On `SHILINGI_MODE`.** `stub` is the safe default and is what you want for demonstration and
> UAT. `live` signs real transactions on Base Sepolia and needs a funded private key in the
> environment. Do not set it without reading `docs/network.md`.

## 1.9 systemd unit — the application

```bash
sudo tee /etc/systemd/system/shilingi.service > /dev/null <<'EOF'
[Unit]
Description=shilingi treasury and bookkeeping
Documentation=file:/opt/shilingi/README.md
After=network-online.target postgresql.service shilingi-sidecar.service
Wants=network-online.target
Requires=postgresql.service

[Service]
Type=simple
User=shilingi
Group=shilingi
WorkingDirectory=/opt/shilingi
EnvironmentFile=/opt/shilingi/config/shilingi.env

ExecStart=/usr/bin/java -XX:MaxRAMPercentage=70 -jar /opt/shilingi/bin/shilingi.jar

Restart=on-failure
RestartSec=10
# Flyway migrations run at startup; give them room on a cold database.
TimeoutStartSec=180

# Hardening
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/shilingi/logs
MemoryMax=2G

StandardOutput=append:/opt/shilingi/logs/shilingi.log
StandardError=append:/opt/shilingi/logs/shilingi.log

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now shilingi
sudo journalctl -u shilingi -f          # watch the first start
```

**On the first start, Flyway creates all ten tables.** Look for:

```
Successfully applied 11 migrations to schema "public", now at version v11
Started ShilingiApplication in N seconds
```

Now go back and apply the grants in 1.5 — the tables exist — then restart:

```bash
sudo systemctl restart shilingi
```

## 1.10 Log rotation

The units append to plain files, which will grow without bound.

```bash
sudo tee /etc/logrotate.d/shilingi > /dev/null <<'EOF'
/opt/shilingi/logs/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
    su shilingi shilingi
}
EOF

sudo logrotate -d /etc/logrotate.d/shilingi     # dry run; check for errors
```

## 1.11 Exposing the demonstration screen (optional)

Only if somebody needs to see the demo from another machine. **Use the demo database.**

Create a separate unit so the demo can never point at real data:

```bash
sudo sed -e 's/^Description=.*/Description=shilingi DEMONSTRATION (wipes its database)/' \
         /etc/systemd/system/shilingi.service \
  | sudo tee /etc/systemd/system/shilingi-demo.service > /dev/null

sudo tee -a /etc/systemd/system/shilingi-demo.service > /dev/null <<'EOF'
EOF
```

Then edit `/etc/systemd/system/shilingi-demo.service` and add, in `[Service]`:

```ini
Environment=SPRING_PROFILES_ACTIVE=demo
Environment=SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/shilingi_demo
Environment=SPRING_DATASOURCE_USERNAME=shilingi_migrator
Environment=SPRING_DATASOURCE_PASSWORD=CHANGE-ME-MIGRATOR
Environment=SPRING_FLYWAY_CLEAN_DISABLED=false
Environment=SERVER_PORT=8090
```

> The demo needs the **migrator** role because it drops and recreates the schema. That is exactly
> why it gets its own database and its own unit. **Check the datasource URL twice.**

nginx in front, with TLS:

```bash
sudo apt install -y nginx certbot python3-certbot-nginx

sudo tee /etc/nginx/sites-available/shilingi > /dev/null <<'EOF'
server {
    listen 80;
    server_name demo.example.com;

    location / {
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 120s;    # the demo replays a whole story per request
    }
}
EOF

sudo ln -s /etc/nginx/sites-available/shilingi /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d demo.example.com
```

Firewall:

```bash
sudo ufw allow OpenSSH
sudo ufw allow 'Nginx Full'
sudo ufw enable
sudo ufw status
```

**Ports 8080, 8090, 8787 and 5432 must never be open to the internet.** They bind to loopback in
this configuration; the firewall is the second line.

## 1.12 Verifying a deployment

Run all of these. Every one should pass before you hand the system over.

| # | Check | Command | Expected |
|---|---|---|---|
| 1 | Services up | `systemctl is-active shilingi shilingi-sidecar postgresql` | three × `active` |
| 2 | Sidecar answers | `curl -s localhost:8787/health` | `"status":"UP"` |
| 3 | Migrations applied | `sudo -u postgres psql -d shilingi -tAc "select max(version) from flyway_schema_history where success"` | `11` |
| 4 | Chart of accounts seeded | `sudo -u postgres psql -d shilingi -tAc "select count(*) from account"` | `10` |
| 5 | Rates seeded | `sudo -u postgres psql -d shilingi -tAc "select count(*) from mid_rate"` | `4` |
| 6 | App role cannot write history | see 1.5 verification | permission denied |
| 7 | No errors on boot | `grep -iE "ERROR\|Exception" /opt/shilingi/logs/shilingi.log` | nothing |
| 8 | Clean disabled | `grep SPRING_FLYWAY_CLEAN_DISABLED /opt/shilingi/config/shilingi.env` | `=true` |

## 1.13 Backups — this is the system of record

The ledger is append-only and immutable. **There is no application-level undo.** A restore from
backup is the only recovery from data loss, so the backup is not optional.

### Nightly dump

```bash
sudo -u postgres mkdir -p /var/backups/shilingi
sudo tee /usr/local/bin/shilingi-backup > /dev/null <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
DEST=/var/backups/shilingi
mkdir -p "$DEST"

pg_dump -Fc -d shilingi -f "$DEST/shilingi-$STAMP.dump"

# Verify the dump is readable. A backup you have not verified is a hope.
pg_restore --list "$DEST/shilingi-$STAMP.dump" > /dev/null

find "$DEST" -name 'shilingi-*.dump' -mtime +30 -delete
echo "shilingi backup ok: $DEST/shilingi-$STAMP.dump"
EOF

sudo chmod 755 /usr/local/bin/shilingi-backup
```

Schedule it:

```bash
sudo tee /etc/systemd/system/shilingi-backup.service > /dev/null <<'EOF'
[Unit]
Description=shilingi nightly database backup
[Service]
Type=oneshot
User=postgres
ExecStart=/usr/local/bin/shilingi-backup
EOF

sudo tee /etc/systemd/system/shilingi-backup.timer > /dev/null <<'EOF'
[Unit]
Description=Run shilingi backup nightly
[Timer]
OnCalendar=*-*-* 01:30:00
Persistent=true
[Install]
WantedBy=timers.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now shilingi-backup.timer
sudo systemctl list-timers shilingi-backup.timer
```

**Copy the dumps off this machine.** A backup on the same disk as the database protects you from
exactly one failure mode, and not the common one.

### Restore drill — do this once, before you need it

```bash
sudo -u postgres createdb shilingi_restore_test
sudo -u postgres pg_restore -d shilingi_restore_test /var/backups/shilingi/shilingi-XXXX.dump

# Prove the restored copy is internally consistent: every entry must still balance.
sudo -u postgres psql -d shilingi_restore_test -c "
  SELECT entry_id, sum(functional_amount_minor) AS out_by
    FROM posting GROUP BY entry_id HAVING sum(functional_amount_minor) <> 0;"
# EXPECT: 0 rows. Any row is a corrupted restore - do not use it.

sudo -u postgres dropdb shilingi_restore_test
```

## 1.14 Upgrading

```bash
# 1. Back up first, always.
sudo /usr/local/bin/shilingi-backup

# 2. Stop the application. Leave PostgreSQL running.
sudo systemctl stop shilingi

# 3. Keep the old JAR so you can go back.
sudo cp /opt/shilingi/bin/shilingi.jar /opt/shilingi/bin/shilingi.jar.prev

# 4. Install the new one.
sudo install -o shilingi -g shilingi -m 640 ./shilingi-0.1.0-SNAPSHOT.jar /opt/shilingi/bin/shilingi.jar

# 5. Start. Flyway applies any new migrations automatically, in order.
sudo systemctl start shilingi
sudo journalctl -u shilingi -n 100 --no-pager | grep -i migrat
```

**Rolling back a migration is not supported.** Flyway migrations here are forward-only, and
several create triggers and constraints. If a new version misbehaves, restore the backup taken in
step 1 and reinstall `shilingi.jar.prev`. This is why step 1 is not optional.

## 1.15 What to monitor

There is no metrics endpoint, so monitoring is SQL. These four queries are the ones that matter;
run them from a monitoring agent or a cron job that mails on non-empty output.

```sql
-- 1. UNEXPLAINED MONEY. Should normally be zero. A non-zero balance is a question
--    nobody has answered, and it is on the balance sheet.
SELECT coalesce(sum(functional_amount_minor), 0) / 100.0 AS suspense_kes
  FROM posting WHERE account_code = '1900';

-- 2. RECONCILIATION EXCEPTIONS. Anything open for more than a couple of days.
SELECT id, kind, first_seen, detail
  FROM recon_item
 WHERE state = 'OPEN' AND first_seen < current_date - INTERVAL '2 days'
 ORDER BY first_seen;

-- 3. PAYMENTS WITH AN UNKNOWN OUTCOME. These are NEVER retried automatically,
--    by design. Somebody has to look.
SELECT id, external_ref, amount_minor / 1000000.0 AS amount, created_at
  FROM instruction WHERE state IN ('AWAITING_RESOLUTION', 'MANUAL_REVIEW')
 ORDER BY created_at;

-- 4. OVERDUE OBLIGATIONS. Past their date with nothing set aside. SPEC.md calls
--    this a bug in the agent's planning and says it must be visible as one.
SELECT id, name, due_date, amount_minor / 100.0 AS amount_kes
  FROM obligation WHERE status = 'SCHEDULED' AND due_date < current_date
 ORDER BY due_date;
```

### Alert thresholds

| Query | Warn | Alarm |
|---|---|---|
| 1 — suspense balance | any non-zero value | unchanged for 5 business days |
| 2 — recon exceptions | any row | any row older than 10 business days |
| 3 — unknown outcomes | any row | any row older than 1 business day |
| 4 — overdue obligations | any row | any row, immediately, if payroll |

### The integrity check — run it weekly

This is the strongest single check available. It asks whether the books still agree with
themselves.

```sql
-- Every journal entry must sum to zero in shillings. Always. No exceptions.
SELECT e.id, e.business_date, e.description,
       sum(p.functional_amount_minor) AS out_by_cents
  FROM journal_entry e JOIN posting p ON p.entry_id = e.id
 GROUP BY e.id, e.business_date, e.description
HAVING sum(p.functional_amount_minor) <> 0;
```

**Any row returned is a serious incident.** It means something wrote to the database outside the
application — the application cannot produce an unbalanced entry, and the database refuses one at
commit. Preserve the evidence, take a backup, and investigate before changing anything.

## 1.16 Routine operations

| When | Do |
|---|---|
| **Daily** | Check services are active. Run monitoring queries 1–4. Confirm last night's backup exists and `pg_restore --list` reads it |
| **Weekly** | Run the integrity check. Review open reconciliation items with whoever can answer them. Check disk |
| **Monthly** | Restore drill into a scratch database (1.13). Review suspense — an item older than a month is a statement about the business, not an annoyance. Check log rotation |
| **Quarterly** | Rotate database passwords. Re-run the hardening verification in 1.5. Apply OS updates and reboot in a window |

## 1.17 Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| Service fails instantly, log says `Could not resolve placeholder 'shilingi.…'` | A required variable is missing from `shilingi.env` | The message names the key. Add it and restart. There are deliberately no defaults |
| `Connection refused` to `localhost:5432` | PostgreSQL down | `systemctl status postgresql`. On some installs it is not enabled at boot: `systemctl enable postgresql` |
| `FATAL: password authentication failed for user "shilingi_app"` | Password mismatch, or `pg_hba.conf` requires a different method | Check `shilingi.env`; check `pg_hba.conf` has `host all all 127.0.0.1/32 scram-sha-256` |
| `permission denied for table posting` **in normal operation** | Grants too tight, or an INSERT path expects UPDATE | This is the hardening working. Confirm which statement failed before loosening anything |
| `ERROR: Table posting is append-only` | Something tried to UPDATE or DELETE a posting | **Working as designed.** A correction is a new reversing entry. Find what issued it |
| `Instruction N is AWAITING_RESOLUTION and must never be retried` | Something tried to re-send a payment with an unknown outcome | **Working as designed** — this is what stops double payment. Re-query the rail, or escalate |
| Flyway: `Migration checksum mismatch` | A migration file was edited after being applied | Never edit an applied migration. Restore from backup, or add a new forward migration |
| Demo page is blank or 500s | Sidecar down, or the demo pointed at the wrong database | `curl localhost:8787/health`; check the demo unit's datasource URL |
| **Data disappeared** | Almost certainly the demo profile pointed at a real database | Restore from backup immediately. Then re-read §0.1 |
| Sidecar logs `fetch failed` / certificate errors in `live` mode | The network blocks testnet RPC endpoints | See `docs/network.md` §4. **Do not disable certificate verification** |

## 1.18 Security checklist

- [ ] Application runs as `shilingi`, never `root`
- [ ] Application connects as `shilingi_app`, which **does not own** the tables (verified, 1.5)
- [ ] `shilingi.env` is `0600`, owned by `shilingi`
- [ ] `SPRING_FLYWAY_CLEAN_DISABLED=true` in every non-demo environment
- [ ] The demo, if running, has its **own database and its own unit**
- [ ] 8080 / 8090 / 8787 / 5432 bound to loopback; firewall enabled
- [ ] TLS terminated at nginx if anything is exposed
- [ ] Backups verified and copied off the machine
- [ ] `SHILINGI_PRIVATE_KEY` is **not** set unless you intend live settlement — and if it is, it is a
      testnet key, and the file holding it is `0600`
- [ ] Certificate verification has not been disabled anywhere

## 1.19 What is missing before this is a production service

Stated so it is a planning input rather than a discovery.

1. **An operator API or UI.** Nothing exposes invoice entry, receipt matching, agent runs or
   reconciliation. Without it the system cannot be *used*, only demonstrated.
2. **A scheduler.** The agent runs when something calls it; nothing calls it. A daily decision
   cycle needs a trigger.
3. **A health endpoint.** Add `spring-boot-starter-actuator` for `/actuator/health` and proper
   liveness/readiness probes.
4. **Authentication and audit of operators.** There are no user accounts. A manual resolution
   records a *name typed by whoever ran it*, unverified.
5. **Payout posting.** Payouts are not posted to the ledger because the chart has no expense
   account — see `ADR-025`. Until that is resolved, the books record treasury movements and not
   the full set of books.

---

# PART 2 — Functional and Acceptance Testing

## 2.1 The three levels available to you

| Level | What it is | Who runs it | Evidence |
|---|---|---|---|
| **L1 — Automated** | 327 tests against a real PostgreSQL | build machine, every change | Surefire reports |
| **L2 — Demonstration** | The thirty-day story, end to end | tester, in a browser | Screenshots + this manual |
| **L3 — Database verification** | SQL against the books | tester with `psql` | Query output |

**There is no L4.** As noted in §0.2 there is no operator interface, so a tester cannot key in a
transaction and watch it flow. Acceptance testing is therefore: *does the automated suite prove
the rules, does the demonstration show them end to end, and do the books say what they should?*

Be explicit about that when reporting UAT results. It is a real limitation of scope, not a gap in
testing effort.

## 2.2 Running the automated suite

**Requires a live PostgreSQL.** This is deliberate — the ledger's guarantees *are* database
constraints, and testing them against an in-memory substitute would test a different system.

```bash
sudo -u postgres createdb shilingi_test          # once
cd /path/to/shilingi
mvn test
```

Expect **about five minutes**. The suite drops and rebuilds the schema between tests so no test
can see another's data, which is most of the runtime.

### Reading the result

```
[INFO] Tests run: 327, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Anything other than `Failures: 0, Errors: 0` is a fail.** There are no known-flaky tests and none
are skipped. Detailed output is in `target/surefire-reports/`.

Run one group:

```bash
mvn test -Dtest=LedgerServiceTest         # the ledger's refusals
mvn test -Dtest=FxEngineTest              # the worked example
mvn test -Dtest=ReplayTest                # reproducibility
```

### If the suite fails to start

| Message | Cause |
|---|---|
| `Connection to localhost:5432 refused` | PostgreSQL not running |
| `database "shilingi_test" does not exist` | Create it |
| `clean-disabled` error | `src/test/resources/application-test.yml` must have `clean-disabled: false`. Tests reset their schema |

## 2.3 What the automated suite proves, mapped to the requirements

Use this as the functional traceability matrix. Every invariant in `SPEC.md` §9 has tests named
after it.

| ID | Requirement | Proven by | Notes |
|---|---|---|---|
| **I1** | No foreign posting without rate, source and timestamp | `JournalStoreTest`, `LedgerServiceTest` | Enforced in **three** places: the type, the ledger, a database constraint |
| **I2** | Entries balance | `LedgerServiceTest`, `EntryMustBalanceConstraintTest` | Raw SQL bypassing the application is still refused, at commit |
| **I3** | No account or code path exists to force agreement | `ChartOfAccountsTest` | Asserts the chart contains no "adjustment"-style account and no runtime account creation |
| **I4** | Difference, spread and fee never combine | `FxEngineTest`, `ChartOfAccountsTest` | Four separate accounts |
| **I5** | Realised and unrealised never share an entry | `LedgerServiceTest`, `BookkeeperTest` | Refused at post time |
| **I6** | Unexplained receipts reach suspense with a date | `ReconcilerTest` | Posted to 1900, ageing |
| **I7** | `AWAITING_RESOLUTION` is never retried | `InstructionStateMachineTest` | **The retry does not compile.** Also blocked at the database |
| **I8** | No obligation left unfunded because the agent held dollars | `TreasuryAgentTest` | Including a well-argued proposal being refused |
| **I9** | No instruction without a committed intent | `IntentLogTest` | A `NOT NULL` foreign key |
| **I10** | Replay reproduces the closing position | `ReplayTest` | Including with the rate feed deleted |
| **F5** | No silent double payment | `ConcurrentCreationTest` | 16 threads racing one reference; exactly one wins |
| §5 | Round once, `HALF_UP` | `ConversionRoundingTest` | The proof is a case where rounding twice differs |
| §5 | Residue goes to the last part | `MoneySplitTest` | Property-tested across amounts and part counts |
| §12 | Payout mock does all six hostile behaviours | `PayoutRailTest` | Plus a seventh, wrong-amount |
| §12 | Java ↔ sidecar contract | `SidecarContractTest` | Starts the **real** Node process |

## 2.4 Setting up a UAT environment

**Use a dedicated machine or VM.** Never run UAT against a database holding real data — the demo
wipes its database on every page load.

```bash
# 1. Follow Part 1 through §1.9.
# 2. Create the demo database and unit (§1.11).
# 3. Confirm the demo database is NOT the production one:
grep DATASOURCE /etc/systemd/system/shilingi-demo.service
# MUST read shilingi_demo. Check it twice.

sudo systemctl start shilingi-demo
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8090/     # expect 200
```

Give the tester read-only database access:

```sql
CREATE ROLE uat_reader LOGIN PASSWORD 'CHANGE-ME';
GRANT CONNECT ON DATABASE shilingi_demo TO uat_reader;
\connect shilingi_demo
GRANT USAGE ON SCHEMA public TO uat_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO uat_reader;
```

## 2.5 UAT scripts

Each script: **preconditions → steps → expected result → pass/fail**. Record the actual result even
when it passes; "as expected" is not a record.

> **Note on figures.** Every amount is checkable against `PROBLEM.md` §5. The books store **minor
> units** (cents for KES, micro-dollars for USDC), so KES 1,290,000.00 is `129000000` in the
> database. The screen shows the readable form.

---

### UAT-01 · The demonstration runs unattended

**Proves:** the system plays a thirty-day story with no human intervention (`SPEC.md` §15, day 12).

| | |
|---|---|
| **Pre** | `shilingi-demo` active; browser can reach the demo URL |
| **1** | Open the demo URL |
| **2** | Do not click anything. There is nothing to click |

**Expected**

- The page loads fully within ~30 seconds.
- Sections appear in order: *What happened* · the refusal · two panels · four questions ·
  *Questions nobody has answered yet* · *What this screen is not showing you*.
- The *What happened* table has rows dated 2026-09-01, 09-12, 09-20 (×3), 09-25 and 09-30.

**Pass if** the page renders complete with no error and no manual step.

---

### UAT-02 · Exchange difference is not revenue

**Proves:** I4, and the error `PROBLEM.md` §5 calls "the single most common error in a small set of
books".

| | |
|---|---|
| **Pre** | UAT-01 passed |
| **1** | Find the *This system* panel |
| **2** | Read `4000 Revenue` |
| **3** | Read `6100 Exchange difference, realised` |

**Expected**

| Account | Value |
|---|---|
| 4000 Revenue | **KES 1,290,000.00** — the invoice, and only the invoice |
| 6100 Exchange difference, realised | **KES 31,600.00** |
| 6110 Exchange difference, unrealised | **KES 2,800.00** |

**Pass if** revenue is exactly the invoice amount and the dollar's movement sits in 6100/6110.
**Fail if** revenue includes any part of the 31,600 — that is the failure the system exists to
prevent.

---

### UAT-03 · Spread and fee are separate, and neither is hidden

**Proves:** F2, I4.

| | |
|---|---|
| **1** | In *This system*, read `6200 Conversion spread` and `6210 Conversion fee` |
| **2** | In the four-question table, read *"What did the provider charge?"* |

**Expected**

- `6200 Conversion spread` = **KES 2,400.00** — 0.40 per dollar over 6,000 dollars, a cost that
  appears on no statement anywhere because it was baked into the rate.
- `6210 Conversion fee` = **KES 7,138.80** — 0.9% of 793,200.
- The answer names both **separately**, never as one total.

**Pass if** the two figures are distinct and separately labelled.

> **Tester's note.** `PROBLEM.md` prints the fee as 7,139. In cents, 0.9% of 79,320,000 is exactly
> 713,880 — that is **KES 7,138.80**, with no rounding at all. 7,139 is only reachable by rounding
> to whole shillings, which the specification's own scale of 2 forbids. **The system is right and
> the document has a display rounding.** Do not raise this as a defect; it is recorded in
> `ADR-016`.

---

### UAT-04 · The system refuses a well-argued instruction

**Proves:** I8, and `SPEC.md` §11's "strongest single moment".

| | |
|---|---|
| **1** | Find the highlighted *The 20th, before anything was converted* block |
| **2** | Read the proposal's reasoning |
| **3** | Read the refusal |

**Expected**

- The reasoning is specific and plausible — a shilling weakening over three sessions, a mid moving
  131.50 → 132.60, a case for waiting a week.
- It is **refused** by invariant I8, and the refusal names the amount unfunded.
- The text states the refusal is in the intent log and **no instruction was created**.

**Pass if** a reasonable-sounding instruction is refused on the numbers alone.

**Why this matters:** the check is handed an action and a set of facts and *cannot see the
reasoning at all*. A well-argued proposal and a badly-argued one that amount to the same action get
the same answer. That is the only way the guarantee can hold in practice.

---

### UAT-05 · The naive comparison is fair

**Proves:** the demonstration is not beating a straw man (`SPEC.md` §19, `ADR-023`).

| | |
|---|---|
| **1** | Read the *A spreadsheet* panel |
| **2** | Verify its arithmetic by hand |
| **3** | Read the four-question table's right-hand column |

**Expected**

- One row, **KES 793,200.00**, dated 2026-09-20, against Client A.
- The total equals that row. **The sum is correct** — the panel says so explicitly.
- Three of four questions answer **"cannot say"**.

**Pass if** you can confirm the naive arithmetic is right, and the difference is what it *cannot
answer* rather than what it got wrong.

**Fail if** the naive panel appears rigged — shown as erroring, styled as a failure, or given worse
inputs. It is given the same inputs, the same clock and the same conversion outcomes.

---

### UAT-06 · Unexplained money is visible and ageing

**Proves:** F3, I6.

| | |
|---|---|
| **1** | Find *Questions nobody has answered yet* |
| **2** | Read the row |
| **3** | Read the suspense balance below the table |

**Expected**

- One item, first seen **2026-09-25**, kind *unmatched inbound*.
- Detail says the rail reported KES 12,500 against a reference **no instruction carries**.
- Age shows **3 business days — exception** (25th to 30th, excluding the weekend).
- Suspense stands at **KES 12,500.00**.

**Pass if** the item is present, aged in business days, and flagged as an exception.

---

### UAT-07 · The books balance — verified independently

**Proves:** I2, by SQL rather than by trusting the screen.

```sql
-- Connect to shilingi_demo AFTER loading the demo page at least once.
SELECT e.id, e.business_date, e.description,
       sum(p.functional_amount_minor) AS out_by_cents
  FROM journal_entry e JOIN posting p ON p.entry_id = e.id
 GROUP BY e.id, e.business_date, e.description
HAVING sum(p.functional_amount_minor) <> 0;
```

**Expected: zero rows.** Every entry balances in shillings.

```sql
-- And the account balances behind the screen.
SELECT account_code, sum(functional_amount_minor) / 100.0 AS kes
  FROM posting GROUP BY account_code ORDER BY account_code;
```

Verified against a live run of the demonstration, not calculated by hand:

| Account | Expected KES |
|---|---|
| 1000 Bank | 865,100.00 |
| 1100 Wallet (carrying value) | 523,200.00 |
| 1200 Receivables | 0.00 — raised and fully settled |
| 1900 Suspense | 12,500.00 |
| 2000 Payables | −91,538.80 |
| 4000 Revenue | −1,290,000.00 |
| 6100 Exchange difference realised | −31,600.00 |
| 6110 Exchange difference unrealised | 2,800.00 |
| 6200 Conversion spread | 2,400.00 |
| 6210 Conversion fee | 7,138.80 |

> **Signs.** Credits are negative internally. Revenue of 1,290,000 is stored as `-129000000` cents.
> The screen flips the sign for readability; the database does not.

---

### UAT-08 · History cannot be altered

**Proves:** immutability is enforced by the database, not by convention.

```sql
-- As a user with write access to the demo database.
UPDATE posting SET amount_minor = 1 WHERE id = (SELECT min(id) FROM posting);
```

**Expected:** `ERROR: Table posting is append-only: UPDATE is not permitted. A correction is a new
entry that reverses and re-posts, never an edit.`

```sql
DELETE FROM posting WHERE id = (SELECT min(id) FROM posting);
TRUNCATE TABLE posting CASCADE;
UPDATE intent SET reasoning = 'I always thought that was a bad idea' WHERE id = 1;
```

**Expected:** all four refused.

**Pass if** every statement is rejected. **Fail if any succeeds** — that is a critical finding;
capture it and stop.

---

### UAT-09 · A decision is recorded before it is acted on

**Proves:** I9, F6.

```sql
SELECT id, created_at, trigger, left(reasoning, 80) AS reasoning
  FROM intent ORDER BY id;

SELECT i.id, i.external_ref, i.state, i.intent_id, i.attempts
  FROM instruction i ORDER BY i.id;
```

**Expected**

- Two or more intents. One carries the refused proposal — check its `proposed_action` contains
  `"permitted":false` and `"refusedBy":"I8"`.
- Exactly **one** instruction, and its `intent_id` is **not** the refused intent's id.
- Every instruction's `intent_id` refers to an intent that exists.

```sql
-- Prove the ordering is structural, not a convention.
INSERT INTO instruction (intent_id, external_ref, created_at, type, amount_minor,
                         currency, state, attempts)
VALUES (999999, 'uat/orphan', now(), 'CONVERSION', 1, 'USDC', 'CREATED', 0);
```

**Expected:** foreign-key violation. An instruction cannot exist without a committed intent.

---

### UAT-10 · A payment with an unknown outcome is never retried

**Proves:** I7, F5.

```sql
-- Put an instruction into the unknown state, as a timeout would.
UPDATE instruction SET state = 'AWAITING_RESOLUTION' WHERE id = (SELECT min(id) FROM instruction);

-- Now attempt what a careless system would do.
UPDATE instruction SET attempts = attempts + 1
 WHERE id = (SELECT min(id) FROM instruction);
```

**Expected:** `ERROR: Instruction N is AWAITING_RESOLUTION and must never be retried. Its outcome
is unknown, and unknown is not failure...`

```sql
UPDATE instruction SET state = 'SUBMITTED' WHERE id = (SELECT min(id) FROM instruction);
```

**Expected:** `ERROR: ... SPEC.md section 7 draws no such arrow.`

**Pass if** both are refused. In Java the retry does not even compile; this proves the database
refuses it too, for everything that is not Java.

---

### UAT-11 · Replay reproduces the position from records alone

**Proves:** I10 — `PROBLEM.md` calls this "the whole point".

```sql
-- 1. Note the closing position.
SELECT account_code, sum(functional_amount_minor) FROM posting GROUP BY account_code;

-- 2. Destroy the rate feed. Every rate the system has ever published.
DELETE FROM mid_rate;

-- 3. Re-read the position.
SELECT account_code, sum(functional_amount_minor) FROM posting GROUP BY account_code;
```

**Expected: identical.** Every figure is derived from rates stored on the postings themselves, not
looked up. If replay reached outside the journal it would break here — and would have been proving
nothing all along, because it would be describing September using today's world.

> Restore the demo by reloading the page; it rebuilds its own database.

---

### UAT-12 · The sidecar behaves like a real rail

**Proves:** §12 — accepted is not settled, unknown is not failed.

```bash
curl -s localhost:8787/health

curl -s -X POST localhost:8787/payments -H 'Content-Type: application/json' \
  --data '{"externalRef":"uat/1","to":"0x1111111111111111111111111111111111111111","amountMinor":"6000000000"}'
```

**Expected:** HTTP 202, `"status":"ACCEPTED"` — *not* settled — with a `txHash` and
`"simulated":true`.

```bash
# Send the SAME reference again.
curl -s -X POST localhost:8787/payments -H 'Content-Type: application/json' \
  --data '{"externalRef":"uat/1","to":"0x1111111111111111111111111111111111111111","amountMinor":"6000000000"}'
```

**Expected:** the **same** `txHash`, plus `"idempotent":true`. Nothing was sent twice.

```bash
curl -s localhost:8787/payments/never-sent
```

**Expected:** HTTP 404, `"status":"UNKNOWN"` — not "FAILED". Unknown and failed are different
things, and conflating them is how money leaves twice.

---

## 2.6 Negative and adversarial tests

For a tester who wants to try to break it. **All of these should be refused.**

| # | Attempt | SQL / action | Expected |
|---|---|---|---|
| N1 | Post an unbalanced entry | Insert two postings summing to 1 cent | Refused at commit — `does not balance in shillings` |
| N2 | Post a foreign amount with no rate | Insert a USDC posting with null `rate_value` | Check constraint violation |
| N3 | Invent an account | `INSERT INTO account VALUES ('9999','Adjustments',...)` | Permission denied for `shilingi_app`; and no code path creates accounts |
| N4 | Post dollars to the shilling bank account | Posting with `account_code='1000'`, `currency='USDC'` | Composite foreign key violation |
| N5 | Backdate an entry into the future | Entry with `business_date` = tomorrow | Ledger refuses — `FutureBusinessDate` |
| N6 | Reset a reconciliation item's age | `UPDATE recon_item SET first_seen = current_date` | Refused — *"stay young for ever"* |
| N7 | Reopen a resolved item | `UPDATE recon_item SET state='OPEN'` on a resolved row | Refused — *"a new question is a new item"* |
| N8 | Pay an obligation that was never funded | `UPDATE obligation SET status='PAID'` on a `SCHEDULED` row | Refused — no such arrow |
| N9 | Change what an instruction instructs | `UPDATE instruction SET amount_minor = 1` | Refused — *only the state and attempt count may change* |
| N10 | Edit a callback's reported amount | `UPDATE inbound_callback SET amount_minor = 1` | Refused — *what a callback said cannot be changed* |

Record the exact error message for each. The messages are part of the deliverable: they say what
to do instead, not merely "no".

## 2.7 Known limitations a tester will meet

Raising these as defects wastes everyone's time. They are scope decisions, each with a written
rationale.

| Observation | Status | Reference |
|---|---|---|
| No screen to enter an invoice or run the agent | **By scope** — one demonstration screen only | §0.2 |
| Payroll is funded but never paid in the books | **A known finding** — the chart has no expense account | `ADR-025` |
| Fee shows 7,138.80 where `PROBLEM.md` says 7,139 | **The system is right** — display rounding in the document | `ADR-016` |
| Transaction hashes are simulated | **By design here** — this network blocks testnet RPC | `docs/network.md` §4, `REAL_VS_SIMULATED.md` |
| Demo data resets on every page load | **By design** — a demo must replay from the start | §0.1 |
| Manual resolutions record an unverified name | **By scope** — no user accounts | `ADR-021` |
| Public holidays not excluded from ageing | **Known** — weekends are; holidays cannot be written from memory | `ADR-027` |

## 2.8 Acceptance sign-off

| # | Test | Result | Tester | Date | Notes |
|---|---|---|---|---|---|
| L1 | Automated suite: 327 pass, 0 fail | ☐ Pass ☐ Fail | | | |
| UAT-01 | Demonstration runs unattended | ☐ Pass ☐ Fail | | | |
| UAT-02 | Exchange difference is not revenue | ☐ Pass ☐ Fail | | | |
| UAT-03 | Spread and fee kept separate | ☐ Pass ☐ Fail | | | |
| UAT-04 | A well-argued instruction is refused | ☐ Pass ☐ Fail | | | |
| UAT-05 | The naive comparison is fair | ☐ Pass ☐ Fail | | | |
| UAT-06 | Unexplained money visible and ageing | ☐ Pass ☐ Fail | | | |
| UAT-07 | Books balance (SQL) | ☐ Pass ☐ Fail | | | |
| UAT-08 | History cannot be altered | ☐ Pass ☐ Fail | | | |
| UAT-09 | Decision recorded before action | ☐ Pass ☐ Fail | | | |
| UAT-10 | Unknown outcome never retried | ☐ Pass ☐ Fail | | | |
| UAT-11 | Replay reproduces from records alone | ☐ Pass ☐ Fail | | | |
| UAT-12 | Sidecar behaves like a real rail | ☐ Pass ☐ Fail | | | |
| N1–N10 | Negative tests all refused | ☐ Pass ☐ Fail | | | |

**Deployment sign-off**

| # | Check | Result | Admin | Date |
|---|---|---|---|---|
| D1 | §1.12 verification, all 8 checks | ☐ Pass ☐ Fail | | |
| D2 | §1.5 hardening verified — app role cannot alter history | ☐ Pass ☐ Fail | | |
| D3 | Backup runs, and `pg_restore --list` reads it | ☐ Pass ☐ Fail | | |
| D4 | Restore drill into a scratch database | ☐ Pass ☐ Fail | | |
| D5 | §1.18 security checklist complete | ☐ Pass ☐ Fail | | |
| D6 | Monitoring queries scheduled and alerting | ☐ Pass ☐ Fail | | |

---

**Accepted by** ............................... **Date** ....................

**Reservations / conditions:**

---

## Appendix A — Every configuration key

| Key | Env var | Default | Effect |
|---|---|---|---|
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | — | Database |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | — | Use the restricted role |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | — | |
| `spring.flyway.user` | `SPRING_FLYWAY_USER` | datasource user | Use the migrator role |
| `spring.flyway.password` | `SPRING_FLYWAY_PASSWORD` | datasource password | |
| `spring.flyway.clean-disabled` | `SPRING_FLYWAY_CLEAN_DISABLED` | **`true`** | ⚠️ `false` permits data destruction |
| `spring.profiles.active` | `SPRING_PROFILES_ACTIVE` | none | ⚠️ `demo` wipes the database per request |
| `shilingi.clock.zone` | `SHILINGI_CLOCK_ZONE` | **none** | Which day, and month, a posting falls in |
| `shilingi.agent.horizon-days` | `SHILINGI_AGENT_HORIZON_DAYS` | **none** | How far ahead the agent plans |
| `shilingi.agent.buffer` | `SHILINGI_AGENT_BUFFER` | **none** | Safety margin, e.g. `0.10` |
| `shilingi.recon.stale-after-business-days` | `SHILINGI_RECON_STALE_AFTER_BUSINESS_DAYS` | **none** | Noise-to-exception threshold |
| `shilingi.settlement.base-url` | `SHILINGI_SETTLEMENT_BASE_URL` | **none** | Sidecar address |
| `shilingi.settlement.timeout-seconds` | `SHILINGI_SETTLEMENT_TIMEOUT_SECONDS` | **none** | A timeout means UNKNOWN, never failed |
| `server.port` | `SERVER_PORT` | `8080` | |
| — | `SHILINGI_MODE` (sidecar) | `stub` | `live` signs real transactions |
| — | `SHILINGI_PORT` (sidecar) | `8787` | |
| — | `SHILINGI_PRIVATE_KEY` (sidecar) | none | Required for `live`. Testnet keys only |

## Appendix B — The tables

| Table | Holds | Mutability |
|---|---|---|
| `account` | chart of accounts, 10 rows | migration only |
| `mid_rate` | dated mid-market rates | migration only |
| `journal_entry` | balanced sets of postings | **append-only** |
| `posting` | individual lines | **append-only** |
| `intent` | decisions, written before acting | **append-only** |
| `instruction` | things to be done | state + attempts only |
| `inbound_callback` | what rails reported | write-once disposition |
| `receivable` | what clients owe | status only |
| `obligation` | what we owe | status only |
| `recon_item` | unmatched things, ageing | resolution only; `first_seen` frozen |
| `flyway_schema_history` | migration record | Flyway only |

## Appendix C — Where to read more

| Document | For |
|---|---|
| `PROBLEM.md` | **Read first.** Why any of this exists |
| `SPEC.md` | What was to be built and the rules it must not break |
| `README.md` | Developer quick start |
| `REAL_VS_SIMULATED.md` | What actually runs against something real |
| `docs/network.md` | Network facts, and why testnet settlement is blocked here |
| `docs/adr/README.md` | 27 decision records, including two findings awaiting an answer |

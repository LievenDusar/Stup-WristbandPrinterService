# Docker multi-environment + per-printer production design

Date: 2026-06-03
Status: Approved (pending spec review)

## Goal

Restructure the container setup into clear local and production topologies, built on a
shared Java 21 base image, with production supporting **one print-service container per
printer** against the Symfony site's Postgres instance.

## Requirements

1. **Local print service** container (dev loop).
2. **Local database** container (Postgres).
3. **Production print service** container(s):
   - Connects to the production database whose URL is configured in the prod compose/env.
   - Uses a **dedicated `wristbands` database** running on the **same Postgres instance**
     the Symfony site uses (not a shared schema; full isolation, no table-name clashes).
   - The Flyway migration scripts must exist and create the tables in that production database.
   - Must support **starting an additional production container bound to an additional
     printer — one production service per printer**, all sharing the same database.
4. A **shared Java 21 "tech stack" base image** used by the other containers, so the
   whole service runs on a fresh host that has only Docker installed — no Java or other
   runtime dependencies on the host.

## Confirmed decisions

| Topic | Decision |
|---|---|
| Prod DB engine | PostgreSQL (existing Flyway/JPA/driver stack unchanged) |
| Java 21 container | Shared **base image** that local & prod app images extend |
| Table coexistence | **Dedicated database** on the same Postgres instance as Symfony |
| Per-printer model | **Compose: one service block per printer** (DRY via YAML anchor) |
| Local exposure | **HTTP 8080**, `local` Spring profile |
| Prod topology | Per-printer containers on **one prod host**; **DB is remote** |
| Migration execution | **Embedded Flyway per instance** (Flyway lock serializes starts) |

## Architecture

### Images (two, not four)

Local and production run the **same application image**; only the Spring profile and
environment differ.

| Image | Purpose | Source |
|---|---|---|
| `wristband-base:21` | Shared runtime base: Temurin 21 JRE + `curl` + keystore tooling (`keytool` ships with the JRE). This is the "Java 21 tech stack." | `docker/base/Dockerfile` |
| `wristband-printer` | Application image. Multi-stage: build stage (`maven:3.9-eclipse-temurin-21`) → runtime stage `FROM wristband-base:21`, carrying `app.jar` + `docker-entrypoint.sh`. | `Dockerfile` |

- The JRE lives inside `wristband-base`, so any host with only Docker can run the stack —
  no host Java. The multi-stage build also needs no host Java (build happens in the Maven image).
- A helper (`Makefile` targets or `build.sh`) builds `wristband-base:21` first, then the app
  image, because the app Dockerfile's runtime stage does `FROM wristband-base:21`.

### Local stack — `docker-compose.yml`

- `postgres` — local DB container, `wristbands` database, published `127.0.0.1:5433:5432`,
  `pgdata` volume, healthcheck (unchanged from today).
- `wristband-printer`:
  - `SPRING_PROFILES_ACTIVE=local`
  - HTTP on `8080` (no TLS locally)
  - `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/wristbands`
  - `depends_on: postgres (service_healthy)`
  - Flyway runs on startup, applying V1–V4 to the local DB.

This replaces the current single-file behavior (which ran the prod profile + HTTPS + a
co-located Postgres). The local file is now unambiguously the dev stack.

### Production stack — `docker-compose.prod.yml`

- **No Postgres container.** Connects to the remote dedicated `wristbands` database on the
  Symfony Postgres instance via `SPRING_DATASOURCE_URL` (from `.env.prod`).
- A YAML anchor `x-printer-base: &printer-base` holds the common config: build/image,
  `SPRING_PROFILES_ACTIVE=prod`, shared DB + API/admin/SSL credentials, resource limits,
  HTTPS healthcheck, `restart: unless-stopped`.
- **One service block per printer.** Each block merges the anchor and overrides only:
  - `PRINTER_HOST` (the printer's LAN IP)
  - published HTTPS port (`8443`, `8444`, `8445`, …)
  - `SSL_CERT_HOSTNAME`
  - its own named `certs` volume (e.g. `certs-printer1`)
- **Adding a printer = copy a ~6-line block and change three values.**

Example shape:

```yaml
x-printer-base: &printer-base
  image: wristband-printer
  build: .
  environment: &printer-env
    SPRING_PROFILES_ACTIVE: prod
    SECURITY_API_KEY: ${API_KEY}
    ADMIN_PASSWORD: ${ADMIN_PASSWORD}
    SPRING_DATASOURCE_URL: ${SPRING_DATASOURCE_URL}
    SPRING_DATASOURCE_USERNAME: ${DB_USERNAME}
    SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
    SSL_KEYSTORE_PATH: /certs/keystore.p12
    SSL_KEYSTORE_PASSWORD: ${SSL_KEYSTORE_PASSWORD}
  restart: unless-stopped
  # healthcheck + deploy limits ...

services:
  printer-1:
    <<: *printer-base
    environment:
      <<: *printer-env
      PRINTER_HOST: ${PRINTER1_HOST}
      SSL_CERT_HOSTNAME: ${PRINTER1_HOSTNAME}
    ports: ["8443:8443"]
    volumes: ["certs-printer1:/certs"]

  printer-2:
    <<: *printer-base
    environment:
      <<: *printer-env
      PRINTER_HOST: ${PRINTER2_HOST}
      SSL_CERT_HOSTNAME: ${PRINTER2_HOSTNAME}
    ports: ["8444:8443"]
    volumes: ["certs-printer2:/certs"]

volumes:
  certs-printer1:
  certs-printer2:
```

### Production database & migration

- The dedicated `wristbands` database lives on the Symfony Postgres instance (remote from
  the prod Docker host).
- **Prerequisite (assumption):** a DBA must first create the empty `wristbands` database and
  a role/login on the prod Postgres instance. Flyway creates objects *inside* a database but
  cannot create the database itself.
- The existing Flyway migrations (`V1__create_print_jobs.sql`, `V2__add_deleted_flag.sql`,
  `V3__create_wristband_templates.sql`, `V4__create_template_assets.sql`) are the migration
  scripts; they apply unchanged to the prod DB.
- **Embedded Flyway per instance:** every per-printer container runs Flyway on startup.
  Flyway acquires a lock on its schema-history table, so concurrent or staggered container
  starts serialize safely; containers started later find the schema current and no-op.
- `ddl-auto` stays `validate` (from base `application.yml`).

### Data flow per printer

- Each per-printer container exposes its own HTTPS API on a distinct port and has its own
  in-memory print queue. A job submitted to a container's API is queued in that container
  and printed on that container's printer.
- Job history from all printers is persisted to the shared `print_jobs` table.
- **Optional (not in scope unless requested):** a `V5` migration adding a `source` /
  `printer_id` column for per-printer traceability in the shared table.

## Configuration / files

| File | Change |
|---|---|
| `docker/base/Dockerfile` | New — `wristband-base:21` (JRE 21 + curl). |
| `Dockerfile` | Runtime stage changed to `FROM wristband-base:21`; build stage unchanged. |
| `docker-compose.yml` | Repurposed as the **local** stack (local profile, HTTP 8080, local Postgres). |
| `docker-compose.prod.yml` | New — production per-printer stack, no Postgres, remote DB. |
| `build.sh` / `Makefile` | New — build base image then app image in order. |
| `.env.example` | Add prod keys: `SPRING_DATASOURCE_URL`, `DB_USERNAME`, `DB_PASSWORD`, `PRINTER1_HOST`, `PRINTER1_HOSTNAME`, … |
| `.env` / `.env.prod` | Real secrets (gitignored). |
| `README.md` | Document local vs prod usage, base image, adding a printer, DB prerequisite. |

No application Java code changes are required (unless the optional `V5` column is wanted).

## Testing / verification

1. `docker compose config` and `docker compose -f docker-compose.prod.yml config` validate.
2. **Base image:** `wristband-base:21` builds; contains a working `java -version` (21) and `curl`.
3. **Fresh-host guarantee:** build + run succeed using only Docker (no host JDK/JRE) — inherent
   to the multi-stage build + JRE-in-base, verified by building from clean.
4. **Local:** `docker compose up` → app healthy on `http://localhost:8080/actuator/health`;
   Flyway applied V1–V4 in the local DB; basic smoke (auth + a print/preview call).
5. **Production (staging dry-run):** point `SPRING_DATASOURCE_URL` at a test DB; bring up
   `printer-1` + `printer-2`; verify both start, connect to the same DB, migrations applied
   once (second instance no-ops), distinct ports respond on HTTPS, each reaches its `PRINTER_HOST`.
6. Confirm existing unit/integration tests still pass (`./mvnw test`).

## Risks / assumptions

- **Assumption:** prod `wristbands` database + role are created by a DBA before first deploy.
- **Assumption:** the prod Docker host can reach the remote Postgres instance (network/firewall);
  consider requiring TLS on the JDBC connection if it crosses untrusted networks.
- **Risk:** divergent `SSL_CERT_HOSTNAME` per printer means multiple self-signed certs to trust
  in the Symfony client; document the trust step per host.
- **Risk:** repurposing `docker-compose.yml` from prod-like to local changes current behavior;
  callers relying on the old file must switch to `docker-compose.prod.yml`.

# Docker Multi-Environment + Per-Printer Production Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the container setup into a shared Java 21 base image, a local stack (HTTP 8080 + local Postgres), and a production stack that runs one print-service container per printer against a remote dedicated Postgres database.

**Architecture:** Two images — `wristband-base:21` (Temurin 21 JRE + curl) and `wristband-printer` (the app, `FROM wristband-base:21`). The same app image runs everywhere, differentiated by Spring profile + env. Local uses `docker-compose.yml` (HTTP 8080, local Postgres); production uses `docker-compose.prod.yml` (no Postgres, remote DB, one service block per printer via YAML anchors). Embedded Flyway applies migrations per instance.

**Tech Stack:** Docker, Docker Compose, Spring Boot 3.4 (profiles local/prod), PostgreSQL 16, Flyway, Eclipse Temurin 21.

Spec: `docs/superpowers/specs/2026-06-03-docker-multi-env-printer-design.md`

---

## File Structure

| File | Responsibility |
|---|---|
| `docker/base/Dockerfile` | **Create** — shared `wristband-base:21` runtime base (JRE 21 + curl). |
| `Dockerfile` | **Modify** — runtime stage extends `wristband-base:21`; expose 8080 + 8443. |
| `docker-entrypoint.sh` | **Modify** — generate the TLS keystore only for the `prod` profile; skip it (and the password requirement) otherwise so local HTTP works. |
| `docker-compose.yml` | **Rewrite** — local stack: local Postgres + app on HTTP 8080, `local` profile. |
| `docker-compose.prod.yml` | **Create** — production: no Postgres, remote DB, one service block per printer. |
| `build.sh` | **Create** — build the base image (prerequisite for any compose build). |
| `.env.example` | **Modify** — add local vs production sections incl. per-printer keys. |
| `.gitignore` | **Modify** — ignore `.env.prod`. |
| `README.md` | **Modify** — document base image, local vs prod usage, adding a printer, DB prerequisite. |

No Java application/source changes are required.

---

### Task 1: Shared Java 21 base image

**Files:**
- Create: `docker/base/Dockerfile`

- [ ] **Step 1: Create the base Dockerfile**

Create `docker/base/Dockerfile`:

```dockerfile
# Shared "Java 21 tech stack" base image.
# The application image (../../Dockerfile) extends this via `FROM wristband-base:21`.
# Keeping the JRE + tooling here means any host with only Docker can run the service —
# no Java on the host. `keytool` ships with the JRE, so only curl is added.
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
```

- [ ] **Step 2: Build the base image**

Run: `docker build -t wristband-base:21 -f docker/base/Dockerfile .`
Expected: build succeeds, ends with `naming to docker.io/library/wristband-base:21` (or `Successfully tagged wristband-base:21`).

- [ ] **Step 3: Verify Java 21 + curl are present**

Run: `docker run --rm wristband-base:21 sh -c "java -version && which curl && which keytool"`
Expected: `openjdk version "21..."` on stderr, `/usr/bin/curl`, and a keytool path (e.g. `/opt/java/openjdk/bin/keytool`).

- [ ] **Step 4: Commit**

```bash
git add docker/base/Dockerfile
git commit -m "feat(docker): add shared wristband-base:21 image (JRE21 + curl)"
```

---

### Task 2: App Dockerfile extends the base image

**Files:**
- Modify: `Dockerfile`

- [ ] **Step 1: Rewrite the runtime stage to use the base image**

Replace the entire contents of `Dockerfile` with:

```dockerfile
# Stage 1: build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: runtime (extends the shared Java 21 base image)
FROM wristband-base:21
WORKDIR /app
COPY --from=build /app/target/wristband-printer-service-*.jar app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh
# 8080 = local HTTP, 8443 = production HTTPS
EXPOSE 8080 8443
ENTRYPOINT ["/app/docker-entrypoint.sh"]
```

(The `RUN apk add --no-cache curl` line is removed — curl now lives in the base image.)

- [ ] **Step 2: Build the app image (base must exist from Task 1)**

Run: `docker build -t wristband-printer .`
Expected: build succeeds; the `FROM wristband-base:21` line resolves to the local image (no registry pull).

- [ ] **Step 3: Commit**

```bash
git add Dockerfile
git commit -m "refactor(docker): app image extends wristband-base:21, expose 8080+8443"
```

---

### Task 3: Entrypoint generates keystore only for prod profile

**Files:**
- Modify: `docker-entrypoint.sh`

**Why:** The current entrypoint always requires `SSL_KEYSTORE_PASSWORD` and generates a keystore. The local stack runs plain HTTP (profile `local`), so it must skip keystore generation. Gate the TLS block on the active profile.

- [ ] **Step 1: Rewrite docker-entrypoint.sh**

Replace the entire contents of `docker-entrypoint.sh` with:

```sh
#!/bin/sh
set -e

# Only the prod profile serves HTTPS and therefore needs a keystore.
# Local (and any non-prod) profile runs plain HTTP, so skip cert generation.
if [ "$SPRING_PROFILES_ACTIVE" = "prod" ]; then
  SSL_KEYSTORE_PATH="${SSL_KEYSTORE_PATH:-/certs/keystore.p12}"
  SSL_CERT_HOSTNAME="${SSL_CERT_HOSTNAME:-localhost}"
  CERT_DIR="$(dirname "$SSL_KEYSTORE_PATH")"

  if [ -z "$SSL_KEYSTORE_PASSWORD" ]; then
    echo "ERROR: SSL_KEYSTORE_PASSWORD is not set (required for prod profile)" >&2
    exit 1
  fi

  mkdir -p "$CERT_DIR"

  if [ ! -f "$SSL_KEYSTORE_PATH" ]; then
    echo "No keystore at $SSL_KEYSTORE_PATH - generating self-signed certificate for '$SSL_CERT_HOSTNAME'..."
    if echo "$SSL_CERT_HOSTNAME" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'; then
      SAN="ip:$SSL_CERT_HOSTNAME"
    else
      SAN="dns:$SSL_CERT_HOSTNAME"
    fi
    keytool -genkeypair \
      -alias wristband \
      -keyalg RSA -keysize 2048 \
      -validity 3650 \
      -storetype PKCS12 \
      -keystore "$SSL_KEYSTORE_PATH" \
      -storepass "$SSL_KEYSTORE_PASSWORD" \
      -dname "CN=$SSL_CERT_HOSTNAME, O=STUP, C=BE" \
      -ext "san=$SAN"
    keytool -exportcert -rfc \
      -alias wristband \
      -keystore "$SSL_KEYSTORE_PATH" \
      -storepass "$SSL_KEYSTORE_PASSWORD" \
      -file "$CERT_DIR/server.crt"
    echo "Keystore generated; public certificate exported to $CERT_DIR/server.crt"
  else
    echo "Reusing existing keystore at $SSL_KEYSTORE_PATH"
  fi
else
  echo "Profile '${SPRING_PROFILES_ACTIVE:-default}' - HTTP mode, skipping TLS keystore generation"
fi

exec java -jar /app/app.jar
```

- [ ] **Step 2: Verify the script is valid shell**

Run: `sh -n docker-entrypoint.sh && echo OK`
Expected: `OK` (no syntax errors).

- [ ] **Step 3: Commit**

```bash
git add docker-entrypoint.sh
git commit -m "feat(docker): generate TLS keystore only for prod profile"
```

---

### Task 4: Local stack — repurpose docker-compose.yml

**Files:**
- Rewrite: `docker-compose.yml`

- [ ] **Step 1: Rewrite docker-compose.yml as the local stack**

Replace the entire contents of `docker-compose.yml` with:

```yaml
# Local development stack: Postgres + the print service on HTTP 8080 (profile "local").
# Production lives in docker-compose.prod.yml.
# Prerequisite: build the base image first  ->  ./build.sh
services:
  postgres:
    image: postgres:16-alpine
    environment:
      - POSTGRES_DB=wristbands
      - POSTGRES_USER=wristbands
      - POSTGRES_PASSWORD=wristbands
    ports:
      # Loopback only, on 5433 to avoid clashing with a local Postgres on 5432.
      - "127.0.0.1:5433:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U wristbands -d wristbands"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  wristband-printer:
    image: wristband-printer
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=local
      # Override application-local.yml's localhost URL to reach the db service.
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/wristbands
      - SPRING_DATASOURCE_USERNAME=wristbands
      - SPRING_DATASOURCE_PASSWORD=wristbands
    depends_on:
      postgres:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-fs", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 20s
    restart: unless-stopped

volumes:
  pgdata:
```

- [ ] **Step 2: Validate the compose file**

Run: `docker compose config -q && echo VALID`
Expected: `VALID` (no errors).

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(compose): repurpose docker-compose.yml as local stack (HTTP 8080, local profile)"
```

---

### Task 5: Production stack — docker-compose.prod.yml

**Files:**
- Create: `docker-compose.prod.yml`

- [ ] **Step 1: Create docker-compose.prod.yml**

Create `docker-compose.prod.yml`:

```yaml
# Production stack: one print-service container PER PRINTER, all sharing the remote
# dedicated `wristbands` database on the Symfony Postgres instance.
# No Postgres container here — the DB is remote (SPRING_DATASOURCE_URL in .env.prod).
# Prerequisites:
#   1. ./build.sh                       (build the base image)
#   2. cp .env.example .env.prod        (fill in real values)
#   3. DBA has created an empty `wristbands` database + role on the prod Postgres instance.
# Run:  docker compose -f docker-compose.prod.yml --env-file .env.prod up --build -d
#
# To ADD A PRINTER: copy the `printer-2` template block below, give it a new service
# name, a unique published port, its PRINTERn_HOST / PRINTERn_HOSTNAME env, and its own
# certs volume (add the volume name under `volumes:` too).

x-printer-base: &printer-base
  image: wristband-printer
  build: .
  restart: unless-stopped
  deploy:
    resources:
      limits:
        memory: 512m
        cpus: "1.0"
  healthcheck:
    # Internal container port is always 8443; only the published host port differs.
    test: ["CMD", "curl", "-fsk", "https://localhost:8443/actuator/health"]
    interval: 30s
    timeout: 5s
    retries: 3
    start_period: 20s

x-printer-env: &printer-env
  SPRING_PROFILES_ACTIVE: prod
  SECURITY_API_KEY: ${API_KEY}
  ADMIN_PASSWORD: ${ADMIN_PASSWORD}
  SPRING_DATASOURCE_URL: ${SPRING_DATASOURCE_URL}
  SPRING_DATASOURCE_USERNAME: ${DB_USERNAME}
  SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
  SSL_KEYSTORE_PATH: /certs/keystore.p12
  SSL_KEYSTORE_PASSWORD: ${SSL_KEYSTORE_PASSWORD}

services:
  printer-1:
    <<: *printer-base
    environment:
      <<: *printer-env
      PRINTER_HOST: ${PRINTER1_HOST}
      SSL_CERT_HOSTNAME: ${PRINTER1_HOSTNAME:-localhost}
    ports:
      - "8443:8443"
    volumes:
      - certs-printer1:/certs

  # --- Printer template: copy this block to add a printer ---
  # printer-2:
  #   <<: *printer-base
  #   environment:
  #     <<: *printer-env
  #     PRINTER_HOST: ${PRINTER2_HOST}
  #     SSL_CERT_HOSTNAME: ${PRINTER2_HOSTNAME:-localhost}
  #   ports:
  #     - "8444:8443"
  #   volumes:
  #     - certs-printer2:/certs

volumes:
  certs-printer1:
  # certs-printer2:
```

- [ ] **Step 2: Validate the prod compose file**

Run: `API_KEY=x ADMIN_PASSWORD=x SPRING_DATASOURCE_URL=x DB_USERNAME=x DB_PASSWORD=x SSL_KEYSTORE_PASSWORD=x PRINTER1_HOST=x docker compose -f docker-compose.prod.yml config -q && echo VALID`
Expected: `VALID` (no errors; the anchors merge and `printer-1` resolves).

- [ ] **Step 3: Commit**

```bash
git add docker-compose.prod.yml
git commit -m "feat(compose): add production per-printer stack (remote DB, one container per printer)"
```

---

### Task 6: Build helper script

**Files:**
- Create: `build.sh`

- [ ] **Step 1: Create build.sh**

Create `build.sh`:

```sh
#!/usr/bin/env sh
# Build the shared Java 21 base image that the app image extends.
# Run this once, and again after changing docker/base/Dockerfile, BEFORE any
# `docker compose build` or `docker compose up --build`.
set -eu
docker build -t wristband-base:21 -f docker/base/Dockerfile .
echo "Built wristband-base:21"
```

- [ ] **Step 2: Make it executable and verify**

Run: `chmod +x build.sh && sh -n build.sh && echo OK`
Expected: `OK`.

- [ ] **Step 3: Commit**

```bash
git add build.sh
git commit -m "feat(docker): add build.sh to build the base image"
```

---

### Task 7: Environment template and gitignore

**Files:**
- Modify: `.env.example`
- Modify: `.gitignore`

- [ ] **Step 1: Rewrite .env.example with local + prod sections**

Replace the entire contents of `.env.example` with:

```sh
# Copy to .env (local) or .env.prod (production) and fill in real values.

# --- Local stack (docker-compose.yml) ---
# Local DB credentials are fixed in docker-compose.yml (wristbands/wristbands);
# nothing is required here for local. The local Spring profile sets its own
# api-key / admin password (see application-local.yml).

# --- Production stack (docker-compose.prod.yml) ---
API_KEY=your-strong-api-key-here
ADMIN_PASSWORD=your-strong-admin-password
SSL_KEYSTORE_PASSWORD=your-strong-keystore-password

# Remote dedicated `wristbands` database on the Symfony Postgres instance.
# The DBA must create this empty database + role beforehand.
SPRING_DATASOURCE_URL=jdbc:postgresql://db.example.internal:5432/wristbands
DB_USERNAME=wristbands
DB_PASSWORD=your-strong-db-password

# One PRINTERn_HOST / PRINTERn_HOSTNAME pair per printer container.
PRINTER1_HOST=192.168.1.50
PRINTER1_HOSTNAME=wristband-printer1.example.local
# PRINTER2_HOST=192.168.1.51
# PRINTER2_HOSTNAME=wristband-printer2.example.local
```

- [ ] **Step 2: Add .env.prod to .gitignore**

In `.gitignore`, find the block:

```
# Environment / secrets — never commit actual credentials
.env
.env.local
```

Replace it with:

```
# Environment / secrets — never commit actual credentials
.env
.env.local
.env.prod
```

- [ ] **Step 3: Verify .env.prod is ignored**

Run: `git check-ignore .env.prod && echo IGNORED`
Expected: `.env.prod` then `IGNORED`.

- [ ] **Step 4: Commit**

```bash
git add .env.example .gitignore
git commit -m "docs(env): split local/prod env template, gitignore .env.prod"
```

---

### Task 8: Update README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Locate the Docker section**

Run: `grep -n -i "docker" README.md | head -30`
Expected: line numbers of the existing Docker / compose documentation to replace.

- [ ] **Step 2: Replace the Docker usage documentation**

Update the Docker section of `README.md` so it documents the new layout. Ensure it contains these exact instructions (adapt surrounding prose/headings to match the existing README style):

````markdown
## Containers

The service ships as two images:

- **`wristband-base:21`** — the shared "Java 21 tech stack" base (Temurin 21 JRE + curl).
- **`wristband-printer`** — the application, built `FROM wristband-base:21`.

Because the JRE lives inside the base image, any host with **only Docker installed** can
build and run the service — no Java on the host.

Build the base image first (run once, and after changing `docker/base/Dockerfile`):

```bash
./build.sh
```

### Local (development)

Postgres + the service on plain HTTP, `local` profile:

```bash
./build.sh
docker compose up --build
# http://localhost:8080/actuator/health
```

### Production (one container per printer)

The production stack has **no database container** — it connects to a dedicated
`wristbands` database on the Symfony site's Postgres instance (remote).

Prerequisites:
1. A DBA creates an empty `wristbands` database + role on the prod Postgres instance
   (Flyway creates the tables, not the database).
2. `cp .env.example .env.prod` and fill in the values.

Run:

```bash
./build.sh
docker compose -f docker-compose.prod.yml --env-file .env.prod up --build -d
# https://<host>:8443/actuator/health   (self-signed cert)
```

Each printer gets its own container, port, and self-signed certificate; all containers
share the same database. Flyway runs in each container on startup and is lock-serialized,
so staggered starts are safe.

### Adding a printer

In `docker-compose.prod.yml`, copy the commented `printer-2` template block, then:
- give it a unique service name and published host port (`8444`, `8445`, …);
- set its `PRINTERn_HOST` / `PRINTERn_HOSTNAME` (add them to `.env.prod`);
- add its own `certs-printerN` volume under `volumes:`.
````

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: document base image, local/prod stacks, and adding a printer"
```

---

### Task 9: End-to-end verification

**Files:** none (verification only)

- [ ] **Step 1: Clean build from base (fresh-host guarantee)**

Run:
```bash
docker image rm wristband-printer wristband-base:21 2>/dev/null; \
./build.sh && docker compose build
```
Expected: base builds, then app builds successfully — proving the chain works with no host Java.

- [ ] **Step 2: Local stack comes up healthy**

Run:
```bash
docker compose up -d && \
sleep 25 && \
docker compose ps
```
Expected: both `postgres` and `wristband-printer` show `running`/`healthy`.

- [ ] **Step 3: Local health endpoint responds over HTTP**

Run: `curl -fs http://localhost:8080/actuator/health`
Expected: `{"status":"UP"}` (or a JSON body containing `"status":"UP"`).

- [ ] **Step 4: Flyway migrations applied in the local DB**

Run: `docker compose exec postgres psql -U wristbands -d wristbands -c "select version from flyway_schema_history order by installed_rank;"`
Expected: rows for versions `1`, `2`, `3`, `4`.

- [ ] **Step 5: Tear down local**

Run: `docker compose down`
Expected: containers removed (the `pgdata` volume persists).

- [ ] **Step 6: Production compose validates with two printers**

Temporarily uncomment the `printer-2` block and its `certs-printer2:` volume in
`docker-compose.prod.yml`, then run:
```bash
API_KEY=x ADMIN_PASSWORD=x SPRING_DATASOURCE_URL=x DB_USERNAME=x DB_PASSWORD=x \
SSL_KEYSTORE_PASSWORD=x PRINTER1_HOST=x PRINTER1_HOSTNAME=h1 \
PRINTER2_HOST=y PRINTER2_HOSTNAME=h2 \
docker compose -f docker-compose.prod.yml config | grep -E "printer-1:|printer-2:|8443|8444"
```
Expected: both `printer-1:` and `printer-2:` services render, with published ports `8443` and `8444`. **Re-comment the `printer-2` block afterwards.**

- [ ] **Step 7: Java unit/integration tests still pass**

Run: `./mvnw test`
Expected: `BUILD SUCCESS`, all tests green.

- [ ] **Step 8: Final commit (if Step 6 left any whitespace changes)**

```bash
git add -A && git diff --cached --quiet || git commit -m "chore: verification adjustments"
```

---

## Notes

- **No code change** is needed for per-printer job attribution; queues are in-memory per
  container, so jobs are partitioned by which container's API receives them. If per-printer
  traceability in the shared `print_jobs` table is wanted later, add a `V5` migration with a
  `source`/`printer_id` column — out of scope here.
- The optional one-shot migrator was considered and rejected (embedded Flyway + lock is
  sufficient for staggered/concurrent container starts).

# STUP Wristband Printer Service

Java 21 / Spring Boot service that generates ZPL wristband labels for Zebra printers.
Used by the STUP Symfony event application to print staff wristbands at events, with a
built-in admin UI, a visual template designer, and support for multiple printers.

## Contents

- [Getting started](#getting-started)
- [Architecture](#architecture)
- [Running locally](#running-locally) → [docs/running-locally.md](docs/running-locally.md)
- [Production deployment](#production-deployment) → [docs/production-deployment.md](docs/production-deployment.md)
  - [Adding a printer](#adding-a-printer)
- [Configuration](#configuration) → [docs/configuration.md](docs/configuration.md)
- [API endpoints](#api-endpoints) → [docs/api.md](docs/api.md)
- [Job management UI](#job-management-ui)
- [Labelary preview](#labelary-preview)
- [Job persistence](#job-persistence)
- [Swagger UI](#swagger-ui)
- [Metrics](#metrics)
- [Running tests](#running-tests)

---

## Getting started

### Prerequisites

- **Docker** with Compose v2 — the only hard requirement; the app and its PostgreSQL run in containers.
- **Git** — to clone the repository.
- *(Optional)* **Java 21 + Maven 3.9+** — only for the native developer workflow and running the tests.

### Local quick start (virtual printers)

```bash
# 1. Clone
git clone <repository-url> Stup-WristbandPrinterService
cd Stup-WristbandPrinterService

# 2. Add the STUP logo used by the default layout
cp /path/to/stup-logo.png src/main/resources/images/stup-logo.png

# 3. Build the shared base image (once, and after changing docker/base/Dockerfile)
./build.sh

# 4. Start a full local stack with two virtual printers
docker compose -f docker-compose.local-cluster.yml up --build -d
```

Open **http://localhost:8080/jobs.html** and sign in with `admin` / `local-admin`. Send a test
print with the curl examples in [docs/running-locally.md](docs/running-locally.md#via-docker).

### Production quick start (real printers)

Needs a remote, empty `stup_wristband_db` database (DB + role created by a DBA) and one reachable Zebra per
printer.

```bash
# 1. Clone the repo and add the logo (steps 1–2 above), then build the base image
./build.sh

# 2. Configure the environment — DB URL/credentials, API key, hostname, printer IPs
cp .env.example .env.prod
$EDITOR .env.prod      # every variable is documented inside

# 3. Launch management (HTTPS) + one worker per printer
docker compose -f docker-compose.prod.yml --env-file .env.prod up --build -d
```

Open **https://&lt;MANAGEMENT_HOSTNAME&gt;:8443/jobs.html** (self-signed cert). For adding more
printers, Symfony certificate trust, and the full topology, see
[Production deployment](#production-deployment) and [Architecture](#architecture).

---

## Architecture

The service runs in **two roles**, selected by Spring profile (the same image, a different
`SPRING_PROFILES_ACTIVE`):

- **management** — active whenever the `worker` profile is *not* set (i.e. `local` / `prod`).
  The only role with a UI, admin login, database, job history and SSE. It holds the **printer
  registry**, renders the ZPL, and forwards each job to the right worker. One instance.
- **worker** (`worker`) — a thin, database-free, UI-free service; **one per physical printer**.
  It exposes an internal `POST /api/internal/print`, writes the received ZPL to its printer
  (`PRINTER_HOST:9100`), and reports success/failure. Reached only by management over the private
  network.

The **registry** is DB-backed and built from **worker self-registration** (no static config): each
worker announces itself to management on startup (`id`, `display-name`, `base-url`) and heartbeats.
`id` is what a caller sends as `printerId`; `base-url` is the worker's in-network address. A print
request with no `printerId` goes to the default printer (the operator-set one, else the
earliest-registered online printer); an unknown `printerId` is rejected with **400**, and a cluster
with no registered printers returns **503**. Each printer has its own queue and worker thread, so
printers print in parallel.

| | Local dev | Production |
|---|---|---|
| Management | `docker-compose.yml` (HTTP 8080) | `docker-compose.prod.yml` (HTTPS 8443) |
| Workers + printers | `docker-compose.local-cluster.yml` (virtual) | `docker-compose.prod.yml` (real Zebras) |

### Images

The service ships as two Docker images, so any host with **only Docker** (no host Java) can build
and run it:

- **`wristband-base:21`** — the shared "Java 21 tech stack" base (Temurin 21 JRE + curl).
- **`wristband-printer`** — the application, built `FROM wristband-base:21`.

Build the base image once (and after changing `docker/base/Dockerfile`):

```bash
./build.sh
```

---

## Running locally

Run the stack from **IntelliJ** (JDK + Maven, fastest inner loop) or entirely with **Docker** (no
host Java; a virtual cluster with fake printers that mirrors production). The [Local quick
start](#local-quick-start-virtual-printers) above gets you printing fast.

Full step-by-step instructions — IntelliJ run configs, the local PostgreSQL, the virtual cluster,
and troubleshooting — are in **[docs/running-locally.md](docs/running-locally.md)**.

### Connecting to the database (IntelliJ)

Both local Docker stacks (`docker-compose.yml` and `docker-compose.local-cluster.yml`) publish
PostgreSQL on the host at **`127.0.0.1:5433`** (loopback only). Open the **Database** tool window
(**View ▸ Tool Windows ▸ Database**), then **+ ▸ Data Source ▸ PostgreSQL** and fill in:

| Field    | Value               |
| -------- | ------------------- |
| Host     | `localhost`         |
| Port     | `5433`              |
| Database | `stup_wristband_db` |
| User     | `wristbands`        |
| Password | `wristbands`        |

(Equivalent URL: `jdbc:postgresql://localhost:5433/stup_wristband_db`.) Click **Test Connection** —
IntelliJ downloads the PostgreSQL driver on first use — then **OK**. The `print_jobs` table and
Flyway's `flyway_schema_history` live in the `public` schema.

> Running management **natively** from IntelliJ (the `local` profile, not Docker) instead expects
> Postgres on **`localhost:5432`** per `application-local.yml` — point the data source at `5432` in
> that case.

### Rebuilding after code changes (Docker)

The app is baked into the image at build time — there is **no live reload**. After editing any code
(Java, `application*.yml`, Flyway migrations, or the static `*.html` / `js` / `css` files), rebuild
the image and recreate the containers in one step:

```bash
docker compose -f docker-compose.local-cluster.yml up --build -d
```

This rebuilds `wristband-printer` and recreates management + both workers from it; Postgres and the
fake printers keep running and your job history survives. To also reset the database, run
`docker compose -f docker-compose.local-cluster.yml down -v` first. Only re-run `./build.sh` when
you change `docker/base/Dockerfile`. See
**[Rebuilding after code changes](docs/running-locally.md#rebuilding-after-code-changes)** for
single-service rebuilds, no-cache builds, and tailing logs.

---

## Production deployment

`docker-compose.prod.yml` runs **one management service** (public, HTTPS on 8443, holds the TLS cert,
DB connection and printer registry) plus **one worker per Zebra printer** (internal HTTP only). The
database is a remote `stup_wristband_db` Postgres; Flyway migrates it on management's first start. The
[Production quick start](#production-quick-start-real-printers) above is the condensed path.

The full guide — `.env.prod` secrets, declaring workers, registering printers, launch & verify,
adding printers later, and HTTPS / Symfony certificate trust — is in
**[docs/production-deployment.md](docs/production-deployment.md)**.

### Adding a printer

Each printer is **one worker service + one registry entry**, edited together. To add `printer-2`:

1. **`.env.prod`** — declare its IP:

   ```dotenv
   PRINTER2_HOST=10.0.0.52
   ```

2. **`docker-compose.prod.yml`** — uncomment the `printer-worker-2` service template and give it
   its self-registration identity (`WORKER_ID` = the `printerId` Symfony sends, `WORKER_DISPLAY_NAME`,
   `WORKER_BASE_URL` = the worker's service URL, `WORKER_MANAGEMENT_BASE_URL`). There is **no
   management registry to edit** — the worker registers itself on startup:

   ```yaml
   environment:
     WORKER_ID: printer-2
     WORKER_DISPLAY_NAME: Inkom
     WORKER_BASE_URL: http://printer-worker-2:8080
     WORKER_MANAGEMENT_BASE_URL: https://management:8443   # see prod TLS prerequisite in docs
   ```

3. **Redeploy** — pick the command for your situation:

   - **Image already built — just add the new worker** (leaves running services untouched):

     ```bash
     docker compose -f docker-compose.prod.yml --env-file .env.prod up -d printer-worker-2
     ```

   - **App code or image changed — rebuild and recreate** management + workers:

     ```bash
     docker compose -f docker-compose.prod.yml --env-file .env.prod up --build -d
     ```

The printer then shows up in `GET /api/wristbands/printers`, the jobs-page filter, and the reprint
picker. Full snippets (worker block, `depends_on`, the local-cluster equivalent) are in
**[docs/production-deployment.md](docs/production-deployment.md#adding-a-printer-later)**. To add a
**virtual** printer to the local cluster, see
**[docs/running-locally.md](docs/running-locally.md#adding-a-third-virtual-printer)**.

---

## Configuration

All settings — printer & routing, security, queue, and the full **Wristband layout** reference
(dimensions, margins, fonts, barcode) with an annotated diagram — are documented in
**[docs/configuration.md](docs/configuration.md)**.

Most-used knobs: `printer.host`/`printer.port` (per worker), `security.api-key`, `queue.max-depth`,
and the `wristband.*` geometry. Profiles: `local` / `prod` (management) and `worker` (printer node).

---

## API endpoints

The complete REST reference — wristband printing, job streaming (SSE), and the template endpoints —
plus `curl` examples is in **[docs/api.md](docs/api.md)**.

All endpoints (except `/api/wristbands/jobs/stream` and `/jobs.html`) require an `X-API-Key` header.
The `/api/wristband-templates` and `/api/wristband-assets` endpoints back the [template designer](docs/template-designer.md).

---

## Job management UI

Open **http://localhost:8080/jobs.html** (you'll be redirected to `/login.html` if not signed in).

- Sign in with the admin credential (`security.admin.username` / `security.admin.password`). The
  session is kept in an HttpOnly cookie — no key is stored in the browser.
- The table updates in real time via Server-Sent Events.
- Each row shows the person's **name**, event, **printer**, status, a truncated job ID (with copy),
  relative timestamps, and per-job actions. Status chips give live counts and filter; columns sort.
- With more than one printer configured, a second chip row **filters by printer**.
- The search box shows a clear (×) button once it holds text.
- Clicking a row opens a **slide-in detail drawer** with the full wristband data (name, club,
  barcode, printer, timestamps) and a **Show preview** button that renders the wristband via Labelary
  on demand.
- **Cancel** stops a PENDING job. **Reprint** re-queues a DONE/FAILED job — with several printers it
  first asks which printer to use (automatic when there is only one).
- **Clear completed** confirms, then **soft-deletes** DONE/FAILED/CANCELLED jobs — hidden from the
  queue but kept in the database (`deleted = true`). Restore one with
  `UPDATE print_jobs SET deleted = false WHERE job_id = '…';`.

---

## Labelary preview

`/api/wristbands/preview/image` sends the generated ZPL to the
[Labelary API](https://labelary.com/service.html) and returns the rendered PNG.

To preview manually, get the ZPL from `/api/wristbands/preview/zpl` and paste it at
[labelary.com/viewer.html](https://labelary.com/viewer.html) with width **1**, height **11**,
density **12dpmm** (300 dpi).

---

## Job persistence

Print jobs are persisted to **PostgreSQL**; the schema is managed by **Flyway**
(`src/main/resources/db/migration`). On startup, any job left `PENDING` or `PRINTING` by a previous
run is marked `FAILED` ("Interrupted by service restart") — a half-printed wristband is never
reprinted automatically; the operator can reprint deliberately.

The local stack (`docker-compose.yml`) starts a `postgres` service with fixed credentials. The
production stack (`docker-compose.prod.yml`) has no DB container — management connects to a remote
`stup_wristband_db` database on the Symfony Postgres instance (via `SPRING_DATASOURCE_*` in `.env.prod`).

---

## Swagger UI

Interactive API docs:
- **http://localhost:8080/swagger-ui.html**
- OpenAPI spec: **http://localhost:8080/v3/api-docs**

Click **Authorize** and enter your API key to test endpoints interactively.

---

## Metrics

Micrometer metrics are exposed via Actuator at **http://localhost:8080/actuator/metrics**:

- `wristband.jobs.submitted` — jobs accepted into the queue
- `wristband.jobs.completed{status=done|failed}` — processed jobs by outcome
- `wristband.queue.depth` — pending jobs waiting to print (summed across all per-printer queues)
- `wristband.printer.send` — timer for sending ZPL to the printer (includes retries; measured on the worker)

Each job's `jobId` and `printerId` are added to the logging MDC while it is processed, so log lines
for a job (and its target printer) can be correlated.

---

## Running tests

```bash
mvn test
```

Tests run the persistence and integration layers against a real PostgreSQL started automatically via
Testcontainers — a running **Docker** daemon is required. The printer and Labelary are mocked (a fake
TCP socket / HTTP endpoint stands in for the printer).

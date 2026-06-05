# STUP Wristband Printer Service

Java 21 / Spring Boot service that generates ZPL wristband labels for Zebra printers.
Used by the STUP Symfony event application to print staff wristbands at events, with a
built-in admin UI, a visual template designer, and support for multiple printers.

## Contents

- [Getting started](#getting-started)
- [Architecture](#architecture)
- [Running locally](#running-locally)
  - [Native (management only)](#native-management-only)
  - [Docker — management only](#docker--management-only)
  - [Docker — multiple virtual printers](#docker--multiple-virtual-printers)
- [Production deployment](#production-deployment)
  - [Adding a printer](#adding-a-printer)
  - [HTTPS and Symfony cert trust](#https-and-symfony-cert-trust)
- [Configuration](#configuration)
- [API endpoints](#api-endpoints)
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
print with the curl examples under [Docker — multiple virtual printers](#docker--multiple-virtual-printers).

### Production quick start (real printers)

Needs a remote, empty `wristbands` database (DB + role created by a DBA) and one reachable Zebra per
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

The **registry** lives in management config under `cluster.printers` — a list of
`{ id, display-name, base-url }`, one per printer. `id` is what a caller sends as `printerId`;
`base-url` is the worker's in-network address. A print request with no `printerId` goes to the
first (default) printer; an unknown `printerId` is rejected with **400**. Each printer has its own
queue and worker thread, so printers print in parallel.

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

### Native (management only)

The native workflow runs the **management** service for UI/template/API development. It does not
print on its own — forwarding goes to a worker; for end-to-end printing use the
[virtual-printer cluster](#docker--multiple-virtual-printers).

**Prerequisites:** Java 21, Maven 3.9+, Docker (for a local PostgreSQL).

1. Place `stup-logo.png` in `src/main/resources/images/`.
2. Start a local PostgreSQL matching the `local` profile (`application-local.yml` uses database
   `wristbands`, user/password `wristbands`/`wristbands` on `localhost:5432`):

   ```bash
   docker run --name stup-pg \
     -e POSTGRES_DB=wristbands -e POSTGRES_USER=wristbands -e POSTGRES_PASSWORD=wristbands \
     -p 5432:5432 -d postgres:16-alpine
   ```

   Flyway creates the schema automatically on startup.
3. Start the service:

   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```

Management starts on **http://localhost:8080**; the admin jobs page is at `/jobs.html`
(username `admin` / password `local-admin`, the `local` default).

To also print locally without Docker, run a **worker** in a second shell pointing at a printer
(or a fake one, e.g. `while true; do nc -l 9100; done`):

```bash
SPRING_PROFILES_ACTIVE=worker SECURITY_API_KEY=local-dev-key \
PRINTER_HOST=localhost PRINTER_PORT=9100 SERVER_PORT=8089 \
mvn spring-boot:run
```

`application-local.yml` already registers `printer-1` at `http://localhost:8089`, so jobs flow
management → worker → printer.

> **Port 5432 already in use?** Run Postgres on another port and override the datasource URL — no
> config change needed:
>
> ```bash
> docker run --name stup-pg -e POSTGRES_DB=wristbands -e POSTGRES_USER=wristbands \
>   -e POSTGRES_PASSWORD=wristbands -p 5433:5432 -d postgres:16-alpine
>
> SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/wristbands \
>   mvn spring-boot:run -Dspring-boot.run.profiles=local
> ```
>
> (PostgreSQL only sets the password when the data volume is first created — if you reused an old
> `stup-pg` with a different password, `docker rm -f stup-pg` and recreate.)

### Docker — management only

Postgres + the management service on plain HTTP (`local` profile). Good for UI/template work, but
**printing fails until a worker exists** — use the cluster stack below for end-to-end prints.

```bash
./build.sh
docker compose up --build
# http://localhost:8080/jobs.html   (admin / local-admin)
```

> **Upgrading from an older compose?** If `docker-compose.yml` previously ran with a custom
> `DB_PASSWORD`, the persisted `pgdata` volume was initialized with it and the new hardcoded
> `wristbands` credentials fail. Run `docker compose down -v` once to recreate the volume.

### Docker — multiple virtual printers

`docker-compose.local-cluster.yml` simulates the full production topology **without any real
printers**: Postgres + management + two workers + two fake printers (`socat` TCP listeners that log
the ZPL they receive). This is the recommended way to develop and demo multi-printer behaviour.

```bash
./build.sh
docker compose -f docker-compose.local-cluster.yml up --build -d
# http://localhost:8080/jobs.html   (admin / local-admin)
```

Two printers are registered — `printer-1` ("Inkom links") and `printer-2` ("Inkom rechts") — each
wired to its own fake printer. Target a specific printer with `"printerId"`:

```bash
# default printer (printer-1)
curl -s -X POST http://localhost:8080/api/wristbands/print \
  -H "Content-Type: application/json" -H "X-API-Key: local-dev-key" \
  -d '{"eventName":"Test","firstName":"Jan","lastName":"Janssen","associationName":"STUP","barcodeValue":"111"}'

# explicitly to printer-2
curl -s -X POST http://localhost:8080/api/wristbands/print \
  -H "Content-Type: application/json" -H "X-API-Key: local-dev-key" \
  -d '{"eventName":"Test","firstName":"An","lastName":"Peeters","associationName":"STUP","barcodeValue":"222","printerId":"printer-2"}'

# watch the ZPL arrive at each printer:
docker compose -f docker-compose.local-cluster.yml logs -f fakeprinter-1 fakeprinter-2
```

The jobs page then shows the **Printer** column, per-printer **filter chips**, parallel printing
and the **reprint printer picker**. Stop with `docker compose -f docker-compose.local-cluster.yml down`.

**Add a virtual printer:** add a `fakeprinter-3` (copy a socat service) and a `worker-3`
(`PRINTER_HOST=fakeprinter-3`), then add a third entry to the management `SPRING_APPLICATION_JSON`
registry pointing at `http://worker-3:8080`.

---

## Production deployment

`docker-compose.prod.yml` runs one **management** service (public, HTTPS) plus one **worker** per
Zebra printer (internal HTTP). There is no database container — management connects to a dedicated
`wristbands` database on the Symfony site's Postgres instance (remote). Only management is published
(HTTPS 8443) and holds the certificate + DB connection; workers are internal-only HTTP and need no
certs or database. Management and every worker share the same `API_KEY`. Flyway runs once, in
management, on startup.

Replace every **`[placeholder]`** below with your real value. The placeholders for each printer
(`[printer-N-ip]`, `[printer-N-label]`) are the ones you fill in per Zebra.

**Prerequisites**

- A DBA has created an empty `wristbands` database + role on the prod Postgres (Flyway creates the
  tables, not the database).
- Each Zebra is reachable from the server — verify with `ping [printer-1-ip]`.
- The base image is built: `./build.sh`.

**Step 1 — Configure secrets, the database, and the printer IPs (`.env.prod`)**

```bash
cp .env.example .env.prod
```

Edit `.env.prod` — one `PRINTERn_HOST` line per physical printer:

```dotenv
API_KEY=[strong-api-key]
ADMIN_PASSWORD=[strong-admin-password]
SSL_KEYSTORE_PASSWORD=[strong-keystore-password]
MANAGEMENT_HOSTNAME=[hostname-symfony-connects-to]

SPRING_DATASOURCE_URL=jdbc:postgresql://[db-host]:5432/wristbands
DB_USERNAME=[db-user]
DB_PASSWORD=[db-password]

PRINTER1_HOST=[printer-1-ip]
PRINTER2_HOST=[printer-2-ip]
```

**Step 2 — Declare one worker per printer (`docker-compose.prod.yml`)**

`printer-worker-1` already exists. For each additional printer, uncomment/copy the
`printer-worker-2` template and point it at that printer's `PRINTERn_HOST`:

```yaml
  printer-worker-2:
    <<: *worker-base
    environment:
      SPRING_PROFILES_ACTIVE: worker
      SECURITY_API_KEY: ${API_KEY}
      PRINTER_HOST: ${PRINTER2_HOST}
```

Add each new worker to the management service's `depends_on` list.

**Step 3 — Register the printers in management (`docker-compose.prod.yml`)**

In the `management` service, edit `SPRING_APPLICATION_JSON` so the registry lists every worker.
`id` is what Symfony sends as `printerId`, `display-name` is shown in the UI, and the `base-url`
host **must** equal the worker's service name. Only `[printer-N-label]` is free text:

```yaml
      SPRING_APPLICATION_JSON: '{"cluster":{"printers":[{"id":"printer-1","display-name":"[printer-1-label]","base-url":"http://printer-worker-1:8080"},{"id":"printer-2","display-name":"[printer-2-label]","base-url":"http://printer-worker-2:8080"}]}}'
```

**Step 4 — Launch**

```bash
./build.sh
docker compose -f docker-compose.prod.yml --env-file .env.prod up --build -d
```

**Step 5 — Verify**

```bash
# health (self-signed cert → -k)
curl -fsk https://[management-hostname]:8443/actuator/health

# the registry lists every printer you configured
curl -fsk https://[management-hostname]:8443/api/wristbands/printers \
  -H "X-API-Key: [api-key]"

# a test print to a specific printer
curl -fsk -X POST https://[management-hostname]:8443/api/wristbands/print \
  -H "X-API-Key: [api-key]" -H "Content-Type: application/json" \
  -d '{"eventName":"Test","firstName":"Jan","lastName":"Janssen","associationName":"STUP","barcodeValue":"123","printerId":"printer-1"}'
```

Then open `https://[management-hostname]:8443/jobs.html` (admin / your `ADMIN_PASSWORD`).

**Adding another printer later** — repeat the same edits for the next index, then redeploy:

1. `.env.prod`: add `PRINTER3_HOST=[printer-3-ip]`.
2. `docker-compose.prod.yml`: add a `printer-worker-3` service (Step 2) and a registry entry
   `{"id":"printer-3","display-name":"[printer-3-label]","base-url":"http://printer-worker-3:8080"}` (Step 3).
3. `docker compose -f docker-compose.prod.yml --env-file .env.prod up --build -d`.

The new printer then appears in `GET /api/wristbands/printers`, the jobs-page filter chips, and the
reprint picker. Workers do **not** publish a host port and need no certificate.

### HTTPS and Symfony cert trust

Only **management** terminates TLS: in the `prod` profile it listens **HTTPS-only on 8443** with a
self-signed certificate. Workers are HTTP on the private Docker network and are never exposed.
Symfony calls management at `https://<MANAGEMENT_HOSTNAME>:8443/...`.

The keystore is generated on first start and stored in the `certs-management` volume (reused across
redeploys, so the cert is stable). `MANAGEMENT_HOSTNAME` (in `.env.prod`) becomes the certificate's
CN/SAN — set it before the first start; the compose file maps it to `SSL_CERT_HOSTNAME`. To
regenerate, remove the volume: `docker volume rm <project>_certs-management`.

Export the public certificate from the running container:

```bash
docker compose -f docker-compose.prod.yml cp management:/certs/server.crt ./server.crt
```

Then either (recommended) point the Symfony HTTP client at it as a CA:

```yaml
# config/packages/framework.yaml
framework:
    http_client:
        scoped_clients:
            wristband.client:
                base_uri: 'https://<MANAGEMENT_HOSTNAME>:8443'
                cafile: '%kernel.project_dir%/config/certs/server.crt'
```

...or, on a trusted private network, disable peer verification instead:

```yaml
                verify_peer: false
                verify_host: false
```

`MANAGEMENT_HOSTNAME` must match the hostname Symfony connects to, or hostname verification fails.

---

## Configuration

| Property | Default | Description |
|---|---|---|
| `cluster.printers` | sentinel | **Management** printer registry: list of `{id, display-name, base-url}`, one per printer. Override per environment — see the compose files |
| `printer.host` | `localhost` | Zebra printer IP/host — **set per worker** via `PRINTER_HOST`; unused by management |
| `printer.port` | `9100` | Zebra printer TCP port (per worker) |
| `printer.timeout-ms` | `5000` | Connection timeout in milliseconds |
| `printer.max-retries` | `2` | Extra attempts after the first on a transient socket failure |
| `printer.retry-backoff-ms` | `500` | Pause between retry attempts |
| `queue.max-depth` | `100` | Max pending jobs **per printer** before new submissions are rejected with HTTP 429 |
| `wristband.dpi` | `300` | Printer DPI (203 or 300) |
| `wristband.logo-path` | `classpath:images/stup-logo.png` | Path to STUP logo PNG — bundled inside the JAR, no external file needed |
| `wristband.logo-side-margin-dots` | `75` | Left/right margin around logo in dots |
| `wristband.margins.*` | see YAML | Spacing between layout elements in dots |
| `wristband.text.*` | see YAML | Font sizes for event name, staff name, association |
| `wristband.barcode.type` | `CODE128` | Barcode symbology |
| `wristband.barcode.height-dots` | `270` | Barcode height in dots |
| `wristband.barcode.show-human-readable` | `false` | Show text below barcode |
| `labelary.base-url` | `http://api.labelary.com` | Labelary API base URL |
| `security.api-key` | `changeme` | Static API key — override in production; shared by management + workers |

**Profile activation:**
- Management — local: `--spring.profiles.active=local`
- Management — production: `SPRING_PROFILES_ACTIVE=prod`
- Worker (printer node): `SPRING_PROFILES_ACTIVE=worker` (no DB/UI; needs `PRINTER_HOST` + `SECURITY_API_KEY`)

> Under the `prod` profile the application refuses to start if `security.api-key` is unset, blank,
> or left at the default `changeme` — set `SECURITY_API_KEY` to a real value.

**ZPL coordinate calibration:** all layout positions are configurable via `wristband.margins.*` and
`wristband.text.*`. After a first test print, adjust the values in `application-prod.yml` — no code
changes needed.

---

## API endpoints

All endpoints (except `/api/wristbands/jobs/stream` and `/jobs.html`) require:

```
X-API-Key: <your-api-key>
```

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/wristbands/print` | Enqueue a print job → `202 + jobId`. Optional `printerId` selects the printer (default = first); unknown id → `400`. Response carries `printerId` + `printerName` |
| `POST` | `/api/wristbands/preview/zpl` | Return generated ZPL as plain text |
| `POST` | `/api/wristbands/preview/image` | Return rendered PNG via Labelary |
| `GET` | `/api/wristbands/printers` | List routable printers (`[{id, displayName}]`) |
| `GET` | `/api/wristbands/jobs` | List all jobs (`?status=PENDING\|PRINTING\|DONE\|FAILED`) |
| `GET` | `/api/wristbands/jobs/{jobId}` | Get one job (incl. `printerId`/`printerName`) |
| `GET` | `/api/wristbands/jobs/stream` | SSE stream — real-time updates for **all** jobs |
| `GET` | `/api/wristbands/jobs/{jobId}/stream` | SSE stream for **one** job; emits its current status, then updates, and closes on a terminal status (for Symfony to follow a single job) |
| `POST` | `/api/wristbands/jobs/{jobId}/reprint` | Reprint a previous job; optional `?printerId=` re-routes it to another printer |
| `DELETE` | `/api/wristbands/jobs/completed` | Soft-delete DONE/FAILED/CANCELLED jobs |
| `POST` | `/api/templates` | Create a wristband template → `201 + detail` |
| `PUT` | `/api/templates/{id}` | Update a template → `200` / `404` |
| `GET` | `/api/templates` | List templates (catalog); `?projectType=` filters |
| `GET` | `/api/templates/{id}` | Get a template's full definition → `200` / `404` |
| `DELETE` | `/api/templates/{id}` | Soft-delete a template → `204` / `404` |
| `GET` | `/api/templates/{id}/preview` | PNG preview with sample data (`?color=` tints stock) |
| `POST` | `/api/templates/{id}/preview` | PNG preview with supplied `WristbandData` body |
| `POST` | `/api/templates/assets` | Upload a logo (multipart `file`) → `201 + assetId` |
| `GET` | `/api/templates/assets/{id}` | Fetch a stored logo PNG |

> **Wristband Template Designer:** the `/api/templates` endpoints back a visual template designer.
> `POST /api/wristbands/print` accepts an optional `templateId` — when set, the wristband is rendered
> from that template instead of the default fixed layout. Architecture, data model, full API and
> roadmap are in [docs/template-designer.md](docs/template-designer.md).

**Example print request:**

```bash
curl -X POST http://localhost:8080/api/wristbands/print \
  -H "X-API-Key: local-dev-key" -H "Content-Type: application/json" \
  -d '{
    "eventName": "Pukkelpop 2026",
    "firstName": "Annechien",
    "lastName": "Van De Wall",
    "associationName": "Chiro Sint-Christina Brustem",
    "barcodeValue": "12345455244226789"
  }'
```

**Example ZPL preview** (paste the output at [labelary.com/viewer.html](https://labelary.com/viewer.html)):

```bash
curl -X POST http://localhost:8080/api/wristbands/preview/zpl \
  -H "X-API-Key: local-dev-key" -H "Content-Type: application/json" \
  -d '{"eventName":"Pukkelpop 2026","firstName":"Annechien","lastName":"Van De Wall","associationName":"Chiro Sint-Christina Brustem","barcodeValue":"12345455244226789"}' \
  | pbcopy
```

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
- Clicking a row opens a **slide-in detail drawer** with the full wristband data (name, association,
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
`wristbands` database on the Symfony Postgres instance (via `SPRING_DATASOURCE_*` in `.env.prod`).

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

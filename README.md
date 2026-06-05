# STUP Wristband Printer Service

Java 21 / Spring Boot API that generates ZPL wristband labels for Zebra printers.
Used by the STUP Symfony event application to print staff wristbands at events.

---

## Running locally

> **Note:** This section is the native developer workflow and requires a local JDK 21 + Maven. To run the service with only Docker installed (no host Java), use the [Containers](#containers) section instead.

**Prerequisites:** Java 21, Maven 3.9+, Docker (for a local PostgreSQL).

1. Place `stup-logo.png` in `src/main/resources/images/`.
2. **Start a local PostgreSQL** matching the `local` profile (`application-local.yml` uses
   database `wristbands`, user/password `wristbands`/`wristbands` on `localhost:5432`):

   ```bash
   docker run --name stup-pg \
     -e POSTGRES_DB=wristbands \
     -e POSTGRES_USER=wristbands \
     -e POSTGRES_PASSWORD=wristbands \
     -p 5432:5432 -d postgres:16-alpine
   ```

   The schema is created automatically by Flyway on startup.

3. Edit `src/main/resources/application-local.yml` — set `printer.host` to your printer's IP.
4. Start:

   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```

Application starts on **http://localhost:8080**. The admin jobs page is at
`/jobs.html`; log in with username `admin` / password `local-admin` (the `local`
profile default).

> **Port 5432 already in use?** If another project occupies `5432`, run the container
> on a different port and override the datasource URL — no config change needed:
>
> ```bash
> docker run --name stup-pg -e POSTGRES_DB=wristbands -e POSTGRES_USER=wristbands \
>   -e POSTGRES_PASSWORD=wristbands -p 5433:5432 -d postgres:16-alpine
>
> SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/wristbands \
>   mvn spring-boot:run -Dspring-boot.run.profiles=local
> ```
>
> (PostgreSQL only sets the password when the data volume is first created — if you
> reused an old `stup-pg` with a different password, `docker rm -f stup-pg` and recreate.)

---

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

### Architecture: management + printer-workers

The service runs in two roles, selected by Spring profile (same image, different
`SPRING_PROFILES_ACTIVE`):

- **management** (`local` / `prod`; active whenever the `worker` profile is *not* set) — the only
  role with a UI, admin login, database, job history and SSE. It holds the **printer registry**,
  renders the ZPL, and forwards each job to the right worker. One instance.
- **worker** (`worker`) — a thin, database-free, UI-free service; one per physical printer. It
  exposes an internal `POST /api/internal/print`, writes the received ZPL to its printer
  (`PRINTER_HOST:9100`) and reports success/failure. Reached only by management over the private
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

### Local — management only (`docker-compose.yml`)

Postgres + the management service on plain HTTP, `local` profile. Good for UI/template work, but
**printing fails until a worker exists** — use the cluster stack below for end-to-end prints.

```bash
./build.sh
docker compose up --build
# http://localhost:8080/actuator/health
```

> **Upgrading from the old compose?** If you previously ran `docker-compose.yml` with a
> custom `DB_PASSWORD`, the persisted `pgdata` volume was initialized with that password,
> so the new hardcoded `wristbands` credentials fail authentication. Run
> `docker compose down -v` once to drop and recreate the volume.

### Local — multiple virtual printers (`docker-compose.local-cluster.yml`)

Simulates the full production topology **without any real printers**: Postgres + management +
two workers + two fake printers (`socat` TCP listeners that log the ZPL they receive). This is the
recommended way to develop and demo multi-printer behaviour locally.

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

### Production — real printers (`docker-compose.prod.yml`)

No database container — management connects to a dedicated `wristbands` database on the Symfony
site's Postgres instance (remote). One management service (public, HTTPS) plus one worker per Zebra
printer (internal HTTP).

Prerequisites:
1. A DBA creates an empty `wristbands` database + role on the prod Postgres instance
   (Flyway creates the tables, not the database).
2. `cp .env.example .env.prod` and fill in the values (each variable is documented in `.env.example`).

Run:

```bash
./build.sh
docker compose -f docker-compose.prod.yml --env-file .env.prod up --build -d
# https://<MANAGEMENT_HOSTNAME>:8443/actuator/health   (self-signed cert)
```

Only the management service is published (HTTPS 8443) and holds the certificate + DB connection;
workers are internal-only HTTP and need no certs or database. Management and every worker share the
same `API_KEY`. Flyway runs once, in management, on startup.

### Adding a printer in production

Two coordinated edits in `docker-compose.prod.yml`:

1. **Add a worker** — copy the commented `printer-worker-2` template, give it a unique service name,
   and point `PRINTER_HOST` at the new Zebra's IP (add `PRINTER2_HOST=…` to `.env.prod`).
2. **Register it** — add a matching entry to the management `SPRING_APPLICATION_JSON` registry:
   `{"id":"printer-2","display-name":"Inkom rechts","base-url":"http://printer-worker-2:8080"}`.
   The `base-url` host **must** equal the worker's service name; `id` is what Symfony sends as `printerId`.

Then redeploy (`up --build -d`). The new printer appears in `GET /api/wristbands/printers`, in the
jobs-page filter chips, and in the reprint picker. Workers do **not** publish a host port and need no
certificate.

> **Printer network access:** workers reach printers over the host network. Each Zebra must be
> reachable from the server — verify with `ping <PRINTERn_HOST>` on the server before deploying.

### HTTPS and Symfony cert trust

Only the **management** service terminates TLS: in the `prod` profile it listens **HTTPS-only on
8443** with a self-signed certificate. Workers are HTTP on the private Docker network and are never
exposed. Symfony calls management at `https://<MANAGEMENT_HOSTNAME>:8443/...`.

The keystore is generated on first start and stored in the `certs-management` volume (reused across
redeploys, so the certificate is stable). `MANAGEMENT_HOSTNAME` (in `.env.prod`) becomes the
certificate's CN/SAN — set it before the first start; the compose file maps it to `SSL_CERT_HOSTNAME`.
To regenerate, remove the volume: `docker volume rm <project>_certs-management`.

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

`MANAGEMENT_HOSTNAME` must match the hostname the Symfony app uses to connect, otherwise hostname verification fails.

---

## Configuration

| Property | Default | Description |
|---|---|---|
| `printer.host` | `localhost` | Zebra printer IP/host — **set per worker** via `PRINTER_HOST`; unused by management |
| `printer.port` | `9100` | Zebra printer TCP port (per worker) |
| `cluster.printers` | sentinel | Printer registry (management only): list of `{id, display-name, base-url}`, one per printer. Override per environment — see the compose files |
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
| `security.api-key` | `changeme` | Static API key — override in production |

**Profile activation:**
- Management — Local: `--spring.profiles.active=local`
- Management — Production: `SPRING_PROFILES_ACTIVE=prod` env var
- Worker (printer node): `SPRING_PROFILES_ACTIVE=worker` (no DB/UI; needs `PRINTER_HOST` + `SECURITY_API_KEY`)

> Under the `prod` profile the application refuses to start if `security.api-key` is unset, blank, or left at the default `changeme` — set `SECURITY_API_KEY` to a real value.

**ZPL coordinate calibration:** All layout positions are configurable via `wristband.margins.*` and `wristband.text.*`. After first test print, adjust values in `application-prod.yml` without code changes.

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
| `GET` | `/api/wristbands/jobs/{jobId}` | Get job status (incl. `printerId`/`printerName`) |
| `GET` | `/api/wristbands/jobs/stream` | SSE stream — real-time updates for **all** jobs |
| `GET` | `/api/wristbands/jobs/{jobId}/stream` | SSE stream for **one** job; sends current status, then updates, and closes on a terminal status (for Symfony to follow a single job) |
| `POST` | `/api/wristbands/jobs/{jobId}/reprint` | Reprint a previous job; optional `?printerId=` re-routes it to another printer |
| `DELETE` | `/api/wristbands/jobs/completed` | Remove DONE and FAILED jobs |
| `POST` | `/api/templates` | Create a wristband template → `201 + detail` |
| `PUT` | `/api/templates/{id}` | Update a template → `200` / `404` |
| `GET` | `/api/templates` | List templates (catalog); `?projectType=` filters |
| `GET` | `/api/templates/{id}` | Get a template's full definition → `200` / `404` |
| `DELETE` | `/api/templates/{id}` | Soft-delete a template → `204` / `404` |
| `GET` | `/api/templates/{id}/preview` | PNG preview with sample data (`?color=` tints stock) |
| `POST` | `/api/templates/{id}/preview` | PNG preview with supplied `WristbandData` body |
| `POST` | `/api/templates/assets` | Upload a logo (multipart `file`) → `201 + assetId` |
| `GET` | `/api/templates/assets/{id}` | Fetch a stored logo PNG |

> **Wristband Template Designer:** the `/api/templates` endpoints back a visual template
> designer. `POST /api/wristbands/print` accepts an optional `templateId` — when set, the
> wristband is rendered from that template instead of the default fixed layout. Architecture,
> data model, full API and roadmap are documented in
> [docs/template-designer.md](docs/template-designer.md).

**Example print request:**
```bash
curl -X POST http://localhost:8080/api/wristbands/print \
  -H "X-API-Key: local-dev-key" \
  -H "Content-Type: application/json" \
  -d '{
    "eventName": "Pukkelpop 2026",
    "firstName": "Annechien",
    "lastName": "Van De Wall",
    "associationName": "Chiro Sint-Christina Brustem",
    "barcodeValue": "12345455244226789"
  }'
```

**Example ZPL preview:**
```bash
curl -X POST http://localhost:8080/api/wristbands/preview/zpl \
  -H "X-API-Key: local-dev-key" \
  -H "Content-Type: application/json" \
  -d '{"eventName":"Pukkelpop 2026","firstName":"Annechien","lastName":"Van De Wall","associationName":"Chiro Sint-Christina Brustem","barcodeValue":"12345455244226789"}' \
  | pbcopy   # paste into https://labelary.com/viewer.html
```

---

## Labelary preview

The `/api/wristbands/preview/image` endpoint sends the generated ZPL to the
[Labelary API](https://labelary.com/service.html) and returns the rendered PNG.

To preview manually, use `/api/wristbands/preview/zpl` to get the ZPL string,
then paste it at [labelary.com/viewer.html](https://labelary.com/viewer.html).
Set width to **1**, height to **11**, density to **12dpmm** (300 dpi).

---

## Job persistence

Print jobs are persisted to **PostgreSQL**; the schema is managed by **Flyway**
(`src/main/resources/db/migration`). On startup, any job left `PENDING` or `PRINTING`
by a previous run is marked `FAILED` ("Interrupted by service restart") — a
half-printed wristband is never reprinted automatically; the operator can reprint
deliberately.

The local Docker Compose stack (`docker-compose.yml`) starts a `postgres` service
automatically, wired to the app via fixed credentials. The production stack
(`docker-compose.prod.yml`) has no DB container — it connects to a remote `wristbands`
database on the Symfony Postgres instance (configured via `SPRING_DATASOURCE_*` in
`.env.prod`).

---

## Job management UI

Open **http://localhost:8080/jobs.html** in a browser (you'll be redirected to
`/login.html` if not signed in).

- Sign in with the admin credential (`security.admin.username` / `security.admin.password`).
  A session is kept in an HttpOnly cookie — no key is stored in the browser.
- The job table updates in real-time via Server-Sent Events.
- Each row shows the person's **name**, event, **printer**, status, a truncated job ID (with copy),
  relative timestamps, and per-job actions. Status chips give live counts and filter; columns sort.
- When more than one printer is configured, a second chip row **filters by printer**.
- The search box has a clear (×) button that appears once it holds text.
- Clicking a row opens a **slide-in detail drawer** with the full wristband data
  (name, association, barcode, printer, timestamps) and a **Show preview** button that renders
  the wristband image via Labelary on demand.
- **Cancel** stops a PENDING job; **Reprint** re-queues a DONE/FAILED job — with several printers it
  first asks which printer to print on (automatic when there is only one).
- **Clear completed** asks for confirmation, then **soft-deletes** DONE/FAILED/CANCELLED
  jobs — they are hidden from the queue but kept in the database (`deleted = true`).
  Restore one with `UPDATE print_jobs SET deleted = false WHERE job_id = '…';`.

---

## Swagger UI

Interactive API docs available at:
- **http://localhost:8080/swagger-ui.html**
- OpenAPI spec: **http://localhost:8080/v3/api-docs**

Click **Authorize** in Swagger UI and enter your API key to test endpoints interactively.

---

## Metrics

Micrometer metrics are exposed via Actuator at **http://localhost:8080/actuator/metrics**:

- `wristband.jobs.submitted` — jobs accepted into the queue
- `wristband.jobs.completed{status=done|failed}` — processed jobs by outcome
- `wristband.queue.depth` — pending jobs waiting to print (summed across all per-printer queues)
- `wristband.printer.send` — timer for sending ZPL to the printer (includes retries; measured on the worker)

Each job's `jobId` and `printerId` are added to the logging MDC while it is processed, so log lines
for a job (and the printer it targets) can be correlated.

---

## Running tests

```bash
mvn test
```

Tests run the persistence and integration layers against a real PostgreSQL started
automatically via Testcontainers — a running **Docker** daemon is required. The
printer and Labelary are still mocked (a fake TCP socket stands in for the printer).

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

### Local (development)

Postgres + the service on plain HTTP, `local` profile:

```bash
./build.sh
docker compose up --build
# http://localhost:8080/actuator/health
```

> **Upgrading from the old compose?** If you previously ran `docker-compose.yml` with a
> custom `DB_PASSWORD`, the persisted `pgdata` volume was initialized with that password,
> so the new hardcoded `wristbands` credentials fail authentication. Run
> `docker compose down -v` once to drop and recreate the volume.

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

> **Printer network access:** The container uses Docker's default bridge network and routes outbound traffic through the host. The Zebra printer must be reachable from the server itself — verify with `ping <PRINTER_HOST>` on the server before deploying.

### HTTPS and Symfony cert trust

In the `prod` profile each container listens **HTTPS-only on port 8443** (or the port you publish) using a self-signed certificate. The Symfony app calls it at `https://<host>:8443/...`.

The keystore is generated automatically on first container start and stored in a named `certs-printerN` Docker volume. It is reused on subsequent starts, so the certificate is stable across redeploys. `PRINTER1_HOSTNAME` (in `.env.prod`) becomes the certificate's CN/SAN — set it before the first start; the compose file maps it to the container's `SSL_CERT_HOSTNAME`. To regenerate the certificate, remove the volume: `docker volume rm <certs-volume-name>`.

Export the public certificate from the running container:

```bash
docker compose -f docker-compose.prod.yml cp printer-1:/certs/server.crt ./server.crt
```

Then either (recommended) point the Symfony HTTP client at it as a CA:

```yaml
# config/packages/framework.yaml
framework:
    http_client:
        scoped_clients:
            wristband.client:
                base_uri: 'https://<host>:8443'
                cafile: '%kernel.project_dir%/config/certs/server.crt'
```

...or, on a trusted private network, disable peer verification instead:

```yaml
                verify_peer: false
                verify_host: false
```

`PRINTER1_HOSTNAME` must match the hostname the Symfony app uses to connect, otherwise hostname verification fails.

---

## Configuration

| Property | Default | Description |
|---|---|---|
| `printer.host` | `localhost` | Zebra printer IP address |
| `printer.port` | `9100` | Zebra printer TCP port |
| `printer.timeout-ms` | `5000` | Connection timeout in milliseconds |
| `printer.max-retries` | `2` | Extra attempts after the first on a transient socket failure |
| `printer.retry-backoff-ms` | `500` | Pause between retry attempts |
| `queue.max-depth` | `100` | Max pending jobs before new submissions are rejected with HTTP 429 |
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
- Local: `--spring.profiles.active=local`
- Production: `SPRING_PROFILES_ACTIVE=prod` env var

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
| `POST` | `/api/wristbands/print` | Enqueue a print job → `202 + jobId` |
| `POST` | `/api/wristbands/preview/zpl` | Return generated ZPL as plain text |
| `POST` | `/api/wristbands/preview/image` | Return rendered PNG via Labelary |
| `GET` | `/api/wristbands/jobs` | List all jobs (`?status=PENDING\|PRINTING\|DONE\|FAILED`) |
| `GET` | `/api/wristbands/jobs/{jobId}` | Get job status |
| `GET` | `/api/wristbands/jobs/stream` | SSE stream — real-time job updates |
| `POST` | `/api/wristbands/jobs/{jobId}/reprint` | Reprint a previous job |
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
- Each row shows the person's **name**, event, status, a truncated job ID (with copy),
  relative timestamps, and per-job actions. Status chips give live counts and filter; columns sort.
- Clicking a row opens a **slide-in detail drawer** with the full wristband data
  (name, association, barcode, timestamps) and a **Show preview** button that renders
  the wristband image via Labelary on demand.
- **Cancel** stops a PENDING job; **Reprint** re-queues a DONE/FAILED job.
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
- `wristband.queue.depth` — pending jobs waiting to print
- `wristband.printer.send` — timer for sending ZPL to the printer (includes retries)

Each job's `jobId` is added to the logging MDC while it is processed, so log lines for a
job can be correlated.

---

## Running tests

```bash
mvn test
```

Tests run the persistence and integration layers against a real PostgreSQL started
automatically via Testcontainers — a running **Docker** daemon is required. The
printer and Labelary are still mocked (a fake TCP socket stands in for the printer).

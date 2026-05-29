# STUP Wristband Printer Service

Java 21 / Spring Boot API that generates ZPL wristband labels for Zebra printers.
Used by the STUP Symfony event application to print staff wristbands at events.

---

## Running locally

**Prerequisites:** Java 21, Maven 3.9+

1. Place `stup-logo.png` in `src/main/resources/images/`
2. Edit `src/main/resources/application-local.yml` — set `printer.host` to your printer's IP
3. Start:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Application starts on **http://localhost:8080**

---

## Docker build and run

```bash
# Build
docker build -t stup/wristband-printer .

# Run (single container)
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SECURITY_API_KEY=your-key \
  -e PRINTER_HOST=192.168.1.100 \
  stup/wristband-printer

# Run with docker-compose
cp .env.example .env   # fill in API_KEY and PRINTER_HOST
docker compose up -d
```

> **Printer network access:** The container uses Docker's default bridge network and routes outbound traffic through the host. The Zebra printer must be reachable from the server itself — verify with `ping <PRINTER_HOST>` on the server before deploying.

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
  -d '{"eventName":"Pukkelpop 2026","firstName":"Jan","lastName":"Janssens","associationName":"STUP vzw","barcodeValue":"123456789"}' \
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

Print jobs are persisted to an embedded H2 database (file `./data/printjobs`), so the
job history survives a restart. On startup, any job left `PENDING` or `PRINTING` by a
previous run is marked `FAILED` ("Interrupted by service restart") — a half-printed
wristband is never reprinted automatically; the operator can reprint deliberately.

Under Docker the database lives on the `printjobs-data` volume (mounted at `/app/data`)
so it survives container recreation.

---

## Job management UI

Open **http://localhost:8080/jobs.html** in a browser.

- Enter the API key in the input at the top (stored in `sessionStorage` for the session)
- The job table updates in real-time via Server-Sent Events — no page refresh needed
- Use the **Reprint** button on any DONE or FAILED job to re-enqueue it
- Use **Clear completed** to remove DONE and FAILED jobs from the view

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

No external dependencies required — the printer and Labelary are mocked in tests.

# Wristband Printer Service — Design Spec

**Date:** 2026-05-21
**Status:** Approved

---

## Context

STUP uses a Symfony web application for event planning, shift management, and staff tracking. Wristband printing was previously handled by a .NET application. This service replaces that functionality with a dedicated Java API that Symfony can call over HTTP.

---

## Stack

- **Java 21**
- **Spring Boot 3.x**
- **Maven**
- **Docker-first deployment**
- **springdoc-openapi** for Swagger UI

---

## Architecture

### Approach

Pure Java programmatic ZPL generation. No ZPL library dependencies. The PNG logo is loaded once at startup, converted to ZPL `^GF` (Graphic Field) monochrome bitmap format, and cached in memory. All layout coordinates are named constants — not magic numbers.

### Package Structure

```
com.stup.wristbandprinter
├── config/
│   ├── PrinterProperties.java         # binds printer.* from YAML
│   ├── WristbandProperties.java       # binds wristband.* from YAML
│   ├── LabelaryProperties.java        # binds labelary.* from YAML
│   └── SecurityConfig.java            # API key filter wiring + Swagger security scheme
├── controller/
│   └── WristbandController.java       # 3 REST endpoints
├── domain/
│   ├── WristbandPrintRequest.java     # inbound DTO with Bean Validation
│   ├── WristbandData.java             # internal domain object
│   ├── WristbandPreviewResponse.java  # outbound DTO for ZPL text preview only (image preview returns ResponseEntity<byte[]> directly)
│   ├── PrintJob.java                  # job ID (UUID), request data, PrintJobStatus, timestamps
│   └── PrintJobStatus.java            # enum: PENDING, PRINTING, DONE, FAILED
├── service/
│   ├── WristbandLayoutService.java    # maps WristbandPrintRequest → WristbandData
│   ├── ZplGeneratorService.java       # WristbandData → ZPL string
│   ├── LogoConversionService.java     # PNG → ^GF bytes, cached at startup via @PostConstruct
│   ├── PrinterService.java            # ZPL string → TCP socket to Zebra printer
│   ├── PrintQueueService.java         # LinkedBlockingQueue + single worker thread + ConcurrentHashMap for job status
│   └── LabelaryPreviewService.java    # ZPL string → PNG via Labelary HTTP API
├── web/
│   └── (static resources — see below)
├── security/
│   └── ApiKeyAuthFilter.java          # OncePerRequestFilter — validates X-API-Key header
├── exception/
│   ├── GlobalExceptionHandler.java    # @RestControllerAdvice
│   ├── PrinterUnavailableException.java
│   ├── LogoNotFoundException.java
│   └── LabelaryUnavailableException.java
└── WristbandPrinterApplication.java
```

### Responsibility Boundaries

- `WristbandLayoutService` is the only class that knows about both `WristbandPrintRequest` and `WristbandData`. It translates between the API layer and domain.
- `ZplGeneratorService` knows only about `WristbandData` and `WristbandProperties`. No HTTP, no printing.
- `PrinterService` knows only about ZPL strings and `PrinterProperties`. No layout logic.
- `LabelaryPreviewService` knows only about ZPL strings and `LabelaryProperties`. No layout logic.
- `LogoConversionService` is called once at startup. No runtime dependencies on requests.
- `PrintQueueService` maintains a list of active `SseEmitter` instances and broadcasts a job update event to all connected clients on every status transition.

---

## API

### Authentication

Every request must include:
```
X-API-Key: <configured-key>
```

Missing or invalid key → `401 Unauthorized`. Configured via `security.api-key` in `application.yml`. Implemented as a Spring Security `OncePerRequestFilter`.

### Endpoints

#### POST /api/wristbands/print
Enqueues the print job and returns immediately. The job is processed asynchronously by a single background worker thread.

**Response:** `202 Accepted`
```json
{ "jobId": "550e8400-e29b-41d4-a716-446655440000", "status": "PENDING" }
```

#### GET /api/wristbands/jobs
Returns all jobs in the in-memory status map. Supports optional `?status=` query parameter to filter by `PENDING`, `PRINTING`, `DONE`, or `FAILED`.


**Response:** `200 OK`
```json
[
  { "jobId": "550e8400-...", "status": "DONE", "eventName": "Pukkelpop 2026", "submittedAt": "2026-05-21T10:00:00Z", "completedAt": "2026-05-21T10:00:03Z" },
  { "jobId": "661f9511-...", "status": "PENDING", "eventName": "Pukkelpop 2026", "submittedAt": "2026-05-21T10:00:05Z", "completedAt": null }
]
```

#### GET /api/wristbands/jobs/{jobId}
Returns the current status of a previously submitted print job. Symfony uses this to poll for completion.

**Response:** `200 OK`
```json
{ "jobId": "550e8400-e29b-41d4-a716-446655440000", "status": "DONE", "eventName": "Pukkelpop 2026", "submittedAt": "2026-05-21T10:00:00Z", "completedAt": "2026-05-21T10:00:03Z" }
```
or on failure:
```json
{ "jobId": "550e8400-e29b-41d4-a716-446655440000", "status": "FAILED", "error": "Could not connect to printer at 192.168.1.100:9100", "submittedAt": "2026-05-21T10:00:00Z", "completedAt": null }
```

Unknown job ID → `404 Not Found`.

#### GET /api/wristbands/jobs/stream
Server-Sent Events (SSE) endpoint. The job management page connects once and receives a push event every time any job changes status. Each event is a JSON-serialized `PrintJob`.

**Response:** `Content-Type: text/event-stream`
```
data: { "jobId": "550e8400-...", "status": "PRINTING", "eventName": "Pukkelpop 2026", ... }

data: { "jobId": "550e8400-...", "status": "DONE", "eventName": "Pukkelpop 2026", ... }
```

Implemented via Spring's `SseEmitter`. `PrintQueueService` holds a list of active emitters and notifies them on every job status transition.

#### POST /api/wristbands/jobs/{jobId}/reprint
Submits a new print job using the same request data as the original job. Returns a new job ID.

**Response:** `202 Accepted`
```json
{ "jobId": "new-uuid", "status": "PENDING" }
```

Unknown job ID → `404 Not Found`.

#### DELETE /api/wristbands/jobs/completed
Removes all `DONE` and `FAILED` jobs from the in-memory status map. Does not affect `PENDING` or `PRINTING` jobs.

**Response:** `204 No Content`

#### POST /api/wristbands/preview/zpl
Generates ZPL and returns it as plain text. Use this to paste into https://labelary.com/viewer.html for manual preview.

**Response:** `200 OK`, `Content-Type: text/plain`

#### POST /api/wristbands/preview/image
Generates ZPL, sends it to the Labelary API, and returns the rendered PNG.

**Response:** `200 OK`, `Content-Type: image/png`

### Request Body (all three endpoints)

```json
{
  "eventName": "Pukkelpop 2026",
  "firstName": "Jan",
  "lastName": "Janssens",
  "associationName": "STUP vzw",
  "barcodeValue": "123456789"
}
```

All fields are required. Validated with `@NotBlank`.

### Error Response Shape

```json
{
  "status": 400,
  "error": "Validation failed",
  "fields": {
    "firstName": "must not be blank"
  }
}
```

For non-validation errors:
```json
{
  "status": 503,
  "error": "Printer unavailable",
  "message": "Could not connect to printer at 192.168.1.100:9100"
}
```

### Error Mapping

| Situation | HTTP status | Error |
|---|---|---|
| Missing/blank request field | 400 | `Validation failed` + field details |
| Missing or invalid API key | 401 | `Unauthorized` |
| Logo not found at startup | 500 — app refuses to start | — |
| Logo not convertable at startup | 500 — app refuses to start | — |
| Printer unreachable (async) | job status `FAILED` + error message | — |
| Labelary unreachable | 503 | `Labelary unavailable` |
| Unknown job ID | 404 | `Job not found` |
| Unexpected error | 500 | `Internal server error` |

---

## ZPL Layout

### Label Dimensions

- Width: 1 inch = **203 dots** at 203 dpi
- Length: 11 inches = **2233 dots** at 203 dpi
- Orientation: vertical (label length = y-axis)

### Rotations

| Element | ZPL rotation | Description |
|---|---|---|
| Both STUP logos | `^FWI` | 180° inverted — upside down |
| Text fields (all 3 lines) | `^FWB` | 90° counter-clockwise (to the left) |
| Barcode | `^BCB` | 90° counter-clockwise — matches text direction |

### Layout (top = non-adhesive end, bottom = adhesive end)

```
┌─────────────────┐  y=0  (non-adhesive end)
│   STUP logo     │  ^FWI — 180° inverted
│                 │
│   [margin]      │  wristband.margins.between-logo-and-text (~150 dots)
│                 │
│   Event name    │  ^FWB, smaller font
│   First + Last  │  ^FWB, larger font
│   Association   │  ^FWB, smaller font
│   [centered]    │  calculated: x = (labelWidth - textWidth) / 2
│                 │
│   [margin]      │  wristband.margins.between-text-and-barcode (~150 dots)
│                 │
│   Barcode       │  ^BCB, Code 128
│   [HRI text]    │  human-readable text, configurable
│                 │
│   [small margin]│  wristband.margins.between-barcode-and-logo (~60 dots)
│                 │
│   STUP logo     │  ^FWI — 180° inverted
└─────────────────┘  y=2233 (adhesive end)
```

### Text Centering

ZPL has no native center alignment for `^FO`-placed fields. Centering is calculated per line:
```
x = (labelWidthDots - (charWidth × charCount)) / 2
```
This calculation lives in `ZplGeneratorService` as a private helper. Font character width is derived from the configured font size parameter. ZPL `^A0` font is roughly monospaced at the sizes used — centering is an approximation that will be fine-tuned against the physical printer.

### Logo Handling

- PNG loaded once at startup by `LogoConversionService` via `@PostConstruct`
- Converted to monochrome ZPL `^GF` format and cached in memory
- Logo scaled to fit within label width with configurable side margins
- Both logo placements reuse the same cached `^GF` data
- Logo path configured via `wristband.logo-path` — supports both `classpath:` and absolute file paths

---

## Configuration

### File Structure

```
src/main/resources/
├── application.yml           # shared base: structure, defaults, logging
├── application-local.yml     # local dev: test printer IP, local logo path, test API key
├── application-prod.yml      # production: real printer IP, server logo path, real API key
├── images/
│   └── stup-logo.png         # default logo (classpath fallback)
└── static/
    └── jobs.html             # job management page (vanilla HTML + CSS + JS)
```

### application.yml (base)

```yaml
server:
  port: 8080

security:
  api-key: changeme

printer:
  host: localhost
  port: 9100
  timeout-ms: 5000

wristband:
  width-dots: 203
  length-dots: 2233
  dpi: 203
  logo-path: classpath:images/stup-logo.png
  margins:
    top-dots: 40
    between-logo-and-text: 150
    between-text-and-barcode: 150
    between-barcode-and-logo: 60
  text:
    font-size-event: 20
    font-size-name: 28
    font-size-association: 20
  logo-side-margin-dots: 10
  barcode:
    type: CODE128
    height-dots: 100
    show-human-readable: true

labelary:
  base-url: http://api.labelary.com
  timeout-ms: 5000

springdoc:
  swagger-ui:
    enabled: true
  api-docs:
    enabled: true
```

### Profile Activation

- **Local:** `--spring.profiles.active=local` in IDE run config
- **Production:** `SPRING_PROFILES_ACTIVE=prod` environment variable (set in Docker Compose or server systemd unit)

---

## Docker & Infrastructure

### Multi-Stage Dockerfile

```
Stage 1 (build):   maven:3.9-eclipse-temurin-21 → mvn package -DskipTests
Stage 2 (runtime): eclipse-temurin:21-jre-alpine → copy JAR, EXPOSE 8080
```

The STUP logo is **not** baked into the image. It is mounted at runtime via a Docker volume, allowing logo updates without rebuilding the image.

### docker-compose.yml (example)

```yaml
services:
  wristband-printer:
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - SECURITY_API_KEY=${API_KEY}
    volumes:
      - /opt/stup/images:/opt/stup/images:ro
    restart: unless-stopped
```

An `.env.example` file documents the required `API_KEY` variable.

---

## Swagger UI

- **URL:** `http://localhost:8080/swagger-ui.html`
- **OpenAPI spec:** `http://localhost:8080/v3/api-docs`
- `X-API-Key` documented as a security scheme — authenticate in Swagger UI and test all endpoints interactively
- Enabled in all profiles; can be disabled in production via `springdoc.swagger-ui.enabled=false` in `application-prod.yml`

---

## Testing Strategy

**Framework:** JUnit 5 + Mockito + Spring Boot Test (`@WebMvcTest` for controller layer)

| Test class | Coverage |
|---|---|
| `ZplGeneratorServiceTest` | ZPL string contains correct commands, coordinates, rotation flags |
| `WristbandLayoutServiceTest` | Request → WristbandData mapping, all fields |
| `LogoConversionServiceTest` | PNG loads and converts correctly; missing file throws `LogoNotFoundException` |
| `PrinterServiceTest` | Happy path sends ZPL to socket; unreachable host throws `PrinterUnavailableException` (mocked socket) |
| `LabelaryPreviewServiceTest` | Happy path returns PNG bytes; HTTP error throws `LabelaryUnavailableException` (mocked `RestClient`) |
| `PrintQueueServiceTest` | Jobs processed in order, failed jobs record error message, job list filtering by status, worker shuts down cleanly via `@PreDestroy` |
| `WristbandControllerTest` | All endpoints: correct status codes, missing fields → 400, invalid API key → 401, unknown job ID → 404 |
| `GlobalExceptionHandlerTest` | Error response shape per exception type |

No integration tests against real printer or real Labelary — both are mocked at the service boundary.

---

## Logging

- Print requests logged at INFO level: event name + barcode value only
- First name, last name, and association name are **not** logged (unnecessary PII in logs)
- Printer connection attempts and failures logged at WARN/ERROR level
- Labelary errors logged at WARN level

---

## Extensibility

- New wristband layouts can be added by creating a new implementation of a `WristbandLayout` interface (or strategy pattern), without modifying `ZplGeneratorService`
- Barcode type is configurable — switching from Code 128 to another type requires only a config change, not code change
- Logo path is fully configurable — supports both classpath resources and absolute filesystem paths

---

## Job Management Page

A static HTML page served at `/jobs.html` from `src/main/resources/static/jobs.html`. No build step, no framework — vanilla HTML + CSS + JavaScript.

### Features

- **Live job table** — connects to `GET /api/wristbands/jobs/stream` (SSE) on page load; job rows update in real-time without any page refresh
- **Status filter** — dropdown to filter visible jobs by `PENDING`, `PRINTING`, `DONE`, `FAILED`, or All
- **Reprint button** — per row on `DONE` and `FAILED` jobs; calls `POST /api/wristbands/jobs/{jobId}/reprint`, new job appears in the table immediately via SSE
- **Clear completed button** — calls `DELETE /api/wristbands/jobs/completed`; removes all `DONE` and `FAILED` rows

### SSE behaviour

The page opens a single `EventSource` connection to `/api/wristbands/jobs/stream`. On each incoming event, it upserts the job row in the table (add if new, update status if existing). On SSE disconnect (network drop, server restart), the `EventSource` API automatically reconnects.

### Authentication on the page

The `X-API-Key` header cannot be set by `EventSource` (browser limitation). The management page is therefore served without API key enforcement — it is excluded from the `ApiKeyAuthFilter` by path (`/jobs.html`, `/api/wristbands/jobs/stream`). All other `/api/**` calls from the page (reprint, clear) include the key via `fetch()` with a header. The API key for the page is read from a JS config block at the top of `jobs.html`, populated from an environment variable at deploy time.

---

## Print Queue

### Design

`PrintQueueService` owns a `LinkedBlockingQueue<PrintJob>` and a single-thread `ExecutorService`. A `ConcurrentHashMap<UUID, PrintJob>` tracks all submitted jobs for status lookup.

**SSE broadcast:** on every job status transition, `PrintQueueService` loops over all active `SseEmitter` instances and sends the updated job as a JSON event. Completed/expired emitters are removed from the list automatically.

**Lifecycle:**
- Worker thread started via `@PostConstruct`
- Worker blocks on `queue.take()`, processes one job at a time
- On completion: updates job status to `DONE`
- On printer error: updates job status to `FAILED` with error message
- Shut down cleanly via `@PreDestroy` — worker finishes current job before stopping

**Job retention:** completed and failed jobs remain in the status map for the lifetime of the process. Jobs are lost on restart — operators can reprint if needed.

**Responsibility boundary:** `PrintQueueService` calls `WristbandLayoutService`, `ZplGeneratorService`, and `PrinterService` internally. The controller only interacts with `PrintQueueService`.

### Sequence

```
Symfony → POST /api/wristbands/print
        → PrintQueueService.enqueue(request)
        → returns { jobId, status: PENDING }  (202 Accepted)

[background worker]
        → PrintQueueService picks up job
        → updates status: PRINTING
        → WristbandLayoutService.buildData(request)
        → ZplGeneratorService.generate(data)
        → PrinterService.send(zpl)
        → updates status: DONE (or FAILED)

Symfony → GET /api/wristbands/jobs/{jobId}
        → returns { jobId, status: DONE }
```

---

## Out of Scope

- Printer status polling
- Authentication beyond static API key
- Wristband template management UI

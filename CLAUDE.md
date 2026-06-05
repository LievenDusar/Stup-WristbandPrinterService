# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project purpose

Java 21 / Spring Boot service that generates **ZPL** wristband labels for **Zebra** thermal
printers. It is called by the external STUP **Symfony** event application to print staff/visitor
wristbands at events. Beyond the print API it ships an admin **jobs UI**, a visual **template
designer**, and routing to **multiple printers**.

The canonical, deeper docs live in [README.md](README.md) and [docs/](docs/) — keep those in sync
when behavior changes. `docs/superpowers/` holds the design specs and step-by-step implementation
plans that drove each feature; read the relevant spec there before changing a subsystem.

## Architecture overview

The big idea: **one image, two roles**, selected by Spring profile. This is the single most
important concept to understand before editing anything.

- **management** role — active whenever the `worker` profile is *not* set (profiles `local` /
  `prod`). The only role with a UI, admin login, database (Postgres + Flyway), job history, SSE,
  the printer **registry**, ZPL rendering, and template designer. **One instance.** Beans in this
  role are annotated `@Profile("!worker")`.
- **worker** role (`worker` profile) — a thin, **DB-free, UI-free** service, **one per physical
  printer**. Exposes only the internal `POST /api/internal/print`, opens a raw TCP socket to its
  Zebra at `PRINTER_HOST:9100`, and reports success/failure. Reached only by management over the
  private Docker network. Beans annotated `@Profile("worker")`.

### Request flow (print)

1. Symfony (or the UI) → `POST /api/wristbands/print` on **management** (`WristbandController`).
2. `PrintQueueService.enqueue` resolves the target `Printer` from the `PrinterRegistry`
   (`printerId` from the request, or the default = first registered printer), persists the job,
   and offers it to that printer's **own** in-memory `BlockingQueue`.
3. A dedicated worker thread per printer dequeues, builds `WristbandData`
   (`WristbandLayoutService`), resolves ZPL (`WristbandZplResolver` → legacy `ZplGeneratorService`
   *or* `TemplateZplRenderer` when a `templateId` is set), then forwards the ZPL to the printer's
   worker via `WorkerClient.print(baseUrl, …)`.
4. The **worker** receives the ZPL (`WorkerPrintController`) and writes it to the Zebra socket
   (`PrinterService`, with retries + optional RAM-cache clear command).
5. Status changes (PENDING → PRINTING → DONE/FAILED) are persisted and broadcast over **SSE** to
   the jobs UI and to any per-job subscriber (Symfony follows a single job this way).

### Printer registry & routing

The registry is management config under `cluster.printers`: a list of
`{ id, display-name, base-url }`, one entry per printer/worker. `id` is the public `printerId`;
`base-url` is the worker's in-network address. `PrinterRegistry` validates the config at startup
(non-empty, no duplicate ids). Each printer gets its **own** queue + worker thread, so printers
print in parallel. Unknown `printerId` → **400**; per-printer queue full → **429**.

### Preview rendering

Previews (`/preview/image`, job/template previews) send the generated ZPL to the external
**Labelary** API and return a PNG. `WristbandZplResolver` is shared by the print path and every
preview path on purpose, so **what you preview is exactly what prints**.

### Template designer

Self-contained feature package `com.stup.wristbandprinter.editor` (auto-scanned). A declarative
JSON element model (`TemplateDefinition`, stored as `jsonb`) is the source of truth; a
`generated_zpl` snapshot is saved alongside for export/audit. Front-end is Konva.js + vanilla JS,
no build step. Full reference: [docs/template-designer.md](docs/template-designer.md).

## Technology stack

- **Java 21**, **Spring Boot 3.4.1** (web, validation, security, data-jpa, actuator)
- **PostgreSQL** + **Flyway** migrations (`src/main/resources/db/migration`, `V1`–`V5`)
- **Lombok**; **springdoc-openapi** (Swagger UI)
- **Micrometer** metrics via Actuator
- **JUnit 5** + **Testcontainers** (real Postgres in tests)
- **Maven** (wrapper `./mvnw`); **Docker** + Compose for all run modes
- Front-end: vanilla JS + Konva.js (`konva-9.3.20.min.js` vendored), no bundler

## Build and run instructions

A shared base image must be built **once** (and after editing `docker/base/Dockerfile`) before any
compose build:

```bash
./build.sh                         # builds wristband-base:21, the FROM base for the app image
```

Local dev — full virtual cluster (no host Java required), management + two fake printers:

```bash
docker compose -f docker-compose.local-cluster.yml up --build -d
# UI: http://localhost:8080/jobs.html   login: admin / local-admin
```

Production — one management (HTTPS 8443) + one worker per real Zebra, remote Postgres:

```bash
cp .env.example .env.prod && $EDITOR .env.prod   # every var is documented inline
docker compose -f docker-compose.prod.yml --env-file .env.prod up --build -d
```

IntelliJ / native run uses the `local` profile and the local Postgres from `docker-compose.yml`.
See [docs/running-locally.md](docs/running-locally.md) and
[docs/production-deployment.md](docs/production-deployment.md).

### Tests

```bash
./mvnw test                              # full suite — needs a running Docker daemon (Testcontainers)
./mvnw test -Dtest=PrintQueueServiceTest # single test class
./mvnw test -Dtest=PrintQueueServiceTest#methodName
```

Tests run persistence/integration against a real Postgres started by Testcontainers; the printer
(TCP socket) and Labelary (HTTP) are mocked. `surefire` pins `-Dapi.version=1.44` so docker-java
negotiates with modern daemons — bump if your daemon requires higher.

## Important business rules

- **Crash recovery:** on startup any job left `PENDING`/`PRINTING` by a previous run is marked
  `FAILED` ("Interrupted by service restart"). A half-printed wristband is **never** auto-reprinted;
  the operator reprints deliberately. (`PrintQueueService.recoverJobs`)
- **Cancel** is only valid while `PENDING`. Once the worker has taken the job → `409`.
- **Clear completed** is a **soft delete** (`deleted = true`) of DONE/FAILED/CANCELLED — rows stay
  in the database. Restore with `UPDATE print_jobs SET deleted = false WHERE job_id = '…';`.
- **Persist-before-enqueue:** a job row is saved *before* it is offered to the queue, to avoid the
  worker thread racing the submitter into a duplicate insert; a lost capacity race undoes the row.
- **Default printer = first** registered in `cluster.printers`. Missing `printerId` → default;
  unknown → `400`.
- **Per-printer queue depth** (`queue.max-depth`, default 100): exceeding it → `429`.
- **Prod safety gate:** under `prod` the app **refuses to start** if `security.api-key` is unset,
  blank, or still `changeme`.
- **API key everywhere except** `/api/wristbands/jobs/stream` and the static UI shells; the jobs UI
  authenticates via an **HttpOnly admin cookie** (no key in the browser). See `SecurityConfig`.
- **Defensive RAM-cache clear:** `printer.clear-command` (`^XA^IDR:*.*^FS^XZ`) is prepended to
  every job by default; wipes the printer's **RAM drive (R:)** only — no flash wear.
- **Print stays monochrome.** Template "colour" tints the **preview only** to judge contrast on
  coloured stock.
- **Legacy layout is the default.** `/print` without a `templateId` uses the fixed programmatic
  layout (logo → barcode → text → logo); zero breaking change for Symfony.
- Wristband geometry is fully config-driven (`wristband.*`) — there are no absolute coordinates to
  maintain; calibrate via YAML, not code. See [docs/configuration.md](docs/configuration.md).

## Folder structure

```
src/main/java/com/stup/wristbandprinter/
├── cluster/        Printer registry + WorkerClient (management→worker forwarding)
├── config/         @ConfigurationProperties, SecurityConfig, ApiKeyValidator
├── controller/     WristbandController, AuthController (management REST)
├── domain/         PrintJob, request/response DTOs, PrintJobStatus
├── editor/         Template designer feature package (domain/persistence/service/controller)
├── exception/      Custom exceptions + GlobalExceptionHandler (maps to HTTP status)
├── persistence/    JobStore / JpaJobStore, PrintJobEntity, repositories
├── security/       ApiKeyAuthFilter, AuthCookieService
├── service/        Queue, layout, ZPL generation, Labelary, printer socket
└── worker/         worker-profile controller, security, API-key filter
src/main/resources/
├── application*.yml           base + local / prod / worker profiles
├── db/migration/              Flyway V1–V5
└── static/                    jobs.html, login.html, template-editor.html, js/, css/
docs/                          README subpages + superpowers/ (specs & plans)
docker/                        base image + supporting Docker assets
```

## Coding conventions

- **Profile guards are load-bearing.** Management beans use `@Profile("!worker")`, worker beans
  `@Profile("worker")`. New beans must declare the correct profile or they will load in the wrong
  role (e.g. a DB bean must never load in a worker). Tests verify this (`WorkerProfileContextTest`).
- **Constructor injection** only (no field `@Autowired`); dependencies are `final`.
- **Configuration** is bound via typed `@ConfigurationProperties` classes in `config/` — add new
  settings there and to `application.yml`, never read raw `@Value` scattered around.
- **Errors → HTTP** are mapped centrally in `GlobalExceptionHandler`; throw a domain exception from
  `exception/` rather than returning status codes ad hoc. Error body shape:
  `{ status, error, message[, fields] }`.
- **Every feature ships with tests** mirroring the package path under `src/test/java`; persistence
  and controller layers are covered against real Postgres via Testcontainers.
- **Metrics & MDC:** long-running/printing paths add `jobId`/`printerId` to the SLF4J MDC and emit
  Micrometer counters/timers (`wristband.jobs.*`, `wristband.queue.depth`, `wristband.printer.send`).
- **Docs are part of the change.** Behavioral changes update README/`docs/`; new subsystems get a
  spec + plan under `docs/superpowers/`.
- Front-end editor is intentionally **build-step-free** vanilla JS — keep it that way; vendored libs
  go under `static/js/vendor/`.

## Known issues / limitations

- **Template renderer emits Code 128 regardless of selected symbology.** CODE39/QR are a planned
  renderer follow-up. (`TemplateZplRenderer`)
- **Barcodes render as a placeholder rectangle** on the editor canvas; the real symbol appears only
  in the PNG preview and on the printer.
- **In-memory job map + queues.** `PrintQueueService` keeps jobs/queues in memory (rebuilt from the
  DB on startup). The design assumes a **single management instance** — do not run management
  horizontally scaled without rework.
- Stray working files exist in the repo root (`wristband copy*.png`) — not used by the app.

## Current work in progress

Recent history (git + `docs/superpowers/plans`) shows the major features are **landed**:

- **Management/worker split** for multi-printer support — merged (plans `2026-06-04-mgmt-printer-split-1..4`).
- **Template designer** — all three plans implemented (persistence/API, rendering/assets/preview,
  Konva editor UI).
- **HTTPS self-signed prod**, **Postgres migration**, **soft-delete jobs**, **jobs detail drawer**.

The most recent commits are **documentation polish** (splitting/restyling README into `docs/`
subpages, layout diagram tweaks). There is no half-finished feature branch; `main` is clean.

## Recommended next steps

These are derived from the limitations above and the stated roadmaps — confirm priority with the
maintainer before starting:

1. **Multi-symbology barcode rendering** in `TemplateZplRenderer` (CODE39/QR) — the clearest open
   functional gap, already flagged as a planned follow-up.
2. **Editor canvas barcode rendering** so the designer WYSIWYG matches the printed output.
3. **Clean the repo root** of stray `wristband copy*.png` working files.
4. If multi-instance management ever becomes a requirement, move queue/job state out of memory
   (e.g. DB-backed claim or a broker) — the current model is explicitly single-instance.

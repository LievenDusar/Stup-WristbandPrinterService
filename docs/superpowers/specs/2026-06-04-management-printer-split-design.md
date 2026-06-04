# Management / printer-worker split

**Date:** 2026-06-04
**Status:** Approved (design)
**Supersedes:** `2026-06-04-printer-name-on-jobs-design.md` (the per-printer
labeling feature is folded into this design)

## Amendments (2026-06-04, during implementation)

Two decisions refine the original design without changing its intent:

1. **Coordination is a synchronous forward, not an async callback.** Management
   keeps its queue (one worker thread per printer), sets the job to `PRINTING`,
   and forwards the rendered ZPL to the worker over HTTP, deriving the outcome
   from the response: `2xx` → `DONE`, error/timeout → `FAILED`. The worker stays
   the thin synchronous service built in sub-plan 1 (returns `200`/`503`). This
   removes the worker-side queue, the callback API, the stuck-`PRINTING`
   reconciliation, and cancel-forwarding (a job waits in management's queue while
   `PENDING`, so cancel works unchanged). The observable status lifecycle
   (`PENDING → PRINTING → DONE/FAILED`) and the SSE stream are identical to the
   original local-printing behavior, and printing across multiple printers is
   still parallel (one thread per printer in phase 2). The async callback model
   remains a future option if very high throughput ever makes a blocked
   per-printer thread during printing a bottleneck.

2. **External (Symfony) status tracking via a per-job SSE endpoint (phase 2).**
   `POST /api/wristbands/print` already returns the `jobId` (HTTP 202); phase 2
   adds `printerId` + `printerName` to that response. To let Symfony follow one
   job's status, phase 2 adds `GET /api/wristbands/jobs/{jobId}/stream`: it emits
   the job's current status on connect, streams only that job's updates, and
   completes the SSE when the job reaches a terminal state
   (`DONE`/`FAILED`/`CANCELLED`). `GET /api/wristbands/jobs/{jobId}` remains as a
   polling fallback. Recommended consumption: Symfony's backend subscribes/polls
   and relays to its own UI so the API key stays server-side; a browser
   connecting directly would require a scoped token + CORS.

The phase-1 internal forward uses plain HTTP between containers on the private
Docker network (the worker's internal endpoint need not be HTTPS); only
management's public UI terminates TLS. Sub-plan 2 targets local correctness over
HTTP; production compose/TLS wiring for workers is a deploy task.

## Problem

Multiple printers are supported today by running one full print-service
container per printer, all sharing a single Postgres `wristbands` database.
Each container serves its own copy of the management UI (jobs page, template
editor, login). That has two problems:

- **Every container runs its own UI**, which multiplies the cross-origin,
  cookie (`SameSite=Strict`), HTTPS-cert and per-port complexity and gives the
  operator several near-identical URLs.
- **There is no clean way to re-route a reprint to a chosen printer**: a reprint
  enqueues to the local container's printer only, because the container that
  serves the page is the only one the browser talks to.

## Goal

Split the system into a single **management** container (control plane) and one
**printer-worker** container per physical printer (data plane):

- The operator uses **one** management URL for all UI and history.
- Printer-workers are minimal: receive bytes, drive the printer, report status.
- Print requests carry a target printer; reprints offer a printer choice
  (a list when there are several printers, automatic when there is one).
- Each job shows which printer it belongs to on the jobs page.

## Decisions

- **Coordination: forward + status callback.** Management owns the DB, creates
  the job record and forwards the print payload to the chosen worker. The worker
  reports status transitions (`PRINTING` / `DONE` / `FAILED`) back to management,
  which updates the DB and the SSE stream. Workers have no DB access.
- **Job intake: external system posts to management with a target printer.** The
  Symfony app posts the print request to the management container and includes
  the target `printerId` in the body (chosen based on where the print button is
  offered in Symfony).
- **ZPL is rendered in management, not in the worker.** Templates, rendering and
  Labelary preview all stay in management. The worker receives ready-to-print
  ZPL and only writes bytes to the printer. Workers carry no template logic.
- **Persist a stable `printer_id` plus a `printer_name` snapshot.** The id is the
  routing/filter key; the name snapshot keeps historical labels intact if a
  printer is later renamed or removed. (This replaces the "display name only"
  choice of the original labeling spec — routing requires a stable id.)
- **One codebase, two Spring profiles** (`management` / `worker`) selected with
  `@Profile`, the same Docker image with a different `SPRING_PROFILES_ACTIVE`.
  Reuses the existing base image and `build.sh`.
- **Reprint keeps the original job untouched.** Reprint creates a new job (new
  UUID) from the original request data, stamped with the chosen printer, and
  runs through the same forward flow. The original row is never mutated.
- **No backfill.** Legacy rows keep `printer_id`/`printer_name` `NULL` and render
  as `—` / an "Unknown" filter group.

## Architecture

### Management container (control plane, one instance)

- Serves all UI (jobs, template editor, login) and the editor APIs (templates,
  assets, preview). The only container with a UI and admin auth.
- Owns the Postgres DB, the job history and the SSE stream to the browser.
- Holds the **printer registry**: per printer a stable `id`, a `displayName` and
  an internal base URL (e.g. `https://printer-1:8443` on the Docker network).
- Front door for print requests; validates the target printer, creates the job,
  renders the ZPL, and forwards `{ jobId, zpl }` to the worker.
- Receives worker status callbacks and updates the DB + SSE.
- Offers the printer choice on reprint (list when multiple, automatic when one).

### Printer-worker (data plane, one per physical printer)

- Receives `{ jobId, zpl }` on an internal endpoint, keeps a local serial queue
  (a printer prints one band at a time), writes bytes to `PRINTER_HOST:9100`
  with the existing retry/backoff behavior.
- Reports `PRINTING` / `DONE` / `FAILED` (+ error text) back to management via an
  internal callback secured with the existing API key.
- No UI, no DB, no template/rendering logic.

### Printer registry

Static configuration in management: a list of printers, each with `id`
(stable slug), `displayName`, and internal `baseUrl`. Management exposes
`GET /api/wristbands/printers` returning `[{ id, displayName }]` for the UI to
build the reprint picker and the filter chips. Routing and forwarding use the
`baseUrl`.

## Data flows

**New print job**

1. Symfony posts the print request to management with `printerId` in the body.
2. Management validates `printerId` against the registry, creates a job record
   (status `PENDING`, stamped with `printer_id` + `printer_name`), persists it,
   and pushes SSE.
3. Management renders the ZPL and forwards `{ jobId, zpl }` to the worker's
   internal print endpoint (API key secured). The worker returns 202 Accepted.
4. The worker enqueues locally, prints, and POSTs status callbacks to management
   on each transition.
5. Management updates the DB and pushes SSE on each callback.

**Reprint**

1. Operator clicks reprint. The UI shows a printer picker when there are several
   printers; with one printer it is chosen automatically.
2. The UI posts to management's reprint endpoint with the chosen `printerId`.
3. Management creates a new job (new UUID) from the original request data,
   stamped with the chosen printer, then runs the same forward flow.
4. The original job row is untouched.

## Security & networking

- Management ↔ worker traffic uses the existing `SECURITY_API_KEY` in both
  directions (management → worker forward, worker → management callback).
- In production the management and worker containers share a Docker network and
  reach each other by service name; the registry `baseUrl`s use those names.
- Worker endpoints are internal only (not published) where possible.

## Failure handling

- Status reporting is now asynchronous (callback) instead of in-process. New
  failure paths to cover:
  - **Worker unreachable on forward:** the job is marked `FAILED` with a clear
    error; it is not left stuck in `PENDING`.
  - **Missing terminal callback:** a job that stays in `PRINTING` past a timeout
    is reconciled to `FAILED` so the queue does not leak.
  - **Callback for an unknown/old job:** ignored idempotently.

## Persistence

- `print_jobs` gains `printer_id` and `printer_name` columns.
- New migration `V5__add_printer_columns.sql`: nullable columns; existing rows
  stay `NULL`.
- The job persistence layer writes both on save and restores both on load.

## API responses

- `PrintJobResponse` and `PrintJobDetailResponse` gain `printerId` +
  `printerName` (table/SSE and drawer respectively).

## Frontend (served only by management)

- **Table:** sortable "Printer" column showing `printerName`; legacy rows show
  `—`. Bump the empty/loading `colspan` accordingly.
- **Drawer:** a "Printer" row in the job detail.
- **Filter chips:** a chip row keyed on `printerId` (label = `printerName`),
  built from the registry; jobs with `NULL` printer group under "Unknown".
- **Reprint:** a printer picker sourced from `GET /api/wristbands/printers`;
  auto-selected when there is exactly one printer.

## Packaging & deployment

- Two Spring profiles in one codebase: `management` and `worker`, combined with
  the existing env profiles (e.g. `prod,management` / `prod,worker`).
- `docker-compose.prod.yml`:
  - one `management` service (published HTTPS port, DB env, certs, registry
    config),
  - one `printer-n` worker service per printer (internal, `PRINTER_HOST`, API
    key, management callback URL, **no DB env**).
- `.env.example` and docs updated for the registry, the worker callback URL and
  the per-printer settings. Names/ids must be unique across printers.

## Implementation phases (each gets its own plan)

1. **Split + registry + internal forward/callback.** Introduce the two Spring
   profiles, the printer registry, the management→worker forward path and the
   worker→management status callback. Move ZPL rendering to management; make the
   worker a thin byte-writer. Wire the compose/profile/deploy changes. Still a
   single printer end to end.
2. **Multi-printer routing + `printerId` in the print API.** Accept and validate
   `printerId` on intake, route to the matching worker, and coordinate the
   Symfony API-contract change. Add `GET /api/wristbands/printers`.
3. **UI.** Printer column, drawer row, filter chips, and the reprint printer
   picker (auto-select with a single printer).

## Out of scope (YAGNI)

- Shared pull-queue dispatch (workers polling the DB) — superseded by the
  forward + callback decision.
- Dynamic printer discovery/registration — the registry is static config.
- Load balancing across printers / automatic printer selection rules.
- Backfilling printer ids/names onto historical rows.

## Risks & dependencies

- **External (Symfony) API contract changes** to carry `printerId` — depends on
  the integrating team.
- **Internal reachability and security** between management and workers must be
  correct (Docker network, API key both directions).
- **Asynchronous status** adds failure paths (worker down, missing callback)
  that the reconciliation logic above must cover.

## Testing

- Registry validation: unknown `printerId` is rejected with a clear error.
- Forward path: management renders ZPL and forwards to the correct worker
  `baseUrl`; worker enqueues and writes bytes.
- Callback path: worker status callbacks update the DB and emit SSE; unknown/old
  job ids are ignored idempotently; a stuck `PRINTING` job is reconciled to
  `FAILED`.
- Persistence round-trips `printer_id` + `printer_name`; legacy rows read `NULL`.
- Reprint creates a new job stamped with the chosen printer and leaves the
  original untouched.
- Manual: management + two workers with distinct ids/names; confirm intake
  routing, the table column, drawer row, filter chips and the reprint picker;
  legacy rows show `—`.

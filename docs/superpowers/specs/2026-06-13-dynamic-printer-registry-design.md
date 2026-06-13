# Dynamic printer registry (DB-backed + worker self-registration)

**Date:** 2026-06-13
**Status:** Approved (design)
**Related:** `2026-06-04-management-printer-split-design.md` (introduced the static,
config-driven registry this design replaces)

## Problem

Printers exist today only as **management configuration** (`cluster.printers`: a list of
`{ id, display-name, base-url }`, one entry per worker container). Two consequences:

1. **Denormalized job rows.** Every `print_jobs` row stores both `printer_id` **and**
   `printer_name`. The printer's human label is repeated on every job instead of living in one
   place.
2. **Static registration.** The set of printers is fixed at management startup. Adding a printer
   means editing `cluster.printers` and restarting management — there is no way for a printer to
   come and go at runtime, even though each printer is already its own Docker worker container.

## Goal

- Normalize printers into their own database table; jobs reference a printer by id (FK), the label
  is stored once.
- Make registration **dynamic and container-driven**: a worker registers itself with management on
  startup, so "a printer appears when its container is added" without editing management config or
  restarting it.

## Decisions

- **D1 — Drop `printer_name` from `print_jobs`; resolve via FK join.** Printers are **never
  hard-deleted** (only marked offline/inactive), so every historical job's FK always resolves. A
  *renamed* printer makes historical jobs show the new name; this is acceptable because it is the
  same physical device. We deliberately do **not** keep a per-job name snapshot — that would defeat
  the normalization.
- **D2 — Remove `cluster.printers` config as the source of truth.** Printers come entirely from
  registrations persisted in the DB. Management rebuilds its in-memory routing from the `printers`
  table on startup.
- **D3 — Worker self-registration over a new internal endpoint.** The worker carries its own
  identity and calls management; management is the only DB holder and owns the table. This
  introduces a **new network direction** (today workers never call management).
- **D4 — Threads are created on demand and never torn down** for the life of the management
  process. An offline printer's queue/thread simply fails sends via the existing
  `PrinterUnavailableException` path; queued jobs are not silently dropped.
- **D5 — Default printer = earliest-registered printer that is currently online** (fallback:
  earliest-registered). An empty cluster (nothing registered yet) rejects print requests cleanly.

## Architecture

### Data model

New table (Flyway **V9** — current head is V8):

```
printers
  id             text  PRIMARY KEY        -- public printerId, e.g. "printer-1"
  display_name   text  NOT NULL
  base_url       text  NOT NULL           -- worker's in-network address (supplied at registration)
  online         boolean NOT NULL DEFAULT false
  last_seen_at   timestamptz
  registered_at  timestamptz NOT NULL DEFAULT now()
```

`print_jobs`:
- Add FK `print_jobs.printer_id REFERENCES printers(id)`.
- **Drop** `print_jobs.printer_name`.

**Migration order (single V9 script):**
1. Create `printers`.
2. Backfill: `INSERT INTO printers (id, display_name, base_url, online, registered_at)
   SELECT DISTINCT printer_id, printer_name, '', false, now() FROM print_jobs WHERE printer_id IS NOT NULL`
   (`base_url` filled in when the worker next registers).
3. Add the FK constraint (now valid for all existing rows).
4. Drop `print_jobs.printer_name`.

### Management (control plane, one instance)

- **`PrinterRegistry`** becomes DB-backed and mutable, thread-safe:
  - On startup: load all rows from `printers` into the in-memory map and create a queue + worker
    thread for each (so a cold start before any worker re-registers can still accept/queue jobs).
  - `register(id, displayName, baseUrl)`: upsert the row (`online=true`, refresh `last_seen_at`,
    `base_url`), add to the in-memory map if new, and tell `PrintQueueService` to ensure a
    queue + `processQueue` task exists for that id.
  - `markOffline(id)` / heartbeat staleness sweep: set `online=false`; the row and queue persist.
  - `get(id)`, `all()`, `getDefault()` keep their current contracts (`getDefault()` re-defined per
    D5; `get(unknown)` still throws `UnknownPrinterException` → 400).
- **`PrintQueueService`**:
  - Worker `ExecutorService` switches from a fixed-size pool to a **cached pool**; a new printer
    triggers `worker.submit(() -> processQueue(queueFor(id)))`. `queueFor` already lazily creates
    the `BlockingQueue`, so no change there.
  - `enqueue` with an empty cluster throws a new `NoPrintersAvailableException` → **503**.
  - The print loop is unchanged: it still resolves `base_url` per job via `printerRegistry.get(...)`,
    which now returns the current (DB-backed) value.
- **New endpoint** `POST /api/internal/printers/register` (management, `@Profile("!worker")`),
  body `{ id, displayName, baseUrl }`, secured by the existing shared API key
  (`ApiKeyAuthFilter`). Idempotent upsert. Returns 200. A matching heartbeat path (same endpoint,
  re-POST) refreshes liveness; an optional `POST /api/internal/printers/{id}/deregister` marks
  offline on graceful worker shutdown.

### Printer-worker (data plane, one per printer)

- New env vars: `PRINTER_ID`, `PRINTER_DISPLAY_NAME`, `WORKER_BASE_URL` (its own reachable address),
  `MANAGEMENT_BASE_URL` (where to register). Keeps the existing shared API key and `PRINTER_HOST`.
- New `@Profile("worker")` `ApplicationRunner` registers on startup via a small client, then a
  scheduled **heartbeat** re-asserts registration every N seconds (config `worker.heartbeat`).
  Best-effort deregister on shutdown (`@PreDestroy`). Registration failures are logged and retried;
  the worker still serves `/api/internal/print`.

## Data flows

**Registration:** worker boots → POST `/api/internal/printers/register` → management upserts row,
sets `online=true`, ensures queue+thread → 200. Heartbeat repeats periodically.

**Print (unchanged downstream):** Symfony/UI → `/crew/print` or `/permit/print` → `enqueue`
resolves printer from the (now DB-backed) registry → per-printer queue → `processQueue` renders ZPL
and forwards to `base_url` via `WorkerClient`.

## Security & networking

- The registration endpoint is internal and behind the shared API key, like `/api/internal/print`.
- Workers must reach management on the private Docker network (`MANAGEMENT_BASE_URL`). Plain HTTP
  between containers is fine; only management's public UI terminates TLS.

## Failure handling

- **Worker offline:** heartbeat stops → staleness sweep flips `online=false`. Row + queue persist;
  jobs routed to it fail at send time via `PrinterUnavailableException` → job `FAILED` (existing
  behavior). The operator reprints deliberately.
- **Empty cluster:** `enqueue` → `NoPrintersAvailableException` → 503.
- **Crash recovery:** unchanged — interrupted `PENDING`/`PRINTING` jobs become `FAILED` on startup;
  queues are rebuilt from the `printers` table.

## Persistence

- `JpaJobStore.loadActive()` resolves `display_name` via join `print_jobs → printers` when mapping
  entity → `PrintJob`, so the in-memory `PrintJob` still carries the label for SSE/responses without
  storing it on the job row.
- `PrintJobResponse.printerName` is unchanged in shape (resolved via the join).

## API responses

- `GET /api/wristbands/printers` reads the `printers` table (adds `online` / `lastSeenAt`).
- Jobs list/detail responses unchanged in shape; `printerName` now comes from the join.

## Frontend (management only)

- Jobs UI gains an **online/offline** indicator for printers (the printer filter already exists).
  Minimal — no new admin CRUD screen in this design (registration is container-driven).

## Packaging & deployment

- `docker-compose.local-cluster.yml` and `docker-compose.prod.yml`: remove the
  `SPRING_APPLICATION_JSON` `cluster.printers` block from management; add `PRINTER_ID`,
  `PRINTER_DISPLAY_NAME`, `WORKER_BASE_URL`, `MANAGEMENT_BASE_URL` to each worker.
- `application.yml`: drop `cluster.printers`; add `worker.heartbeat` defaults.

## Implementation phases (each gets its own plan)

1. **Table + normalization + DB-backed registry seeded from config.** V9 migration & backfill;
   `printers` entity/repo; `PrinterRegistry` loads from DB; `JpaJobStore` join; drop `printer_name`.
   Registry is still *seeded from `cluster.printers` on first boot* so behavior is unchanged — no
   self-registration yet. Fully shippable on its own.
2. **Self-registration + dynamic queues + remove `cluster.printers`.** Registration endpoint;
   worker registration runner + heartbeat; cached executor + on-demand queue creation; empty-cluster
   503; default-printer redefinition; compose/env changes; UI online indicator.

## Out of scope (YAGNI)

- Admin CRUD UI for printers (rename/delete from the browser).
- Operator-settable default printer (a `is_default` flag).
- Tearing down queues/threads when a printer is removed.
- Multi-instance / horizontally-scaled management (still single-instance; in-memory queues).

## Risks & dependencies

- **New worker→management call path** — workers previously never called management; verify Docker
  network reachability and that the registration endpoint is covered by the API-key filter, not the
  admin-cookie auth.
- **Migration is destructive** (drops `printer_name`). Backfill must run before the FK + column drop.
- **Concurrency** around mutable registry + cached executor — registration and the print loop touch
  shared maps; use the existing `ConcurrentHashMap` patterns and guard queue/thread creation so a
  printer never gets two `processQueue` tasks.

## Testing

- V9 migration + backfill against real Postgres (Testcontainers): existing jobs keep a resolvable
  printer; FK holds; `printer_name` gone.
- Registration endpoint: upsert is idempotent; creates queue/thread; API-key required.
- Worker registration runner + heartbeat (`@Profile("worker")`); `@Profile` guard tests
  (`WorkerProfileContextTest`) confirm the endpoint is management-only and the runner worker-only.
- Dynamic queue creation: a registration for a new id makes `enqueue` route to it.
- Empty cluster → 503; default-printer selection per D5.
- Jobs response resolves `printerName` via join after `printer_name` is dropped.

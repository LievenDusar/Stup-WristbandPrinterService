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
- Give operators a **"Manage printers" modal** in the management UI to view registered printers and
  **rename** them. *Adding* a printer is not done here — that is registering a new worker container.
  A rename **propagates live to the jobs table** (the printer-name column updates without a page
  refresh).

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
- **D5 — Default printer = earliest-registered printer that is currently online and not hidden**
  (fallback: earliest-registered non-hidden). An empty cluster (nothing registered yet) rejects
  print requests cleanly.
- **D6 — Printer changes propagate live via a new named SSE `printer` event.** When a printer is
  renamed (or registers / goes online-offline), management broadcasts a small `printer` event on the
  existing jobs SSE stream carrying the printer's current `{ id, displayName, online, lastSeenAt }`.
  The jobs UI keeps a client-side `printersById` map (seeded from `GET /printers`, upserted on each
  `printer` event) and renders the name column from that map keyed by `job.printerId` — so one event
  updates every matching row and the filter dropdown label at once, with no refresh and no re-send
  of jobs. This reuses and reinforces D1 (the displayed name is always the printer's *current* name).
- **D7 — The modal renames and soft-hides; it does not add or hard-delete.** Add = a new worker
  container (D3). Hard delete is excluded (would break the FK / history). The modal **can hide an
  offline printer** (soft `hidden=true`): a hidden printer is dropped from the printer filter and
  the default-printer selection (D5), but its row is kept so historical jobs still resolve its name.
  Hide is **auto-reversing** — any registration/heartbeat (i.e. the printer comes back online)
  **clears `hidden`** and the printer reappears. Hide is therefore only offered for *offline*
  printers; it cannot stick for an online one.
- **D8 — On-demand liveness probe ("Test").** Each modal row has a **Test** action:
  `POST /api/wristbands/printers/{id}/test` makes management probe the worker's
  `base_url` health endpoint right now and report reachable/unreachable, updating
  `online`/`last_seen_at` and broadcasting a `printer` event. A successful probe on a hidden printer
  brings it back (clears `hidden`). This lets the operator verify a printer without waiting for the
  heartbeat window. (Scope: connectivity/health probe, **not** a physical test print.)

## Architecture

### Data model

New table (Flyway **V9** — current head is V8):

```
printers
  id             text  PRIMARY KEY        -- public printerId, e.g. "printer-1"
  display_name   text  NOT NULL
  base_url       text  NOT NULL           -- worker's in-network address (supplied at registration)
  online         boolean NOT NULL DEFAULT false
  hidden         boolean NOT NULL DEFAULT false   -- soft-hide; auto-cleared when the printer comes online (D7)
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
  - `register(id, displayName, baseUrl)`: upsert the row (`online=true`, `hidden=false` — see D7
    auto-unhide — refresh `last_seen_at`, `base_url`), add to the in-memory map if new, and tell
    `PrintQueueService` to ensure a queue + `processQueue` task exists for that id.
  - `markOffline(id)` / heartbeat staleness sweep: set `online=false`; the row and queue persist.
  - `setHidden(id, true)` (offline only) and `rename(id, displayName)`: mutate the row + in-memory
    map and broadcast a `printer` event.
  - `get(id)`, `all()`, `getDefault()` keep their current contracts (`getDefault()` re-defined per
    D5; `get(unknown)` still throws `UnknownPrinterException` → 400).
- **`PrintQueueService`**:
  - Worker `ExecutorService` switches from a fixed-size pool to a **cached pool**; a new printer
    triggers `worker.submit(() -> processQueue(queueFor(id)))`. `queueFor` already lazily creates
    the `BlockingQueue`, so no change there.
  - `enqueue` with an empty cluster throws a new `NoPrintersAvailableException` → **503**.
  - The print loop is unchanged: it still resolves `base_url` per job via `printerRegistry.get(...)`,
    which now returns the current (DB-backed) value.
- **New internal endpoint** `POST /api/internal/printers/register` (management, `@Profile("!worker")`),
  body `{ id, displayName, baseUrl }`, secured by the existing shared API key
  (`ApiKeyAuthFilter`). Idempotent upsert. Returns 200. A matching heartbeat path (same endpoint,
  re-POST) refreshes liveness; an optional `POST /api/internal/printers/{id}/deregister` marks
  offline on graceful worker shutdown. Each register/heartbeat/offline transition that changes the
  printer's public state broadcasts a `printer` SSE event (D6).
  - **Note on `displayName` precedence:** registration sets `display_name` only when the row is
    first created; on subsequent (re)registrations the worker's `PRINTER_DISPLAY_NAME` does **not**
    overwrite an operator's rename. The worker is the source of identity (`id`) and address
    (`base_url`); the operator is the source of the label once renamed.
- **New admin endpoints** (management, **admin-cookie** auth like the other jobs-UI endpoints —
  *not* the API key); each broadcasts a `printer` SSE event so every connected jobs UI updates live
  (D6); unknown id → 404:
  - `PATCH /api/wristbands/printers/{id}` body `{ displayName }` — rename; validates non-blank.
  - `POST /api/wristbands/printers/{id}/hide` — soft-hide (D7); rejected with 409 if the printer is
    currently online (hide is offline-only).
  - `POST /api/wristbands/printers/{id}/test` — on-demand liveness probe (D8): management calls the
    worker's `base_url` health endpoint, updates `online`/`last_seen_at` (and clears `hidden` on
    success), and returns `{ reachable, online }`.

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

- `GET /api/wristbands/printers` reads the `printers` table; response includes
  `{ id, displayName, online, hidden, lastSeenAt }` (the jobs UI seeds its `printersById` map from
  this). The printer **filter** dropdown excludes hidden printers; the manage-printers modal shows
  them (so they can be tested/un-hidden).
- `PATCH /api/wristbands/printers/{id}` → 200 with the updated printer; 404 unknown; 400 blank name.
- `POST …/{id}/hide` → 200; 409 if online. `POST …/{id}/test` → 200 `{ reachable, online }`.
- Jobs list/detail responses unchanged in shape; `printerName` now comes from the join.

## SSE events

The global jobs stream `GET /api/wristbands/jobs/stream` now carries **two** event types:
- **(existing) unnamed job event** — `data:` is a `PrintJobResponse`; consumed via `onmessage`.
- **(new) `printer` event** — `event: printer`, `data:` is
  `{ id, displayName, online, hidden, lastSeenAt }`; consumed via `addEventListener('printer', …)`.
  Backward compatible (named events don't reach `onmessage`). Broadcast on rename, hide,
  register/heartbeat, offline, and test-probe results.

The per-job stream `GET /api/wristbands/jobs/{jobId}/stream` (Symfony) is **unaffected** — it still
emits only that job's status; a mid-job rename is an ignorable edge case there.

## Frontend (management only)

- **Client-side printers map.** `jobs.js` keeps `printersById`, seeded from `GET /printers` on load
  and upserted on every `printer` SSE event. The jobs table renders the printer-name column from
  `printersById[job.printerId]?.displayName` (falling back to the job's value), so a rename or
  online/offline change repaints all matching rows and the printer-filter option label **without a
  refresh** and without collapsing an open filter menu (reuse the existing "rebuild options only
  when the set changes" guard — a rename changes a label in place, not the set).
- **"Manage printers" modal**, opened from the existing top **Menu** dropdown, styled with
  `app.css` (deep-purple glass theme, no build step). Lists **all** printers (including hidden) with
  their `online/offline` status, `lastSeenAt`, an inline-editable `displayName` with **Save**
  (→ `PATCH`), a **Test** button (→ `…/test`, shows reachable/unreachable inline), and a **Hide**
  action for offline printers (→ `…/hide`; disabled while online). The modal updates live from the
  same `printer` SSE events. Adding is intentionally absent — a tooltip/hint states "add a printer by
  starting its worker container."
- Jobs UI also gains a small **online/offline** indicator for printers (the filter already exists).

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
2. **Self-registration + dynamic queues + remove `cluster.printers`.** Registration endpoint
   (+ `printer` SSE broadcast); worker registration runner + heartbeat; cached executor + on-demand
   queue creation; empty-cluster 503; default-printer redefinition; compose/env changes.
3. **Manage-printers modal + live propagation.** Rename / hide / test endpoints; `hidden` column;
   `printer` SSE event end to end; `jobs.js` `printersById` map + name column rendered from it; the
   modal in the Menu dropdown (rename, Test, Hide); online/offline indicator; filter excludes hidden.

## Out of scope (YAGNI)

- *Adding* a printer from the browser (add = a worker container) and **hard delete** of a printer.
- Operator-settable default printer — no `is_default` flag and **no "set as default" action in the
  manage-printers modal**; the default stays the earliest-registered online printer (D5).
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
- `PATCH` rename: persists `display_name`, 404 unknown, 400 blank; re-registration does not
  overwrite an operator rename (displayName precedence).
- Hide (D7): `hide` on an offline printer sets `hidden=true` and drops it from `GET /printers`'
  filter set + default selection; `hide` on an online printer → 409; a subsequent
  register/heartbeat **auto-clears** `hidden`.
- Test probe (D8): reachable worker → `online=true`, `last_seen_at` refreshed, `hidden` cleared,
  `{ reachable:true }`; unreachable → `online=false`, `{ reachable:false }`; both broadcast `printer`.
- `printer` SSE event is broadcast on rename/hide/register/offline/test and is a named event (does
  not reach `onmessage`). Front-end behavior (modal rename/Test/Hide + live cell/filter update)
  verified via the preview tools (vanilla JS, no JS test harness).

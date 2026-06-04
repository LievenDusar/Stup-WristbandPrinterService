# Per-printer name on the jobs page

**Date:** 2026-06-04
**Status:** Superseded by `2026-06-04-management-printer-split-design.md`
(the per-printer labeling is folded into that larger design; this spec is kept
for history only)

## Problem

Multiple printers are supported by running one print-service container per
printer (`docker-compose.prod.yml`), each pointing at its own physical printer
via `PRINTER_HOST`. All containers share a single Postgres `wristbands`
database, so the jobs page (`jobs.html`) already aggregates jobs from every
printer — but there is currently no way to tell which printer a job belongs to.

## Goal

Let each container declare a human-readable display name in its YAML/env config,
stamp that name onto every job it creates, and surface it on the jobs page:

- a short printer label in the jobs **table** (new sortable column),
- the printer name in the job **detail drawer**,
- a **filter chip** row to view jobs from one printer at a time.

## Decisions

- **Config carries a display name only** — no stable printer ID. The display
  name is the sole identifier and doubles as the filter key, so each container
  **must** use a unique `PRINTER_DISPLAY_NAME`. This constraint is documented in
  the config files.
- **Reprint keeps the original job untouched.** Reprint already creates a brand
  new job (`WristbandController.reprint` → `enqueue(original.getRequest())` →
  `new PrintJob(UUID.randomUUID(), ...)`), so the original row is never mutated
  and the reprint is naturally stamped with the name of the container that
  handled it (the printer it physically came out of). No special-casing.
- **No backfill.** Existing rows keep `printer_name = NULL` and render as `—`.

## Design

### 1. Config (YAML + env)

- `PrinterProperties` gains a `displayName` field, default `"Printer"`.
- `application.yml` adds `printer.display-name: Printer` under the existing
  `printer:` block.
- Both compose files wire a `PRINTER_DISPLAY_NAME` env var (Spring relaxed
  binding maps it to `printer.display-name`):
  - `docker-compose.prod.yml`: per-service (not in the shared `x-printer-env`
    anchor, since each printer needs a distinct value); add to the `printer-1`
    service and the commented `printer-2` template block.
  - `docker-compose.yml`: add for the local service.
  - `.env.example`: add `PRINTER1_DISPLAY_NAME=` with a comment noting names
    must be unique across printers.

### 2. Stamp at enqueue

The display name is server config, not request data, so it lives on the job,
not on `WristbandPrintRequest`.

- `PrintJob` gains a `printerName` field, set via the constructor and the
  `restore(...)` factory.
- `PrintQueueService.enqueue()` reads `printerProperties.getDisplayName()` and
  passes it into the new `PrintJob`. This is the single stamping point; reprint
  flows through `enqueue()` too. `PrinterProperties` is injected into
  `PrintQueueService` if not already present.

### 3. Persistence

- `PrintJobEntity` gains a `printerName` column.
- New migration `V5__add_printer_name.sql`: add a nullable `printer_name`
  column; existing rows stay `NULL`.
- `JpaJobStore.save()` writes `job.getPrinterName()`; `JpaJobStore.toDomain()`
  reads it back into `PrintJob.restore(...)`.

### 4. API responses

- `PrintJobResponse` gains `printerName` (used by the table and the SSE stream).
- `PrintJobDetailResponse` gains `printerName` (used by the drawer).
- `PrintJob.toResponse()` / `toDetailResponse()` include the field.

### 5. Frontend (`jobs.html` + `jobs.js`)

- **Table:** add a sortable "Printer" column (`sortBy('printerName')`) showing
  the short name; `NULL`/legacy rows render `—`. Bump the empty/loading
  `colspan` from 7 to 8.
- **Drawer:** add a `['Printer', d.printerName]` row in `showDetail`.
- **Filter chips:** render a second chip row below the status chips, built from
  the distinct `printerName` values present in the loaded jobs. Add a
  `printerFilter` state variable combined with `statusFilter` and the search box
  in `render()`. A job with `NULL` printerName is grouped under an "Unknown"
  chip. Clicking a chip toggles the filter, mirroring the status chip behavior.

## Out of scope (YAGNI)

- Stable printer ID separate from the display name.
- Re-routing a reprint to an operator-chosen printer.
- Backfilling printer names onto historical rows.

## Testing

- `PrintQueueService` enqueues a job stamped with the configured display name.
- `JpaJobStore` round-trips `printerName` (save → loadActive).
- Migration applies cleanly on an existing DB; legacy rows read back `NULL`.
- Manual: two containers with distinct `PRINTER_DISPLAY_NAME`, confirm the
  table column, drawer row, and filter chips separate jobs by printer; legacy
  rows show `—`.

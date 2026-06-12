# Copies per print job — design

**Date:** 2026-06-12
**Status:** Approved (pending spec review)

## Problem

The print API and jobs UI submit exactly one physical wristband per job. Events
routinely need many identical bands at once (e.g. 120 permit bands for a campsite
resource). Today an operator (or Symfony) would have to fire one request per band,
which floods the queue, hits the per-printer `queue.max-depth` (100), and is slow
(one socket send per band).

## Goal

Let a caller specify how many physical copies a single job should print. Default is
**1** (zero behavioural change for existing callers). The number is free to choose up
to a configurable cap. One job submission = one job row in the UI, regardless of the
copy count.

## Approach

Zebra ZPL has a native print-quantity command, `^PQ`. One ZPL stream with `^PQ<n>`
makes the printer itself emit `n` copies of the label. We use that:

- **One** `PrintJob`, **one** queue entry, **one** socket send, **one** status row.
- The printer iterates the copies — fast and queue-friendly.

Alternatives considered and rejected:

- *N separate jobs* — pollutes the jobs list, collides with `queue.max-depth`, and is
  N socket sends slower. Rejected.
- *Worker loops the send N times* — one row but N sends; loses the efficiency of `^PQ`
  and spreads the copy concept into the worker. Rejected.

### Critical constraint: previews must stay single-label

`WristbandZplResolver.resolve(...)` is shared by the print path **and** every preview
path on purpose ("what you preview is exactly what prints"). Baking `^PQ` into the
resolver would make Labelary render `n` pages for a preview. Therefore the copy count
is applied **only on the print path**, after `resolve(...)` and before forwarding to
the worker. The resolver, all preview endpoints, the worker, and `PrintForwardRequest`
are unchanged.

## Detailed design

### 1. Field name and semantics

- Field name: **`copies`** (matches the English codebase; aligns with ZPL `^PQ` =
  Print Quantity).
- Type on the wire/DTO: `Integer` (nullable). `null` means "not supplied" → default 1.
- Normalised accessor on the domain returns a plain `int` (never null).

### 2. Domain / request DTOs

- `PrintableRequest` (sealed interface) gains:
  - `int getCopies()` — implementations normalise `null` → `1`.
  - `PrintableRequest withCopies(int copies)` — wither, symmetric with the existing
    `withPrinterId`, used by the reprint override.
- `WristbandPrintRequest` and `PermitWristbandPrintRequest` each gain:
  - `private Integer copies;` with `@Min(value = 1, message = "copies must be at least 1")`.
    (`@Min` treats `null` as valid, so omitting the field = default.)
  - getter/setter; `copies` copied through in `withPrinterId(...)`; `withCopies(...)`
    returns a copy with the new value.
  - `getCopies()` returns `copies == null ? 1 : copies`.

### 3. ZPL `^PQ` — print path only

New pure-functional utility `com.stup.wristbandprinter.service.ZplCopies`:

```
static String apply(String zpl, int copies)
```

- `copies <= 1` → returns `zpl` unchanged.
- Otherwise inserts `^PQ<copies>,0,0,Y` immediately before the **last** `^XZ`.
  (`^PQq,p,r,o`: q=quantity, p=pause-between-groups 0, r=replicates 0, o=override-pause
  Y so the printer doesn't pause between bands on a continuous roll.)
- If no `^XZ` is present (should never happen — generators always emit one), append
  `^PQ<copies>,0,0,Y` defensively; covered by a test asserting generators end with `^XZ`.

Call site: `PrintQueueService.processQueue`:

```
String zpl = wristbandZplResolver.resolve(job.getRequest());
zpl = ZplCopies.apply(zpl, job.getRequest().getCopies());
workerClient.print(printer.baseUrl(), job.getJobId(), zpl);
```

The worker's clear-cache command (`^XA^IDR:*.*^FS^XZ`) is prepended as its own format
block in `PrinterService.send`; `^PQ` lives only in the label block, so the clear still
happens once and the label prints `n` times.

### 4. Configurable cap

- New `@ConfigurationProperties("print")` class `PrintProperties` with
  `int maxCopies` (default **200**).
- `application.yml`: `print.max-copies: 200`.
- `PrintQueueService.enqueue(...)` validates `request.getCopies() <= maxCopies`; on
  violation throws a new `InvalidCopiesException` mapped to **400** in
  `GlobalExceptionHandler` (body: `{ status, error, message }`). Enforced at the single
  enqueue choke-point, so the reprint path is covered too.
- Lower bound (`>= 1`) is enforced by `@Min(1)` Bean Validation on the request body.

### 5. Persistence

- Flyway `V7__add_copies.sql`:
  `ALTER TABLE print_jobs ADD COLUMN copies integer NOT NULL DEFAULT 1;`
- `PrintJobEntity`: add `private Integer copies;` column, constructor param, getter.
- `JpaJobStore.save(...)`: persist `r.getCopies()`.
- `JpaJobStore.toDomain(...)`: set `copies` on the rebuilt CREW/PERMIT request.

### 6. API responses

- `PrintJobResponse` and `PrintJobDetailResponse` gain `int copies`.
- `PrintJob.toResponse()` / `toDetailResponse()` populate it from `request.getCopies()`.

### 7. Reprint endpoint

- `POST /api/wristbands/jobs/{jobId}/reprint` gains optional `@RequestParam Integer copies`.
- When supplied, the re-enqueued request is overridden via `req.withCopies(copies)`
  (applied alongside the existing optional `printerId` override). When omitted, the
  original job's `copies` carries over.

### 8. UI (`static/js/jobs.js`)

- **Reprint dialog**: extend the existing confirm-overlay reuse (today it only asks for
  a printer) to also include a number input for copies, pre-filled with the original
  job's `copies`. Resolves to `{ copies, printerId }` or `null` (cancel). The fetch
  appends `?copies=N` (and `printerId` when chosen).
- **Detail drawer**: add a `Copies` row to the "Printing" section.
- **Table row**: when `job.copies > 1`, render a subtle `×N` pill after the name.
- No new page-level CSS — reuse `app.css` tokens/classes (e.g. existing pill/badge and
  input styles). No build step.

### 9. Tests

- `ZplCopiesTest`: `copies=1` → unchanged; `copies=5` → `^PQ5,0,0,Y` before the final
  `^XZ`; placement when multiple `^XZ` present; generators end with `^XZ`.
- `PrintQueueServiceTest`: enqueue persists `copies`; `copies > maxCopies` →
  `InvalidCopiesException`.
- Persistence round-trip (Testcontainers): save + load preserves `copies`.
- Controller tests: crew and permit print accept `copies` in the body;
  `copies < 1` → 400; reprint with `?copies=` overrides.
- Default behaviour: request without `copies` → job has `copies == 1`, ZPL has no `^PQ`.

### 10. Docs

- `CLAUDE.md`: business rule (copies default 1, `^PQ` print-path only, cap
  `print.max-copies`) and a note in the request-flow section.
- `docs/configuration.md`: document `print.max-copies`.
- `HANDOVER.md`: dated section.
- `application.yml`: `print.max-copies`.

## Out of scope

- A "new print" form in the UI (none exists today; Symfony submits fresh prints). Copies
  are operator-settable only via reprint.
- Per-copy status / partial-batch reporting — a job is DONE when the worker accepts the
  `^PQ` stream; the printer's per-band success is not individually tracked (same as
  today for a single band).
- Pause/peel (`^PQ` group pause) configuration — fixed to `,0,0,Y` (no pause) for
  continuous wristband rolls.

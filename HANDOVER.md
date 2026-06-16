# Project handover — STUP Wristband Printer Service

**Prepared:** 2026-06-05

> **Scope & provenance.** This document was reconstructed from the project's prior Claude Code
> sessions and the design records under [`docs/superpowers/specs`](docs/superpowers/specs) and
> [`docs/superpowers/plans`](docs/superpowers/plans). The session in which it was written produced
> only [CLAUDE.md](CLAUDE.md); the decisions and rejected approaches below were made in earlier
> sessions. Where a fact could not be confirmed against the current code it is flagged as such —
> nothing here is assumed.
>
> For architecture, conventions, and business rules aimed at a coding agent, see
> [CLAUDE.md](CLAUDE.md). This file is the *why*: the decisions, the roads not taken, and what is
> left to do.

---

## 1. Decisions that were made

Grouped by subsystem, with the source design record.

### Core service (`2026-05-21-wristband-printer-design`)
- **Config-driven ZPL geometry.** The wristband is generated programmatically from `wristband.*`
  YAML — no absolute coordinates in code. Calibrate via config, not edits.
- **Legacy fixed layout** (logo → barcode → text → logo) is the default and stays a zero-breaking-change
  path for Symfony.
- **Transient socket failures retry with backoff** rather than failing the job immediately
  (`printer.max-retries`, `printer.retry-backoff-ms`).

### Jobs page & admin auth (`2026-05-29-jobs-page-redesign-design`)
- **Two independent credentials.** A dedicated **admin** credential for the UI (exchanged at login
  for an **HttpOnly session cookie**) is separate from the machine **`X-API-Key`** used by Symfony.
  Leaking one does not expose the other.
- **Full reskin** to match stupvzw.be (Poppins, deep-purple gradient, orange CTA, glass cards).
- Backend scope deliberately widened beyond cosmetics to close real operational gaps: **cancel a
  pending job** and a **full job-detail view**.
- Static page split into separate HTML / CSS / JS files.

### Postgres migration (`2026-05-29-postgres-migration-design`)
- Moved persistence to **PostgreSQL + Flyway**; `ddl-auto: validate` (schema owned by migrations,
  never by Hibernate). No data migration from the prior H2 store.

### Soft-delete jobs (`2026-06-01-soft-delete-jobs-design`)
- **Separate `deleted` boolean flag** (not a `DELETED` status), so the original outcome
  (done/failed/cancelled) stays intact and restore is a single-field flip.
- Hard `deleteById` is retained **only** for the enqueue capacity-race rollback (a row inserted in
  error).
- Confirmation uses a **styled in-app modal**, not the browser's native `confirm()`.

### Template designer (`2026-06-02-wristband-template-designer-design`)
- **Fixed data blocks + static extras** (5 data fields + free text, logo, shapes, barcode). No
  user-defined field names.
- **Hybrid storage (Option C):** declarative JSON `definition` is the source of truth; a
  `generated_zpl` snapshot is saved alongside for export/audit.
- **Colour is preview-only** — tints the preview to judge contrast on coloured stock; print stays
  monochrome.
- **Rotation quantized to 0/90/180/270** — a hard ZPL constraint.
- **Konva.js + vanilla JS, no build step**; served as an admin page behind the existing login.
- **Package-by-feature:** all designer code under `com.stup.wristbandprinter.editor`.
- Symfony integration via **catalog + `templateId`**; `projectType` is an optional, **non-unique** tag.

### Template grouping & alignment (`2026-06-03-template-grouping-alignment-design`)
- **Recursive group nodes (Approach A):** a `GROUP` element holds `children`; nesting allowed.
- **Auto-stack** with choosable direction (`LENGTH`/`WIDTH`) + one editable margin per group;
  cross-axis `crossAlign` (START/CENTER/END) plus a "center on band" action.
- New optional fields live in the existing `jsonb` column — **no DB migration**; flat (group-less)
  definitions render byte-for-byte as before.
- **Per-element `sampleText`** drives the canvas and live preview; real prints still use Symfony data.

### HTTPS in prod (`2026-06-03-https-self-signed-prod-design`)
- **Spring Boot built-in TLS, no reverse proxy.** Prod is **HTTPS-only on 8443**; no HTTP listener.
- **Self-signed PKCS12 keystore generated once** by `docker-entrypoint.sh` and persisted on a named
  volume, so the cert is stable across restarts and Symfony trusts it once. Public cert exported to
  `/certs/server.crt` for the Symfony trust store.

### Management / printer-worker split (`2026-06-04-management-printer-split-design`)
- **One image, two Spring profiles** (`management` = `!worker`, and `worker`). Same Docker image,
  different `SPRING_PROFILES_ACTIVE`.
- **Management owns everything stateful** (DB, UI, registry, ZPL rendering, Labelary). Workers are
  thin: receive ready-to-print ZPL, write bytes, report status. No template logic in workers.
- **Coordination is a synchronous HTTP forward** (amended during implementation — see §2), not an
  async callback: `2xx` → DONE, error/timeout → FAILED.
- **Persist `printer_id` (routing key) + a `printer_name` snapshot** so historical labels survive a
  printer rename/removal. No backfill — legacy rows stay `NULL` / render as `—`/"Unknown".
- **Reprint creates a new job** (new UUID) stamped with the chosen printer; the original row is
  never mutated.
- **Per-job SSE** `GET /api/wristbands/jobs/{jobId}/stream` for Symfony to follow one job; closes on
  a terminal state.

### Printer-name labelling (`2026-06-04-printer-name-on-jobs-design`)
- Superseded by / folded into the management-split design (routing needs a stable id, not just a
  display name).

### Password transmission review (session *Password transmission security*, 2026-06-05)
- Reviewed whether sending the login password over the wire is safe. **Conclusion: skip client-side
  hashing** (TLS already protects the transport); instead **(1) store a hashed admin password** and
  **(2) use a dedicated cookie secret**. See Open tasks §4 — this appears **not yet implemented**.

---

## 2. Rejected approaches

- **Async worker callback for print status** — *rejected in favour of a synchronous forward* during
  the management-split implementation. The callback model added a worker-side queue, a callback API,
  stuck-`PRINTING` reconciliation, and cancel-forwarding for no observable benefit. The synchronous
  forward keeps the exact same `PENDING → PRINTING → DONE/FAILED` lifecycle and SSE behaviour.
  *(Kept on the shelf as a future option only if a blocked per-printer thread ever becomes a
  throughput bottleneck.)*
- **One full service container per printer (the pre-split model)** — *rejected.* Every container ran
  its own UI/cookie/TLS/port, and reprints could not be re-routed because the browser only talked to
  the container that served the page. Replaced by the single-management + thin-workers split.
- **Display-name-only printer labelling** — *rejected.* Routing requires a stable `printer_id`; the
  name is kept as a historical snapshot alongside it.
- **`DELETED` status for cleared jobs** — *rejected* in favour of a separate `deleted` flag, to
  preserve the real terminal outcome and make restore a one-field flip.
- **Client-side password hashing at login** — *rejected* as security theatre over TLS; hash at rest
  + dedicated cookie secret chosen instead.
- **Disabling Symfony peer verification (`verify_peer: false`)** — *documented as the lesser
  alternative*; the recommended path is trusting the exported `server.crt`.
- **Flat element model for groups** — *rejected* (awkward nesting / cross-referencing, more
  bookkeeping) in favour of recursive `GROUP` nodes.
- **Native `confirm()` dialogs** — *rejected* for a themed in-app modal.
- **Seeding the legacy layout as a "default" template** — *deferred (YAGNI)*; the legacy path stays
  as-is.
- Explicitly **out of scope (YAGNI):** arbitrary rotation angles, user-defined field names,
  multi-colour printing, template version history, real-time collaborative editing, multi-line free
  text (`^FB`), per-child group margins, align/distribute beyond center-on-band, group-level
  rotation, `local`-profile HTTPS, a reverse proxy, and CA-issued certs / auto-renewal.

---

## 3. Implementation details worth carrying forward

- **Profile guards are load-bearing.** `@Profile("!worker")` vs `@Profile("worker")` decides which
  role a bean loads in; `WorkerProfileContextTest` verifies a worker context has no DB/UI beans. A
  `prod,worker` boot intentionally fails (`ApiKeyValidator` demands `ADMIN_PASSWORD`, which workers
  lack) — that is the *intended* signal that worker nodes don't run the admin role.
- **Shared ZPL resolver.** `WristbandZplResolver` is used by both the print path and every preview
  path, guaranteeing "what you preview is what prints."
- **Persist-before-enqueue.** A job row is saved before being offered to the in-memory queue to
  avoid a duplicate-insert race with the worker thread; a lost capacity race hard-deletes the row.
- **Crash recovery.** On startup, jobs left `PENDING`/`PRINTING` are marked `FAILED` ("Interrupted by
  service restart") — never auto-reprinted.
- **In-memory queues + job map**, one queue and one worker thread per printer (parallel printing),
  rebuilt from the DB on startup. **Single management instance assumed.**
- **Defensive RAM-cache clear** (`^XA^IDR:*.*^FS^XZ`) prepended to every job; wipes the printer's R:
  drive only (no flash wear).
- **Keystore lifecycle** handled entirely in `docker-entrypoint.sh`; only the `prod` profile
  generates/uses it. `exec java -jar` keeps the JVM as PID 1 for signal handling.
- **Errors → HTTP** centralised in `GlobalExceptionHandler`; body shape
  `{ status, error, message[, fields] }`. Includes the 405-not-500 fix for wrong HTTP methods.
- **Group rendering** flattens recursively to absolute dot positions in `TemplateZplRenderer`;
  "center on band" needs no renderer special-casing (the editor stores the resulting `x`).
- **Tests** run against a real Postgres via Testcontainers; surefire pins `-Dapi.version=1.44`.

---

## 4. Open tasks

| # | Task | Source / status |
|---|------|-----------------|
| 1 | **Multi-symbology barcode rendering** (CODE39/QR). `TemplateZplRenderer` currently emits Code 128 regardless of the selected symbology. | Known limitation, flagged as a planned renderer follow-up. |
| 2 | **Editor canvas barcode rendering.** Barcodes show as a placeholder rectangle on the Konva canvas; the real symbol appears only in the PNG preview and on the printer. | Known limitation. |
| 3 | **Production TLS wiring for workers.** Sub-plan 2 targeted local correctness over plain HTTP between containers; prod compose/TLS wiring for workers was called out as a remaining deploy task. | `management-printer-split` design. |
| 4 | **Admin auth hardening:** store a **hashed** admin password and use a **dedicated cookie secret** (currently the cookie key is derived from the admin password — see `application.yml`). | Recommended 2026-06-05; **appears not yet implemented** — verify against current code before starting. |
| 5 | **Repo hygiene:** remove stray `wristband copy*.png` working files from the repo root. | Not used by the app. |

> ⚠️ Item 4 is a *recommendation from a review session*, not a confirmed gap. Confirm the current
> state of `AuthCookieService` / `ApiKeyValidator` before acting.

---

## 5. Next steps (suggested priority — confirm with the maintainer)

1. **Item 4 — admin auth hardening.** Security-sensitive and the most recently raised; low effort,
   clear recommendation already on record. Verify-then-implement.
2. **Item 1 — multi-symbology barcodes.** The clearest open *functional* gap; templates already let
   users pick a symbology that is silently ignored today.
3. **Item 3 — worker TLS in prod.** Needed before a real multi-printer production rollout if the
   private network is not fully trusted.
4. **Item 2 — editor canvas barcode WYSIWYG.** Quality-of-life; lower urgency since the PNG preview
   already shows the true output.
5. **Item 5 — repo cleanup.** Trivial; bundle into any nearby change.

If horizontally scaling management ever becomes a requirement, the in-memory queue/job model must be
reworked (DB-backed claim or a broker) — it is explicitly single-instance today.

---

## Permit wristband (added 2026-06-09)

### What it is
A new wristband type for campsite resource access (electricity, parking, …). Unlike crew
bands it has no personal details. Any non-blank `permitLabel` creates a valid permit band.

### URL scheme (at time of writing — superseded by 2026-06-16 restructure)
- Permit enqueue, ZPL preview, and PNG preview each had dedicated `/permit/…` paths.
- Crew used a dedicated type-specific path with a legacy 308-redirect alias.
- See the 2026-06-16 section below for the current single polymorphic endpoint.

### Request fields (`PermitWristbandPrintRequest`)

_(This class still exists; since the 2026-06-16 restructure its fields are sent as the permit variant of the polymorphic `POST /api/wristbands/print` body — see that section.)_

| Field | Required | Notes |
|-------|----------|-------|
| `eventName` | ✅ | Printed in block 4 |
| `permitLabel` | ✅ | e.g. `ELEKTRICITEIT`, `PARKING` |
| `clubName` | ❌ | If absent, a dotted fill-in line is printed |
| `iconName` | ❌ | Stored only — not rendered yet |
| `codeValue` | ❌ | When present, prints a scan code in block 3 |
| `codeSymbology` | ❌ | CODE128 (default) / CODE39 / QR |
| `stockColorCode` | ❌ | Preview-only PNG tint (1=white, 2=purple, …) |
| `printerId` | ❌ | Defaults to first registered printer |

### Stock color palette
`wristband.stock-colors` in `application.yml`. Integer codes map to hex strings.
Tinting is applied to the Labelary PNG only — ZPL stays monochrome.

### Gallery page
`/wristband-gallery.html` — shows all band types with lazy-loaded PNG previews.
`GET /api/wristbands/gallery` returns the catalog.

### DB columns added (V6 migration)
`wristband_type` (CREW/PERMIT), `permit_label`, `icon_name`, `stock_color_code`,
`code_value`, `code_symbology`.

### Known limitations
- `iconName` is persisted but not rendered on the band.
- `PermitEventLogoService` logs a warning and omits the event logo if the configured path
  doesn't exist at startup (graceful degradation).

---

## Jobs UI & gallery refresh (added 2026-06-11)

A **front-end-only** pass over the operator pages, with one small backend addition. No change to the
print / routing / worker pipeline or the DB schema.

### What changed
- **Filters reorganised.** The three rows of status/type/printer **chips** became compact dropdown
  `<select>`s — **Status, Type, Event (new), Printer** — each with live counts, beside the search
  box. A subdued **Clear filters** button appears only when a filter or search is active.
  - The motivating request was a **CREW/PERMIT type filter**; `wristbandType` was already on the
    list response, so that part needed no backend change.
- **Clickable rows + ⋮ menu.** The whole row (except the last cell) opens the detail drawer. The
  **Job ID column was dropped**; per-row actions (Details, Copy job ID, Reprint, Cancel) moved into
  a **⋮ popover** (`#row-menu`, `position:fixed`, flips above near the viewport edge, lives outside
  the table's `overflow:hidden`).
- **Top "Menu" dropdown** in the header: navigation (Gallery; Template editor — badged **beta**) and
  **Sign out**. **Clear completed** was moved here too, out of the main toolbar, to prevent
  accidental clicks — it still goes through the themed confirm modal.
- **Detail slide-in redesigned.** Header carries type + status **badges**, the identity as a title
  (crew name, or the **permit label** for permit bands) and the event as a subtitle. The body is
  grouped into titled **sections** (Wristband, Printing) that omit empty fields instead of printing
  `null`. **Job ID + actions are pinned in a footer**; status moved from a mid-panel alert box to
  the header badge, with the **error shown as a footer line** for FAILED jobs; the full-width Close
  button became a subtle **× in the corner**; the panel no longer scrolls (fixed header / flexible
  middle / fixed footer).
- **Permit label surfaced.** `permitLabel` was added to **`PrintJobResponse`** (the list endpoint)
  and populated in `PrintJob.toResponse()`; the detail response already had it. The jobs table shows
  it in the **Name** column for permit bands (no person name) and it is matched by search.
- **Gallery restyled.** `wristband-gallery.html` previously linked a **non-existent
  `/css/admin.css`**; it now uses the shared **`app.css`** design system — glass tiles on the purple
  gradient, **smaller thumbnails** (responsive grid), and a preview **modal whose image fills 90% of
  the viewport height**, with a floating × and Esc-to-close.

### Where it lives
- Front-end: `static/jobs.html`, `static/js/jobs.js`, `static/wristband-gallery.html`,
  `static/js/gallery.js`, `static/css/app.css` (shared design system — the dead `.chips`,
  `.status-box`, `admin.css` styles were removed).
- Backend: `domain/PrintJobResponse.java` (+ `PrintJob.toResponse()`), covered by
  `PrintJobTest#toResponse_usesPermitLabelForPermitBands`.

### Notes / choices
- Filter dropdowns rebuild their options only when the option set changes and never while focused,
  so a live SSE update can't collapse an open dropdown.
- The detail footer is pinned to the panel bottom, so jobs with few fields show a deliberate gap
  between the details and the actions — intentional; trivial to switch to "actions directly under
  the details" if preferred.
- All verification this session was done by serving the real `app.css`/`jobs.js`/`gallery.js`
  against a stubbed backend (mock `fetch`/`EventSource`) — the management UI normally needs the
  admin cookie + Postgres, which a static harness can't provide.

## 2026-06-12 — Copies per job + jobs-table column chooser

- **Copies per job.** `copies` (default 1) on crew & permit print requests; the printer
  prints N bands from one job via `^PQ`, appended on the print path only
  (`ZplCopies.apply` in `PrintQueueService`), so previews stay single-label. Capped by
  `print.max-copies` (default 200) → 400 when exceeded. Persisted (Flyway `V7`), surfaced
  on `PrintJobResponse`/`PrintJobDetailResponse`, and overridable on reprint
  (`POST /jobs/{id}/reprint?copies=N`).
- **Jobs table.** Now data-driven from a `COLUMNS` array in `jobs.js`. Added a **Copies**
  column; removed **Completed** from the table (kept in the detail drawer). New
  **Columns ▾** chooser toggles visible data columns (max 5 + always-on Actions),
  persisted in `localStorage` under `jobs.visibleColumns`. Reprint now prompts for a copy
  count (and printer when more than one is configured).
- No change to the worker, `PrintForwardRequest`, or the route/forward pipeline.

## 2026-06-13 — Rename `associationName` → `clubName`

The wristband property is now **`clubName`** everywhere, matching the Symfony app's field
name. Applies to both crew (`WristbandPrintRequest`) and permit
(`PermitWristbandPrintRequest`) requests, `WristbandData`/`PermitWristbandData`, the
entity/response DTOs, the layout/ZPL services, the jobs UI + template designer, Swagger
schemas, config, docs, and tests. No endpoint **URL** changed at this point (the endpoint
restructure came later — see the 2026-06-16 section).

- **DB.** Flyway **`V8__rename_association_name_to_club_name.sql`** does
  `ALTER TABLE print_jobs RENAME COLUMN association_name TO club_name` — existing rows preserved.
- **Template binding.** `DataBinding.ASSOCIATION_NAME` → **`CLUB_NAME`**. A
  `@JsonAlias("ASSOCIATION_NAME")` keeps templates saved before the rename (jsonb) deserialising;
  they re-serialise as `CLUB_NAME` on next save.
- **Config (operator action).** The YAML keys `wristband.text.font-size-association` and
  `wristband.permit.text.font-size-association` are renamed to **`…font-size-club`**. Any prod
  override of the old keys must be updated or it silently falls back to the default.
- **Caller action.** Symfony / API clients must send `clubName` (was `associationName`). Endpoint
  URLs were unchanged at the time of this rename (the endpoint restructure came later — see
  the 2026-06-16 section).
- Historical specs/plans under `docs/superpowers/` were left as-is (point-in-time records).

---

## 2026-06-16 — API endpoint restructure

### What changed

- **Single polymorphic print endpoint.** `POST /api/wristbands/print` (and its preview siblings
  `POST /api/wristbands/preview/zpl` / `POST /api/wristbands/preview/image`) now handle both crew
  and permit wristbands via a `wristbandType` discriminator field in the JSON body.

- **`wristbandType` is lowercase on the wire** — both in requests and in the jobs list response:
  `"crew"` and `"permit"`. The Java enum stays uppercase internally (`WristbandType.CREW` /
  `WristbandType.PERMIT`).

- **Removed paths (hard cut — no aliases or redirects):** All type-specific sub-paths under
  `/api/wristbands/` and the legacy 308-redirect alias on `POST /api/wristbands/print` are gone.

  **Symfony must deploy the new paths in lockstep with this service.**

- **Templates renamed:** The old template path prefix is gone; all template endpoints now live
  under `/api/wristband-templates/**`. CRUD is unchanged; only the base path moved.

- **Assets split out:** The old asset sub-paths under the template prefix are gone; assets are now
  `POST /api/wristband-assets` and `GET /api/wristband-assets/{id}`.

- **Template preview folded into a single optional-body POST:** The old
  `GET /api/wristband-templates/{id}/preview` is removed. Preview is now
  `POST /api/wristband-templates/{id}/preview` with an **optional** body: omit the body for sample
  data (Symfony thumbnails), or supply a `WristbandData` body for live preview (editor).

### Request fields (new merged body)

Crew fields: `wristbandType` (`"crew"`), `eventName`, `firstName`, `lastName`, `clubName`,
`barcodeValue` (+ optional `templateId`, `codeSymbology`, `stockColorCode`, `printerId`, `copies`).

Permit fields: `wristbandType` (`"permit"`), `eventName`, `permitLabel` (+ optional `clubName`,
`iconName`, `codeValue`, `codeSymbology`, `stockColorCode`, `printerId`, `copies`).

### Source records

Design spec: `docs/superpowers/specs/2026-06-16-api-endpoint-restructure-design.md`  
Implementation plan: `docs/superpowers/plans/2026-06-16-api-endpoint-restructure.md`

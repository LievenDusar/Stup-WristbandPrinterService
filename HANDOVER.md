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

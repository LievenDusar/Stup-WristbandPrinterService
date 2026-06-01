# Jobs Page — Name Column + Detail Drawer with Wristband Preview

**Date:** 2026-06-01
**Status:** Approved (pending spec review)
**Builds on:** the jobs-page redesign (`docs/superpowers/specs/2026-05-29-jobs-page-redesign-design.md`), already implemented on `feat/jobs-page`.

## Goal

Make the print queue easier to scan and inspect:
1. Show the **person's name** in each table row (not just the event).
2. Replace the center "Details" modal with a **right-side slide-in drawer** showing all
   of a job's info.
3. In the drawer, offer an **on-demand "Show preview" button** that renders the actual
   wristband as an image via the existing Labelary viewer.

## Decisions (agreed)

- Table: add a **Name** column (first + last); keep the Event column; association/barcode
  remain in the detail view (option A).
- Detail UX: a **slide-in drawer** from the right replaces the existing modal.
- Wristband preview: rendered **on demand** (button click), not automatically on open —
  avoids an external Labelary call every time the drawer opens.
- Adding `firstName`/`lastName` to the list/SSE payload is acceptable: all those endpoints
  are admin-authenticated (post Plan 2), so names are only visible to logged-in admins who
  can already open any job's detail. Barcode and association stay detail-only (minimised).

## Backend changes

### Name on the list/SSE payload
- Add `firstName` and `lastName` to the `PrintJobResponse` record and to
  `PrintJob.toResponse()`. This payload is used by `GET /jobs`, the SSE stream, and the
  `print`/`reprint`/`cancel` responses — all gain the two fields (backward compatible).
- `PrintJobDetailResponse` already carries the full set (used by the drawer); unchanged.

### Wristband preview by job id
- **New endpoint:** `GET /api/wristbands/jobs/{jobId}/preview` → `image/png` (authenticated,
  i.e. admin cookie or `X-API-Key`; it falls under the existing `.anyRequest().authenticated()`).
- **Behaviour:** load the job via `PrintQueueService.getJob(jobId)`; if absent → `404`.
  Otherwise build the layout (`WristbandLayoutService.buildData(job.getRequest())`), generate
  ZPL (`ZplGeneratorService.generate(...)`), render via the existing
  `LabelaryPreviewService.renderPreview(zpl)`, and return the PNG bytes with
  `Content-Type: image/png`. Reuses services already wired into `WristbandController`.
- Labelary failure maps to `503` via the existing `LabelaryUnavailableException` handler;
  the frontend treats any non-OK preview response as "preview unavailable".

## Frontend changes (`jobs.html`, `js/jobs.js`, `css/app.css`)

### Name column
- Add a **Name** column after Job ID (renders `firstName lastName`, HTML-escaped).
- The client-side **search** also matches the name (in addition to job ID and event).
- Uses `firstName`/`lastName` now present on the list/SSE payload.

### Slide-in drawer (replaces the modal)
- Replace the center-modal markup/CSS/logic with a **right-side drawer**: a fixed panel that
  slides in (CSS `transform: translateX`) over a dimmed overlay; closes via a close button,
  clicking the overlay, or `Esc`.
- Opening a row still fetches `GET /jobs/{id}` (full `PrintJobDetailResponse`) and renders all
  fields: job id, name, association, event, barcode, status, submitted, completed, error.
- The drawer also hosts the per-job **actions** (Reprint for DONE/FAILED, Cancel for PENDING),
  in addition to the row buttons.

### On-demand wristband preview
- The drawer includes a **"Show preview"** button. On click: show a loading state, then set an
  `<img>` `src` to `/api/wristbands/jobs/{id}/preview` (the browser sends the admin cookie,
  same-origin). On the image's `onload` show it; on `onerror` show "Preview unavailable".
- The preview is fetched only on click, and only re-fetched if the drawer is reopened for a
  different job.

## Testing

- **Backend (TDD):**
  - `GET /jobs/{id}/preview` returns `200` + `Content-Type: image/png` and the bytes from a
    mocked `LabelaryPreviewService`; unknown job → `404`.
  - `PrintJobResponse`/`toResponse()` now include `firstName`/`lastName` (assert via a
    controller test on `GET /jobs` or the existing `PrintJobTest`).
  - Existing tests that consume `toResponse()` still pass (additive change).
- **Frontend:** manual browser verification (no JS test harness), per the redesign spec.

## Out of scope

- Auto-rendering the preview on drawer open (explicitly chosen on-demand).
- Adding association/barcode as table columns (kept in the drawer).
- Caching/persisting the rendered image server-side (each click calls Labelary live).

## Affected files

- `src/main/java/.../domain/PrintJobResponse.java` — add `firstName`, `lastName`.
- `src/main/java/.../domain/PrintJob.java` — update `toResponse()`.
- `src/main/java/.../controller/WristbandController.java` — new preview-by-jobId endpoint.
- `src/main/resources/static/js/jobs.js` — Name column, search, drawer, preview button.
- `src/main/resources/static/jobs.html` — drawer markup (replacing modal markup).
- `src/main/resources/static/css/app.css` — drawer + preview styles (replacing modal styles).
- Tests: `WristbandControllerTest` (preview endpoint + name fields), `PrintJobTest` if needed.

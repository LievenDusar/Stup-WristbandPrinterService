# Jobs Page Redesign — Design

**Date:** 2026-05-29
**Status:** Approved (pending spec review)

## Goal

Make the print-queue management page (`src/main/resources/static/jobs.html`) more
operator-friendly and visually consistent with the STUP product site
(stupvzw.be). The trigger requirement is being able to see the **full job ID**;
alongside that we add usability and operational improvements and a brand reskin.

## Scope decisions (agreed)

- **Visual:** Full reskin to match stupvzw.be (option A).
- **Backend:** Frontend changes **plus** the two backend additions that close real
  operational gaps — cancel a pending job, and a full job-detail view (option B).
- **Auth:** Replace the browser's "type key → store in sessionStorage" model with a
  server-issued **HttpOnly session cookie** (Option B from the auth discussion). The
  long-lived secret never sits in JS-readable storage, and the SSE stream becomes
  authenticated. The machine integration (Symfony) keeps using the `X-API-Key`
  header unchanged.

## Design system (extracted from stupvzw.be `landing.d89c7cbe.css`)

- **Font:** `Poppins, sans-serif` (loaded via Google Fonts).
- **Background:** deep-purple gradient — `#0e0118` → `#0d0520` → `#1a0a38`.
- **Brand purple:** `#4f1574` (accents), with `#632d87`, `#9b5fc4`, `#ce93d8`.
- **Accent / primary CTA:** orange `#f57c00` (hover `#e65100`).
- **Glass cards:** background `hsla(0,0%,100%,.05)`, border `hsla(0,0%,100%,.1)`,
  shadow `0 8px 40px rgba(0,0,0,.35)`, radius `16px`.
- **Text:** body `#e8e0f0`, muted `#d4c8e8`.
- **Radii:** sm 8, md 12, lg 16, xl 20, 2xl 24px. **Transitions:** 0.2s / 0.3s ease.
- **Status colors:** PENDING grey `#6c757d`, PRINTING blue `#2196f3`,
  DONE green `#4caf50`, FAILED red `#f44336`, CANCELLED muted purple `#9b5fc4`.

Implementation note: replace Bootstrap with lightweight **custom CSS** built on
these tokens. Bootstrap's light defaults fight a dark glass theme; a single
self-contained `jobs.html` (no build step) is retained.

## Frontend features (existing API)

1. **Full job ID + copy-to-clipboard.** Table shows a compact/truncated ID; a copy
   button (or clicking the cell) copies the full UUID and shows a toast.
2. **Search box.** Live client-side filter across job ID and event name (and the
   person's name once a detail has been loaded into memory).
3. **Status summary chips.** Live counts (Pending/Printing/Done/Failed/Cancelled);
   clicking a chip filters the table by that status.
4. **Column sorting** by submitted time, completed time, and status.
5. **Timestamps** shown relative ("2 min ago") with the exact time on hover.
6. **Toast notifications** replace `alert()` for reprint/cancel/clear outcomes.
7. **Styled SSE indicator** (Live / Reconnecting).
8. Improved empty and loading states.

## Authentication (session cookie)

Replaces the typed-key-in-`sessionStorage` approach for the browser UI. The static
`security.api-key` stays on the server; the browser is just never handed it to keep.

- **`POST /api/wristbands/login`** — body `{ "apiKey": "..." }`. If it matches
  `security.api-key`, respond `200` and set an **HttpOnly** cookie (`Secure` +
  `SameSite=Strict` in prod; non-Secure + `SameSite=Lax` in `local`). Wrong key → `401`.
- **`POST /api/wristbands/logout`** — clears the cookie, `204`.
- **Cookie contents (stateless, no server-side session):** a signed token of the form
  `expiry|HMAC-SHA256(expiry, signingSecret)`. The server validates the signature and
  expiry without storing sessions — consistent with the existing
  `SessionCreationPolicy.STATELESS` design. Signing secret is configurable
  (`security.cookie-secret`); if unset it is derived from `security.api-key`.
  Cookie max-age ~12h.
- **`ApiKeyAuthFilter` accepts either** a valid `X-API-Key` header (machine clients,
  unchanged) **or** a valid auth cookie (browser). Constant-time comparison retained
  for the header path.
- **`SecurityConfig`:** `/api/wristbands/login` is `permitAll` (you must reach it to
  authenticate); `/jobs.html` stays `permitAll` (the static page shell). The SSE
  stream `/api/wristbands/jobs/stream` is **removed from `permitAll`** — it now
  requires the cookie, which `EventSource` sends automatically on same-origin requests.

### Cancel a pending job
- **Endpoint:** `POST /api/wristbands/jobs/{jobId}/cancel` (requires API key).
- **Behavior:** valid only while the job is `PENDING`. Removes it from the worker
  queue, sets status `CANCELLED`, persists, and broadcasts the update over SSE.
- **Conflict:** if the job is already `PRINTING`, `DONE`, `FAILED`, or `CANCELLED`,
  return `409 Conflict` (it is too late / not applicable). Not found → `404`.
- **Race handling:** if the worker dequeues the job between the status check and
  `queue.remove`, the job is already `PRINTING`; cancel returns `409`.
- **New status:** add `CANCELLED` to `PrintJobStatus`. Update badge styling and the
  status filter list. `clearCompleted()` also removes `CANCELLED` rows
  (DONE/FAILED/CANCELLED are the terminal, clearable states).

### Full job detail (privacy-aware)
- **Endpoint:** extend the existing authenticated `GET /api/wristbands/jobs/{jobId}`
  to return a richer `PrintJobDetailResponse` including `firstName`, `lastName`,
  `associationName`, `barcodeValue` (in addition to the current fields).
- **Privacy / payload constraint:** even though the SSE stream is now authenticated
  (via the cookie), personal data (names, barcode) is still **not** broadcast to
  every connected client — the SSE/list payload (`PrintJobResponse`) stays lean:
  id, status, eventName, submittedAt, completedAt, error. The details modal fetches
  the full record on demand via `GET /jobs/{id}`. This keeps broadcast payloads
  small and PII fetched only when an operator explicitly opens a job.

### Frontend wiring for backend features
- **Login form** replaces the API-key-into-sessionStorage card: the operator enters
  the key once, the page `POST`s it to `/login`, and on success relies on the cookie
  for all subsequent calls (no key kept in JS). A **Sign out** button calls `/logout`.
  If a call returns `401`, the page shows the login form again.
- **Cancel button** appears only on `PENDING` rows; calls the cancel endpoint (cookie
  auth); on `409` shows a toast ("Job already started").
- **Details modal** opens from a row action; fetches `GET /jobs/{id}` (cookie auth)
  and renders the full wristband data.

## Testing

- **Backend (TDD):**
  - Auth: `POST /login` with correct key → 200 + `Set-Cookie`; wrong key → 401.
    A protected endpoint and the SSE stream succeed with the cookie and are rejected
    (401) without it. Header `X-API-Key` still works (machine path unchanged).
    Cookie token signing/validation (valid, tampered, expired) unit-tested.
  - Cancel: `PENDING → CANCELLED` succeeds and removes from queue; non-pending → 409;
    unknown id → 404.
  - `clearCompleted` also removes CANCELLED jobs.
  - `GET /jobs/{id}` returns the extended detail fields; list/SSE payload unchanged
    (no PII).
  - Update `WristbandIntegrationTest` for login + cookie, cancel flow, and detail.
- **Frontend:** single self-contained file, verified live in the browser (no JS test
  harness introduced). Local dev: `local` profile issues a non-Secure cookie so the
  login flow works over `http://localhost:8080`.

## Out of scope

- No live metrics panel on the page (the metrics endpoint sits behind auth; deferred).
- No per-user identity / SSO (Option C) — still a single static server-side key,
  just delivered to the browser as a session cookie instead of a stored secret.
- No build tooling / SPA framework — stays a single static HTML file.

## Affected files

- `src/main/resources/static/jobs.html` — reskin + all frontend features + login/logout.
- `src/main/java/.../domain/PrintJobStatus.java` — add `CANCELLED`.
- `src/main/java/.../domain/PrintJobDetailResponse.java` — new detail DTO.
- `src/main/java/.../domain/PrintJob.java` — `toDetailResponse()`.
- `src/main/java/.../service/PrintQueueService.java` — `cancel(jobId)` logic.
- `src/main/java/.../controller/WristbandController.java` — cancel endpoint;
  detail response on `GET /jobs/{id}`.
- `src/main/java/.../controller/AuthController.java` — new `login`/`logout` endpoints.
- `src/main/java/.../security/AuthCookieService.java` — new; sign/verify the cookie token.
- `src/main/java/.../security/ApiKeyAuthFilter.java` — accept header **or** auth cookie.
- `src/main/java/.../config/SecurityConfig.java` — permit `/login`; require auth on
  `/jobs/stream`.
- `src/main/resources/application*.yml` — `security.cookie-secret`, profile-specific
  cookie `Secure`/`SameSite` settings.
- `src/main/java/.../exception/GlobalExceptionHandler.java` — map cancel conflict to 409.
- Tests: `PrintQueueServiceTest`, `WristbandControllerTest`, `AuthControllerTest`,
  `AuthCookieServiceTest`, `WristbandIntegrationTest`.

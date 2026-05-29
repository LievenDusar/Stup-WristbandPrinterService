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
- **Auth:** Admin-only access to the jobs page via a **dedicated admin credential**
  (separate from the machine `X-API-Key`), exchanged at login for a server-issued
  **HttpOnly session cookie**. The job data (list, detail, SSE) is only visible after
  admin login. The machine integration (Symfony) keeps using the `X-API-Key` header
  unchanged. The admin credential and the machine key are independent — leaking one
  does not expose the other.
- **Static assets:** Split the single self-contained page into separate
  HTML / CSS / JS files (see "Static file structure").

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
these tokens. Bootstrap's light defaults fight a dark glass theme. No build step —
plain static files served from `src/main/resources/static`.

## Static file structure

Split the single inline page into separate files (the agreed "necessary files"):

- `static/jobs.html` — the queue page markup; links the shared CSS and `js/jobs.js`.
- `static/login.html` — the admin login form; links the shared CSS and `js/login.js`.
- `static/css/app.css` — shared dark-glass theme (tokens + components) used by both pages.
- `static/js/jobs.js` — queue logic (SSE, render, search, sort, actions).
- `static/js/login.js` — login form submit + redirect.

A shared `app.css` (rather than per-page stylesheets) avoids duplicating the theme.
`SecurityConfig` permits the static assets (`/login.html`, `/css/**`, `/js/**`,
`/jobs.html`); only the **data** endpoints require auth. `jobs.js` checks auth on load
(e.g., the SSE connection / a job fetch) and redirects to `/login.html` on `401`, so an
unauthenticated visitor never sees job data.

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

## Authentication (admin login → session cookie)

Two **independent** credentials:
- **Machine key** — `security.api-key`, sent as `X-API-Key` by the Symfony integration.
  Unchanged.
- **Admin credential** — `security.admin.username` (default `admin`) +
  `security.admin.password`. Used by humans to log into the jobs page. The admin
  never sees or uses the machine key, and vice-versa.

Flow:
- **`POST /api/wristbands/login`** — body `{ "username": "...", "password": "..." }`.
  If it matches the configured admin credential (constant-time comparison), respond
  `200` and set an **HttpOnly** cookie (`Secure` + `SameSite=Strict` in prod;
  non-Secure + `SameSite=Lax` in `local`). Wrong credential → `401`.
- **`POST /api/wristbands/logout`** — clears the cookie, `204`.
- **Cookie contents (stateless, no server-side session):** a signed token of the form
  `expiry|HMAC-SHA256(expiry, signingSecret)`. The server validates signature + expiry
  without storing sessions — consistent with the existing `SessionCreationPolicy.STATELESS`
  design. Signing secret is `security.cookie-secret`; if unset it is derived from
  `security.admin.password`. Cookie max-age ~12h.
- **`ApiKeyAuthFilter` accepts either** a valid `X-API-Key` header (machine clients,
  unchanged) **or** a valid auth cookie (admin browser). Constant-time comparison
  retained for the header path.
- **Prod startup guard:** extend the existing `ApiKeyValidator` so that under the
  `prod` profile the application also refuses to start if `security.admin.password`
  is unset, blank, or a known default (same treatment as the machine key).
- **`SecurityConfig`:** `permitAll` for `/api/wristbands/login`, `/login.html`,
  `/css/**`, `/js/**`, and `/jobs.html` (static shells carry no data). All data
  endpoints — including the SSE stream `/api/wristbands/jobs/stream`, which is
  **removed from the old `permitAll`** — require auth (cookie or header). `EventSource`
  sends the cookie automatically on same-origin requests.

> Note: this is a single shared admin login (no per-user accounts/SSO). The app will
> not create accounts; the admin credential is provided via env/config
> (`ADMIN_PASSWORD` in prod).

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
- **Admin login page** (`login.html`): username + password form that `POST`s to
  `/login`. On success the cookie is set and the browser redirects to `/jobs.html`.
  `jobs.html` redirects back to `/login.html` if a data call returns `401`. A **Sign
  out** button on the jobs page calls `/logout`. No secret is kept in JS storage.
- **Cancel button** appears only on `PENDING` rows; calls the cancel endpoint (cookie
  auth); on `409` shows a toast ("Job already started").
- **Details modal** opens from a row action; fetches `GET /jobs/{id}` (cookie auth)
  and renders the full wristband data.

## Testing

- **Backend (TDD):**
  - Auth: `POST /login` with correct admin username+password → 200 + `Set-Cookie`;
    wrong credential → 401. A protected endpoint and the SSE stream succeed with the
    cookie and are rejected (401) without it. Header `X-API-Key` still works (machine
    path unchanged). Cookie token signing/validation (valid, tampered, expired)
    unit-tested. Prod startup guard rejects a blank/default admin password.
  - Cancel: `PENDING → CANCELLED` succeeds and removes from queue; non-pending → 409;
    unknown id → 404.
  - `clearCompleted` also removes CANCELLED jobs.
  - `GET /jobs/{id}` returns the extended detail fields; list/SSE payload unchanged
    (no PII).
  - Update `WristbandIntegrationTest` for login + cookie, cancel flow, and detail.
- **Frontend:** separate static files (`jobs.html`, `login.html`, `css/app.css`,
  `js/jobs.js`, `js/login.js`), verified live in the browser (no JS test harness
  introduced). Local dev: `local` profile issues a non-Secure cookie and a default
  admin credential so the login flow works over `http://localhost:8080`.

## Out of scope

- No live metrics panel on the page (the metrics endpoint sits behind auth; deferred).
- No per-user identity / SSO (Option C) — a single shared admin credential, exchanged
  for a session cookie. No accounts created by the app.
- No build tooling / SPA framework — plain static files, no bundler.

## Affected files

- `src/main/resources/static/jobs.html` — queue page markup (reskin), links css/js.
- `src/main/resources/static/login.html` — new admin login page.
- `src/main/resources/static/css/app.css` — new shared dark-glass theme.
- `src/main/resources/static/js/jobs.js` — new; queue logic + all frontend features.
- `src/main/resources/static/js/login.js` — new; login submit + redirect.
- `src/main/java/.../domain/PrintJobStatus.java` — add `CANCELLED`.
- `src/main/java/.../domain/PrintJobDetailResponse.java` — new detail DTO.
- `src/main/java/.../domain/PrintJob.java` — `toDetailResponse()`.
- `src/main/java/.../service/PrintQueueService.java` — `cancel(jobId)` logic.
- `src/main/java/.../controller/WristbandController.java` — cancel endpoint;
  detail response on `GET /jobs/{id}`.
- `src/main/java/.../controller/AuthController.java` — new `login`/`logout` endpoints
  (validate admin username+password).
- `src/main/java/.../config/AdminProperties.java` — new; `security.admin.username/password`.
- `src/main/java/.../security/AuthCookieService.java` — new; sign/verify the cookie token.
- `src/main/java/.../security/ApiKeyAuthFilter.java` — accept header **or** auth cookie.
- `src/main/java/.../config/SecurityConfig.java` — permit `/login`, `/login.html`,
  `/css/**`, `/js/**`, `/jobs.html`; require auth on `/jobs/stream` and other data endpoints.
- `src/main/java/.../config/ApiKeyValidator.java` — also guard the admin password in prod.
- `src/main/resources/application*.yml` — `security.admin.*`, `security.cookie-secret`,
  profile-specific cookie `Secure`/`SameSite` settings.
- `src/main/java/.../exception/GlobalExceptionHandler.java` — map cancel conflict to 409.
- Tests: `PrintQueueServiceTest`, `WristbandControllerTest`, `AuthControllerTest`,
  `AuthCookieServiceTest`, `WristbandIntegrationTest`.

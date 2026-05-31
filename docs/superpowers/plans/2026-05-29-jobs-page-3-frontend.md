# Jobs Page Redesign — Plan 3 of 3: Frontend Redesign

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the jobs page in the STUP dark-glass style as separate HTML/CSS/JS files, add an admin login page, and wire the new operations (full job ID + copy, search, status chips, sorting, relative times, toasts, cancel, details modal, login/logout).

**Architecture:** Plain static files served from `src/main/resources/static` (no build step). A shared `css/app.css` carries the theme; `login.html`/`js/login.js` handle admin login; `jobs.html`/`js/jobs.js` render the live queue using the cookie established by Plan 2 (no secret stored in JS). The page redirects to login on any `401`.

**Tech Stack:** Vanilla HTML/CSS/JS, EventSource (SSE), Fetch API.

**Spec:** `docs/superpowers/specs/2026-05-29-jobs-page-redesign-design.md`

**Prerequisites:** Plan 1 (cancel + detail endpoints, `CANCELLED` status) and Plan 2 (admin login/logout, secured SSE, `/css/**` `/js/**` `/login.html` permitted) MUST be implemented first — this frontend calls those endpoints and relies on the auth cookie.

**Testing note:** Per the spec, no JS test harness is introduced; tasks are verified manually in a browser. Run the app with `mvn spring-boot:run -Dspring-boot.run.profiles=local` (local profile: admin password `local-admin`, non-Secure cookie so it works over `http://localhost:8080`). On `main` the datastore is H2 (no Docker needed); if executed after the Postgres branch is merged, start a local Postgres first.

---

## File structure

- `src/main/resources/static/css/app.css` — new shared theme.
- `src/main/resources/static/login.html` — new admin login page.
- `src/main/resources/static/js/login.js` — new login submit logic.
- `src/main/resources/static/jobs.html` — rewritten (markup only; no inline CSS/JS).
- `src/main/resources/static/js/jobs.js` — new queue logic (all features).

---

### Task 1: Shared theme — `css/app.css`

**Files:**
- Create: `src/main/resources/static/css/app.css`

- [ ] **Step 1: Create the stylesheet**

Create `src/main/resources/static/css/app.css`:

```css
:root {
  --purple-deep: #0e0118;
  --purple-dark: #1a0a38;
  --purple: #4f1574;
  --purple-mid: #632d87;
  --purple-light: #9b5fc4;
  --orange: #f57c00;
  --orange-deep: #e65100;
  --text-body: #e8e0f0;
  --text-muted: #d4c8e8;
  --glass-bg: hsla(0, 0%, 100%, 0.05);
  --glass-border: hsla(0, 0%, 100%, 0.1);
  --shadow-card: 0 8px 40px rgba(0, 0, 0, 0.35);
  --radius-md: 12px;
  --radius-lg: 16px;
  --t-fast: 0.2s ease;
}

* { box-sizing: border-box; }

body {
  margin: 0;
  font-family: Poppins, system-ui, sans-serif;
  color: var(--text-body);
  background: radial-gradient(1200px 800px at 20% -10%, var(--purple-dark), var(--purple-deep));
  min-height: 100vh;
}

a { color: var(--purple-light); }

.container { max-width: 1100px; margin: 0 auto; padding: 24px; }

.glass {
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(8px);
}

.app-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 20px;
}
.app-header h1 { font-size: 1.5rem; font-weight: 600; margin: 0; }

.btn {
  font-family: inherit; font-size: 0.85rem; cursor: pointer;
  border-radius: var(--radius-md); padding: 8px 14px;
  border: 1px solid var(--glass-border); background: var(--glass-bg);
  color: var(--text-body); transition: background var(--t-fast), transform var(--t-fast);
}
.btn:hover { background: hsla(0, 0%, 100%, 0.1); }
.btn:active { transform: translateY(1px); }
.btn-primary { background: var(--orange); border-color: var(--orange); color: #fff; }
.btn-primary:hover { background: var(--orange-deep); }
.btn-sm { padding: 4px 10px; font-size: 0.78rem; }
.btn-danger { border-color: #f4433680; }

.controls { display: flex; gap: 10px; align-items: center; margin-bottom: 16px; flex-wrap: wrap; }
.search { flex: 1; min-width: 200px; }
.input {
  font-family: inherit; font-size: 0.9rem; color: var(--text-body);
  background: var(--glass-bg); border: 1px solid var(--glass-border);
  border-radius: var(--radius-md); padding: 9px 12px; width: 100%;
}
.input:focus { outline: 1px solid var(--purple-light); }

.chips { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 16px; }
.chip {
  cursor: pointer; border-radius: 999px; padding: 6px 14px; font-size: 0.8rem;
  border: 1px solid var(--glass-border); background: var(--glass-bg); color: var(--text-muted);
  transition: background var(--t-fast);
}
.chip:hover { background: hsla(0, 0%, 100%, 0.1); }
.chip.active { background: var(--purple); border-color: var(--purple-mid); color: #fff; }
.chip .count { font-weight: 600; }

table { width: 100%; border-collapse: collapse; font-size: 0.88rem; }
thead th {
  text-align: left; padding: 12px; color: var(--text-muted); font-weight: 600;
  border-bottom: 1px solid var(--glass-border); cursor: pointer; user-select: none;
}
tbody td { padding: 11px 12px; border-bottom: 1px solid hsla(0, 0%, 100%, 0.05); }
tbody tr:hover { background: hsla(0, 0%, 100%, 0.03); }
.mono { font-family: ui-monospace, monospace; font-size: 0.82rem; }
.id-cell { display: flex; align-items: center; gap: 6px; }
.copy-btn { background: none; border: none; cursor: pointer; color: var(--purple-light); font-size: 0.9rem; padding: 2px; }

.badge { padding: 3px 10px; border-radius: 999px; font-size: 0.72rem; font-weight: 600; color: #fff; }
.badge.PENDING   { background: #6c757d; }
.badge.PRINTING  { background: #2196f3; }
.badge.DONE      { background: #4caf50; }
.badge.FAILED    { background: #f44336; }
.badge.CANCELLED { background: var(--purple-light); }

.muted { color: var(--text-muted); }
.error-text { color: #ff8a80; }
.empty { text-align: center; padding: 28px; color: var(--text-muted); }

#sse-status { font-size: 0.8rem; }
.live { color: #6ee7a8; }
.reconnecting { color: #ffcf6e; }

/* Login */
.login-wrap { min-height: 100vh; display: flex; align-items: center; justify-content: center; }
.login-card { width: 340px; padding: 28px; }
.login-card h1 { font-size: 1.3rem; margin: 0 0 4px; }
.login-card .sub { color: var(--text-muted); font-size: 0.85rem; margin-bottom: 20px; }
.field { margin-bottom: 14px; }
.field label { display: block; font-size: 0.8rem; color: var(--text-muted); margin-bottom: 5px; }

/* Modal */
.modal-overlay {
  position: fixed; inset: 0; background: rgba(0, 0, 0, 0.6);
  display: none; align-items: center; justify-content: center; padding: 20px;
}
.modal-overlay.open { display: flex; }
.modal { width: 420px; max-width: 100%; padding: 24px; }
.modal h2 { margin: 0 0 16px; font-size: 1.1rem; }
.detail-row { display: flex; justify-content: space-between; gap: 16px; padding: 7px 0; border-bottom: 1px solid hsla(0,0%,100%,0.06); }
.detail-row .k { color: var(--text-muted); }
.detail-row .v { text-align: right; word-break: break-all; }
.modal-close { margin-top: 18px; width: 100%; }

/* Toasts */
#toasts { position: fixed; bottom: 20px; right: 20px; display: flex; flex-direction: column; gap: 8px; z-index: 100; }
.toast {
  padding: 12px 16px; border-radius: var(--radius-md); font-size: 0.85rem; color: #fff;
  box-shadow: var(--shadow-card); animation: toast-in var(--t-fast);
}
.toast.ok { background: #2e7d32; }
.toast.err { background: #c62828; }
@keyframes toast-in { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: none; } }
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/css/app.css
git commit -m "feat(ui): add shared STUP dark-glass stylesheet"
```

---

### Task 2: Admin login page

**Files:**
- Create: `src/main/resources/static/login.html`
- Create: `src/main/resources/static/js/login.js`

- [ ] **Step 1: Create `login.html`**

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>STUP — Admin Login</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link href="https://fonts.googleapis.com/css2?family=Poppins:wght@400;500;600&display=swap" rel="stylesheet">
  <link href="/css/app.css" rel="stylesheet">
</head>
<body>
  <div class="login-wrap">
    <div class="glass login-card">
      <h1>STUP Print Queue</h1>
      <div class="sub">Admin sign in</div>
      <form id="login-form">
        <div class="field">
          <label for="username">Username</label>
          <input class="input" type="text" id="username" autocomplete="username" value="admin">
        </div>
        <div class="field">
          <label for="password">Password</label>
          <input class="input" type="password" id="password" autocomplete="current-password">
        </div>
        <div id="login-error" class="error-text" style="display:none;margin-bottom:12px;font-size:0.83rem"></div>
        <button class="btn btn-primary" type="submit" style="width:100%">Sign in</button>
      </form>
    </div>
  </div>
  <script src="/js/login.js"></script>
</body>
</html>
```

- [ ] **Step 2: Create `js/login.js`**

```javascript
document.getElementById('login-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const errorEl = document.getElementById('login-error');
  errorEl.style.display = 'none';

  const username = document.getElementById('username').value.trim();
  const password = document.getElementById('password').value;

  try {
    const res = await fetch('/api/wristbands/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    });
    if (res.ok) {
      window.location.href = '/jobs.html';
    } else {
      errorEl.textContent = 'Invalid username or password.';
      errorEl.style.display = 'block';
    }
  } catch (err) {
    errorEl.textContent = 'Could not reach the server.';
    errorEl.style.display = 'block';
  }
});
```

- [ ] **Step 3: Manually verify**

Start the app (`mvn spring-boot:run -Dspring-boot.run.profiles=local`). Open `http://localhost:8080/login.html`.
- Submitting `admin` / wrong password shows the inline error.
- Submitting `admin` / `local-admin` redirects to `/jobs.html` and sets the `stup_admin` cookie (check DevTools → Application → Cookies; it should be HttpOnly).

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/login.html src/main/resources/static/js/login.js
git commit -m "feat(ui): add admin login page"
```

---

### Task 3: Rebuilt jobs page — `jobs.html` + `js/jobs.js`

**Files:**
- Modify (full rewrite): `src/main/resources/static/jobs.html`
- Create: `src/main/resources/static/js/jobs.js`

- [ ] **Step 1: Replace `jobs.html` entirely**

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>STUP — Print Queue</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link href="https://fonts.googleapis.com/css2?family=Poppins:wght@400;500;600&display=swap" rel="stylesheet">
  <link href="/css/app.css" rel="stylesheet">
</head>
<body>
  <div class="container">
    <div class="app-header">
      <h1>STUP Print Queue</h1>
      <div style="display:flex;align-items:center;gap:14px">
        <span id="sse-status" class="reconnecting">Connecting…</span>
        <button class="btn btn-sm" onclick="logout()">Sign out</button>
      </div>
    </div>

    <div class="chips" id="chips"></div>

    <div class="controls">
      <div class="search">
        <input class="input" id="search" type="text" placeholder="Search by job ID or event…" oninput="render()">
      </div>
      <button class="btn btn-danger" onclick="clearCompleted()">Clear completed</button>
    </div>

    <div class="glass" style="overflow:hidden">
      <table>
        <thead>
          <tr>
            <th onclick="sortBy('jobId')">Job ID</th>
            <th onclick="sortBy('eventName')">Event</th>
            <th onclick="sortBy('status')">Status</th>
            <th onclick="sortBy('submittedAt')">Submitted</th>
            <th onclick="sortBy('completedAt')">Completed</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody id="jobs-body">
          <tr><td colspan="6" class="empty">Loading…</td></tr>
        </tbody>
      </table>
    </div>
  </div>

  <div class="modal-overlay" id="modal-overlay">
    <div class="glass modal" id="modal-content"></div>
  </div>

  <div id="toasts"></div>

  <script src="/js/jobs.js"></script>
</body>
</html>
```

- [ ] **Step 2: Create `js/jobs.js`**

```javascript
const jobs = {};
let statusFilter = '';
let sortKey = 'submittedAt';
let sortDir = -1; // newest first
let eventSource = null;

const STATUSES = ['PENDING', 'PRINTING', 'DONE', 'FAILED', 'CANCELLED'];

window.addEventListener('load', init);

async function init() {
  // Auth gate + initial load. A 401 means no valid admin cookie → go to login.
  try {
    const res = await fetch('/api/wristbands/jobs');
    if (res.status === 401) { redirectToLogin(); return; }
    (await res.json()).forEach(j => { jobs[j.jobId] = j; });
  } catch (e) { /* SSE will retry */ }
  render();
  connectSse();
}

function redirectToLogin() { window.location.href = '/login.html'; }

function connectSse() {
  if (eventSource) eventSource.close();
  eventSource = new EventSource('/api/wristbands/jobs/stream');
  eventSource.onopen = () => setSse('● Live', 'live');
  eventSource.onmessage = (e) => { const job = JSON.parse(e.data); jobs[job.jobId] = job; render(); };
  eventSource.onerror = async () => {
    setSse('○ Reconnecting…', 'reconnecting');
    // Distinguish a dropped connection from a lost session.
    try { if ((await fetch('/api/wristbands/jobs')).status === 401) redirectToLogin(); } catch (e) {}
  };
}

function setSse(text, cls) {
  const el = document.getElementById('sse-status');
  el.textContent = text;
  el.className = cls;
}

function sortBy(key) {
  if (sortKey === key) { sortDir *= -1; } else { sortKey = key; sortDir = 1; }
  render();
}

function setFilter(status) { statusFilter = (statusFilter === status) ? '' : status; render(); }

function render() {
  renderChips();
  const search = document.getElementById('search').value.trim().toLowerCase();
  const tbody = document.getElementById('jobs-body');

  let list = Object.values(jobs)
    .filter(j => !statusFilter || j.status === statusFilter)
    .filter(j => !search
      || j.jobId.toLowerCase().includes(search)
      || (j.eventName || '').toLowerCase().includes(search));

  list.sort((a, b) => {
    const av = a[sortKey] || '', bv = b[sortKey] || '';
    return av < bv ? -sortDir : av > bv ? sortDir : 0;
  });

  if (list.length === 0) {
    tbody.innerHTML = '<tr><td colspan="6" class="empty">No jobs.</td></tr>';
    return;
  }

  tbody.innerHTML = list.map(rowHtml).join('');
}

function rowHtml(job) {
  const actions = [`<button class="btn btn-sm" onclick="showDetail('${job.jobId}')">Details</button>`];
  if (job.status === 'PENDING') {
    actions.push(`<button class="btn btn-sm" onclick="cancelJob('${job.jobId}')">Cancel</button>`);
  }
  if (job.status === 'DONE' || job.status === 'FAILED') {
    actions.push(`<button class="btn btn-sm btn-primary" onclick="reprint('${job.jobId}')">Reprint</button>`);
  }
  return `<tr>
    <td><div class="id-cell">
      <span class="mono" title="${job.jobId}">${job.jobId.substring(0, 8)}…</span>
      <button class="copy-btn" title="Copy full ID" onclick="copyId('${job.jobId}')">⧉</button>
    </div></td>
    <td>${esc(job.eventName)}</td>
    <td><span class="badge ${job.status}">${job.status}</span></td>
    <td title="${job.submittedAt || ''}">${relTime(job.submittedAt)}</td>
    <td title="${job.completedAt || ''}">${job.completedAt ? relTime(job.completedAt) : '—'}</td>
    <td><div style="display:flex;gap:6px">${actions.join('')}</div></td>
  </tr>`;
}

function renderChips() {
  const counts = {};
  STATUSES.forEach(s => counts[s] = 0);
  Object.values(jobs).forEach(j => { counts[j.status] = (counts[j.status] || 0) + 1; });
  const chips = [`<span class="chip ${statusFilter === '' ? 'active' : ''}" onclick="setFilter('')">All <span class="count">${Object.values(jobs).length}</span></span>`];
  STATUSES.forEach(s => {
    chips.push(`<span class="chip ${statusFilter === s ? 'active' : ''}" onclick="setFilter('${s}')">${s} <span class="count">${counts[s]}</span></span>`);
  });
  document.getElementById('chips').innerHTML = chips.join('');
}

async function copyId(id) {
  try { await navigator.clipboard.writeText(id); toast('Job ID copied', 'ok'); }
  catch (e) { toast('Copy failed', 'err'); }
}

async function showDetail(id) {
  const res = await guarded(fetch('/api/wristbands/jobs/' + id));
  if (!res) return;
  if (!res.ok) { toast('Could not load job', 'err'); return; }
  const d = await res.json();
  const rows = [
    ['Job ID', d.jobId], ['Status', d.status], ['Event', d.eventName],
    ['First name', d.firstName], ['Last name', d.lastName],
    ['Association', d.associationName], ['Barcode', d.barcodeValue],
    ['Submitted', d.submittedAt], ['Completed', d.completedAt || '—'],
    ['Error', d.error || '—']
  ].map(([k, v]) => `<div class="detail-row"><span class="k">${k}</span><span class="v">${esc(String(v))}</span></div>`).join('');
  document.getElementById('modal-content').innerHTML =
    `<h2>Job detail</h2>${rows}<button class="btn modal-close" onclick="closeModal()">Close</button>`;
  document.getElementById('modal-overlay').classList.add('open');
}

function closeModal() { document.getElementById('modal-overlay').classList.remove('open'); }

async function reprint(id) {
  const res = await guarded(fetch('/api/wristbands/jobs/' + id + '/reprint', { method: 'POST' }));
  if (!res) return;
  toast(res.ok ? 'Reprint queued' : 'Reprint failed', res.ok ? 'ok' : 'err');
}

async function cancelJob(id) {
  const res = await guarded(fetch('/api/wristbands/jobs/' + id + '/cancel', { method: 'POST' }));
  if (!res) return;
  if (res.ok) toast('Job cancelled', 'ok');
  else if (res.status === 409) toast('Job already started', 'err');
  else toast('Cancel failed', 'err');
}

async function clearCompleted() {
  const res = await guarded(fetch('/api/wristbands/jobs/completed', { method: 'DELETE' }));
  if (!res) return;
  if (res.ok) {
    Object.keys(jobs).forEach(id => {
      const s = jobs[id].status;
      if (s === 'DONE' || s === 'FAILED' || s === 'CANCELLED') delete jobs[id];
    });
    render();
    toast('Completed jobs cleared', 'ok');
  } else { toast('Clear failed', 'err'); }
}

async function logout() {
  try { await fetch('/api/wristbands/logout', { method: 'POST' }); } catch (e) {}
  redirectToLogin();
}

// Wrap a fetch promise; on 401 redirect to login and return null.
async function guarded(promise) {
  try {
    const res = await promise;
    if (res.status === 401) { redirectToLogin(); return null; }
    return res;
  } catch (e) { toast('Network error', 'err'); return null; }
}

function relTime(iso) {
  if (!iso) return '—';
  const diff = (Date.now() - new Date(iso).getTime()) / 1000;
  if (diff < 60) return Math.floor(diff) + 's ago';
  if (diff < 3600) return Math.floor(diff / 60) + 'm ago';
  if (diff < 86400) return Math.floor(diff / 3600) + 'h ago';
  return new Date(iso).toLocaleString();
}

function esc(str) {
  return (str ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function toast(msg, kind) {
  const el = document.createElement('div');
  el.className = 'toast ' + (kind === 'ok' ? 'ok' : 'err');
  el.textContent = msg;
  document.getElementById('toasts').appendChild(el);
  setTimeout(() => el.remove(), 3000);
}
```

- [ ] **Step 3: Manually verify the full flow**

With the app running (local profile) and logged in:
- Page loads the queue (or shows "No jobs."), SSE indicator turns to "● Live".
- Submit a print job (via curl/Swagger) → a row appears live; status transitions in real time.
- The job ID shows truncated with a copy button; clicking copies the full UUID (toast confirms).
- Status chips show live counts; clicking a chip filters; clicking again clears the filter.
- Search filters by ID/event. Clicking column headers sorts (toggles direction).
- "Details" opens the modal with first/last name, association, barcode (fetched from `/jobs/{id}`).
- On a PENDING job, "Cancel" sets it to CANCELLED (or toasts "Job already started" if it just began).
- On DONE/FAILED, "Reprint" queues a new job. "Clear completed" removes DONE/FAILED/CANCELLED.
- "Sign out" calls `/logout` and returns to the login page; reloading `/jobs.html` now redirects to login.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/jobs.html src/main/resources/static/js/jobs.js
git commit -m "feat(ui): rebuild jobs page with dark-glass theme, search, cancel and detail"
```

---

### Task 4: Update README for the new UI

**Files:**
- Modify: `src/main/resources/README.md` → actually `README.md` at repo root.

- [ ] **Step 1: Update the "Job management UI" section**

Replace the body of the "## Job management UI" section with:

```
Open **http://localhost:8080/jobs.html** in a browser (you'll be redirected to
`/login.html` if not signed in).

- Sign in with the admin credential (`security.admin.username` / `security.admin.password`).
  A session is kept in an HttpOnly cookie — no key is stored in the browser.
- The job table updates in real-time via Server-Sent Events.
- Each row shows a truncated job ID with a copy-to-clipboard button; status chips give
  live counts and filter the table; columns are sortable; timestamps are relative.
- **Details** shows the full wristband data for a job. **Cancel** stops a PENDING job.
  **Reprint** re-queues a DONE/FAILED job. **Clear completed** removes DONE/FAILED/CANCELLED.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: document the redesigned admin jobs page"
```

---

## Self-review

**Spec coverage (frontend portion):**
- Separate files: `jobs.html`, `login.html`, `css/app.css`, `js/jobs.js`, `js/login.js` → Tasks 1–3. ✓
- Dark-glass STUP reskin (Poppins, purple gradient, orange CTAs, glass cards, branded badges incl. CANCELLED) → Task 1. ✓
- Full job ID + copy-to-clipboard → Task 3 (`copyId`). ✓
- Search, status chips with counts + filter, sortable columns, relative timestamps, toasts, styled SSE indicator → Task 3. ✓
- Admin login/logout; no secret in JS storage; redirect to login on 401 → Tasks 2 & 3 (`guarded`, `init`, `logout`). ✓
- Cancel button on PENDING (409 → "already started"); Details modal via authenticated `GET /jobs/{id}`; reprint; clear-completed incl. CANCELLED → Task 3. ✓

**Placeholder scan:** No TBD/TODO; complete file contents provided; verification is manual per spec.

**Type/contract consistency:** `js/jobs.js` calls match the endpoints from Plans 1–2: `GET /api/wristbands/jobs`, SSE `/jobs/stream`, `GET /jobs/{id}` (detail fields `firstName/lastName/associationName/barcodeValue`), `POST /jobs/{id}/reprint`, `POST /jobs/{id}/cancel` (409 handled), `DELETE /jobs/completed`, `POST /login`, `POST /logout`. Badge CSS classes match `PrintJobStatus` names including `CANCELLED`. Cookie auth is implicit (same-origin fetch/EventSource send it automatically).

**Fix applied during review:** Task 4 file path corrected to the repo-root `README.md`.

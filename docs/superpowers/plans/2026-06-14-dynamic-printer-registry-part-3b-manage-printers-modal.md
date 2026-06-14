# Dynamic Printer Registry — Part 3b: Manage-printers modal + live SSE (frontend)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. This is **front-end vanilla JS** (no JS test harness) — verification is via the running local cluster + browser, not unit tests.

**Goal:** Give operators a "Manage printers" modal (rename / test / hide / set-default) and make the jobs page react **live** to printer changes via the `printer` SSE event — renames and status changes update the table and filter without a refresh.

**Architecture:** `jobs.js` keeps a client-side `printersById` map (seeded from `GET /api/wristbands/printers`, upserted on each named `printer` SSE event) and renders the printer-name column from it. A Menu-launched modal (reusing the `.modal-overlay` design system) lists all printers with status/default indicators and per-row actions that call the Part 3a admin endpoints; the `printer` SSE event refreshes both the table and the open modal. The printer **filter** excludes hidden printers; the modal shows them.

**Tech Stack:** Vanilla JS + the shared `static/css/app.css` design system (no build step). Backend endpoints from Part 3a; `printer` SSE event from Part 2a.

**Spec:** `docs/superpowers/specs/2026-06-13-dynamic-printer-registry-design.md` (D6 frontend, the modal in D7/D8/D9). Depends on Part 3a (rename/hide/test/default endpoints + enriched `GET /printers`) and Part 2a (the `printer` SSE event) — Part 3a must be merged first.

---

## Context for the implementer (current state of the front-end)

- `src/main/resources/static/js/jobs.js` (639 lines). Relevant:
  - Globals incl. `let printers = [];` (line 6) — will become `let printersById = {};`.
  - `COLUMNS` (lines 19–35): the **Printer** column is `{ key:'printer', label:'Printer', sort:'printerName', cell: j => esc(j.printerName || '—') }` (lines 27–28).
  - `init()` (line 112): fetches `/api/wristbands/jobs` then `/api/wristbands/printers` into `printers`, then `render()` + `connectSse()`.
  - `connectSse()` (line 129): `eventSource.onmessage` handles job updates (line 133). **No named-event listener yet.**
  - The printer **filter** is built in the `syncSelect('filter-printer', …)` block (lines 252–259) from `printers`, shown only when `printers.length > 1`.
  - Top **Menu** dropdown handlers: `toggleNavMenu`/`closeNavMenu` (lines 329–340). Helpers: `toast(msg, kind)` (line 624), `guarded(promise)` (line 595, wraps fetch + 401 handling), `esc(...)`, `relTime(...)`, `fmtDateTime(...)`.
- `src/main/resources/static/jobs.html`:
  - The Menu popover `#nav-menu` (lines 19–24): Gallery, Template editor, Clear completed, Sign out — ADD "Manage printers" here.
  - The printer filter `<select id="filter-printer" … hidden>` (line 38).
  - Existing overlays: `.drawer` (#drawer), `.confirm-overlay`, `#row-menu`. `<script src="/js/jobs.js">` at the end (line 75) — add the modal markup before it.
- `src/main/resources/static/css/app.css`: `.modal-overlay` / `.modal-overlay.open { display:flex }` (line 285), `.modal-close` (line 296), `.btn`/`.btn-sm`/`.btn-primary`/`.btn-danger`, `.badge` (+ status colors), `.menu-item`, `.input`/`.select`. Reuse these; add a small block for the printers panel/table.
- Part 3a backend (must be merged first) provides: `GET /api/wristbands/printers` → `[{id, displayName, online, hidden, isDefault, lastSeenAt}]`; `PATCH /api/wristbands/printers/{id}` `{displayName}`; `POST /api/wristbands/printers/{id}/hide` (409 if online); `POST /api/wristbands/printers/{id}/test` → `{reachable, online}`; `POST /api/wristbands/printers/{id}/default` (409 if hidden). The named `printer` SSE event `{id, displayName, online, hidden, isDefault, lastSeenAt}` is broadcast on every change.

---

## File Structure

**Modify:**
- `src/main/resources/static/js/jobs.js` (printersById map, SSE consumer, printer-column rendering, filter-excludes-hidden, the modal logic + actions)
- `src/main/resources/static/jobs.html` (Menu item + modal markup)
- `src/main/resources/static/css/app.css` (printers panel/table styles)

No backend changes (Part 3a covers those).

---

## Task 1: `printersById` map, live SSE consumer, column rendering, filter excludes hidden

**Files:** `src/main/resources/static/js/jobs.js`

- [ ] **Step 1: Replace the `printers` array with an id-keyed map**

Change `let printers = [];` (line 6) to:
```javascript
let printersById = {};   // id -> { id, displayName, online, hidden, isDefault, lastSeenAt }
```

In `init()`, change the printers fetch to populate the map:
```javascript
  try {
    const pr = await fetch('/api/wristbands/printers');
    if (pr.ok) {
      printersById = {};
      (await pr.json()).forEach(p => { printersById[p.id] = p; });
    }
  } catch (e) { /* the printer filter just won't render */ }
```

- [ ] **Step 2: Render the Printer column from the map (so renames propagate live)**

Replace the Printer column `cell` (lines 27–28) with a helper call:
```javascript
  { key: 'printer',   label: 'Printer',   sort: 'printerName',
    cell: j => printerLabel(j) },
```
Add the helper (near the other small render helpers):
```javascript
// Printer name resolved live from printersById (so a rename repaints all rows);
// falls back to the value captured on the job. A known-offline printer gets a muted dot.
function printerLabel(j) {
  const p = j.printerId ? printersById[j.printerId] : null;
  const name = (p && p.displayName) || j.printerName;
  if (!name) return '<span class="muted">—</span>';
  const dot = p && !p.online ? '<span class="printer-dot off" title="offline"></span>' : '';
  return dot + esc(name);
}
```

- [ ] **Step 3: Consume the named `printer` SSE event**

In `connectSse()`, after the `onmessage` handler, add:
```javascript
  eventSource.addEventListener('printer', (e) => {
    const p = JSON.parse(e.data);
    printersById[p.id] = p;
    render();                       // repaints table cells + rebuilds the filter
    if (isManageOpen()) renderManageModal();
  });
```
(`isManageOpen`/`renderManageModal` are added in Task 2 — for this task you may temporarily guard with `if (typeof renderManageModal === 'function' && isManageOpen())`; once Task 2 lands, the plain form is fine.)

- [ ] **Step 4: Build the printer filter from the map, excluding hidden**

In the `syncSelect('filter-printer', …)` block (lines 252–259), source the printers from `printersById` excluding hidden:
```javascript
  const visiblePrinters = Object.values(printersById).filter(p => !p.hidden);
  const printerSel = document.getElementById('filter-printer');
  if (visiblePrinters && visiblePrinters.length > 1) {
    const pc = {};
    all.forEach(j => { if (j.printerId) pc[j.printerId] = (pc[j.printerId] || 0) + 1; });
    syncSelect('filter-printer', [
      { value: '', label: 'All printers' },
      ...visiblePrinters.map(p => ({ value: p.id, label: `${p.displayName} (${pc[p.id] || 0})` }))
    ]);
    printerSel.hidden = false;
  } else {
    printerSel.hidden = true;
  }
```
(Match the surrounding code's existing show/hide handling for the select; keep the "only when >1 printer" behavior.)

- [ ] **Step 5: Verify (no JS tests — quick syntax + smoke)**

Run a JS syntax check: `node --check src/main/resources/static/js/jobs.js` → no output (valid). Full UI verification happens in Task 4 against the running cluster.

- [ ] **Step 6: Commit**
```bash
git add src/main/resources/static/js/jobs.js
git commit -m "feat(jobs-ui): printersById map + live printer SSE consumer; filter excludes hidden"
```

---

## Task 2: The "Manage printers" modal (markup + open/close/render + styles)

**Files:** `src/main/resources/static/jobs.html`, `src/main/resources/static/js/jobs.js`, `src/main/resources/static/css/app.css`

- [ ] **Step 1: Add the Menu item + modal markup to `jobs.html`**

In the `#nav-menu` popover (after the "Template editor" link, before "Clear completed"), add:
```html
            <button class="menu-item" onclick="openManageModal()">Manage printers</button>
```
Before `<script src="/js/jobs.js"></script>` (line 75), add the modal:
```html
  <div class="modal-overlay" id="manage-overlay" onclick="if (event.target === this) closeManageModal()">
    <div class="manage-panel">
      <button class="modal-close" onclick="closeManageModal()" aria-label="Close">×</button>
      <h2 class="manage-title">Printers</h2>
      <p class="manage-hint">Add a printer by starting its worker container — it registers itself.</p>
      <table class="manage-table"><tbody id="manage-rows"></tbody></table>
    </div>
  </div>
```

- [ ] **Step 2: Add modal open/close/render to `jobs.js`**

```javascript
// ── Manage-printers modal ──────────────────────────────────────────────────────
function isManageOpen() {
  const o = document.getElementById('manage-overlay');
  return o && o.classList.contains('open');
}

function openManageModal() {
  closeNavMenu();
  renderManageModal();
  document.getElementById('manage-overlay').classList.add('open');
}

function closeManageModal() {
  document.getElementById('manage-overlay').classList.remove('open');
}

function renderManageModal() {
  const rows = Object.values(printersById)
    .sort((a, b) => a.id.localeCompare(b.id))
    .map(p => {
      const status = p.online
        ? '<span class="badge DONE">online</span>'
        : '<span class="badge PENDING">offline</span>';
      const hidden = p.hidden ? ' <span class="muted">(hidden)</span>' : '';
      const star = p.isDefault
        ? '<span class="default-star on" title="default printer">★</span>'
        : `<button class="btn btn-sm" onclick="setDefaultPrinter('${p.id}')"
             ${p.hidden ? 'disabled title="hidden printers can\\'t be default"' : ''}>Set default</button>`;
      const hideBtn = p.online
        ? '<button class="btn btn-sm" disabled title="can only hide an offline printer">Hide</button>'
        : (p.hidden ? '' : `<button class="btn btn-sm" onclick="hidePrinter('${p.id}')">Hide</button>`);
      return `<tr>
        <td><input class="input manage-name" id="pname-${p.id}" value="${esc(p.displayName)}">
            <button class="btn btn-sm" onclick="renamePrinter('${p.id}')">Save</button></td>
        <td>${status}${hidden}<div class="muted manage-seen">${p.lastSeenAt ? relTime(p.lastSeenAt) : ''}</div></td>
        <td>${star}</td>
        <td class="manage-actions">
          <button class="btn btn-sm" onclick="testPrinter('${p.id}')">Test</button>
          ${hideBtn}
        </td></tr>`;
    }).join('');
  document.getElementById('manage-rows').innerHTML = rows
    || '<tr><td class="muted">No printers registered. Start a worker container.</td></tr>';
}
```
(The action functions `renamePrinter`/`testPrinter`/`hidePrinter`/`setDefaultPrinter` arrive in Task 3 — the buttons reference them now; they'll be defined before any click can occur.)

- [ ] **Step 3: Add styles to `app.css`** (after the existing modal block):

```css
/* Manage-printers modal */
.manage-panel {
  background: var(--glass-bg); border: 1px solid var(--glass-border);
  border-radius: var(--radius-md); backdrop-filter: blur(12px);
  padding: 24px; width: min(680px, 95vw); max-height: 85vh; overflow-y: auto;
  box-shadow: var(--shadow-card);
}
.manage-title { margin: 0 0 4px; font-size: 1.25rem; }
.manage-hint { color: var(--text-muted); font-size: 0.82rem; margin: 0 0 16px; }
.manage-table { width: 100%; border-collapse: collapse; }
.manage-table td { padding: 10px 8px; border-top: 1px solid var(--glass-border); vertical-align: top; }
.manage-name { width: 200px; margin-right: 6px; }
.manage-actions { display: flex; gap: 6px; }
.manage-seen { font-size: 0.72rem; margin-top: 4px; }
.default-star.on { color: var(--orange); font-size: 1.1rem; }
.printer-dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; margin-right: 6px; }
.printer-dot.off { background: #9e9e9e; }
```

- [ ] **Step 4: Verify** `node --check src/main/resources/static/js/jobs.js`; full UI check in Task 4.

- [ ] **Step 5: Commit**
```bash
git add src/main/resources/static/jobs.html src/main/resources/static/js/jobs.js src/main/resources/static/css/app.css
git commit -m "feat(jobs-ui): manage-printers modal (list, status, default indicator)"
```

---

## Task 3: Modal actions (rename / test / hide / set-default)

**Files:** `src/main/resources/static/js/jobs.js`

- [ ] **Step 1: Add the action functions** (each uses `guarded(fetch(...))` + `toast(...)`; the `printer` SSE event refreshes the table + modal, but also re-render the modal locally for snappiness):

```javascript
async function renamePrinter(id) {
  const v = document.getElementById('pname-' + id).value.trim();
  if (!v) { toast('Name cannot be empty', 'err'); return; }
  const res = await guarded(fetch('/api/wristbands/printers/' + id, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ displayName: v })
  }));
  if (res && res.ok) toast('Renamed', 'ok'); else if (res) toast('Rename failed', 'err');
}

async function testPrinter(id) {
  const res = await guarded(fetch('/api/wristbands/printers/' + id + '/test', { method: 'POST' }));
  if (!res) return;
  if (res.ok) { const r = await res.json(); toast(r.reachable ? 'Printer reachable' : 'Printer unreachable', r.reachable ? 'ok' : 'err'); }
  else toast('Test failed', 'err');
}

async function hidePrinter(id) {
  const res = await guarded(fetch('/api/wristbands/printers/' + id + '/hide', { method: 'POST' }));
  if (res && res.ok) toast('Printer hidden', 'ok');
  else if (res && res.status === 409) toast('Can only hide an offline printer', 'err');
  else if (res) toast('Hide failed', 'err');
}

async function setDefaultPrinter(id) {
  const res = await guarded(fetch('/api/wristbands/printers/' + id + '/default', { method: 'POST' }));
  if (res && res.ok) toast('Default printer set', 'ok');
  else if (res && res.status === 409) toast('A hidden printer cannot be the default', 'err');
  else if (res) toast('Set default failed', 'err');
}
```
(These rely on the `printer` SSE event for the live UI refresh — `renderManageModal()` + `render()` run from the Task 1 listener. If the SSE round-trip ever feels laggy, the functions could call `renderManageModal()` after a successful fetch, but prefer the SSE-driven refresh to keep one source of truth.)

- [ ] **Step 2: Close the modal on Escape (nicety, consistent with the drawer if it does so)** — optional; only add if the drawer has an equivalent Escape handler to mirror. Otherwise skip.

- [ ] **Step 3: Verify** `node --check src/main/resources/static/js/jobs.js`.

- [ ] **Step 4: Commit**
```bash
git add src/main/resources/static/js/jobs.js
git commit -m "feat(jobs-ui): manage-printers actions (rename/test/hide/set-default)"
```

---

## Task 4: Verify against the running cluster

**Files:** none (verification). This replaces unit testing for the front-end.

- [ ] **Step 1: Bring up the local cluster** (Parts 2a/2b/2c/3a must be merged/available on the branch being tested):
```bash
docker compose -f docker-compose.local-cluster.yml up --build -d
```
Open `http://localhost:8080/jobs.html` (login `admin` / `local-admin`).

- [ ] **Step 2: Verify the modal + live updates** (use the browser preview tooling if it can attach to `localhost:8080`, otherwise drive it manually / via the Chrome MCP):
  - Menu ▾ → **Manage printers** opens the modal; `printer-1` shows **online** (worker-1 self-registered). 
  - **Rename** `printer-1` → Save → toast; the **jobs table Printer column updates live** (no refresh) for existing rows, and the filter option label updates.
  - **Test** → toast "Printer reachable" (worker-1 up); stop worker-1 (`docker compose … stop worker-1`), Test again → "unreachable" and the row flips to **offline** live.
  - **Hide** is disabled while online; after the worker is offline, Hide works → the printer disappears from the **filter** dropdown but still shows in the modal as "(hidden)".
  - **Set default** marks the ★; setting it on a hidden printer is disabled/409.
  - Open a second browser tab on jobs.html → a change in one tab appears in the other live (SSE `printer` event fan-out).

- [ ] **Step 3:** Capture a screenshot of the modal for the PR if the tooling supports it. No code change.

---

## Out of scope (YAGNI)
- Adding/removing printers from the browser (add = a worker container; hard delete excluded — both per the spec).
- A background heartbeat-staleness sweep (the modal's Test covers on-demand liveness).

## Self-Review

**Spec coverage (D6 frontend + modal for D7/D8/D9):** `printersById` map seeded from `GET /printers` + upserted on the `printer` SSE event (Task 1); printer-name column rendered from it so renames propagate live (Task 1); filter excludes hidden (Task 1); Menu-launched modal listing all printers with online/offline + default indicators and "add via container" hint (Task 2); rename/test/hide(offline-only)/set-default actions hitting the Part 3a endpoints with 409 handling (Task 3); live cross-tab refresh via SSE (Task 4 verification). ✓

**Placeholder scan:** complete code in every step. The forward references (`renderManageModal`/action functions referenced before their task) are real functions defined in a later task within the same plan and before any user interaction can trigger them; Task 1 Step 3 notes the temporary `typeof` guard until Task 2 lands. ✓

**Consistency:** reuses `toast`/`guarded`/`esc`/`relTime` and the `.modal-overlay`/`.btn`/`.badge`/`.input` design system; endpoint URLs + the `{reachable}` response shape match Part 3a; the `printer` SSE event field shape matches Part 2a/3a. ✓

**Verification approach:** front-end has no JS test harness (per the repo's build-step-free vanilla JS), so `node --check` guards syntax and the running-cluster walkthrough (Task 4) is the behavioral verification — matching how the repo validates UI changes.

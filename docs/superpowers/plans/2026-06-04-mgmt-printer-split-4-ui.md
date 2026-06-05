# Management/Printer Split — Phase 3: Jobs-page UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or executing-plans. Checkbox (`- [ ]`) steps. Frontend is verified visually by the user (no Java test covers it); the backend reprint change is unit-tested.

**Goal:** Surface the printer on the jobs page — a Printer column in the table, a Printer row in the detail drawer, per-printer filter chips, and a reprint that lets the operator pick a printer (auto when there is only one).

**Architecture:** The backend already returns `printerId`/`printerName` on job responses and exposes `GET /api/wristbands/printers`. This phase adds an optional `printerId` to the reprint endpoint and renders the printer in the static UI ([jobs.html](src/main/resources/static/jobs.html), [jobs.js](src/main/resources/static/js/jobs.js)).

**Branch:** `feat/printer-worker-split` (continue; do not switch).

---

### Task 1: Reprint accepts a target printer (backend)

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/controller/WristbandController.java`
- Modify: `src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java`

- [ ] **Step 1: Update the reprint endpoint**

Accept an optional `printerId` query parameter; when present (and non-blank) reprint on that printer, else reprint on the original job's printer (current behavior). Replace the `reprint` method:

```java
    @PostMapping("/jobs/{jobId}/reprint")
    @Operation(summary = "Reprint a previous job using the same data, optionally on a chosen printer")
    public ResponseEntity<PrintJobResponse> reprint(@PathVariable UUID jobId,
                                                    @RequestParam(required = false) String printerId) {
        return printQueueService.getJob(jobId)
            .map(original -> {
                WristbandPrintRequest req = original.getRequest();
                if (printerId != null && !printerId.isBlank()) {
                    req = copyWithPrinter(req, printerId);
                }
                PrintJob newJob = printQueueService.enqueue(req);
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(newJob.toResponse());
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /** Copy a request, overriding only the target printer (so the original job's request is untouched). */
    private static WristbandPrintRequest copyWithPrinter(WristbandPrintRequest src, String printerId) {
        WristbandPrintRequest copy = new WristbandPrintRequest();
        copy.setEventName(src.getEventName());
        copy.setFirstName(src.getFirstName());
        copy.setLastName(src.getLastName());
        copy.setAssociationName(src.getAssociationName());
        copy.setBarcodeValue(src.getBarcodeValue());
        copy.setTemplateId(src.getTemplateId());
        copy.setPrinterId(printerId);
        return copy;
    }
```

- [ ] **Step 2: Tests**

In `WristbandControllerTest.java`, add two tests (match the file's existing MockMvc style and how it stubs `printQueueService.getJob`/`enqueue`):
- `reprint_withPrinterId_enqueuesRequestTargetingThatPrinter`: stub `getJob` to return a job, capture the `WristbandPrintRequest` passed to `enqueue` (Mockito `ArgumentCaptor`), POST `/api/wristbands/jobs/{id}/reprint?printerId=printer-2`, assert the captured request's `getPrinterId()` is `printer-2`.
- `reprint_withoutPrinterId_reusesOriginalRequest`: POST without the param, assert `enqueue` is called with the original request instance (or a request whose `printerId` equals the original's).

Stub `enqueue(any())` to return a job so the 202 path completes.

- [ ] **Step 3: Run + commit**

Run: `./mvnw -q test -Dtest=WristbandControllerTest` → PASS. Then `./mvnw test` → full suite green.

```bash
git add src/main/java/com/stup/wristbandprinter/controller/WristbandController.java \
        src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java
git commit -m "feat: reprint can target a chosen printer"
```

---

### Task 2: Printer column + drawer row (frontend)

**Files:**
- Modify: `src/main/resources/static/jobs.html`
- Modify: `src/main/resources/static/js/jobs.js`

- [ ] **Step 1: Table header + colspans (`jobs.html`)**

Add a sortable Printer header after the Event header:
```html
            <th onclick="sortBy('printerName')">Printer</th>
```
Update BOTH placeholder rows from `colspan="7"` to `colspan="8"` (the "Loading…" and "No jobs." rows).

- [ ] **Step 2: Table cell (`jobs.js` `rowHtml`)**

After the Event `<td>`, add:
```javascript
    <td>${esc(job.printerName || '—')}</td>
```

- [ ] **Step 3: Drawer row (`jobs.js` `showDetail`)**

In the `rows` array, add a Printer entry (after Event):
```javascript
    ['Printer', d.printerName || '—'],
```

- [ ] **Step 4: Visual check (user)** — table shows a Printer column; legacy jobs show `—`; drawer shows Printer. Commit:

```bash
git add src/main/resources/static/jobs.html src/main/resources/static/js/jobs.js
git commit -m "feat: show printer in the jobs table and detail drawer"
```

---

### Task 3: Per-printer filter chips (frontend)

**Files:**
- Modify: `src/main/resources/static/jobs.html`
- Modify: `src/main/resources/static/js/jobs.js`

- [ ] **Step 1: Container (`jobs.html`)**

Add a second chips row under the existing status chips:
```html
    <div class="chips" id="printer-chips"></div>
```
(immediately after `<div class="chips" id="chips"></div>`).

- [ ] **Step 2: State + fetch (`jobs.js`)**

Add near the top: `let printerFilter = '';` and `let printers = [];`.

In `init()`, after the auth-gated jobs load, fetch the printers (non-fatal if it fails):
```javascript
  try {
    const pr = await fetch('/api/wristbands/printers');
    if (pr.ok) printers = await pr.json();
  } catch (e) { /* chips just won't render */ }
```

- [ ] **Step 3: Render chips + filter (`jobs.js`)**

Add a `renderPrinterChips()` and call it from `render()` (after `renderChips()`), and add a `setPrinterFilter`:
```javascript
function setPrinterFilter(id) { printerFilter = (printerFilter === id) ? '' : id; render(); }

function renderPrinterChips() {
  const el = document.getElementById('printer-chips');
  if (!printers || printers.length < 2) { el.innerHTML = ''; return; }  // no point with one printer
  const counts = {};
  Object.values(jobs).forEach(j => { if (j.printerId) counts[j.printerId] = (counts[j.printerId] || 0) + 1; });
  const chips = [`<span class="chip ${printerFilter === '' ? 'active' : ''}" onclick="setPrinterFilter('')">All printers</span>`];
  printers.forEach(p => {
    chips.push(`<span class="chip ${printerFilter === p.id ? 'active' : ''}" onclick="setPrinterFilter('${p.id}')">${esc(p.displayName)} <span class="count">${counts[p.id] || 0}</span></span>`);
  });
  el.innerHTML = chips.join('');
}
```

In `render()`, add the printer filter to the existing filter chain:
```javascript
    .filter(j => !printerFilter || j.printerId === printerFilter)
```
(insert alongside the existing `statusFilter` / search filters), and call `renderPrinterChips();` near the `renderChips();` call.

- [ ] **Step 4: Visual check (user)** — with ≥2 configured printers, a second chip row appears; clicking filters the table; counts are correct. Commit:

```bash
git add src/main/resources/static/jobs.html src/main/resources/static/js/jobs.js
git commit -m "feat: per-printer filter chips on the jobs page"
```

---

### Task 4: Reprint printer picker (frontend)

**Files:**
- Modify: `src/main/resources/static/js/jobs.js`

- [ ] **Step 1: Picker dialog (reusing the confirm overlay markup)**

Add a `choosePrinter()` that resolves to a printer id (or null if cancelled), built on the existing `#confirm-overlay` / `.confirm-card` structure:
```javascript
function choosePrinter() {
  return new Promise(resolve => {
    const overlay = document.getElementById('confirm-overlay');
    const card = overlay.querySelector('.confirm-card');
    const prevHtml = card.innerHTML;
    const buttons = printers.map(p =>
      `<button class="btn btn-sm" data-printer="${p.id}">${esc(p.displayName)}</button>`).join('');
    card.innerHTML = `<div>Reprint on which printer?</div>
      <div class="confirm-actions" style="flex-wrap:wrap">${buttons}
      <button class="btn" data-printer="">Cancel</button></div>`;
    overlay.classList.add('open');
    const done = (id) => {
      overlay.classList.remove('open');
      card.innerHTML = prevHtml;   // restore the original confirm markup
      resolve(id);
    };
    card.querySelectorAll('button[data-printer]').forEach(b =>
      b.onclick = () => done(b.getAttribute('data-printer') || null));
  });
}
```

- [ ] **Step 2: Use it in `reprint`**

Change `reprint(id)` to pick a printer when there are several:
```javascript
async function reprint(id) {
  let printerId = null;
  if (printers && printers.length > 1) {
    printerId = await choosePrinter();
    if (printerId === null) return;        // cancelled
  }
  const url = '/api/wristbands/jobs/' + id + '/reprint' + (printerId ? ('?printerId=' + encodeURIComponent(printerId)) : '');
  const res = await guarded(fetch(url, { method: 'POST' }));
  if (!res) return;
  toast(res.ok ? 'Reprint queued' : 'Reprint failed', res.ok ? 'ok' : 'err');
}
```
(With one printer, behavior is unchanged — no prompt, no `printerId` param.)

- [ ] **Step 3: Visual check (user)** — with ≥2 printers, Reprint asks which printer and routes there; with one printer, it reprints immediately. Commit:

```bash
git add src/main/resources/static/js/jobs.js
git commit -m "feat: reprint printer picker on the jobs page"
```

---

## Self-Review

- **Spec coverage (phase 3):** Printer column (Task 2), drawer row (Task 2), filter chips (Task 3), reprint picker + backend routing (Tasks 1, 4). Legacy/null printer renders `—`; chips hidden when <2 printers; reprint auto when 1 printer.
- **Placeholder scan:** No TBD/TODO; concrete code throughout. Frontend verified visually by the user (stated approach); backend reprint change is unit-tested.
- **Type/contract consistency:** uses `job.printerId`/`job.printerName` (already on `PrintJobResponse`), `GET /api/wristbands/printers` returning `{id, displayName}`, and `POST /jobs/{id}/reprint?printerId=` (added in Task 1). `copyWithPrinter` leaves the original request untouched.

## Out of scope

- Production `docker-compose.prod.yml` worker services + TLS trust between management and workers (separate deploy task).
- Symfony-side consumption of the per-job stream.

# Jobs Page — Name Column + Detail Drawer + Wristband Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the person's name in each queue row, replace the detail modal with a right-side slide-in drawer, and add an on-demand "Show preview" button in the drawer that renders the wristband as a PNG via the existing Labelary viewer.

**Architecture:** Backend adds `firstName`/`lastName` to the lean `PrintJobResponse` (list/SSE) and a new authenticated `GET /jobs/{id}/preview` endpoint that reuses `WristbandLayoutService` + `ZplGeneratorService` + `LabelaryPreviewService`. Frontend (plain static files) gains a Name column, a drawer replacing the modal, and an `<img>`-based preview triggered by a button (browser sends the admin cookie same-origin).

**Tech Stack:** Java 21, Spring Boot 3.4.1, Spring MVC, JUnit 5 + Mockito + AssertJ; vanilla HTML/CSS/JS.

**Spec:** `docs/superpowers/specs/2026-06-01-jobs-detail-drawer-design.md`
**Branch:** `feat/jobs-page` (jobs-page redesign already implemented here).

**Test note:** backend tests run against Testcontainers Postgres (Docker required; surefire pins `-Dapi.version=1.44`). Frontend is verified manually in a browser.

---

### Task 1: Add name to the list/SSE payload

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/domain/PrintJobResponse.java`
- Modify: `src/main/java/com/stup/wristbandprinter/domain/PrintJob.java`
- Test: `src/test/java/com/stup/wristbandprinter/domain/PrintJobTest.java`

- [ ] **Step 1: Write the failing test**

Add to `PrintJobTest`:

```java
    @Test
    void toResponse_includesName() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setAssociationName("STUP vzw");
        r.setBarcodeValue("123456789");
        PrintJob job = new PrintJob(java.util.UUID.randomUUID(), r);

        PrintJobResponse resp = job.toResponse();

        assertThat(resp.firstName()).isEqualTo("Jan");
        assertThat(resp.lastName()).isEqualTo("Janssens");
        assertThat(resp.eventName()).isEqualTo("Pukkelpop 2026");
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=PrintJobTest#toResponse_includesName`
Expected: FAIL — `firstName()`/`lastName()` do not exist on `PrintJobResponse`.

- [ ] **Step 3: Add the fields to the record**

Replace `PrintJobResponse.java` body:

```java
package com.stup.wristbandprinter.domain;

import java.time.Instant;
import java.util.UUID;

public record PrintJobResponse(
    UUID jobId,
    PrintJobStatus status,
    String eventName,
    String firstName,
    String lastName,
    Instant submittedAt,
    Instant completedAt,
    String error
) {}
```

- [ ] **Step 4: Update `PrintJob.toResponse()`**

In `PrintJob.java`, replace the `toResponse()` body's `new PrintJobResponse(...)` call with:

```java
        return new PrintJobResponse(
            jobId,
            status,
            request.getEventName(),
            request.getFirstName(),
            request.getLastName(),
            submittedAt,
            completedAt,
            error
        );
```

- [ ] **Step 5: Run the test, then the full suite**

Run: `./mvnw test -Dtest=PrintJobTest`
Expected: PASS.
Run: `./mvnw test`
Expected: `BUILD SUCCESS` (existing consumers of `toResponse()` are unaffected; the integration test reads jobs by name-mapped JSON).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/PrintJobResponse.java \
        src/main/java/com/stup/wristbandprinter/domain/PrintJob.java \
        src/test/java/com/stup/wristbandprinter/domain/PrintJobTest.java
git commit -m "feat: include first/last name in the job list payload"
```

---

### Task 2: Wristband preview endpoint (`GET /jobs/{id}/preview`)

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/controller/WristbandController.java`
- Test: `src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `WristbandControllerTest` (the class has `mockMvc`, the `@MockitoBean`s `printQueueService`/`wristbandLayoutService`/`zplGeneratorService`/`labelaryPreviewService`, and `test-key`):

```java
    @Test
    void jobPreview_returnsPng() throws Exception {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setAssociationName("STUP vzw");
        r.setBarcodeValue("123456789");
        UUID id = UUID.randomUUID();
        Mockito.when(printQueueService.getJob(id))
            .thenReturn(java.util.Optional.of(new PrintJob(id, r)));
        Mockito.when(wristbandLayoutService.buildData(Mockito.any()))
            .thenReturn(new WristbandData("Pukkelpop 2026", "Jan", "Janssens", "STUP vzw", "123456789"));
        Mockito.when(zplGeneratorService.generate(Mockito.any())).thenReturn("^XA^XZ");
        Mockito.when(labelaryPreviewService.renderPreview(Mockito.any()))
            .thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/api/wristbands/jobs/" + id + "/preview")
                .header("X-API-Key", "test-key"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void jobPreview_unknownJob_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.when(printQueueService.getJob(id)).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/wristbands/jobs/" + id + "/preview")
                .header("X-API-Key", "test-key"))
            .andExpect(status().isNotFound());
    }
```

Ensure these static imports / imports exist in the file (add any missing):
`import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;`
`import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;`
`import org.springframework.http.MediaType;`
`import com.stup.wristbandprinter.domain.WristbandData;`

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest=WristbandControllerTest#jobPreview_returnsPng+jobPreview_unknownJob_returns404`
Expected: FAIL — endpoint returns 404/no mapping for the preview path.

- [ ] **Step 3: Add the endpoint**

In `WristbandController.java`, add after the `getJob` handler:

```java
    @GetMapping(value = "/jobs/{jobId}/preview", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Render a job's wristband as a PNG via Labelary")
    public ResponseEntity<byte[]> jobPreview(@PathVariable UUID jobId) {
        return printQueueService.getJob(jobId)
            .<ResponseEntity<byte[]>>map(job -> {
                WristbandData data = wristbandLayoutService.buildData(job.getRequest());
                String zpl = zplGeneratorService.generate(data);
                byte[] png = labelaryPreviewService.renderPreview(zpl);
                return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
```

- [ ] **Step 4: Run the tests, then the full suite**

Run: `./mvnw test -Dtest=WristbandControllerTest`
Expected: PASS.
Run: `./mvnw test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/controller/WristbandController.java \
        src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java
git commit -m "feat: render a job's wristband preview by id"
```

---

### Task 3: Name column + name search (frontend)

**Files:**
- Modify: `src/main/resources/static/jobs.html`
- Modify: `src/main/resources/static/js/jobs.js`

- [ ] **Step 1: Add the Name column header and widen the loading row**

In `jobs.html`, replace the `<thead>` row and the loading `<tbody>` row:

```html
        <thead>
          <tr>
            <th onclick="sortBy('jobId')">Job ID</th>
            <th onclick="sortBy('firstName')">Name</th>
            <th onclick="sortBy('eventName')">Event</th>
            <th onclick="sortBy('status')">Status</th>
            <th onclick="sortBy('submittedAt')">Submitted</th>
            <th onclick="sortBy('completedAt')">Completed</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody id="jobs-body">
          <tr><td colspan="7" class="empty">Loading…</td></tr>
        </tbody>
```

And update the search placeholder:

```html
        <input class="input" id="search" type="text" placeholder="Search by name, job ID or event…" oninput="render()">
```

- [ ] **Step 2: Render the name cell, widen empty row, and search by name**

In `js/jobs.js`:

Replace the empty-list line in `render()`:

```javascript
    tbody.innerHTML = '<tr><td colspan="7" class="empty">No jobs.</td></tr>';
```

Replace the search filter in `render()` with one that also matches the name:

```javascript
    .filter(j => !search
      || j.jobId.toLowerCase().includes(search)
      || (j.eventName || '').toLowerCase().includes(search)
      || ((j.firstName || '') + ' ' + (j.lastName || '')).toLowerCase().includes(search));
```

In `rowHtml(job)`, add the Name cell immediately after the Job ID `<td>` (before the event cell):

```javascript
    <td>${esc(((job.firstName || '') + ' ' + (job.lastName || '')).trim())}</td>
```

- [ ] **Step 3: Manually verify**

Rebuild/restart the app; with a job in the queue, the table shows a **Name** column, searching by name filters rows, and clicking the Name header sorts. (Backend Task 1 must be running so the list payload carries the name.)

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/jobs.html src/main/resources/static/js/jobs.js
git commit -m "feat(ui): show person name column and search by name"
```

---

### Task 4: Slide-in drawer with on-demand wristband preview (frontend)

**Files:**
- Modify: `src/main/resources/static/jobs.html`
- Modify: `src/main/resources/static/css/app.css`
- Modify: `src/main/resources/static/js/jobs.js`

- [ ] **Step 1: Replace the modal markup with the drawer**

In `jobs.html`, replace this block:

```html
  <div class="modal-overlay" id="modal-overlay">
    <div class="glass modal" id="modal-content"></div>
  </div>
```

with:

```html
  <div class="drawer-overlay" id="drawer-overlay" onclick="closeDrawer()"></div>
  <aside class="drawer" id="drawer" aria-hidden="true">
    <div id="drawer-content"></div>
  </aside>
```

- [ ] **Step 2: Replace the Modal CSS block with Drawer CSS**

In `css/app.css`, replace the entire `/* Modal */` section (from `.modal-overlay { ... }` through `.modal-close { ... }`) with:

```css
/* Drawer */
.drawer-overlay {
  position: fixed; inset: 0; background: rgba(0, 0, 0, 0.5);
  opacity: 0; pointer-events: none; transition: opacity var(--t-fast); z-index: 90;
}
.drawer-overlay.open { opacity: 1; pointer-events: auto; }
.drawer {
  position: fixed; top: 0; right: 0; height: 100%; width: 440px; max-width: 92vw;
  background: var(--purple-dark); border-left: 1px solid var(--glass-border);
  box-shadow: var(--shadow-card); transform: translateX(100%);
  transition: transform 0.25s ease; z-index: 91; overflow-y: auto; padding: 24px;
}
.drawer.open { transform: translateX(0); }
.drawer h2 { margin: 0 0 16px; font-size: 1.15rem; }
.detail-row {
  display: flex; justify-content: space-between; gap: 16px; padding: 8px 0;
  border-bottom: 1px solid hsla(0, 0%, 100%, 0.06);
}
.detail-row .k { color: var(--text-muted); }
.detail-row .v { text-align: right; word-break: break-all; }
.drawer-actions { display: flex; gap: 8px; margin: 18px 0; }
.preview-section { margin-top: 16px; }
.wristband-preview { width: 100%; margin-top: 12px; border-radius: var(--radius-md); background: #fff; }
.drawer-close { width: 100%; margin-top: 18px; }
```

- [ ] **Step 3: Replace `showDetail`/`closeModal` with drawer logic + preview**

In `js/jobs.js`, replace the `showDetail` and `closeModal` functions with:

```javascript
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

  const actions = [];
  if (d.status === 'PENDING') {
    actions.push(`<button class="btn btn-sm" onclick="cancelJob('${d.jobId}'); closeDrawer()">Cancel</button>`);
  }
  if (d.status === 'DONE' || d.status === 'FAILED') {
    actions.push(`<button class="btn btn-sm btn-primary" onclick="reprint('${d.jobId}')">Reprint</button>`);
  }

  document.getElementById('drawer-content').innerHTML = `
    <h2>Job detail</h2>
    ${rows}
    <div class="drawer-actions">${actions.join('')}</div>
    <div class="preview-section">
      <button class="btn btn-sm" onclick="showPreview('${d.jobId}')">Show preview</button>
      <div id="preview-box"></div>
    </div>
    <button class="btn drawer-close" onclick="closeDrawer()">Close</button>`;

  document.getElementById('drawer').classList.add('open');
  document.getElementById('drawer').setAttribute('aria-hidden', 'false');
  document.getElementById('drawer-overlay').classList.add('open');
}

function closeDrawer() {
  document.getElementById('drawer').classList.remove('open');
  document.getElementById('drawer').setAttribute('aria-hidden', 'true');
  document.getElementById('drawer-overlay').classList.remove('open');
}

function showPreview(id) {
  const box = document.getElementById('preview-box');
  box.innerHTML = '<div class="muted" style="padding:12px 0">Rendering…</div>';
  const img = new Image();
  img.className = 'wristband-preview';
  img.alt = 'Wristband preview';
  img.onload = () => { box.innerHTML = ''; box.appendChild(img); };
  img.onerror = () => {
    box.innerHTML = '<div class="error-text" style="padding:12px 0">Preview unavailable</div>';
  };
  img.src = '/api/wristbands/jobs/' + id + '/preview';
}
```

- [ ] **Step 4: Close the drawer on Escape**

In `js/jobs.js`, add at the end of the file:

```javascript
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeDrawer(); });
```

- [ ] **Step 5: Manually verify**

Restart the app and sign in. Clicking **Details** (or a row) slides a panel in from the right with all fields + Reprint/Cancel. **Show preview** renders the wristband image inline (white PNG); if Labelary is unreachable it shows "Preview unavailable". Close via the button, the overlay, or `Esc`. Cancel/Reprint from the drawer behave like the row buttons.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/jobs.html src/main/resources/static/css/app.css \
        src/main/resources/static/js/jobs.js
git commit -m "feat(ui): replace detail modal with slide-in drawer + on-demand preview"
```

---

### Task 5: README note

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the "Job management UI" bullet list**

In `README.md`, in the "## Job management UI" section, replace the bullet describing the table/details with:

```
- Each row shows the person's **name**, event, status, a truncated job ID (with copy),
  relative timestamps, and per-job actions. Status chips give live counts and filter; columns sort.
- Clicking a row opens a **slide-in detail drawer** with the full wristband data
  (name, association, barcode, timestamps) and a **Show preview** button that renders
  the wristband image via Labelary on demand.
- **Cancel** stops a PENDING job; **Reprint** re-queues a DONE/FAILED job;
  **Clear completed** removes DONE/FAILED/CANCELLED.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: describe name column, detail drawer and wristband preview"
```

---

## Self-review

**Spec coverage:**
- Name column (first+last) + search by name → Tasks 1 & 3. ✓
- `firstName`/`lastName` added to list/SSE payload (`PrintJobResponse`/`toResponse`) → Task 1. ✓
- Slide-in drawer replacing the modal, all fields + actions → Task 4. ✓
- On-demand "Show preview" button → new `GET /jobs/{id}/preview` (Task 2) + drawer button/img (Task 4). ✓
- Graceful "preview unavailable" fallback → Task 4 (`img.onerror`). ✓
- Barcode/association remain drawer-only (not table columns) → Tasks 3/4. ✓

**Placeholder scan:** No TBD/TODO; every step has complete code and exact commands.

**Type consistency:** `PrintJobResponse` field order (`jobId, status, eventName, firstName, lastName, submittedAt, completedAt, error`) matches `toResponse()` in Task 1. Frontend reads `job.firstName`/`job.lastName` (Task 3) which Task 1 puts on the payload. Preview endpoint path `/jobs/{id}/preview` matches the `img.src` in Task 4. `showDetail`/`closeDrawer`/`showPreview` are defined in Task 4 and referenced by `rowHtml` (existing `showDetail` call) and the drawer markup.

**Note:** `img.onerror` also fires on a `401` (treated as "preview unavailable" rather than redirecting to login). Acceptable — the drawer only opens for an already-authenticated session; a mid-session expiry is an edge case that still shows a sensible message.

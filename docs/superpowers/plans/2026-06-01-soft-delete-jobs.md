# Soft-Delete Completed Jobs + Styled Confirm — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** "Clear completed" asks for confirmation (styled dialog) and performs a **soft delete** — DONE/FAILED/CANCELLED jobs get a `deleted` flag, vanish from the UI, but stay in the DB (restore is a manual `UPDATE`).

**Architecture:** Add a `deleted` boolean to `print_jobs` (Flyway V2). The store loads only non-deleted rows and "clears" by flagging instead of removing; the enqueue rollback keeps a real hard delete. Frontend gains a reusable on-brand confirm dialog.

**Tech Stack:** Java 21, Spring Boot 3.4.1, Spring Data JPA, Flyway, PostgreSQL, Testcontainers; vanilla HTML/CSS/JS.

**Spec:** `docs/superpowers/specs/2026-06-01-soft-delete-jobs-design.md`
**Branch:** `feat/jobs-page`
**Test note:** backend tests use Testcontainers Postgres (Docker required; surefire pins `-Dapi.version=1.44`).

---

### Task 1: Add the `deleted` column (Flyway V2) + entity field

**Files:**
- Create: `src/main/resources/db/migration/V2__add_deleted_flag.sql`
- Modify: `src/main/java/com/stup/wristbandprinter/persistence/PrintJobEntity.java`

- [ ] **Step 1: Create the migration**

`src/main/resources/db/migration/V2__add_deleted_flag.sql`:

```sql
ALTER TABLE print_jobs ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
```

- [ ] **Step 2: Map the column on the entity**

In `PrintJobEntity.java`, add the field after `error` (line 34) and a getter after `getError()`:

```java
    private boolean deleted;
```

```java
    public boolean isDeleted() { return deleted; }
```

(New rows are written via the existing all-args constructor, which doesn't set `deleted`, so it defaults to `false` — correct for active jobs. Hibernate populates it on load.)

- [ ] **Step 3: Verify compile**

Run: `./mvnw -q -DskipTests test-compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/migration/V2__add_deleted_flag.sql \
        src/main/java/com/stup/wristbandprinter/persistence/PrintJobEntity.java
git commit -m "feat: add deleted flag to print_jobs (Flyway V2)"
```

---

### Task 2: Soft-delete plumbing (repository, store, service) + tests

This task renames `JobStore.loadAll()`→`loadActive()` and `deleteCompleted()`→`softDeleteCompleted()`, so all implementers/callers/tests change together to keep the build compiling.

**Files:**
- Modify: `persistence/PrintJobRepository.java`
- Modify: `persistence/JobStore.java`
- Modify: `persistence/JpaJobStore.java`
- Modify: `service/PrintQueueService.java`
- Modify: `test/.../persistence/JpaJobStoreTest.java`
- Modify: `test/.../service/PrintQueueServiceTest.java`

- [ ] **Step 1: Write the failing JPA tests**

Replace the body of `JpaJobStoreTest` (keep the class annotations and container) — add the repository injection and replace the two test methods:

```java
    @Autowired
    private JpaJobStore store;

    @Autowired
    private PrintJobRepository repository;

    @Test
    void saveAndLoad_roundTripsAllFields() {
        UUID id = UUID.randomUUID();
        Instant submitted = Instant.now();
        store.save(PrintJob.restore(id, request(), PrintJobStatus.DONE, submitted, submitted, null));

        List<PrintJob> loaded = store.loadActive();

        assertThat(loaded).hasSize(1);
        PrintJob job = loaded.get(0);
        assertThat(job.getJobId()).isEqualTo(id);
        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.DONE);
        assertThat(job.getRequest().getEventName()).isEqualTo("Pukkelpop 2026");
        assertThat(job.getRequest().getBarcodeValue()).isEqualTo("123456789");
    }

    @Test
    void softDeleteCompleted_flagsTerminalRowsButKeepsThem() {
        store.save(PrintJob.restore(UUID.randomUUID(), request(), PrintJobStatus.DONE, Instant.now(), Instant.now(), null));
        store.save(PrintJob.restore(UUID.randomUUID(), request(), PrintJobStatus.FAILED, Instant.now(), Instant.now(), "boom"));
        store.save(PrintJob.restore(UUID.randomUUID(), request(), PrintJobStatus.PENDING, Instant.now(), null, null));

        store.softDeleteCompleted();

        List<PrintJob> active = store.loadActive();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getStatus()).isEqualTo(PrintJobStatus.PENDING);
        assertThat(repository.count()).isEqualTo(3); // rows still present (soft, not hard, delete)
    }

    @Test
    void deleteById_hardRemovesRow() {
        UUID id = UUID.randomUUID();
        store.save(PrintJob.restore(id, request(), PrintJobStatus.DONE, Instant.now(), Instant.now(), null));
        store.deleteById(id);
        assertThat(repository.count()).isZero();
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw test -Dtest=JpaJobStoreTest`
Expected: FAIL — compile error (`loadActive`/`softDeleteCompleted` don't exist).

- [ ] **Step 3: Repository methods**

Replace `PrintJobRepository.java` with:

```java
package com.stup.wristbandprinter.persistence;

import com.stup.wristbandprinter.domain.PrintJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PrintJobRepository extends JpaRepository<PrintJobEntity, UUID> {

    List<PrintJobEntity> findByDeletedFalse();

    @Modifying
    @Query("update PrintJobEntity e set e.deleted = true where e.status in :statuses and e.deleted = false")
    int softDeleteByStatusIn(Collection<PrintJobStatus> statuses);
}
```

- [ ] **Step 4: JobStore interface — rename methods**

In `JobStore.java`, change the two method signatures:

```java
    List<PrintJob> loadActive();
```
```java
    void softDeleteCompleted();
```

(keep `save(PrintJob)` and `deleteById(UUID)` as-is.)

- [ ] **Step 5: JpaJobStore — implement**

In `JpaJobStore.java`, replace the `loadAll()` and `deleteCompleted()` methods with:

```java
    @Override
    @Transactional(readOnly = true)
    public List<PrintJob> loadActive() {
        return repository.findByDeletedFalse().stream().map(JpaJobStore::toDomain).toList();
    }

    @Override
    @Transactional
    public void softDeleteCompleted() {
        repository.softDeleteByStatusIn(
            List.of(PrintJobStatus.DONE, PrintJobStatus.FAILED, PrintJobStatus.CANCELLED));
    }
```

(`save` and `deleteById` are unchanged.)

- [ ] **Step 6: PrintQueueService — use the renamed methods**

In `PrintQueueService.java`:
- In `recoverJobs()`, change `jobStore.loadAll()` to `jobStore.loadActive()`.
- In `clearCompleted()`, change `jobStore.deleteCompleted()` to `jobStore.softDeleteCompleted()`.

- [ ] **Step 7: Update the in-memory fake in PrintQueueServiceTest**

In `PrintQueueServiceTest.java`, add imports `java.util.HashSet;` and `java.util.Set;` (alongside the existing `java.util.*` single imports), then replace the `InMemoryJobStore` class with:

```java
    /** Minimal in-memory JobStore so the queue service can be unit-tested without a database. */
    private static class InMemoryJobStore implements JobStore {
        private final Map<UUID, PrintJob> store = new LinkedHashMap<>();
        private final Set<UUID> deleted = new HashSet<>();

        @Override
        public void save(PrintJob job) {
            store.put(job.getJobId(), job);
        }

        @Override
        public List<PrintJob> loadActive() {
            List<PrintJob> active = new ArrayList<>();
            store.forEach((id, job) -> { if (!deleted.contains(id)) active.add(job); });
            return active;
        }

        @Override
        public void deleteById(UUID jobId) {
            store.remove(jobId);
            deleted.remove(jobId);
        }

        @Override
        public void softDeleteCompleted() {
            store.forEach((id, job) -> {
                if (job.getStatus() == PrintJobStatus.DONE
                    || job.getStatus() == PrintJobStatus.FAILED
                    || job.getStatus() == PrintJobStatus.CANCELLED) {
                    deleted.add(id);
                }
            });
        }
    }
```

- [ ] **Step 8: Run the backend tests**

Run: `./mvnw test -Dtest=JpaJobStoreTest,PrintQueueServiceTest`
Expected: PASS (JpaJobStoreTest 3, PrintQueueServiceTest all green). The existing `clearCompleted_*` tests still pass because the service still empties its in-memory map.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/persistence/PrintJobRepository.java \
        src/main/java/com/stup/wristbandprinter/persistence/JobStore.java \
        src/main/java/com/stup/wristbandprinter/persistence/JpaJobStore.java \
        src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java \
        src/test/java/com/stup/wristbandprinter/persistence/JpaJobStoreTest.java \
        src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java
git commit -m "feat: soft-delete completed jobs instead of removing them"
```

---

### Task 3: Styled confirm dialog on "Clear completed"

**Files:**
- Modify: `src/main/resources/static/jobs.html`
- Modify: `src/main/resources/static/css/app.css`
- Modify: `src/main/resources/static/js/jobs.js`

- [ ] **Step 1: Add the confirm-dialog markup**

In `jobs.html`, immediately after the drawer markup (the `<aside class="drawer" …></aside>` block) and before `<div id="toasts"></div>`, add:

```html
  <div class="confirm-overlay" id="confirm-overlay">
    <div class="glass confirm-card">
      <div id="confirm-message"></div>
      <div class="confirm-actions">
        <button class="btn" id="confirm-cancel">Cancel</button>
        <button class="btn btn-danger" id="confirm-ok">Confirm</button>
      </div>
    </div>
  </div>
```

- [ ] **Step 2: Add the confirm-dialog styles**

In `css/app.css`, after the `.drawer-close { … }` rule, add:

```css
/* Confirmation dialog */
.confirm-overlay {
  position: fixed; inset: 0; background: rgba(0, 0, 0, 0.6);
  display: flex; align-items: center; justify-content: center; padding: 20px;
  opacity: 0; pointer-events: none; transition: opacity var(--t-fast); z-index: 110;
}
.confirm-overlay.open { opacity: 1; pointer-events: auto; }
.confirm-card { width: 420px; max-width: 100%; padding: 22px; }
.confirm-card #confirm-message { margin-bottom: 18px; line-height: 1.5; }
.confirm-actions { display: flex; justify-content: flex-end; gap: 10px; }
```

- [ ] **Step 3: Add the `confirmDialog` helper and gate `clearCompleted`**

In `js/jobs.js`, replace the existing `clearCompleted` function with:

```javascript
function confirmDialog(message, okLabel = 'Confirm') {
  return new Promise(resolve => {
    const overlay = document.getElementById('confirm-overlay');
    const okBtn = document.getElementById('confirm-ok');
    const cancelBtn = document.getElementById('confirm-cancel');
    document.getElementById('confirm-message').textContent = message;
    okBtn.textContent = okLabel;
    overlay.classList.add('open');
    const done = (result) => {
      overlay.classList.remove('open');
      okBtn.onclick = null; cancelBtn.onclick = null; overlay.onclick = null;
      document.removeEventListener('keydown', onKey);
      resolve(result);
    };
    const onKey = (e) => { if (e.key === 'Escape') done(false); };
    okBtn.onclick = () => done(true);
    cancelBtn.onclick = () => done(false);
    overlay.onclick = (e) => { if (e.target === overlay) done(false); };
    document.addEventListener('keydown', onKey);
  });
}

async function clearCompleted() {
  const ok = await confirmDialog(
    'Hide all completed, failed and cancelled jobs from the queue? This is a soft delete — '
    + 'they stay in the database and can only be restored by an admin.', 'Clear');
  if (!ok) return;
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
```

- [ ] **Step 4: Verify build + manually check**

Run: `./mvnw -q -DskipTests package`
Expected: `BUILD SUCCESS`.
Manual (after restarting the app): click **Clear completed** → the styled dialog appears; **Cancel**/overlay/`Esc` dismisses without changes; **Clear** soft-deletes (DONE/FAILED/CANCELLED disappear) and they remain in the DB (`SELECT * FROM print_jobs WHERE deleted = true`).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/jobs.html src/main/resources/static/css/app.css \
        src/main/resources/static/js/jobs.js
git commit -m "feat(ui): styled confirm dialog before clearing completed jobs"
```

---

### Task 4: README note

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the "Job management UI" clear-completed bullet**

In `README.md`, replace the bullet describing **Clear completed** with:

```
- **Clear completed** asks for confirmation, then **soft-deletes** DONE/FAILED/CANCELLED
  jobs — they are hidden from the queue but kept in the database (`deleted = true`).
  Restore one with `UPDATE print_jobs SET deleted = false WHERE job_id = '…';`.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: note soft-delete + confirmation for clear completed"
```

---

### Final: full suite

- [ ] Run `./mvnw test` → `BUILD SUCCESS`, all green (Docker running for Testcontainers).

---

## Self-review

**Spec coverage:**
- `deleted` column via Flyway V2 + entity mapping → Task 1. ✓
- Repository `findByDeletedFalse` + `softDeleteByStatusIn`; `deleteByStatusIn` removed → Task 2. ✓
- `JobStore` rename `loadActive`/`softDeleteCompleted`; `save`/`deleteById` kept → Task 2. ✓
- `clearCompleted` soft-deletes; `recoverJobs` loads active only → Task 2 (service). ✓
- Enqueue rollback still hard-deletes (`deleteById` unchanged) → Task 2. ✓
- Styled confirm dialog replacing native confirm → Task 3. ✓
- Tests: soft-delete keeps rows, `loadActive` excludes deleted, `deleteById` hard-deletes → Task 2 (JpaJobStoreTest); fake updated → Task 2. ✓
- README note → Task 4. ✓

**Placeholder scan:** none — every step has full code/commands.

**Type consistency:** `loadActive()`/`softDeleteCompleted()` used identically across `JobStore`, `JpaJobStore`, `PrintQueueService`, and both the JPA store and the in-memory fake. `findByDeletedFalse`/`softDeleteByStatusIn` defined in Task 2 step 3 and called in step 5. `deleted` column name (`V2`) matches the entity field `deleted` (snake/camel identical). `isDeleted()` getter added but only needed for JPA mapping (no caller depends on it — acceptable).

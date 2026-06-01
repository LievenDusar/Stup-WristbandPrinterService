# Soft-Delete Completed Jobs + Clear Confirmation — Design

**Date:** 2026-06-01
**Status:** Approved (pending spec review)
**Branch:** `feat/jobs-page`

## Goal

1. "Clear completed" asks for confirmation before doing anything.
2. Clearing is a **soft delete**: DONE / FAILED / CANCELLED jobs are flagged `deleted`
   rather than removed. They disappear from the UI but stay in the database; the only
   way to bring one back is a manual DB update. The job's real status is preserved.

## Decisions (agreed)

- Soft-delete is a **separate `deleted` boolean flag** (option A), not a `DELETED`
  status — so the original outcome (done/failed/cancelled) stays intact and recovery
  is a single-field flip.
- Confirmation uses a **styled in-app dialog** matching the STUP dark/glass theme
  (a small reusable confirm modal), not the browser's native `confirm()`.
- The `DELETE /api/wristbands/jobs/completed` endpoint path and the frontend's local
  removal behavior are unchanged; only the persistence effect changes (soft vs hard).

## Backend

### Schema (Flyway V2)
- `src/main/resources/db/migration/V2__add_deleted_flag.sql`:
  `ALTER TABLE print_jobs ADD COLUMN deleted boolean NOT NULL DEFAULT false;`
- `PrintJobEntity` gains a mapped `boolean deleted` field (primitive → NOT NULL column),
  so `ddl-auto: validate` passes. New rows default to `false`.

### Repository (`PrintJobRepository`)
- Add `List<PrintJobEntity> findByDeletedFalse();` — active (non-deleted) rows.
- Add a soft-delete bulk update:
  ```java
  @Modifying
  @Query("update PrintJobEntity e set e.deleted = true where e.status in :statuses and e.deleted = false")
  int softDeleteByStatusIn(Collection<PrintJobStatus> statuses);
  ```
- Keep `deleteById` (JpaRepository built-in) for the enqueue capacity-race rollback —
  that remains a real hard delete (the row was inserted in error).
- `deleteByStatusIn` is removed (replaced by the soft-delete update).

### Store (`JobStore` / `JpaJobStore`)
- Rename `loadAll()` → `loadActive()`; implementation uses `findByDeletedFalse()`.
- Rename `deleteCompleted()` → `softDeleteCompleted()`; implementation calls
  `softDeleteByStatusIn(List.of(DONE, FAILED, CANCELLED))` inside a `@Transactional`.
- `save(...)` and `deleteById(...)` are unchanged. Jobs handed to `save` are always
  active (`deleted = false`), since soft-deleted jobs leave the in-memory map.

### Service (`PrintQueueService`)
- `clearCompleted()`: still `removeIf` DONE/FAILED/CANCELLED from the in-memory `jobs`
  map (so they vanish from the UI), then `jobStore.softDeleteCompleted()` instead of the
  old hard delete.
- `recoverJobs()`: load via `jobStore.loadActive()` so soft-deleted jobs are never
  reloaded into the map after a restart (they stay hidden).

## Frontend (`jobs.html`, `css/app.css`, `js/jobs.js`)
- **Reusable styled confirm dialog** (on-brand, replaces the native `confirm()`):
  - `jobs.html`: a `confirm-overlay` containing a glass `confirm-card` with a message
    element and **Cancel** / confirm (danger-styled) buttons.
  - `css/app.css`: `.confirm-overlay` (fixed, centered, dimmed, fade in) and
    `.confirm-card` styles, matching the drawer/modal look.
  - `js/jobs.js`: a promise-based helper `confirmDialog(message, okLabel)` that shows the
    overlay, resolves `true` on confirm and `false` on Cancel / overlay click / `Esc`,
    and cleans up its handlers.
- `clearCompleted()` becomes: `if (!(await confirmDialog("Hide all completed, failed and
  cancelled jobs from the queue? This is a soft delete — they stay in the database and can
  only be restored by an admin.", "Clear"))) return;` then calls the existing
  `DELETE /jobs/completed` and removes DONE/FAILED/CANCELLED from the local `jobs` map as
  today. No change to the endpoint or its response.

## Recovery (operational note)
To restore a soft-deleted job: `UPDATE print_jobs SET deleted = false WHERE job_id = '…';`
It reappears in the UI after the next backend load (restart) — or immediately if still in
the running instance's memory it was already removed, so a restart is the reliable path.

## Testing
- **`JpaJobStoreTest`** (Testcontainers Postgres):
  - `softDeleteCompleted` sets `deleted = true` on DONE/FAILED/CANCELLED rows, and those
    rows still exist (row count unchanged) but are excluded from `loadActive()`.
  - A PENDING row is left active.
  - `deleteById` still removes the row entirely.
- **`PrintQueueServiceTest`**: existing `clearCompleted` tests still pass (map emptied);
  the in-memory `JobStore` fake updated to implement `loadActive()` + `softDeleteCompleted()`
  (the fake keeps a `deleted` marker and excludes it from `loadActive`).
- Full suite green (`./mvnw test`, Docker required for Testcontainers).

## Out of scope
- Any UI to view or restore soft-deleted jobs (restore is DB-only, by design).
- Changing the API path or response of `DELETE /jobs/completed`.

## Affected files
- `src/main/resources/db/migration/V2__add_deleted_flag.sql` (new)
- `src/main/java/.../persistence/PrintJobEntity.java`
- `src/main/java/.../persistence/PrintJobRepository.java`
- `src/main/java/.../persistence/JobStore.java` + `JpaJobStore.java`
- `src/main/java/.../service/PrintQueueService.java`
- `src/main/resources/static/jobs.html` (confirm dialog markup)
- `src/main/resources/static/css/app.css` (confirm dialog styles)
- `src/main/resources/static/js/jobs.js` (confirmDialog helper + clearCompleted)
- Tests: `JpaJobStoreTest`, `PrintQueueServiceTest`

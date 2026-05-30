# Jobs Page Redesign — Plan 1 of 3: Backend Job Operations

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the backend operations the redesigned jobs page needs: cancel a pending job (new `CANCELLED` status) and a full job-detail endpoint exposing the wristband fields.

**Architecture:** Extends the existing `PrintQueueService` (in-memory queue + `JobStore`) and `WristbandController`. Cancel removes a still-pending job from the worker queue and marks it `CANCELLED`; if the worker already took it, cancel returns `409`. A new `PrintJobDetailResponse` is returned only by the authenticated `GET /jobs/{id}` (PII stays off the SSE/list payloads).

**Tech Stack:** Java 21, Spring Boot 3.4.1, Spring Web, JUnit 5, Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-05-29-jobs-page-redesign-design.md`

---

## Scope

This plan is **backend only** — no auth changes, no frontend. Plans 2 (admin auth) and 3 (frontend redesign) follow. This plan produces working, tested software on its own: the cancel and detail endpoints work with the existing `X-API-Key` header auth.

## File structure

- `domain/PrintJobStatus.java` — add `CANCELLED`.
- `domain/PrintJobDetailResponse.java` — new record (full fields incl. PII).
- `domain/PrintJob.java` — add `toDetailResponse()`.
- `exception/JobNotCancellableException.java` — new (maps to 409).
- `service/PrintQueueService.java` — add `cancel(UUID)`; include `CANCELLED` in `clearCompleted`.
- `persistence/JobStore.java` / `JpaJobStore.java` — `deleteCompleted` also removes `CANCELLED`.
- `controller/WristbandController.java` — cancel endpoint; detail response on `GET /jobs/{id}`.
- `exception/GlobalExceptionHandler.java` — map `JobNotCancellableException` → 409.
- Tests: `PrintQueueServiceTest`, `WristbandControllerTest`.

---

### Task 1: Add CANCELLED status

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/domain/PrintJobStatus.java`

- [ ] **Step 1: Add the enum value**

Replace the enum body so it reads:

```java
public enum PrintJobStatus {
    PENDING,
    PRINTING,
    DONE,
    FAILED,
    CANCELLED
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./mvnw -q -DskipTests test-compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/PrintJobStatus.java
git commit -m "feat: add CANCELLED print job status"
```

---

### Task 2: PrintJobDetailResponse + PrintJob.toDetailResponse()

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/domain/PrintJobDetailResponse.java`
- Modify: `src/main/java/com/stup/wristbandprinter/domain/PrintJob.java`
- Test: `src/test/java/com/stup/wristbandprinter/domain/PrintJobTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/domain/PrintJobTest.java`:

```java
package com.stup.wristbandprinter.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PrintJobTest {

    @Test
    void toDetailResponse_includesAllWristbandFields() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setAssociationName("STUP vzw");
        r.setBarcodeValue("123456789");
        PrintJob job = new PrintJob(UUID.randomUUID(), r);

        PrintJobDetailResponse detail = job.toDetailResponse();

        assertThat(detail.jobId()).isEqualTo(job.getJobId());
        assertThat(detail.status()).isEqualTo(PrintJobStatus.PENDING);
        assertThat(detail.eventName()).isEqualTo("Pukkelpop 2026");
        assertThat(detail.firstName()).isEqualTo("Jan");
        assertThat(detail.lastName()).isEqualTo("Janssens");
        assertThat(detail.associationName()).isEqualTo("STUP vzw");
        assertThat(detail.barcodeValue()).isEqualTo("123456789");
        assertThat(detail.submittedAt()).isEqualTo(job.getSubmittedAt());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=PrintJobTest`
Expected: FAIL — compile error, `PrintJobDetailResponse` and `toDetailResponse()` do not exist.

- [ ] **Step 3: Create the DTO**

Create `src/main/java/com/stup/wristbandprinter/domain/PrintJobDetailResponse.java`:

```java
package com.stup.wristbandprinter.domain;

import java.time.Instant;
import java.util.UUID;

public record PrintJobDetailResponse(
    UUID jobId,
    PrintJobStatus status,
    String eventName,
    String firstName,
    String lastName,
    String associationName,
    String barcodeValue,
    Instant submittedAt,
    Instant completedAt,
    String error
) {}
```

- [ ] **Step 4: Add the mapping method to PrintJob**

In `src/main/java/com/stup/wristbandprinter/domain/PrintJob.java`, add this method immediately after the existing `toResponse()` method:

```java
    public synchronized PrintJobDetailResponse toDetailResponse() {
        return new PrintJobDetailResponse(
            jobId,
            status,
            request.getEventName(),
            request.getFirstName(),
            request.getLastName(),
            request.getAssociationName(),
            request.getBarcodeValue(),
            submittedAt,
            completedAt,
            error
        );
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=PrintJobTest`
Expected: PASS, `Tests run: 1`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/PrintJobDetailResponse.java \
        src/main/java/com/stup/wristbandprinter/domain/PrintJob.java \
        src/test/java/com/stup/wristbandprinter/domain/PrintJobTest.java
git commit -m "feat: add full job detail response mapping"
```

---

### Task 3: Detail endpoint on GET /jobs/{id}

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/controller/WristbandController.java`
- Test: `src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java`

- [ ] **Step 1: Write the failing test**

Add this test method to `WristbandControllerTest` (it uses the existing `mockMvc`, `printQueueService` mock, and `test-key` from that class — match the existing patterns in the file; import `com.stup.wristbandprinter.domain.PrintJob`, `WristbandPrintRequest`, `java.util.UUID`, `java.util.Optional`, and `org.mockito.Mockito` as needed):

```java
    @Test
    void getJob_returnsFullDetailFields() throws Exception {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setAssociationName("STUP vzw");
        r.setBarcodeValue("123456789");
        UUID id = UUID.randomUUID();
        PrintJob job = new PrintJob(id, r);
        Mockito.when(printQueueService.getJob(id)).thenReturn(java.util.Optional.of(job));

        mockMvc.perform(get("/api/wristbands/jobs/" + id)
                .header("X-API-Key", "test-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.firstName").value("Jan"))
            .andExpect(jsonPath("$.lastName").value("Janssens"))
            .andExpect(jsonPath("$.barcodeValue").value("123456789"));
    }
```

If `get(...)` / `jsonPath(...)` are not already statically imported in the test file, add:
`import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;`
`import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;`

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=WristbandControllerTest#getJob_returnsFullDetailFields`
Expected: FAIL — `$.firstName` does not exist (current endpoint returns the lean `PrintJobResponse`).

- [ ] **Step 3: Change the endpoint to return the detail response**

In `WristbandController.java`, replace the existing `getJob` handler with:

```java
    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "Get full detail of a specific print job")
    public ResponseEntity<PrintJobDetailResponse> getJob(@PathVariable UUID jobId) {
        return printQueueService.getJob(jobId)
            .map(job -> ResponseEntity.ok(job.toDetailResponse()))
            .orElse(ResponseEntity.notFound().build());
    }
```

(`PrintJobDetailResponse` is in the same `domain` package already imported via `import com.stup.wristbandprinter.domain.*;`.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=WristbandControllerTest`
Expected: PASS (all methods in the class, including the new one).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/controller/WristbandController.java \
        src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java
git commit -m "feat: return full wristband detail from GET /jobs/{id}"
```

---

### Task 4: Cancel logic in PrintQueueService

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/exception/JobNotCancellableException.java`
- Modify: `src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java`
- Test: `src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java`

- [ ] **Step 1: Write the failing tests**

Add these two tests to `PrintQueueServiceTest` (the class already has `newService(int)`, `sampleRequest()`, the `jobStore` fake, and imports for `PrintJobStatus`, `assertThatThrownBy`). Add `import com.stup.wristbandprinter.exception.JobNotCancellableException;`:

```java
    @Test
    void cancel_pendingJob_marksCancelledAndRemovesFromQueue() {
        // No worker started, so the job stays PENDING in the queue.
        PrintJob job = service.enqueue(sampleRequest());

        PrintJob cancelled = service.cancel(job.getJobId());

        assertThat(cancelled.getStatus()).isEqualTo(PrintJobStatus.CANCELLED);
        assertThat(service.getJobs(PrintJobStatus.CANCELLED)).hasSize(1);
    }

    @Test
    void cancel_nonPendingJob_throws() {
        PrintJob job = service.enqueue(sampleRequest());
        job.setStatus(PrintJobStatus.DONE); // simulate already-processed

        assertThatThrownBy(() -> service.cancel(job.getJobId()))
            .isInstanceOf(JobNotCancellableException.class);
    }

    @Test
    void cancel_unknownJob_returnsNull() {
        assertThat(service.cancel(java.util.UUID.randomUUID())).isNull();
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest=PrintQueueServiceTest`
Expected: FAIL — compile error, `cancel(...)` and `JobNotCancellableException` do not exist.

- [ ] **Step 3: Create the exception**

Create `src/main/java/com/stup/wristbandprinter/exception/JobNotCancellableException.java`:

```java
package com.stup.wristbandprinter.exception;

public class JobNotCancellableException extends RuntimeException {
    public JobNotCancellableException(String message) { super(message); }
}
```

- [ ] **Step 4: Implement cancel() in PrintQueueService**

Add this method to `PrintQueueService` (after `clearCompleted()`), and add the import `import com.stup.wristbandprinter.exception.JobNotCancellableException;`:

```java
    /**
     * Cancel a job that has not started printing. Only valid while PENDING: the job is
     * removed from the worker queue and marked CANCELLED. Returns null if no such job
     * exists; throws JobNotCancellableException if the job is no longer pending (the
     * worker has already taken it or it has finished).
     */
    public PrintJob cancel(UUID jobId) {
        PrintJob job = jobs.get(jobId);
        if (job == null) {
            return null;
        }
        if (job.getStatus() != PrintJobStatus.PENDING) {
            throw new JobNotCancellableException(
                "Job " + jobId + " is " + job.getStatus() + " and cannot be cancelled");
        }
        if (!queue.remove(job)) {
            // The worker dequeued it between the status check and now.
            throw new JobNotCancellableException(
                "Job " + jobId + " has already started printing");
        }
        job.complete(PrintJobStatus.CANCELLED, null, Instant.now());
        jobStore.save(job);
        broadcastUpdate(job);
        log.info("Job {} cancelled", jobId);
        return job;
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=PrintQueueServiceTest`
Expected: PASS (all methods).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/exception/JobNotCancellableException.java \
        src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java \
        src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java
git commit -m "feat: cancel a pending print job"
```

---

### Task 5: Cancel endpoint + 409 mapping

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/controller/WristbandController.java`
- Modify: `src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `WristbandControllerTest`:

```java
    @Test
    void cancel_pendingJob_returns200() throws Exception {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setAssociationName("STUP vzw");
        r.setBarcodeValue("123456789");
        UUID id = UUID.randomUUID();
        PrintJob job = new PrintJob(id, r);
        Mockito.when(printQueueService.cancel(id)).thenReturn(job);

        mockMvc.perform(post("/api/wristbands/jobs/" + id + "/cancel")
                .header("X-API-Key", "test-key"))
            .andExpect(status().isOk());
    }

    @Test
    void cancel_alreadyStarted_returns409() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.when(printQueueService.cancel(id))
            .thenThrow(new com.stup.wristbandprinter.exception.JobNotCancellableException("already started"));

        mockMvc.perform(post("/api/wristbands/jobs/" + id + "/cancel")
                .header("X-API-Key", "test-key"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void cancel_unknownJob_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.when(printQueueService.cancel(id)).thenReturn(null);

        mockMvc.perform(post("/api/wristbands/jobs/" + id + "/cancel")
                .header("X-API-Key", "test-key"))
            .andExpect(status().isNotFound());
    }
```

If `post(...)` is not already statically imported, add
`import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;`

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest=WristbandControllerTest`
Expected: FAIL — no `/cancel` mapping (404 for all) and no 409 handler.

- [ ] **Step 3: Add the cancel endpoint**

In `WristbandController.java`, add after the `reprint` handler:

```java
    @PostMapping("/jobs/{jobId}/cancel")
    @Operation(summary = "Cancel a pending print job")
    public ResponseEntity<PrintJobResponse> cancel(@PathVariable UUID jobId) {
        PrintJob job = printQueueService.cancel(jobId);
        return job == null
            ? ResponseEntity.notFound().build()
            : ResponseEntity.ok(job.toResponse());
    }
```

- [ ] **Step 4: Map the conflict to 409**

In `GlobalExceptionHandler.java`, add this handler (after `handleQueueFull`):

```java
    @ExceptionHandler(JobNotCancellableException.class)
    public ResponseEntity<Map<String, Object>> handleNotCancellable(JobNotCancellableException ex) {
        log.warn("Job not cancellable: {}", ex.getMessage());
        return errorResponse(HttpStatus.CONFLICT, "Job not cancellable", ex.getMessage());
    }
```

Add the import `import com.stup.wristbandprinter.exception.JobNotCancellableException;` if the handler class does not already import the exception package wildcard. (The handler is in the `exception` package, so `JobNotCancellableException` is in the same package and needs no import.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=WristbandControllerTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/controller/WristbandController.java \
        src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java \
        src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java
git commit -m "feat: add cancel endpoint mapping not-cancellable to 409"
```

---

### Task 6: Clear CANCELLED jobs too

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java`
- Modify: `src/main/java/com/stup/wristbandprinter/persistence/JpaJobStore.java`
- Test: `src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java`

- [ ] **Step 1: Write the failing test**

Add to `PrintQueueServiceTest`:

```java
    @Test
    void clearCompleted_alsoRemovesCancelledJobs() {
        PrintJob job = service.enqueue(sampleRequest());
        service.cancel(job.getJobId()); // now CANCELLED

        service.clearCompleted();

        assertThat(service.getJobs(null)).isEmpty();
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=PrintQueueServiceTest#clearCompleted_alsoRemovesCancelledJobs`
Expected: FAIL — `clearCompleted` currently only removes DONE/FAILED, so the CANCELLED job remains.

- [ ] **Step 3: Include CANCELLED in clearCompleted()**

In `PrintQueueService.clearCompleted()`, change the `removeIf` predicate to include CANCELLED:

```java
    public void clearCompleted() {
        jobs.values().removeIf(job ->
            job.getStatus() == PrintJobStatus.DONE
                || job.getStatus() == PrintJobStatus.FAILED
                || job.getStatus() == PrintJobStatus.CANCELLED);
        jobStore.deleteCompleted();
    }
```

- [ ] **Step 4: Include CANCELLED in JpaJobStore.deleteCompleted()**

In `JpaJobStore.deleteCompleted()`, change the status list:

```java
    @Override
    @Transactional
    public void deleteCompleted() {
        repository.deleteByStatusIn(
            List.of(PrintJobStatus.DONE, PrintJobStatus.FAILED, PrintJobStatus.CANCELLED));
    }
```

- [ ] **Step 5: Run the test to verify it passes, then the full suite**

Run: `./mvnw test -Dtest=PrintQueueServiceTest`
Expected: PASS.
Run: `./mvnw test`
Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java \
        src/main/java/com/stup/wristbandprinter/persistence/JpaJobStore.java \
        src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java
git commit -m "feat: clear CANCELLED jobs alongside DONE and FAILED"
```

---

## Self-review

**Spec coverage (backend portion):**
- `CANCELLED` status added → Task 1. ✓
- Cancel endpoint, PENDING-only, 409 when not pending, 404 when unknown → Tasks 4 & 5. ✓
- Worker-race handling (queue.remove false → 409) → Task 4. ✓
- `clearCompleted` removes CANCELLED → Task 6. ✓
- Full job detail via authenticated `GET /jobs/{id}` (`PrintJobDetailResponse`) → Tasks 2 & 3. ✓
- PII kept off SSE/list (those still use `PrintJobResponse`) → unchanged; only `GET /jobs/{id}` returns detail. ✓

**Placeholder scan:** No TBD/TODO; every code step contains complete code and exact commands.

**Type consistency:** `PrintJobDetailResponse` field names (`firstName`, `lastName`, `associationName`, `barcodeValue`) used identically in the DTO (Task 2), `toDetailResponse()` (Task 2), and the controller test (Task 3). `JobNotCancellableException` created in Task 4 and referenced in Tasks 4–5. `cancel(UUID)` returns `PrintJob` (or null) consistently across service (Task 4) and controller (Task 5).

**Note for executor:** This plan is DB-engine-agnostic. `JpaJobStore.deleteCompleted()` (Task 6) exists on both `main` (H2) and the `feat/postgres-migration` branch; the change is identical either way.

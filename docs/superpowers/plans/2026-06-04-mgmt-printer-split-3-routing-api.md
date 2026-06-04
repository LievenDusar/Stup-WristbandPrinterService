# Management/Printer Split — Phase 2: Routing + Symfony API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let callers target a specific printer (`printerId` on the print request), process each printer's jobs on its own worker thread (parallel across printers), and give external callers (Symfony) a printers list and a per-job status stream.

**Architecture:** `PrintQueueService` keeps one bounded queue + one daemon worker thread per registered printer; `enqueue` selects the printer from the request (or the default) and routes the job to that printer's queue; each worker thread forwards to its printer's worker (unchanged sync forward). An unknown `printerId` is rejected with 400. New endpoints: `GET /api/wristbands/printers` (id + display name) and `GET /api/wristbands/jobs/{jobId}/stream` (per-job SSE that completes on a terminal status).

**Tech Stack:** Java 21, Spring Boot 3.4.1, Spring MVC SSE (`SseEmitter`), JUnit 5, Mockito, Maven.

**Spec:** `docs/superpowers/specs/2026-06-04-management-printer-split-design.md` (Amendments: synchronous forward; per-job stream).

**Branch:** `feat/printer-worker-split` (continue; do not switch).

---

## File Structure

- `domain/WristbandPrintRequest.java` (modify) — add optional `printerId`.
- `exception/UnknownPrinterException.java` (new) — thrown for an unknown `printerId`; mapped to 400.
- `cluster/PrinterRegistry.java` (modify) — `get(id)` throws `UnknownPrinterException`.
- `exception/GlobalExceptionHandler.java` (modify) — map `UnknownPrinterException` → 400.
- `service/PrintQueueService.java` (modify) — per-printer queues + threads; route by printer; select printer from request.
- `domain/PrinterSummaryResponse.java` (new) — `{ id, displayName }` for the printers endpoint.
- `controller/WristbandController.java` (modify) — `GET /printers` and `GET /jobs/{jobId}/stream`.
- Tests: `cluster/PrinterRegistryTest` (update exception type), `service/PrintQueueServiceTest` (stub `all()`, routing tests), `controller/WristbandControllerTest` (printers + per-job stream), `WristbandIntegrationTest` (route to a 2nd printer + per-job stream).

---

### Task 1: Optional printerId on the request + 400 for unknown printer

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/domain/WristbandPrintRequest.java`
- Create: `src/main/java/com/stup/wristbandprinter/exception/UnknownPrinterException.java`
- Modify: `src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistry.java`
- Modify: `src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java`
- Modify: `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryTest.java`

- [ ] **Step 1: Add `printerId` to the request**

In `WristbandPrintRequest.java`, add an optional field (no `@NotBlank` — absence means "use the default printer"):

```java
    @Schema(description = "Optional id of the printer to use; when omitted the default printer is used")
    private String printerId;

    public String getPrinterId() { return printerId; }
    public void setPrinterId(String printerId) { this.printerId = printerId; }
```

- [ ] **Step 2: Create the exception**

```java
package com.stup.wristbandprinter.exception;

/** Thrown when a print request targets a printer id that is not in the registry. */
public class UnknownPrinterException extends RuntimeException {
    public UnknownPrinterException(String message) {
        super(message);
    }
}
```

- [ ] **Step 3: Make the registry throw it**

In `PrinterRegistry.get(String id)`, replace the `IllegalArgumentException` with:

```java
        Printer printer = byId.get(id);
        if (printer == null) {
            throw new com.stup.wristbandprinter.exception.UnknownPrinterException("Unknown printer id: " + id);
        }
        return printer;
```

Update `PrinterRegistryTest.get_unknownId_throws` to expect `UnknownPrinterException` instead of `IllegalArgumentException` (import it; keep the `hasMessageContaining("nope")` assertion).

- [ ] **Step 4: Map it to 400**

In `GlobalExceptionHandler.java`, add (place near the other handlers):

```java
    @ExceptionHandler(com.stup.wristbandprinter.exception.UnknownPrinterException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownPrinter(
            com.stup.wristbandprinter.exception.UnknownPrinterException ex) {
        log.warn("Unknown printer: {}", ex.getMessage());
        return errorResponse(HttpStatus.BAD_REQUEST, "Unknown printer", ex.getMessage());
    }
```

- [ ] **Step 5: Compile + run the registry test**

Run: `./mvnw -q test -Dtest=PrinterRegistryTest`
Expected: PASS (6 cases; the unknown-id case now asserts `UnknownPrinterException`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/WristbandPrintRequest.java \
        src/main/java/com/stup/wristbandprinter/exception/UnknownPrinterException.java \
        src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistry.java \
        src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java \
        src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryTest.java
git commit -m "feat: accept optional printerId and reject unknown printers with 400"
```

---

### Task 2: Per-printer queues and worker threads

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java`
- Modify: `src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java`

This refactor keeps one bounded `LinkedBlockingQueue` and one daemon thread per registered printer. Queues are created in the constructor (so `enqueue` works before workers start, as the tests rely on); threads start in `startWorker()`.

- [ ] **Step 1: Update the test first (TDD)**

In `PrintQueueServiceTest.java`:
- In `setUp()`, stub `all()` in addition to the existing `getDefault()`/`get()` stubs (the service builds its queues from `all()`):
  ```java
  org.mockito.Mockito.lenient().when(printerRegistry.all())
      .thenReturn(java.util.List.of(
          new com.stup.wristbandprinter.cluster.Printer("printer-1", "Test Printer", "http://worker:8080")));
  ```
- Add a routing test proving a request's `printerId` is honored:
  ```java
  @Test
  void enqueue_routesToRequestedPrinter() {
      org.mockito.Mockito.when(printerRegistry.get("printer-2"))
          .thenReturn(new com.stup.wristbandprinter.cluster.Printer("printer-2", "Second", "http://worker2:8080"));
      WristbandPrintRequest r = sampleRequest();
      r.setPrinterId("printer-2");
      PrintJob job = service.enqueue(r);
      assertThat(job.getPrinterId()).isEqualTo("printer-2");
      assertThat(job.getPrinterName()).isEqualTo("Second");
  }
  ```
  For this test the service must also have a queue for `printer-2`. Because queues are built from `all()` at construction, add `printer-2` to the `all()` stub in this test (re-stub `all()` to include both printers and rebuild via `service = newService(100)` inside the test before enqueuing), OR—simpler—keep a single-printer `all()` and have `enqueue` create the queue lazily if absent. Implement lazy queue creation in Step 2 so routing to any registry printer always has a queue. With lazy creation this test needs only the `get("printer-2")` stub above.
- Keep all existing tests; they use the default printer (`printer-1`).

- [ ] **Step 2: Refactor PrintQueueService**

Replace the single `queue` field and its usages with a per-printer map and lazy queue creation:

```java
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.BlockingQueue<PrintJob>> queues
        = new java.util.concurrent.ConcurrentHashMap<>();
```

Remove the `private final LinkedBlockingQueue<PrintJob> queue;` field and the `this.queue = new LinkedBlockingQueue<>(...)` line from the constructor.

Add a helper that returns (creating if needed) a printer's bounded queue:

```java
    private java.util.concurrent.BlockingQueue<PrintJob> queueFor(String printerId) {
        return queues.computeIfAbsent(printerId,
            id -> new java.util.concurrent.LinkedBlockingQueue<>(queueProperties.getMaxDepth()));
    }
```

Update the queue-depth gauge to sum all per-printer queues:

```java
        Gauge.builder("wristband.queue.depth", queues,
                q -> q.values().stream().mapToInt(java.util.Collection::size).sum())
            .description("Pending print jobs waiting to be processed").register(meterRegistry);
```

Change `enqueue` to select + route by printer:

```java
    public PrintJob enqueue(WristbandPrintRequest request) {
        Printer printer = (request.getPrinterId() == null || request.getPrinterId().isBlank())
            ? printerRegistry.getDefault()
            : printerRegistry.get(request.getPrinterId());   // throws UnknownPrinterException -> 400

        java.util.concurrent.BlockingQueue<PrintJob> q = queueFor(printer.id());
        if (q.size() >= queueProperties.getMaxDepth()) {
            throw queueFull(request);
        }

        PrintJob job = new PrintJob(UUID.randomUUID(), request, printer.id(), printer.displayName());
        jobStore.save(job);
        jobs.put(job.getJobId(), job);

        if (!q.offer(job)) {
            jobs.remove(job.getJobId());
            jobStore.deleteById(job.getJobId());
            throw queueFull(request);
        }

        submittedCounter.increment();
        broadcastUpdate(job);
        log.info("Job {} enqueued for printer {} ({}), event: {}, barcode: {}",
            job.getJobId(), printer.id(), printer.displayName(),
            request.getEventName(), request.getBarcodeValue());
        return job;
    }
```

Change `startWorker()` to start one daemon thread per registered printer, each draining that printer's queue:

```java
    public void startWorker() {
        java.util.List<Printer> printers = printerRegistry.all();
        worker = Executors.newFixedThreadPool(Math.max(1, printers.size()), r -> {
            Thread t = new Thread(r, "print-queue-worker");
            t.setDaemon(true);
            return t;
        });
        for (Printer p : printers) {
            java.util.concurrent.BlockingQueue<PrintJob> q = queueFor(p.id());
            worker.submit(() -> processQueue(q));
        }
        log.info("Started {} print-queue worker(s)", printers.size());
    }
```

Change `processQueue()` to take a specific queue and otherwise keep its body identical (it already resolves the printer per job via `printerRegistry.get(job.getPrinterId())`):

```java
    private void processQueue(java.util.concurrent.BlockingQueue<PrintJob> q) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                PrintJob job = q.take();
                // ... unchanged body: MDC, set PRINTING, save, broadcast, buildData,
                // resolve, printerRegistry.get(job.getPrinterId()), workerClient.print(...),
                // complete DONE/FAILED, save, broadcast ...
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
```

Change `cancel(...)` to remove the job from its own printer's queue:

```java
        java.util.concurrent.BlockingQueue<PrintJob> q = queueFor(job.getPrinterId());
        if (!q.remove(job)) {
            throw new JobNotCancellableException("Job " + jobId + " has already started printing");
        }
```

Leave `recoverJobs`, `getJob`, `getJobs`, `clearCompleted`, `subscribe`, `broadcastUpdate`, `stopWorker`, and metrics counters otherwise unchanged. `stopWorker()` already calls `worker.shutdownNow()`, which interrupts all per-printer threads.

The `worker` field stays `private ExecutorService worker;` (now a fixed pool instead of a single-thread executor).

- [ ] **Step 3: Run the queue test**

Run: `./mvnw -q test -Dtest=PrintQueueServiceTest`
Expected: PASS (existing cases + the new `enqueue_routesToRequestedPrinter`).

- [ ] **Step 4: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS, 0 failures. Paste the summary.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java \
        src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java
git commit -m "feat: per-printer queues and worker threads for parallel printing"
```

---

### Task 3: Printers list endpoint

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/domain/PrinterSummaryResponse.java`
- Modify: `src/main/java/com/stup/wristbandprinter/controller/WristbandController.java`
- Modify: `src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java`

- [ ] **Step 1: Response record**

```java
package com.stup.wristbandprinter.domain;

/** A printer as exposed to UI/external callers (no internal base URL). */
public record PrinterSummaryResponse(String id, String displayName) {}
```

- [ ] **Step 2: Endpoint**

Inject `PrinterRegistry` into `WristbandController` (add a constructor parameter + field). Add:

```java
    @GetMapping("/printers")
    @Operation(summary = "List the printers this service can route to")
    public ResponseEntity<List<PrinterSummaryResponse>> printers() {
        List<PrinterSummaryResponse> list = printerRegistry.all().stream()
            .map(p -> new PrinterSummaryResponse(p.id(), p.displayName()))
            .toList();
        return ResponseEntity.ok(list);
    }
```

- [ ] **Step 3: Test (TDD — write before wiring if you prefer; either order, but it must pass at the end)**

In `WristbandControllerTest.java`, the controller is constructed/mocked. Add a `PrinterRegistry` mock to the controller's collaborators (match how the test builds the controller — constructor injection or `@MockBean`), stub `all()` to return two printers, and assert `GET /api/wristbands/printers` returns 200 with both ids. Read the test file to match its existing wiring style (it already mocks `PrintQueueService` etc.).

- [ ] **Step 4: Run the controller test**

Run: `./mvnw -q test -Dtest=WristbandControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/PrinterSummaryResponse.java \
        src/main/java/com/stup/wristbandprinter/controller/WristbandController.java \
        src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java
git commit -m "feat: add GET /api/wristbands/printers"
```

---

### Task 4: Per-job SSE stream endpoint

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java`
- Modify: `src/main/java/com/stup/wristbandprinter/controller/WristbandController.java`
- Modify: `src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java`

The per-job stream emits the job's current state immediately, then only that job's updates, and completes when the job is terminal. Implement it as a focused method on `PrintQueueService` that filters the broadcast.

- [ ] **Step 1: Add `subscribeToJob` (TDD)**

In `PrintQueueServiceTest.java`, add a test:
```java
    @Test
    void subscribeToJob_unknownJob_returnsNull() {
        assertThat(service.subscribeToJob(java.util.UUID.randomUUID())).isNull();
    }

    @Test
    void subscribeToJob_emitsImmediatelyAndCompletesWhenTerminal() throws Exception {
        PrintJob job = service.enqueue(sampleRequest()); // PENDING (no worker started)
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
            service.subscribeToJob(job.getJobId());
        assertThat(emitter).isNotNull();
        // Drive it to a terminal state and confirm the emitter completes.
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        emitter.onCompletion(() -> completed.set(true));
        job.complete(PrintJobStatus.DONE, null, java.time.Instant.now());
        // broadcastUpdate is what the service calls after a status change:
        service.publish(job);
        assertThat(completed).isTrue();
    }
```
(If exposing a `publish(PrintJob)` test hook is undesirable, instead assert via `cancel(...)` which already triggers a broadcast: enqueue, then `service.cancel(job.getJobId())` and assert the per-job emitter completed. Choose the approach that keeps production code clean; prefer reusing `cancel` over adding a test-only `publish`.)

- [ ] **Step 2: Implement on PrintQueueService**

Maintain per-job emitters and notify them inside the existing `broadcastUpdate`:

```java
    private final java.util.concurrent.ConcurrentHashMap<UUID, java.util.List<SseEmitter>> jobEmitters
        = new java.util.concurrent.ConcurrentHashMap<>();

    /** Subscribe to one job's updates; null if the job is unknown. Completes on a terminal status. */
    public SseEmitter subscribeToJob(UUID jobId) {
        PrintJob job = jobs.get(jobId);
        if (job == null) {
            return null;
        }
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        jobEmitters.computeIfAbsent(jobId, id -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeJobEmitter(jobId, emitter));
        emitter.onTimeout(() -> removeJobEmitter(jobId, emitter));
        emitter.onError(e -> removeJobEmitter(jobId, emitter));
        try {
            emitter.send(SseEmitter.event().data(job.toResponse()));   // current snapshot
            if (isTerminal(job.getStatus())) {
                emitter.complete();
            }
        } catch (java.io.IOException e) {
            removeJobEmitter(jobId, emitter);
        }
        return emitter;
    }

    private void removeJobEmitter(UUID jobId, SseEmitter emitter) {
        java.util.List<SseEmitter> list = jobEmitters.get(jobId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) jobEmitters.remove(jobId);
        }
    }

    private static boolean isTerminal(PrintJobStatus status) {
        return status == PrintJobStatus.DONE
            || status == PrintJobStatus.FAILED
            || status == PrintJobStatus.CANCELLED;
    }
```

In the existing `broadcastUpdate(PrintJob job)`, after the global-emitter loop, also notify per-job subscribers and complete them on terminal status:

```java
        java.util.List<SseEmitter> perJob = jobEmitters.get(job.getJobId());
        if (perJob != null) {
            boolean terminal = isTerminal(job.getStatus());
            for (SseEmitter emitter : perJob) {
                try {
                    emitter.send(SseEmitter.event().data(job.toResponse()));
                    if (terminal) emitter.complete();
                } catch (IOException e) {
                    removeJobEmitter(job.getJobId(), emitter);
                }
            }
        }
```

If Step 1 used a `publish` hook, instead expose `public void publish(PrintJob job) { broadcastUpdate(job); }` — but prefer testing via `cancel` and do NOT add a public hook.

- [ ] **Step 3: Controller endpoint**

In `WristbandController.java` add:

```java
    @GetMapping(value = "/jobs/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to a single job's status updates via SSE")
    public ResponseEntity<SseEmitter> streamJob(@PathVariable UUID jobId) {
        SseEmitter emitter = printQueueService.subscribeToJob(jobId);
        return emitter == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(emitter);
    }
```

- [ ] **Step 4: Run tests**

Run: `./mvnw -q test -Dtest=PrintQueueServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java \
        src/main/java/com/stup/wristbandprinter/controller/WristbandController.java \
        src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java
git commit -m "feat: add per-job SSE stream endpoint"
```

---

### Task 5: Full verification + integration coverage

**Files:**
- Modify: `src/test/java/com/stup/wristbandprinter/WristbandIntegrationTest.java`

- [ ] **Step 1: Add an integration test routing to a second printer**

Extend the integration test's fake-worker setup to register a SECOND printer in the registry (`cluster.printers[1].*`) pointing at the same fake worker (or a second one), submit a print with `"printerId":"printer-2"`, and assert the response `printerId` is `printer-2` and the job reaches DONE. Reuse the existing fake-worker `HttpServer`; add the second registry entry via `@DynamicPropertySource`:
```java
registry.add("cluster.printers[1].id", () -> "printer-2");
registry.add("cluster.printers[1].display-name", () -> "Second printer");
registry.add("cluster.printers[1].base-url",
    () -> "http://localhost:" + workerServer.getAddress().getPort());
```
Add the request-body variant with `"printerId": "printer-2"`.

- [ ] **Step 2: Add a per-job stream integration test**

Submit a print, then open `GET /api/wristbands/jobs/{jobId}/stream` with the API key and assert at least one `data:` line is received (mirror the existing `sseStream_emitsJobUpdates` style, but hit the per-job URL with the returned jobId).

- [ ] **Step 3: Full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS, 0 failures. Paste the summary.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/stup/wristbandprinter/WristbandIntegrationTest.java
git commit -m "test: integration coverage for printer routing and per-job stream"
```

---

## Self-Review

- **Spec coverage (phase 2):** `printerId` request field + routing (Task 1–2), parallel per-printer threads (Task 2), `GET /printers` (Task 3), per-job SSE stream that completes on terminal status (Task 4), and integration coverage for routing to a second printer + the per-job stream (Task 5). Unknown printer → 400 (Task 1).
- **Placeholder scan:** No TBD/TODO; every step has concrete code. Test edits that touch existing files name the exact additions and let the implementer match the file's existing wiring (read-then-edit).
- **Type consistency:** `WristbandPrintRequest.getPrinterId()` feeds `PrinterRegistry.get(...)` / `getDefault()`; `Printer` record reused; `PrinterSummaryResponse(id, displayName)` used by `GET /printers`; `subscribeToJob(UUID)` returns `SseEmitter` and is used by the controller; `isTerminal`/`removeJobEmitter` are private helpers defined where used.

## Out of scope (this phase)

- Jobs-page UI: printer column, drawer row, filter chips, reprint printer picker (phase 3).
- Production `docker-compose.prod.yml` worker services + TLS trust (deploy task).
- Mercure/relay specifics on the Symfony side (consumer's concern).

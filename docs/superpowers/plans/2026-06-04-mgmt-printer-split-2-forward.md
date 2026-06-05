# Management/Printer Split — Sub-plan 2: Forward to Worker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the management service stop printing locally and instead render ZPL and forward it synchronously to a configured printer-worker (built in sub-plan 1), deriving job status from the worker's HTTP response, while stamping each job with its printer's id and display name.

**Architecture:** A static printer **registry** (`cluster.printers` config) lists each printer's `id`, `displayName`, and internal `baseUrl`. At enqueue, management stamps the job with the (single, in phase 1) printer and persists it. The existing per-queue worker thread, instead of calling `PrinterService.send`, calls a new `WorkerClient` that POSTs `PrintForwardRequest` to the worker's `/api/internal/print` over HTTP. A `2xx` response means the print succeeded (`DONE`); any error or timeout throws `PrinterUnavailableException` → `FAILED`. The observable status lifecycle (`PENDING → PRINTING → DONE/FAILED`), SSE, cancel, and recovery are unchanged. Single printer end-to-end; multi-printer routing and the `printerId` request field come in phase 2.

**Tech Stack:** Java 21, Spring Boot 3.4.1 (`RestClient`), Spring Security, Flyway, JPA/Postgres, JUnit 5, Mockito, Maven (`./mvnw`).

**Spec:** `docs/superpowers/specs/2026-06-04-management-printer-split-design.md` (see the "Amendments" section — synchronous forward).

**Branch:** `feat/printer-worker-split` (continue on it; do not switch).

---

## File Structure

- `cluster/Printer.java` (new) — immutable `record Printer(String id, String displayName, String baseUrl)`; the registry's public type.
- `cluster/PrinterRegistryProperties.java` (new, `@Profile("!worker")`) — `@ConfigurationProperties(prefix = "cluster")` binding `printers` (a list of mutable entries).
- `cluster/PrinterRegistry.java` (new, `@Component`, `@Profile("!worker")`) — validates and exposes printers: `get(id)`, `getDefault()`, `all()`.
- `cluster/WorkerClient.java` (new, `@Component`, `@Profile("!worker")`) — forwards a print to a worker `baseUrl` via `RestClient`; throws `PrinterUnavailableException` on any failure.
- `domain/PrintJob.java` (modify) — carry `printerId` + `printerName`.
- `domain/PrintJobResponse.java`, `domain/PrintJobDetailResponse.java` (modify) — add `printerId`, `printerName`.
- `persistence/PrintJobEntity.java`, `persistence/JpaJobStore.java` (modify) — persist/restore the two columns.
- `src/main/resources/db/migration/V5__add_printer_columns.sql` (new) — add nullable columns.
- `service/PrintQueueService.java` (modify) — stamp printer at enqueue; forward via `WorkerClient` instead of `PrinterService`.
- `src/main/resources/application.yml`, `application-local.yml` (modify) — define `cluster.printers`.
- Tests (new/modified): `cluster/PrinterRegistryTest`, `cluster/WorkerClientTest`, and updates to `service/PrintQueueServiceTest`, `domain/PrintJobTest`, `persistence/JpaJobStoreTest`.

Note: `PrinterService` remains (still used by the worker profile) but is no longer a collaborator of `PrintQueueService`.

---

### Task 1: Printer registry config types

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/cluster/Printer.java`
- Create: `src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistryProperties.java`

- [ ] **Step 1: Create the `Printer` record**

```java
package com.stup.wristbandprinter.cluster;

/** A printer in the registry: a stable id, a human label, and the worker's internal base URL. */
public record Printer(String id, String displayName, String baseUrl) {}
```

- [ ] **Step 2: Create the configuration properties**

```java
package com.stup.wristbandprinter.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;

/** Binds `cluster.printers[*]` from configuration. Management-only. */
@ConfigurationProperties(prefix = "cluster")
@Profile("!worker")
public class PrinterRegistryProperties {

    private List<Entry> printers = new ArrayList<>();

    public List<Entry> getPrinters() { return printers; }
    public void setPrinters(List<Entry> printers) { this.printers = printers; }

    /** Mutable holder for binding a single printer entry. */
    public static class Entry {
        private String id;
        private String displayName;
        private String baseUrl;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }
}
```

- [ ] **Step 3: Verify compile**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/cluster/Printer.java \
        src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistryProperties.java
git commit -m "feat: add printer registry config types"
```

---

### Task 2: PrinterRegistry component

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistry.java`
- Test: `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.stup.wristbandprinter.cluster;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrinterRegistryTest {

    private static PrinterRegistryProperties props(PrinterRegistryProperties.Entry... entries) {
        PrinterRegistryProperties p = new PrinterRegistryProperties();
        for (PrinterRegistryProperties.Entry e : entries) {
            p.getPrinters().add(e);
        }
        return p;
    }

    private static PrinterRegistryProperties.Entry entry(String id, String name, String url) {
        PrinterRegistryProperties.Entry e = new PrinterRegistryProperties.Entry();
        e.setId(id);
        e.setDisplayName(name);
        e.setBaseUrl(url);
        return e;
    }

    @Test
    void getDefault_returnsFirstConfiguredPrinter() {
        PrinterRegistry registry = new PrinterRegistry(
            props(entry("printer-1", "Inkom links", "http://printer-1:8080"),
                  entry("printer-2", "Inkom rechts", "http://printer-2:8080")));
        assertThat(registry.getDefault().id()).isEqualTo("printer-1");
        assertThat(registry.getDefault().displayName()).isEqualTo("Inkom links");
    }

    @Test
    void get_returnsPrinterById() {
        PrinterRegistry registry = new PrinterRegistry(
            props(entry("printer-1", "Inkom links", "http://printer-1:8080")));
        assertThat(registry.get("printer-1").baseUrl()).isEqualTo("http://printer-1:8080");
    }

    @Test
    void get_unknownId_throws() {
        PrinterRegistry registry = new PrinterRegistry(
            props(entry("printer-1", "Inkom links", "http://printer-1:8080")));
        assertThatThrownBy(() -> registry.get("nope"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nope");
    }

    @Test
    void all_returnsAllPrintersInOrder() {
        PrinterRegistry registry = new PrinterRegistry(
            props(entry("printer-1", "A", "http://a:8080"),
                  entry("printer-2", "B", "http://b:8080")));
        assertThat(registry.all()).extracting(Printer::id)
            .containsExactly("printer-1", "printer-2");
    }

    @Test
    void emptyRegistry_throwsAtConstruction() {
        assertThatThrownBy(() -> new PrinterRegistry(props()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cluster.printers");
    }

    @Test
    void duplicateIds_throwAtConstruction() {
        assertThatThrownBy(() -> new PrinterRegistry(
            props(entry("dup", "A", "http://a:8080"),
                  entry("dup", "B", "http://b:8080"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dup");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=PrinterRegistryTest`
Expected: FAIL — `PrinterRegistry` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.stup.wristbandprinter.cluster;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only view over the configured printers. Validates the config at startup. */
@Component
@Profile("!worker")
public class PrinterRegistry {

    private final Map<String, Printer> byId = new LinkedHashMap<>();

    public PrinterRegistry(PrinterRegistryProperties props) {
        if (props.getPrinters().isEmpty()) {
            throw new IllegalStateException(
                "cluster.printers must define at least one printer for the management service");
        }
        for (PrinterRegistryProperties.Entry e : props.getPrinters()) {
            if (byId.containsKey(e.getId())) {
                throw new IllegalStateException("Duplicate printer id in cluster.printers: " + e.getId());
            }
            byId.put(e.getId(), new Printer(e.getId(), e.getDisplayName(), e.getBaseUrl()));
        }
    }

    /** The printer used when a request does not specify one (phase 1: the only printer). */
    public Printer getDefault() {
        return byId.values().iterator().next();
    }

    public Printer get(String id) {
        Printer printer = byId.get(id);
        if (printer == null) {
            throw new IllegalArgumentException("Unknown printer id: " + id);
        }
        return printer;
    }

    public List<Printer> all() {
        return List.copyOf(byId.values());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=PrinterRegistryTest`
Expected: PASS (6 cases)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistry.java \
        src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryTest.java
git commit -m "feat: add PrinterRegistry with config validation"
```

---

### Task 3: Carry printer id + name on PrintJob and responses

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/domain/PrintJob.java`
- Modify: `src/main/java/com/stup/wristbandprinter/domain/PrintJobResponse.java`
- Modify: `src/main/java/com/stup/wristbandprinter/domain/PrintJobDetailResponse.java`
- Modify (if it constructs these directly): `src/test/java/com/stup/wristbandprinter/domain/PrintJobTest.java`

- [ ] **Step 1: Add fields to the response records**

`PrintJobResponse.java` — add `printerId` and `printerName` (place after `status`):

```java
package com.stup.wristbandprinter.domain;

import java.time.Instant;
import java.util.UUID;

public record PrintJobResponse(
    UUID jobId,
    PrintJobStatus status,
    String printerId,
    String printerName,
    String eventName,
    String firstName,
    String lastName,
    Instant submittedAt,
    Instant completedAt,
    String error
) {}
```

`PrintJobDetailResponse.java`:

```java
package com.stup.wristbandprinter.domain;

import java.time.Instant;
import java.util.UUID;

public record PrintJobDetailResponse(
    UUID jobId,
    PrintJobStatus status,
    String printerId,
    String printerName,
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

- [ ] **Step 2: Add printer fields to `PrintJob`**

In `PrintJob.java`: add two final fields and thread them through both constructors, the `restore` factory, and both response builders. Replace the class body's constructors/factory/builders with:

```java
    private final UUID jobId;
    private final WristbandPrintRequest request;
    private final String printerId;
    private final String printerName;
    private PrintJobStatus status;
    private final Instant submittedAt;
    private Instant completedAt;
    private String error;

    public PrintJob(UUID jobId, WristbandPrintRequest request, String printerId, String printerName) {
        this.jobId = jobId;
        this.request = request;
        this.printerId = printerId;
        this.printerName = printerName;
        this.status = PrintJobStatus.PENDING;
        this.submittedAt = Instant.now();
    }

    private PrintJob(UUID jobId, WristbandPrintRequest request, String printerId, String printerName,
                     PrintJobStatus status, Instant submittedAt, Instant completedAt, String error) {
        this.jobId = jobId;
        this.request = request;
        this.printerId = printerId;
        this.printerName = printerName;
        this.status = status;
        this.submittedAt = submittedAt;
        this.completedAt = completedAt;
        this.error = error;
    }

    /** Rebuild a job from durable storage, preserving its original timestamps and state. */
    public static PrintJob restore(UUID jobId, WristbandPrintRequest request, String printerId,
                                   String printerName, PrintJobStatus status, Instant submittedAt,
                                   Instant completedAt, String error) {
        return new PrintJob(jobId, request, printerId, printerName, status, submittedAt, completedAt, error);
    }

    public UUID getJobId() { return jobId; }
    public WristbandPrintRequest getRequest() { return request; }
    public String getPrinterId() { return printerId; }
    public String getPrinterName() { return printerName; }
```

Then update both response builders to include the new fields:

```java
    public synchronized PrintJobResponse toResponse() {
        return new PrintJobResponse(
            jobId,
            status,
            printerId,
            printerName,
            request.getEventName(),
            request.getFirstName(),
            request.getLastName(),
            submittedAt,
            completedAt,
            error
        );
    }

    public synchronized PrintJobDetailResponse toDetailResponse() {
        return new PrintJobDetailResponse(
            jobId,
            status,
            printerId,
            printerName,
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

Keep the existing `getStatus`/`setStatus`/`getSubmittedAt`/`getCompletedAt`/`setCompletedAt`/`getError`/`setError`/`complete` methods unchanged.

- [ ] **Step 3: Fix any now-broken callers and the PrintJob test**

Compile to find every broken call site:

Run: `./mvnw -q -DskipTests test-compile`
Expected: compile errors at `PrintJob.restore(...)` / `new PrintJob(...)` / `new PrintJobResponse(...)` / `new PrintJobDetailResponse(...)` call sites.

Update each:
- Any `new PrintJob(id, request)` → `new PrintJob(id, request, "printer-1", "Test Printer")` in tests (use a clear test value).
- Any `PrintJob.restore(id, request, status, submittedAt, completedAt, error)` → insert `"printer-1", "Test Printer"` after `request`: `PrintJob.restore(id, request, "printer-1", "Test Printer", status, submittedAt, completedAt, error)`.
- Any direct `new PrintJobResponse(...)` / `new PrintJobDetailResponse(...)` in tests → add the two printer args after `status`.

For `domain/PrintJobTest.java`, read it and apply the above; if it asserts on `toResponse()`/`toDetailResponse()` add assertions that `printerId`/`printerName` round-trip the constructor values.

- [ ] **Step 4: Run the domain test**

Run: `./mvnw -q test -Dtest=PrintJobTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain \
        src/test/java/com/stup/wristbandprinter/domain/PrintJobTest.java
git commit -m "feat: carry printerId and printerName on PrintJob and responses"
```

(The full suite will not be green until Tasks 4 and 6 update the persistence layer and the queue service; that is expected mid-refactor. Do not run the whole suite yet.)

---

### Task 4: Persist printer columns

**Files:**
- Create: `src/main/resources/db/migration/V5__add_printer_columns.sql`
- Modify: `src/main/java/com/stup/wristbandprinter/persistence/PrintJobEntity.java`
- Modify: `src/main/java/com/stup/wristbandprinter/persistence/JpaJobStore.java`
- Modify: `src/test/java/com/stup/wristbandprinter/persistence/JpaJobStoreTest.java`

- [ ] **Step 1: Write the migration**

```sql
ALTER TABLE print_jobs ADD COLUMN printer_id   VARCHAR(255);
ALTER TABLE print_jobs ADD COLUMN printer_name VARCHAR(255);
```

- [ ] **Step 2: Add columns to the entity**

In `PrintJobEntity.java`, add two fields, thread them through the constructor (after `status`), and add getters:

```java
    @Enumerated(EnumType.STRING)
    private PrintJobStatus status;

    private String printerId;
    private String printerName;
```

Update the all-args constructor signature to accept `String printerId, String printerName` immediately after `PrintJobStatus status`, assign them, and add:

```java
    public String getPrinterId() { return printerId; }
    public String getPrinterName() { return printerName; }
```

- [ ] **Step 3: Map them in JpaJobStore**

In `JpaJobStore.save`, pass the job's printer fields into the entity constructor (after `job.getStatus()`):

```java
        repository.save(new PrintJobEntity(
            job.getJobId(),
            job.getStatus(),
            job.getPrinterId(),
            job.getPrinterName(),
            r.getEventName(),
            r.getFirstName(),
            r.getLastName(),
            r.getAssociationName(),
            r.getBarcodeValue(),
            job.getSubmittedAt(),
            job.getCompletedAt(),
            job.getError()
        ));
```

In `JpaJobStore.toDomain`, pass the entity's printer fields into `restore` (after `request`):

```java
        return PrintJob.restore(
            e.getJobId(),
            request,
            e.getPrinterId(),
            e.getPrinterName(),
            e.getStatus(),
            e.getSubmittedAt(),
            e.getCompletedAt(),
            e.getError()
        );
```

- [ ] **Step 4: Update and run the JpaJobStore test**

Read `persistence/JpaJobStoreTest.java`. Update any `new PrintJob(...)`/`PrintJob.restore(...)` calls to the new signatures (printer args after `request`), and add a test asserting a saved job's `printerId`/`printerName` survive `loadActive()` round-trip. Then:

Run: `./mvnw -q test -Dtest=JpaJobStoreTest`
Expected: PASS (a local Postgres is running; Flyway applies V5).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V5__add_printer_columns.sql \
        src/main/java/com/stup/wristbandprinter/persistence \
        src/test/java/com/stup/wristbandprinter/persistence/JpaJobStoreTest.java
git commit -m "feat: persist printer_id and printer_name on print jobs"
```

---

### Task 5: WorkerClient (forward over HTTP)

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/cluster/WorkerClient.java`
- Test: `src/test/java/com/stup/wristbandprinter/cluster/WorkerClientTest.java`

- [ ] **Step 1: Write the failing test**

Uses Spring's `MockRestServiceServer` against a `RestClient.Builder` to verify the request and the failure mapping.

```java
package com.stup.wristbandprinter.cluster;

import com.stup.wristbandprinter.exception.PrinterUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WorkerClientTest {

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private WorkerClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new WorkerClient("test-key", builder);
    }

    @Test
    void print_postsForwardRequestWithApiKey() {
        UUID jobId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        server.expect(requestTo("http://worker:8080/api/internal/print"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(header("X-API-Key", "test-key"))
            .andExpect(jsonPath("$.jobId").value(jobId.toString()))
            .andExpect(jsonPath("$.zpl").value("^XA^XZ"))
            .andRespond(withSuccess());

        assertThatCode(() -> client.print("http://worker:8080", jobId, "^XA^XZ"))
            .doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void print_throwsPrinterUnavailable_onErrorResponse() {
        UUID jobId = UUID.randomUUID();
        server.expect(requestTo("http://worker:8080/api/internal/print"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.print("http://worker:8080", jobId, "^XA^XZ"))
            .isInstanceOf(PrinterUnavailableException.class)
            .hasMessageContaining("http://worker:8080");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=WorkerClientTest`
Expected: FAIL — `WorkerClient` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.stup.wristbandprinter.cluster;

import com.stup.wristbandprinter.cluster.dto.PrintForwardRequest;
import com.stup.wristbandprinter.exception.PrinterUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

/** Forwards a rendered print job from management to a printer-worker over HTTP. */
@Component
@Profile("!worker")
public class WorkerClient {

    private static final Logger log = LoggerFactory.getLogger(WorkerClient.class);

    private final String apiKey;
    private final RestClient restClient;

    public WorkerClient(@Value("${security.api-key}") String apiKey, RestClient.Builder builder) {
        this.apiKey = apiKey;
        this.restClient = builder.build();
    }

    /**
     * Send the job to the worker at {@code baseUrl} and block until it finishes.
     * A non-2xx response or any transport failure is surfaced as PrinterUnavailableException
     * so the queue worker marks the job FAILED, exactly as a local printer failure would.
     */
    public void print(String baseUrl, UUID jobId, String zpl) {
        try {
            restClient.post()
                .uri(baseUrl + "/api/internal/print")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PrintForwardRequest(jobId, zpl))
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Forward to worker {} failed for job {}: {}", baseUrl, jobId, e.getMessage());
            throw new PrinterUnavailableException(
                "Worker at " + baseUrl + " could not print job " + jobId + ": " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=WorkerClientTest`
Expected: PASS (2 cases)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/cluster/WorkerClient.java \
        src/test/java/com/stup/wristbandprinter/cluster/WorkerClientTest.java
git commit -m "feat: add WorkerClient to forward prints to a worker over HTTP"
```

---

### Task 6: Forward from the queue instead of printing locally

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java`
- Modify: `src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java`

- [ ] **Step 1: Update the failing test first (TDD)**

In `PrintQueueServiceTest.java`:
- Replace `@Mock private PrinterService printerService;` with:
  ```java
  @Mock private com.stup.wristbandprinter.cluster.PrinterRegistry printerRegistry;
  @Mock private com.stup.wristbandprinter.cluster.WorkerClient workerClient;
  ```
- In `newService(...)`, change the constructor call to pass `printerRegistry, workerClient` in place of `printerService`:
  ```java
  return new PrintQueueService(layoutService, wristbandZplResolver, printerRegistry, workerClient,
      queueProperties, jobStore, meterRegistry);
  ```
- Add a default-printer stub so `enqueue` can stamp jobs. In `setUp()` (lenient, since not every test enqueues through processing):
  ```java
  org.mockito.Mockito.lenient().when(printerRegistry.getDefault())
      .thenReturn(new com.stup.wristbandprinter.cluster.Printer("printer-1", "Test Printer", "http://worker:8080"));
  org.mockito.Mockito.lenient().when(printerRegistry.get("printer-1"))
      .thenReturn(new com.stup.wristbandprinter.cluster.Printer("printer-1", "Test Printer", "http://worker:8080"));
  ```
- In the processing tests, replace `printerService.send(...)` stubbing/verification with `workerClient.print(...)`:
  - `enqueue_jobBecomesAfterProcessing`: `doAnswer(inv -> { latch.countDown(); return null; }).when(workerClient).print(any(), any(), any());`
  - `worker_sendsResolvedZpl`: stub the same `doAnswer`, then `verify(workerClient).print("http://worker:8080", job's id, "^XA^XZ-resolved");` — capture the job id via the returned `PrintJob`. Use `eq(...)`/`any()` as needed:
    ```java
    when(layoutService.buildData(any())).thenReturn(sampleData());
    when(wristbandZplResolver.resolve(any(), any())).thenReturn("^XA^XZ-resolved");
    doAnswer(inv -> { latch.countDown(); return null; }).when(workerClient).print(any(), any(), any());
    service.startWorker();
    PrintJob job = service.enqueue(sampleRequest());
    assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    verify(workerClient).print("http://worker:8080", job.getJobId(), "^XA^XZ-resolved");
    ```
  - `enqueue_jobBecomesFailed_whenPrinterThrows`: `doAnswer(inv -> { latch.countDown(); throw new PrinterUnavailableException("Printer down"); }).when(workerClient).print(any(), any(), any());`
- Add a test that the enqueued job is stamped:
  ```java
  @Test
  void enqueue_stampsDefaultPrinter() {
      PrintJob job = service.enqueue(sampleRequest());
      assertThat(job.getPrinterId()).isEqualTo("printer-1");
      assertThat(job.getPrinterName()).isEqualTo("Test Printer");
  }
  ```
- Remove the now-unused `PrinterService` import if present.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=PrintQueueServiceTest`
Expected: FAIL — `PrintQueueService` constructor signature does not match / `getPrinterId` missing on enqueued job.

- [ ] **Step 3: Refactor PrintQueueService**

Change the imports, fields, and constructor to depend on `PrinterRegistry` + `WorkerClient` instead of `PrinterService`:

```java
import com.stup.wristbandprinter.cluster.Printer;
import com.stup.wristbandprinter.cluster.PrinterRegistry;
import com.stup.wristbandprinter.cluster.WorkerClient;
```

Replace the `private final PrinterService printerService;` field with:

```java
    private final PrinterRegistry printerRegistry;
    private final WorkerClient workerClient;
```

Update the constructor parameter list (swap `PrinterService printerService` for the two new collaborators, keep the rest and the order otherwise the same) and assignments accordingly.

In `enqueue(...)`, stamp the default printer when constructing the job:

```java
    public PrintJob enqueue(WristbandPrintRequest request) {
        if (queue.size() >= queueProperties.getMaxDepth()) {
            throw queueFull(request);
        }

        Printer printer = printerRegistry.getDefault();
        PrintJob job = new PrintJob(UUID.randomUUID(), request, printer.id(), printer.displayName());
        // Persist before exposing the job to the worker: otherwise the worker thread can
        // dequeue and save it concurrently with this thread's save, causing duplicate inserts.
        jobStore.save(job);
        jobs.put(job.getJobId(), job);

        if (!queue.offer(job)) {
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

In `processQueue()`, replace the `printerService.send(zpl)` call with a forward to the job's printer:

```java
                        WristbandData data = layoutService.buildData(job.getRequest());
                        String zpl = wristbandZplResolver.resolve(job.getRequest(), data);
                        Printer printer = printerRegistry.get(job.getPrinterId());
                        workerClient.print(printer.baseUrl(), job.getJobId(), zpl);
                        job.complete(PrintJobStatus.DONE, null, Instant.now());
                        doneCounter.increment();
```

Leave the `catch (PrinterUnavailableException e)` / generic catch blocks, status transitions, persistence, SSE, cancel, and recovery exactly as they are.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=PrintQueueServiceTest`
Expected: PASS (all cases, including the new stamping test)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java \
        src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java
git commit -m "refactor: forward prints to the worker instead of printing locally"
```

---

### Task 7: Wire config, full verification, end-to-end smoke test

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.yml`

- [ ] **Step 1: Add a default registry to base config**

In `application.yml`, add a top-level `cluster` block (a single printer pointing at a worker on the same host/port the management would reach; overridden per environment):

```yaml
cluster:
  printers:
    - id: printer-1
      display-name: Printer 1
      base-url: http://localhost:8089
```

- [ ] **Step 2: Point the local registry at the local worker**

In `application-local.yml`, add:

```yaml
cluster:
  printers:
    - id: printer-1
      display-name: Lokale printer
      base-url: http://localhost:8089
```

- [ ] **Step 3: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS, 0 failures. (A local Postgres must be running for the integration tests.) Paste the `Tests run:` summary.

- [ ] **Step 4: End-to-end smoke test (management → worker → printer)**

In four terminals from the repo root:

1. Fake printer: `while true; do echo "── wacht ──"; nc -l 9100; echo; done`
2. Worker: `SPRING_PROFILES_ACTIVE=worker SECURITY_API_KEY=local-dev-key PRINTER_HOST=localhost PRINTER_PORT=9100 SERVER_PORT=8089 ./mvnw -q spring-boot:run`
3. Management (local): start Postgres if needed (`docker compose up -d postgres`), then `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/wristbands ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local`
4. Submit a job:
   ```bash
   curl -s -X POST http://localhost:8080/api/wristbands/print \
     -H "Content-Type: application/json" -H "X-API-Key: local-dev-key" \
     -d '{"eventName":"Test","firstName":"Jan","lastName":"Janssen","associationName":"STUP","barcodeValue":"123456"}'
   ```
   Expected: the response JSON contains a `jobId`, `printerId":"printer-1"`, and `printerName`. The ZPL appears in terminal 1 (the fake printer). `GET http://localhost:8080/api/wristbands/jobs/<jobId>` shows status `DONE` with the printer fields populated. Stop terminals 1–3 when done.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.yml src/main/resources/application-local.yml
git commit -m "feat: configure a single-printer registry for base and local profiles"
```

---

## Self-Review

- **Spec coverage (phase 1 / sub-plan 2):** registry (Task 1–2), management forwards over HTTP deriving status from the response (Task 5–6), ZPL still rendered in management (unchanged in `processQueue`), printer stamping + persistence (Task 3–4), single printer end-to-end (Task 7). Cancel/recovery/SSE are deliberately untouched (synchronous forward keeps them working). Deferred: multi-printer routing + `printerId` request field + per-job stream (phase 2); UI (phase 3); production compose/TLS for workers (a later deploy task).
- **Placeholder scan:** No TBD/TODO. Test updates that touch existing files (`PrintJobTest`, `JpaJobStoreTest`, `PrintQueueServiceTest`) specify the exact signature changes and the new assertions; the implementer applies them by compiling and fixing the enumerated call sites.
- **Type consistency:** `Printer(id, displayName, baseUrl)` record is used consistently in `PrinterRegistry`, `WorkerClient.print(baseUrl, jobId, zpl)`, and `PrintQueueService`. `PrintJob` gains `getPrinterId()`/`getPrinterName()` used by `JpaJobStore` and the response builders. `PrintForwardRequest(jobId, zpl)` (from sub-plan 1) is reused by `WorkerClient`. The `PrintQueueService` constructor parameter order is defined in Task 6 and matched in the test.

## Out of scope (this sub-plan)

- `printerId` in the public `POST /print` request and multi-printer routing (phase 2).
- `GET /api/wristbands/jobs/{jobId}/stream` per-job SSE endpoint (phase 2).
- Jobs-page printer column, drawer row, filter chips, reprint picker (phase 3).
- Production `docker-compose.prod.yml` worker services and TLS trust between management and workers (deploy task).

# Copies per print job (+ jobs-table column chooser) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a caller print N identical wristbands from one job (default 1) via the Zebra `^PQ` command, and refresh the jobs table with a `Copies` column plus an operator-controlled column-visibility chooser.

**Architecture:** A `copies` field (default 1) rides on the shared `PrintableRequest`. The print path appends `^PQ<n>` to the resolved ZPL **after** the shared resolver (so previews stay single-label) and forwards one job to one worker. Persistence gains one column; responses gain `copies`; reprint gains a `copies` override. The jobs table becomes data-driven from a single `COLUMNS` array so visibility can be toggled (max 5 data columns + always-on Actions), remembered in `localStorage`.

**Tech Stack:** Java 21, Spring Boot 3.4.1, Flyway, JPA/Postgres (Testcontainers), JUnit 5 + Mockito + MockMvc, vanilla JS (no build step), `app.css` design system.

**Conventions for every task:** constructor injection, `@Profile("!worker")` on management beans, typed `@ConfigurationProperties`, domain exceptions mapped centrally. **Every `git commit` message must end with the trailer:**
```
Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
```

**Spec:** `docs/superpowers/specs/2026-06-12-copies-per-job-design.md`

---

## Task 1: `copies` on the request DTOs + sealed interface

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/domain/PrintableRequest.java`
- Modify: `src/main/java/com/stup/wristbandprinter/domain/WristbandPrintRequest.java`
- Modify: `src/main/java/com/stup/wristbandprinter/domain/PermitWristbandPrintRequest.java`
- Test: `src/test/java/com/stup/wristbandprinter/domain/PrintableRequestCopiesTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/domain/PrintableRequestCopiesTest.java`:

```java
package com.stup.wristbandprinter.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrintableRequestCopiesTest {

    @Test
    void crew_defaultsToOne_whenCopiesNull() {
        assertThat(new WristbandPrintRequest().getCopies()).isEqualTo(1);
    }

    @Test
    void permit_defaultsToOne_whenCopiesNull() {
        assertThat(new PermitWristbandPrintRequest().getCopies()).isEqualTo(1);
    }

    @Test
    void crew_returnsSetValue() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setCopies(42);
        assertThat(r.getCopies()).isEqualTo(42);
    }

    @Test
    void withCopies_returnsCopyWithNewCount_preservingOtherFields() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setBarcodeValue("123");
        r.setCopies(3);

        PrintableRequest updated = r.withCopies(120);

        assertThat(updated.getCopies()).isEqualTo(120);
        assertThat(((WristbandPrintRequest) updated).getEventName()).isEqualTo("Pukkelpop 2026");
        assertThat(((WristbandPrintRequest) updated).getBarcodeValue()).isEqualTo("123");
        assertThat(r.getCopies()).isEqualTo(3); // original untouched
    }

    @Test
    void withPrinterId_carriesCopiesThrough() {
        PermitWristbandPrintRequest r = new PermitWristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setPermitLabel("Elektriciteit");
        r.setCopies(7);

        PrintableRequest stamped = r.withPrinterId("printer-2");

        assertThat(stamped.getCopies()).isEqualTo(7);
        assertThat(stamped.getPrinterId()).isEqualTo("printer-2");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PrintableRequestCopiesTest`
Expected: FAIL — compile error, `getCopies()` / `withCopies(...)` / `setCopies(...)` do not exist yet.

- [ ] **Step 3: Add the interface members**

In `PrintableRequest.java`, add inside the interface (after `getStockColorCode()`):

```java
    /** Number of physical copies to print (^PQ). Always ≥ 1; null on the DTO means default 1. */
    int getCopies();

    /** Return a copy of this request with the copies count overridden. */
    PrintableRequest withCopies(int copies);
```

- [ ] **Step 4: Implement on `WristbandPrintRequest`**

In `WristbandPrintRequest.java`:

Add the field after `stockColorCode`:

```java
    @io.swagger.v3.oas.annotations.media.Schema(description = "Number of copies to print; defaults to 1 when omitted", example = "1")
    @jakarta.validation.constraints.Min(value = 1, message = "copies must be at least 1")
    private Integer copies;
```

Add accessors (near the other getters/setters):

```java
    @Override
    public int getCopies() { return copies == null ? 1 : copies; }
    public void setCopies(Integer copies) { this.copies = copies; }
```

In `withPrinterId(...)`, add the copy line (alongside the existing `copy.xxx = this.xxx;` lines):

```java
        copy.copies         = this.copies;
```

Add the wither (after `withPrinterId`):

```java
    @Override
    public PrintableRequest withCopies(int copies) {
        WristbandPrintRequest copy = (WristbandPrintRequest) withPrinterId(this.printerId);
        copy.copies = copies;
        return copy;
    }
```

- [ ] **Step 5: Implement on `PermitWristbandPrintRequest`**

In `PermitWristbandPrintRequest.java`:

Add the field after `stockColorCode`:

```java
    @Schema(description = "Number of copies to print; defaults to 1 when omitted", example = "1")
    @jakarta.validation.constraints.Min(value = 1, message = "copies must be at least 1")
    private Integer copies;
```

Add accessors:

```java
    @Override
    public int getCopies() { return copies == null ? 1 : copies; }
    public void setCopies(Integer copies) { this.copies = copies; }
```

In `withPrinterId(...)`, add (alongside the other `copy.xxx` lines):

```java
        copy.copies          = this.copies;
```

Add the wither:

```java
    @Override
    public PrintableRequest withCopies(int copies) {
        PermitWristbandPrintRequest copy = (PermitWristbandPrintRequest) withPrinterId(this.printerId);
        copy.copies = copies;
        return copy;
    }
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=PrintableRequestCopiesTest`
Expected: PASS (5 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/PrintableRequest.java \
        src/main/java/com/stup/wristbandprinter/domain/WristbandPrintRequest.java \
        src/main/java/com/stup/wristbandprinter/domain/PermitWristbandPrintRequest.java \
        src/test/java/com/stup/wristbandprinter/domain/PrintableRequestCopiesTest.java
git commit -m "feat(domain): add copies field to printable requests"
```

---

## Task 2: `ZplCopies` — apply `^PQ` on the print path

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/service/ZplCopies.java`
- Test: `src/test/java/com/stup/wristbandprinter/service/ZplCopiesTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/service/ZplCopiesTest.java`:

```java
package com.stup.wristbandprinter.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ZplCopiesTest {

    @Test
    void copiesOfOne_leavesZplUnchanged() {
        String zpl = "^XA^FO10,10^FDhi^FS^XZ";
        assertThat(ZplCopies.apply(zpl, 1)).isEqualTo(zpl);
    }

    @Test
    void copiesOfZeroOrLess_leavesZplUnchanged() {
        String zpl = "^XA^XZ";
        assertThat(ZplCopies.apply(zpl, 0)).isEqualTo(zpl);
    }

    @Test
    void insertsPrintQuantityBeforeFinalXZ() {
        String zpl = "^XA^FO10,10^FDhi^FS^XZ";
        assertThat(ZplCopies.apply(zpl, 5)).isEqualTo("^XA^FO10,10^FDhi^FS^PQ5,0,0,Y^XZ");
    }

    @Test
    void insertsBeforeTheLastXZ_whenMultiplePresent() {
        // mimics clear-block + label-block; ^PQ must land in the LAST (label) block
        String zpl = "^XA^IDR:*.*^FS^XZ^XA^FDlabel^FS^XZ";
        assertThat(ZplCopies.apply(zpl, 3))
            .isEqualTo("^XA^IDR:*.*^FS^XZ^XA^FDlabel^FS^PQ3,0,0,Y^XZ");
    }

    @Test
    void noXZ_appendsDefensively() {
        assertThat(ZplCopies.apply("^XAbroken", 2)).isEqualTo("^XAbroken^PQ2,0,0,Y");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ZplCopiesTest`
Expected: FAIL — `ZplCopies` does not exist.

- [ ] **Step 3: Implement `ZplCopies`**

Create `src/main/java/com/stup/wristbandprinter/service/ZplCopies.java`:

```java
package com.stup.wristbandprinter.service;

/**
 * Appends the Zebra print-quantity command (^PQ) to a finished ZPL label so the printer
 * emits multiple physical copies from a single stream.
 *
 * <p>Applied on the PRINT path only — never inside {@code WristbandZplResolver}, which is
 * shared with preview endpoints (a preview must stay a single label).</p>
 *
 * <p>{@code ^PQq,p,r,o}: q=quantity, p=pause-between-groups (0), r=replicates (0),
 * o=override-pause (Y) so a continuous wristband roll prints without pausing.</p>
 */
public final class ZplCopies {

    private ZplCopies() {
    }

    public static String apply(String zpl, int copies) {
        if (zpl == null || copies <= 1) {
            return zpl;
        }
        String pq = "^PQ" + copies + ",0,0,Y";
        int idx = zpl.lastIndexOf("^XZ");
        if (idx < 0) {
            return zpl + pq;
        }
        return zpl.substring(0, idx) + pq + zpl.substring(idx);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=ZplCopiesTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/ZplCopies.java \
        src/test/java/com/stup/wristbandprinter/service/ZplCopiesTest.java
git commit -m "feat(zpl): ZplCopies appends ^PQ print-quantity to a label"
```

---

## Task 3: `print.max-copies` config + `InvalidCopiesException` + HTTP mapping

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/config/PrintProperties.java`
- Create: `src/main/java/com/stup/wristbandprinter/exception/InvalidCopiesException.java`
- Modify: `src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/stup/wristbandprinter/exception/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: Write the failing test**

In `GlobalExceptionHandlerTest.java`, add this test method (the class is a `@WebMvcTest(WristbandController.class)` with `printQueueService` already a `@MockitoBean`; add the needed imports `org.mockito.Mockito` is already imported, and add `import com.stup.wristbandprinter.exception.InvalidCopiesException;` is in-package so no import needed):

```java
    @Test
    void invalidCopies_returns400() throws Exception {
        Mockito.when(printQueueService.enqueue(Mockito.any()))
            .thenThrow(new InvalidCopiesException("copies must be between 1 and 200 (was 999)"));

        String body = """
            {
              "eventName": "Pukkelpop 2026",
              "firstName": "Jan",
              "lastName": "Janssens",
              "associationName": "STUP vzw",
              "barcodeValue": "123",
              "copies": 999
            }
            """;

        mockMvc.perform(post("/api/wristbands/crew/print")
                .header("X-API-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid copies"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=GlobalExceptionHandlerTest#invalidCopies_returns400`
Expected: FAIL — `InvalidCopiesException` does not exist (compile error).

- [ ] **Step 3: Create the exception**

Create `src/main/java/com/stup/wristbandprinter/exception/InvalidCopiesException.java`:

```java
package com.stup.wristbandprinter.exception;

public class InvalidCopiesException extends RuntimeException {
    public InvalidCopiesException(String message) { super(message); }
}
```

- [ ] **Step 4: Map it to 400**

In `GlobalExceptionHandler.java`, add a handler next to the other `@ExceptionHandler` methods (e.g. after `handleInvalidStockColor`):

```java
    @ExceptionHandler(InvalidCopiesException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCopies(InvalidCopiesException ex) {
        return errorResponse(HttpStatus.BAD_REQUEST, "Invalid copies", ex.getMessage());
    }
```

- [ ] **Step 5: Create `PrintProperties`**

Create `src/main/java/com/stup/wristbandprinter/config/PrintProperties.java`:

```java
package com.stup.wristbandprinter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "print")
public class PrintProperties {

    /** Hard cap on copies per job; a request above this is rejected with 400. */
    private int maxCopies = 200;

    public int getMaxCopies() { return maxCopies; }
    public void setMaxCopies(int maxCopies) { this.maxCopies = maxCopies; }
}
```

(`@ConfigurationPropertiesScan` on `WristbandPrinterApplication` auto-registers it.)

- [ ] **Step 6: Add the YAML key**

In `src/main/resources/application.yml`, add a `print:` block next to the existing `queue:` block:

```yaml
print:
  max-copies: 200         # hard cap on copies per job (^PQ); requests above this are rejected with 400
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./mvnw test -Dtest=GlobalExceptionHandlerTest#invalidCopies_returns400`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/config/PrintProperties.java \
        src/main/java/com/stup/wristbandprinter/exception/InvalidCopiesException.java \
        src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java \
        src/main/resources/application.yml \
        src/test/java/com/stup/wristbandprinter/exception/GlobalExceptionHandlerTest.java
git commit -m "feat(config): print.max-copies cap + InvalidCopiesException (400)"
```

---

## Task 4: Wire copies into `PrintQueueService` (validate + apply ^PQ)

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java`
- Test: `src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java`

- [ ] **Step 1: Update the test setup + write failing tests**

In `PrintQueueServiceTest.java`:

(a) Add imports:

```java
import com.stup.wristbandprinter.config.PrintProperties;
import com.stup.wristbandprinter.exception.InvalidCopiesException;
```

(b) Replace `newService(int maxDepth)` so it injects a `PrintProperties` (default `maxCopies = 200`). The `PrintProperties` field lets tests tweak the cap:

```java
    private PrintProperties printProperties;

    private PrintQueueService newService(int maxDepth) {
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setMaxDepth(maxDepth);
        printProperties = new PrintProperties();
        jobStore = new InMemoryJobStore();
        meterRegistry = new SimpleMeterRegistry();
        return new PrintQueueService(wristbandZplResolver, printerRegistry, workerClient,
            queueProperties, printProperties, jobStore, meterRegistry);
    }
```

(c) Add two tests (place near the other enqueue tests):

```java
    @Test
    void enqueue_persistsCopies() {
        WristbandPrintRequest req = sampleRequest();
        req.setCopies(120);

        PrintJob job = service.enqueue(req);

        assertThat(job.getRequest().getCopies()).isEqualTo(120);
    }

    @Test
    void enqueue_rejectsCopiesAboveMax() {
        printProperties.setMaxCopies(200);
        WristbandPrintRequest req = sampleRequest();
        req.setCopies(201);

        assertThatThrownBy(() -> service.enqueue(req))
            .isInstanceOf(InvalidCopiesException.class);
    }

    @Test
    void enqueue_rejectsCopiesBelowOne() {
        WristbandPrintRequest req = sampleRequest();
        req.setCopies(0);

        assertThatThrownBy(() -> service.enqueue(req))
            .isInstanceOf(InvalidCopiesException.class);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=PrintQueueServiceTest`
Expected: FAIL — constructor arity mismatch (compile error) until the service is updated.

- [ ] **Step 3: Update `PrintQueueService` constructor + field**

In `PrintQueueService.java`:

Add import:

```java
import com.stup.wristbandprinter.config.PrintProperties;
import com.stup.wristbandprinter.exception.InvalidCopiesException;
```

Add the field near `queueProperties`:

```java
    private final PrintProperties printProperties;
```

Update the constructor signature and body — insert `PrintProperties printProperties` right after `QueueProperties queueProperties`:

```java
    public PrintQueueService(WristbandZplResolver wristbandZplResolver,
                              PrinterRegistry printerRegistry,
                              WorkerClient workerClient,
                              QueueProperties queueProperties,
                              PrintProperties printProperties,
                              JobStore jobStore,
                              MeterRegistry meterRegistry) {
        this.wristbandZplResolver = wristbandZplResolver;
        this.printerRegistry = printerRegistry;
        this.workerClient = workerClient;
        this.queueProperties = queueProperties;
        this.printProperties = printProperties;
        this.jobStore = jobStore;
```

(Leave the rest of the constructor — meter registration — unchanged.)

- [ ] **Step 4: Validate copies in `enqueue`**

In `enqueue(PrintableRequest request)`, add at the very top of the method (before resolving the printer):

```java
        int copies = request.getCopies();
        if (copies < 1 || copies > printProperties.getMaxCopies()) {
            throw new InvalidCopiesException(
                "copies must be between 1 and " + printProperties.getMaxCopies()
                    + " (was " + copies + ")");
        }
```

- [ ] **Step 5: Apply `^PQ` in `processQueue`**

In `processQueue(...)`, change the resolve line so the copies command is appended on the print path. Replace:

```java
                        String zpl = wristbandZplResolver.resolve(job.getRequest());
```

with:

```java
                        String zpl = wristbandZplResolver.resolve(job.getRequest());
                        zpl = ZplCopies.apply(zpl, job.getRequest().getCopies());
```

(`ZplCopies` is in the same package — no import needed.)

- [ ] **Step 6: Run tests to verify they pass**

Run: `./mvnw test -Dtest=PrintQueueServiceTest`
Expected: PASS (all existing + 3 new). Requires a running Docker daemon only if other classes need it; `PrintQueueServiceTest` itself uses in-memory mocks, so it runs without Docker.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java \
        src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java
git commit -m "feat(queue): validate copies and append ^PQ on the print path"
```

---

## Task 5: Persist `copies` (Flyway V7 + entity + store)

**Files:**
- Create: `src/main/resources/db/migration/V7__add_copies.sql`
- Modify: `src/main/java/com/stup/wristbandprinter/persistence/PrintJobEntity.java`
- Modify: `src/main/java/com/stup/wristbandprinter/persistence/JpaJobStore.java`
- Test: `src/test/java/com/stup/wristbandprinter/persistence/JpaJobStoreTest.java`

- [ ] **Step 1: Write the failing test**

In `JpaJobStoreTest.java`, add (the `request()` helper builds a `WristbandPrintRequest`; set copies on a fresh one):

```java
    @Test
    void saveAndLoad_roundTripsCopies() {
        UUID id = UUID.randomUUID();
        WristbandPrintRequest req = new WristbandPrintRequest();
        req.setEventName("Pukkelpop 2026");
        req.setFirstName("Jan");
        req.setLastName("Janssens");
        req.setAssociationName("STUP vzw");
        req.setBarcodeValue("123456789");
        req.setCopies(120);
        Instant now = Instant.now();
        store.save(PrintJob.restore(id, req, PrintJobStatus.DONE, now, now, null));

        PrintJob loaded = store.loadActive().get(0);

        assertThat(loaded.getRequest().getCopies()).isEqualTo(120);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=JpaJobStoreTest#saveAndLoad_roundTripsCopies` (needs a running Docker daemon for Testcontainers)
Expected: FAIL — `copies` is not persisted; loaded value is `1`.

- [ ] **Step 3: Add the Flyway migration**

Create `src/main/resources/db/migration/V7__add_copies.sql`:

```sql
-- Number of physical copies a job prints (Zebra ^PQ). Existing rows default to 1.
ALTER TABLE print_jobs ADD COLUMN copies integer NOT NULL DEFAULT 1;
```

- [ ] **Step 4: Add the entity column**

In `PrintJobEntity.java`:

Add the field in the "Shared optional fields" group (after `codeSymbology`):

```java
    @Column(nullable = false)
    private Integer copies;
```

Update the constructor signature — insert `Integer copies` after `codeSymbology` and before `submittedAt`:

```java
    public PrintJobEntity(UUID jobId, PrintJobStatus status, WristbandType wristbandType,
                          String printerId, String printerName,
                          String eventName, String firstName, String lastName,
                          String associationName, String barcodeValue,
                          String permitLabel, String iconName,
                          Integer stockColorCode, String codeValue, CodeSymbology codeSymbology,
                          Integer copies,
                          Instant submittedAt, Instant completedAt, String error) {
```

Assign it in the constructor body (after `this.codeSymbology = codeSymbology;`):

```java
        this.copies         = copies;
```

Add the getter (normalised; never null in practice because the column is NOT NULL):

```java
    public int getCopies()           { return copies == null ? 1 : copies; }
```

- [ ] **Step 5: Thread copies through `JpaJobStore`**

In `JpaJobStore.java`:

In `save(...)`, update the `repository.save(new PrintJobEntity(...))` call to pass `r.getCopies()` in the new position (after `codeSymbology`, before `job.getSubmittedAt()`):

```java
        repository.save(new PrintJobEntity(
            job.getJobId(),
            job.getStatus(),
            r.getWristbandType(),
            job.getPrinterId(),
            job.getPrinterName(),
            eventName, firstName, lastName, assocName, barcodeValue,
            permitLabel, iconName,
            r.getStockColorCode(), codeValue, codeSymbology,
            r.getCopies(),
            job.getSubmittedAt(),
            job.getCompletedAt(),
            job.getError()
        ));
```

In `toDomain(...)`, set copies on both rebuilt request types. After `p.setStockColorCode(e.getStockColorCode());` add:

```java
            p.setCopies(e.getCopies());
```

After `w.setStockColorCode(e.getStockColorCode());` add:

```java
            w.setCopies(e.getCopies());
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=JpaJobStoreTest` (Docker daemon required)
Expected: PASS (existing round-trip + new copies round-trip).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V7__add_copies.sql \
        src/main/java/com/stup/wristbandprinter/persistence/PrintJobEntity.java \
        src/main/java/com/stup/wristbandprinter/persistence/JpaJobStore.java \
        src/test/java/com/stup/wristbandprinter/persistence/JpaJobStoreTest.java
git commit -m "feat(persistence): persist copies (Flyway V7)"
```

---

## Task 6: Expose `copies` on the API responses

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/domain/PrintJobResponse.java`
- Modify: `src/main/java/com/stup/wristbandprinter/domain/PrintJobDetailResponse.java`
- Modify: `src/main/java/com/stup/wristbandprinter/domain/PrintJob.java`
- Test: `src/test/java/com/stup/wristbandprinter/domain/PrintJobTest.java`

- [ ] **Step 1: Write the failing test**

In `PrintJobTest.java`, add:

```java
    @Test
    void responses_carryCopies() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("E"); r.setFirstName("F"); r.setLastName("L");
        r.setAssociationName("A"); r.setBarcodeValue("B");
        r.setCopies(25);
        PrintJob job = new PrintJob(UUID.randomUUID(), r);

        assertThat(job.toResponse().copies()).isEqualTo(25);
        assertThat(job.toDetailResponse().copies()).isEqualTo(25);
    }

    @Test
    void responses_defaultCopiesToOne() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("E"); r.setFirstName("F"); r.setLastName("L");
        r.setAssociationName("A"); r.setBarcodeValue("B");
        PrintJob job = new PrintJob(UUID.randomUUID(), r);

        assertThat(job.toResponse().copies()).isEqualTo(1);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PrintJobTest`
Expected: FAIL — `copies()` accessor does not exist (compile error).

- [ ] **Step 3: Add `copies` to `PrintJobResponse`**

In `PrintJobResponse.java`, add `int copies` after `permitLabel`:

```java
public record PrintJobResponse(
    UUID jobId,
    PrintJobStatus status,
    WristbandType wristbandType,
    String printerId,
    String printerName,
    String eventName,
    String firstName,   // null for PERMIT bands
    String lastName,    // null for PERMIT bands
    String permitLabel, // null for CREW bands
    int copies,
    Instant submittedAt,
    Instant completedAt,
    String error
) {}
```

- [ ] **Step 4: Add `copies` to `PrintJobDetailResponse`**

In `PrintJobDetailResponse.java`, add `int copies` after `permitLabel`:

```java
public record PrintJobDetailResponse(
    UUID jobId,
    PrintJobStatus status,
    WristbandType wristbandType,
    String printerId,
    String printerName,
    String eventName,
    String firstName,    // null for PERMIT bands
    String lastName,     // null for PERMIT bands
    String associationName, // null for PERMIT bands
    String barcodeValue,    // null for PERMIT bands
    String permitLabel,     // null for CREW bands
    int copies,
    Instant submittedAt,
    Instant completedAt,
    String error
) {}
```

- [ ] **Step 5: Populate `copies` in `PrintJob`**

In `PrintJob.java`:

In `toResponse()`, update the constructor call to include `request.getCopies()` after `permitLabel`:

```java
        return new PrintJobResponse(
            jobId, status, request.getWristbandType(),
            printerId, printerName,
            eventName, firstName, lastName, permitLabel,
            request.getCopies(),
            submittedAt, completedAt, error);
```

In `toDetailResponse()`, update the constructor call to include `request.getCopies()` after `permitLabel`:

```java
        return new PrintJobDetailResponse(
            jobId, status, request.getWristbandType(),
            printerId, printerName,
            eventName, firstName, lastName,
            assocName, barcodeValue, permitLabel,
            request.getCopies(),
            submittedAt, completedAt, error);
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=PrintJobTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/PrintJobResponse.java \
        src/main/java/com/stup/wristbandprinter/domain/PrintJobDetailResponse.java \
        src/main/java/com/stup/wristbandprinter/domain/PrintJob.java \
        src/test/java/com/stup/wristbandprinter/domain/PrintJobTest.java
git commit -m "feat(api): expose copies on job responses"
```

---

## Task 7: Reprint `copies` override + request validation tests

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/controller/WristbandController.java`
- Test: `src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java`
- Test: `src/test/java/com/stup/wristbandprinter/controller/PermitWristbandControllerTest.java`

- [ ] **Step 1: Write the failing tests**

In `WristbandControllerTest.java`, add a test that reprint forwards a copies override. Match the existing test style (mock `printQueueService.getJob(...)` and `enqueue(...)`). Add imports if missing: `import org.mockito.ArgumentCaptor;` and ensure `com.stup.wristbandprinter.domain.*` is imported.

```java
    @Test
    void reprint_withCopiesParam_overridesCopies() throws Exception {
        UUID jobId = UUID.randomUUID();
        WristbandPrintRequest original = new WristbandPrintRequest();
        original.setEventName("Pukkelpop 2026");
        original.setFirstName("Jan"); original.setLastName("Janssens");
        original.setAssociationName("STUP vzw"); original.setBarcodeValue("123");
        original.setCopies(1);
        PrintJob originalJob = new PrintJob(jobId, original, "printer-1", "Inkom");
        when(printQueueService.getJob(jobId)).thenReturn(java.util.Optional.of(originalJob));

        ArgumentCaptor<PrintableRequest> captor = ArgumentCaptor.forClass(PrintableRequest.class);
        when(printQueueService.enqueue(captor.capture()))
            .thenAnswer(inv -> new PrintJob(UUID.randomUUID(), inv.getArgument(0)));

        mockMvc.perform(post("/api/wristbands/jobs/" + jobId + "/reprint?copies=50")
                .header("X-API-Key", API_KEY))
            .andExpect(status().isAccepted());

        org.assertj.core.api.Assertions.assertThat(captor.getValue().getCopies()).isEqualTo(50);
    }
```

Confirm `WristbandControllerTest` declares `private static final String API_KEY = "test-key";` (the `PermitWristbandControllerTest` does; if `WristbandControllerTest` uses a literal `"test-key"` instead, use that literal here).

In `WristbandControllerTest.java`, add a body-validation test:

```java
    @Test
    void crewPrint_returns400_whenCopiesBelowOne() throws Exception {
        String body = """
            {
              "eventName": "Pukkelpop 2026",
              "firstName": "Jan",
              "lastName": "Janssens",
              "associationName": "STUP vzw",
              "barcodeValue": "123",
              "copies": 0
            }
            """;

        mockMvc.perform(post("/api/wristbands/crew/print")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }
```

In `PermitWristbandControllerTest.java`, add:

```java
    @Test
    void permitPrint_returns400_whenCopiesBelowOne() throws Exception {
        String body = """
            {
              "eventName": "Pukkelpop 2026",
              "permitLabel": "Elektriciteit",
              "copies": 0
            }
            """;

        mockMvc.perform(post("/api/wristbands/permit/print")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=WristbandControllerTest,PermitWristbandControllerTest`
Expected: FAIL — reprint ignores `copies` (captor sees 1, not 50). The `copies:0` validation tests already pass thanks to `@Min(1)` from Task 1 — that's fine; the reprint test is the one driving this task.

- [ ] **Step 3: Implement the reprint override**

In `WristbandController.java`, update the `reprint` handler to accept and apply `copies`:

```java
    @PostMapping("/jobs/{jobId}/reprint")
    @Operation(summary = "Reprint a previous job, optionally on a different printer and/or copy count", tags = {"Jobs"})
    public ResponseEntity<PrintJobResponse> reprint(@PathVariable UUID jobId,
                                                     @RequestParam(required = false) String printerId,
                                                     @RequestParam(required = false) Integer copies) {
        return printQueueService.getJob(jobId)
            .map(original -> {
                PrintableRequest req = original.getRequest();
                if (printerId != null && !printerId.isBlank()) {
                    req = req.withPrinterId(printerId);
                }
                if (copies != null) {
                    req = req.withCopies(copies);
                }
                PrintJob newJob = printQueueService.enqueue(req);
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(newJob.toResponse());
            })
            .orElse(ResponseEntity.notFound().build());
    }
```

(An out-of-range `copies` here is caught by `enqueue`'s validation → `InvalidCopiesException` → 400.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=WristbandControllerTest,PermitWristbandControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/controller/WristbandController.java \
        src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java \
        src/test/java/com/stup/wristbandprinter/controller/PermitWristbandControllerTest.java
git commit -m "feat(api): reprint accepts a copies override"
```

---

## Task 8: Backend regression — full suite green

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite**

Run: `./mvnw test` (Docker daemon required for Testcontainers classes)
Expected: BUILD SUCCESS. If `WristbandIntegrationTest` asserts an exact jobs JSON shape, the new `copies` field is additive (Jackson serializes by name) and should not break it; if any assertion counts fields, update it to expect `copies`.

- [ ] **Step 2: Commit (only if a fixup was needed)**

```bash
git add -A
git commit -m "test: keep suite green after copies field"
```

(Skip if nothing changed.)

---

## Task 9: Front-end — data-driven jobs table (Copies column, drop Completed)

**Files:**
- Modify: `src/main/resources/static/jobs.html`
- Modify: `src/main/resources/static/js/jobs.js`

No automated JS tests exist in this project (vanilla, no build step); verification is manual via the running local cluster.

- [ ] **Step 1: Make the table head dynamic in `jobs.html`**

Replace the static `<thead>…</thead>` block (the `<tr>` with Name…Completed…Actions headers) with an empty, JS-filled head:

```html
        <thead id="jobs-head"></thead>
```

Leave the `<tbody id="jobs-body">` and its loading row unchanged (the `colspan="8"` loading row is harmless — browsers clamp colspan to the actual column count).

- [ ] **Step 2: Define the `COLUMNS` model in `jobs.js`**

At the top of `jobs.js`, after the `const TYPE_LABELS = …;` line, add:

```javascript
// Data-driven table columns. `Actions` is always rendered last and is NOT in this list.
const COLUMNS = [
  { key: 'name',      label: 'Name',      sort: 'firstName',
    cell: j => { const n = ((j.firstName || '') + ' ' + (j.lastName || '')).trim() || j.permitLabel;
                 return n ? esc(n) : '<span class="muted">—</span>'; } },
  { key: 'type',      label: 'Type',      sort: 'wristbandType',
    cell: j => typeBadge(j.wristbandType) },
  { key: 'event',     label: 'Event',     sort: 'eventName',
    cell: j => esc(j.eventName) },
  { key: 'printer',   label: 'Printer',   sort: 'printerName',
    cell: j => esc(j.printerName || '—') },
  { key: 'copies',    label: 'Copies',    sort: 'copies',
    cell: j => (j.copies > 1 ? `<strong>${j.copies}</strong>` : `<span class="muted">${j.copies ?? 1}</span>`) },
  { key: 'status',    label: 'Status',    sort: 'status',
    cell: j => `<span class="badge ${j.status}">${j.status}</span>` },
  { key: 'submitted', label: 'Submitted', sort: 'submittedAt',
    cell: j => `<span title="${fmtDateTime(j.submittedAt)}">${relTime(j.submittedAt)}</span>` },
];

const MAX_COLS = 5;
const MIN_COLS = 1;
const DEFAULT_COLS = ['name', 'type', 'event', 'copies', 'status'];
const ALL_COL_KEYS = COLUMNS.map(c => c.key);
let visibleCols = loadVisibleCols();

function loadVisibleCols() {
  try {
    const raw = JSON.parse(localStorage.getItem('jobs.visibleColumns'));
    if (Array.isArray(raw)) {
      const valid = raw.filter(k => ALL_COL_KEYS.includes(k));
      if (valid.length >= MIN_COLS && valid.length <= MAX_COLS) return valid;
    }
  } catch (e) { /* fall through to default */ }
  return DEFAULT_COLS.slice();
}

function saveVisibleCols() {
  localStorage.setItem('jobs.visibleColumns', JSON.stringify(visibleCols));
}

// COLUMNS in declared order, filtered to the visible set (keeps a stable column order).
function visibleColumnDefs() {
  return COLUMNS.filter(c => visibleCols.includes(c.key));
}

function renderHeader() {
  const cols = visibleColumnDefs();
  document.getElementById('jobs-head').innerHTML =
    '<tr>' + cols.map(c => `<th onclick="sortBy('${c.sort}')">${c.label}</th>`).join('')
    + '<th aria-label="Actions"></th></tr>';
}
```

- [ ] **Step 3: Render header on each render + dynamic empty colspan**

In `render()`, add `renderHeader();` as the first line of the function body (before `renderFilters();`). Then update the empty-state row to use a dynamic colspan. Replace:

```javascript
    tbody.innerHTML = '<tr><td colspan="8" class="empty">No jobs.</td></tr>';
```

with:

```javascript
    tbody.innerHTML = `<tr><td colspan="${visibleCols.length + 1}" class="empty">No jobs.</td></tr>`;
```

- [ ] **Step 4: Rebuild `rowHtml` from the visible columns**

Replace the whole `rowHtml(job)` function with:

```javascript
// Visible data cells (in column order) + the always-present ⋮ actions cell.
// The whole row opens the detail slide-in; the actions cell stops propagation.
function rowHtml(job) {
  const cells = visibleColumnDefs().map(c => `<td>${c.cell(job)}</td>`).join('');
  return `<tr onclick="showDetail('${job.jobId}')">${cells}
    <td class="actions-cell" onclick="event.stopPropagation()">
      <button class="kebab" title="Actions" aria-label="Row actions" onclick="openRowMenu(event, '${job.jobId}')">⋮</button>
    </td>
  </tr>`;
}
```

- [ ] **Step 5: Verify in the browser**

Start the local cluster (or, if already running, just reload):

```bash
docker compose -f docker-compose.local-cluster.yml up --build -d
```

Open `http://localhost:8080/jobs.html` (login `admin` / `local-admin`). Confirm:
- The table shows columns `Name · Type · Event · Copies · Status` + the ⋮ actions cell.
- `Completed` is gone; `Submitted` still shows a relative time with a full timestamp on hover.
- Sorting by clicking a header still works.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/jobs.html src/main/resources/static/js/jobs.js
git commit -m "feat(jobs-ui): data-driven table with Copies column, drop Completed"
```

---

## Task 10: Front-end — column visibility chooser

**Files:**
- Modify: `src/main/resources/static/jobs.html`
- Modify: `src/main/resources/static/js/jobs.js`
- Modify: `src/main/resources/static/css/app.css`

- [ ] **Step 1: Add the chooser button to the controls bar**

In `jobs.html`, inside the `<div class="controls">`, add after the `filter-reset` button:

```html
      <div class="menu-wrap">
        <button class="btn btn-sm" onclick="toggleColumnsMenu(event)">Columns ▾</button>
        <div class="popover nav-menu" id="columns-menu" onclick="event.stopPropagation()" hidden></div>
      </div>
```

(The `onclick="event.stopPropagation()"` on the popover keeps it open while you toggle several checkboxes.)

- [ ] **Step 2: Add the chooser logic to `jobs.js`**

Add these functions (e.g. just below `renderHeader()`):

```javascript
function toggleColumnsMenu(e) {
  e.stopPropagation();
  closeRowMenu();
  closeNavMenu();
  const m = document.getElementById('columns-menu');
  if (m.hidden) { renderColumnsMenu(); m.hidden = false; }
  else { m.hidden = true; }
}

function closeColumnsMenu() {
  const m = document.getElementById('columns-menu');
  if (m && !m.hidden) m.hidden = true;
}

function renderColumnsMenu() {
  const atMax = visibleCols.length >= MAX_COLS;
  const atMin = visibleCols.length <= MIN_COLS;
  document.getElementById('columns-menu').innerHTML = COLUMNS.map(c => {
    const on = visibleCols.includes(c.key);
    const disabled = (!on && atMax) || (on && atMin);
    return `<label class="menu-item col-toggle">
      <input type="checkbox" ${on ? 'checked' : ''} ${disabled ? 'disabled' : ''}
             onchange="toggleColumn('${c.key}')">${c.label}</label>`;
  }).join('');
}

function toggleColumn(key) {
  const on = visibleCols.includes(key);
  if (on) {
    if (visibleCols.length <= MIN_COLS) return;        // keep at least one data column
    visibleCols = visibleCols.filter(k => k !== key);
  } else {
    if (visibleCols.length >= MAX_COLS) return;        // cap at five data columns
    visibleCols.push(key);
  }
  saveVisibleCols();
  renderColumnsMenu();
  render();                                            // render() also calls renderHeader()
}
```

- [ ] **Step 3: Wire the chooser into the existing close handlers**

In `jobs.js`, update the global close handlers so the chooser closes like the other menus.

Replace:

```javascript
document.addEventListener('click', () => { closeRowMenu(); closeNavMenu(); });
```
with:
```javascript
document.addEventListener('click', () => { closeRowMenu(); closeNavMenu(); closeColumnsMenu(); });
```

Replace:
```javascript
window.addEventListener('resize', () => { closeRowMenu(); closeNavMenu(); });
```
with:
```javascript
window.addEventListener('resize', () => { closeRowMenu(); closeNavMenu(); closeColumnsMenu(); });
```

Replace:
```javascript
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') { closeDrawer(); closeRowMenu(); closeNavMenu(); }
});
```
with:
```javascript
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') { closeDrawer(); closeRowMenu(); closeNavMenu(); closeColumnsMenu(); }
});
```

In `openRowMenu(e, jobId)`, after the existing `closeNavMenu();` line add:
```javascript
  closeColumnsMenu();
```

In `toggleNavMenu(e)`, after the existing `closeRowMenu();` line add:
```javascript
  closeColumnsMenu();
```

- [ ] **Step 4: Add minimal styling to `app.css`**

Append to `src/main/resources/static/css/app.css`:

```css
/* Column chooser checkboxes (jobs table) */
.col-toggle { display: flex; align-items: center; gap: 8px; cursor: pointer; }
.col-toggle input[disabled] { opacity: .4; cursor: not-allowed; }
```

- [ ] **Step 5: Verify in the browser**

Reload `http://localhost:8080/jobs.html`. Confirm:
- A `Columns ▾` button sits in the controls bar; clicking it opens a checkbox list.
- Toggling a column shows/hides it immediately; the menu stays open.
- With 5 checked, the unchecked boxes are disabled; with 1 checked, that box is disabled.
- The `Actions` (⋮) column is always present and is not in the list.
- Reload the page — your selection persists (localStorage). Clear it with
  `localStorage.removeItem('jobs.visibleColumns')` to confirm the default set returns.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/jobs.html \
        src/main/resources/static/js/jobs.js \
        src/main/resources/static/css/app.css
git commit -m "feat(jobs-ui): column visibility chooser (max 5 + always-on Actions)"
```

---

## Task 11: Front-end — reprint copies input + detail-drawer Copies row

**Files:**
- Modify: `src/main/resources/static/js/jobs.js`
- Modify: `src/main/resources/static/css/app.css`

- [ ] **Step 1: Add a Copies row to the detail drawer**

In `showDetail(...)`, update `printingRows` to include copies. Replace:

```javascript
  const printingRows = detailRows([
    ['Printer', d.printerName],
    ['Submitted', fmtDateTime(d.submittedAt)],
    ['Completed', d.completedAt ? fmtDateTime(d.completedAt) : '—']
  ]);
```

with:

```javascript
  const printingRows = detailRows([
    ['Printer', d.printerName],
    ['Copies', d.copies],
    ['Submitted', fmtDateTime(d.submittedAt)],
    ['Completed', d.completedAt ? fmtDateTime(d.completedAt) : '—']
  ]);
```

(Both `Submitted` and `Completed` stay in the drawer — only the table dropped `Completed`.)

- [ ] **Step 2: Replace `reprint(...)` and `choosePrinter()` with a copies+printer dialog**

Replace the entire `reprint(id)` function and the entire `choosePrinter()` function with:

```javascript
// Reprint a finished job. Asks for a copy count (defaulting to the original job's copies)
// and, when more than one printer exists, a target printer.
async function reprint(id) {
  const job = jobs[id];
  const sel = await reprintDialog(job && job.copies ? job.copies : 1);
  if (!sel) return;                                  // cancelled
  const params = new URLSearchParams();
  if (sel.printerId) params.set('printerId', sel.printerId);
  if (sel.copies && sel.copies !== 1) params.set('copies', String(sel.copies));
  const qs = params.toString();
  const res = await guarded(fetch('/api/wristbands/jobs/' + id + '/reprint' + (qs ? '?' + qs : ''),
                                  { method: 'POST' }));
  if (!res) return;
  toast(res.ok ? 'Reprint queued' : 'Reprint failed', res.ok ? 'ok' : 'err');
}

// Reprint dialog: a copies number-input plus an optional printer <select>, reusing the
// confirm overlay. Resolves to { copies, printerId } or null (cancel).
function reprintDialog(defaultCopies) {
  return new Promise(resolve => {
    const overlay = document.getElementById('confirm-overlay');
    const card = overlay.querySelector('.confirm-card');
    const prevHtml = card.innerHTML;
    const printerField = (printers && printers.length > 1)
      ? `<label class="reprint-field">Printer
           <select class="select" id="reprint-printer">
             ${printers.map(p => `<option value="${esc(p.id)}">${esc(p.displayName)}</option>`).join('')}
           </select></label>`
      : '';
    card.innerHTML = `
      <div style="font-weight:600;margin-bottom:4px">Reprint</div>
      <label class="reprint-field">Copies
        <input class="input" id="reprint-copies" type="number" min="1" value="${defaultCopies}"></label>
      ${printerField}
      <div class="confirm-actions">
        <button class="btn" id="reprint-cancel">Cancel</button>
        <button class="btn btn-primary" id="reprint-go">Reprint</button>
      </div>`;
    overlay.classList.add('open');
    const done = (val) => { overlay.classList.remove('open'); card.innerHTML = prevHtml; resolve(val); };
    card.querySelector('#reprint-cancel').onclick = () => done(null);
    card.querySelector('#reprint-go').onclick = () => {
      const copies = Math.max(1, parseInt(card.querySelector('#reprint-copies').value, 10) || 1);
      const sel = card.querySelector('#reprint-printer');
      done({ copies, printerId: sel ? sel.value : null });
    };
  });
}
```

(The backend enforces the `print.max-copies` cap; an over-cap entry returns 400 → "Reprint failed" toast.)

- [ ] **Step 3: Style the reprint fields in `app.css`**

Append to `src/main/resources/static/css/app.css`:

```css
/* Reprint dialog fields */
.reprint-field { display: flex; flex-direction: column; gap: 6px; margin: 10px 0; text-align: left; font-size: 13px; }
.reprint-field .input, .reprint-field .select { width: 100%; }
```

- [ ] **Step 4: Verify in the browser**

Reload `http://localhost:8080/jobs.html`. Confirm:
- Open the ⋮ menu on a DONE/FAILED job → `Reprint` opens a dialog with a Copies number field
  (pre-filled with that job's copies) and, with multiple printers configured, a printer select.
- Reprinting with copies = N enqueues a new job; opening its detail drawer shows `Copies: N`.
- The detail drawer of any job shows a `Copies` row.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/js/jobs.js src/main/resources/static/css/app.css
git commit -m "feat(jobs-ui): reprint with copies input + drawer Copies row"
```

---

## Task 12: Docs

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/configuration.md`
- Modify: `HANDOVER.md`

- [ ] **Step 1: Update `CLAUDE.md`**

Under "Important business rules", add a bullet:

```markdown
- **Copies per job:** a request may set `copies` (default 1). The printer prints that
  many physical bands from one job via the Zebra `^PQ` command, which is appended **only
  on the print path** (`PrintQueueService` → `ZplCopies.apply`), never in the shared
  `WristbandZplResolver` (so previews stay a single label). `copies` must be between 1 and
  `print.max-copies` (default 200) or the request is rejected with **400**.
```

In the "Request flow (print)" section, in the step describing `WorkerClient.print`, add a sentence:

```markdown
   Before forwarding, `PrintQueueService` appends `^PQ<copies>` to the resolved ZPL via
   `ZplCopies` when `copies > 1` (the worker and preview paths are unchanged).
```

In the "Jobs admin UI" section, add to the bullet list:

```markdown
- A **Copies** column (replacing the old *Completed* column, which now lives only in the
  detail drawer) and a **Columns ▾** chooser to toggle which data columns are visible
  (max 5 + always-on Actions), remembered per browser in `localStorage`.
```

- [ ] **Step 2: Update `docs/configuration.md`**

Add an entry documenting `print.max-copies` (follow the file's existing format for a config key — name, default, meaning):

```markdown
### `print.max-copies`

Maximum number of copies a single print job may request (Zebra `^PQ`). Default **200**.
A request with `copies` outside `1..max-copies` is rejected with HTTP 400. Raise it if an
event legitimately prints larger batches.
```

- [ ] **Step 3: Update `HANDOVER.md`**

Add a dated section at the end:

```markdown
## 2026-06-12 — Copies per job + jobs-table column chooser

- **Copies per job.** `copies` (default 1) on crew & permit print requests; the printer
  prints N bands from one job via `^PQ`, appended on the print path only
  (`ZplCopies.apply` in `PrintQueueService`), so previews stay single-label. Capped by
  `print.max-copies` (default 200) → 400 when exceeded. Persisted (Flyway `V7`), surfaced
  on `PrintJobResponse`/`PrintJobDetailResponse`, and overridable on reprint
  (`POST /jobs/{id}/reprint?copies=N`).
- **Jobs table.** Now data-driven from a `COLUMNS` array in `jobs.js`. Added a **Copies**
  column; removed **Completed** from the table (kept in the detail drawer). New
  **Columns ▾** chooser toggles visible data columns (max 5 + always-on Actions),
  persisted in `localStorage` under `jobs.visibleColumns`. Reprint now prompts for a copy
  count (and printer when more than one is configured).
- No change to the worker, `PrintForwardRequest`, or the route/forward pipeline.
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/configuration.md HANDOVER.md
git commit -m "docs: document copies-per-job and jobs-table column chooser"
```

---

## Final verification

- [ ] **Backend:** `./mvnw test` → BUILD SUCCESS (Docker daemon running).
- [ ] **End-to-end (local cluster):**
  - `POST /api/wristbands/permit/print` with `"copies": 3` → 202; the job row shows Copies 3.
  - The forwarded ZPL contains `^PQ3,0,0,Y` (check the worker log / `PrinterService` send log).
  - A preview (`/api/wristbands/permit/preview/image`) still renders exactly one band.
  - `copies: 0` and `copies: 999` (above cap) → 400.
  - Column chooser: toggle columns, enforce max 5 / min 1, persists across reload.
  - Reprint dialog: copies pre-filled, override works.
```

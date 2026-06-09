# Permit Wristband – Part 2: Request Model & Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce `PrintableRequest` (sealed interface), update `WristbandPrintRequest` with optional `codeSymbology` and `stockColorCode`, add the stock-color palette to config, run the V6 Flyway migration, and make `PrintJobEntity` / `JpaJobStore` / `PrintJob` / `PrintQueueService` type-aware — all without touching any controller or permit-band renderer yet.

**Architecture:** `PrintableRequest` is a sealed interface permitted by `WristbandPrintRequest` and (in Part 3) `PermitWristbandPrintRequest`. It declares three methods every request must implement: `getPrinterId()`, `getWristbandType()`, `getStockColorCode()`. `PrintJob`'s `request` field changes from `WristbandPrintRequest` to `PrintableRequest`. The V6 migration adds four nullable columns to `print_jobs` (`wristband_type`, `permit_label`, `icon_name`, `stock_color_code`) and backfills `wristband_type = 'CREW'` on existing rows. `JpaJobStore` branches on the discriminator to reconstruct the right domain object.

**Tech Stack:** Java 21, Spring Boot 3.4.1, PostgreSQL + Flyway V6, JUnit 5, Testcontainers.

**Prerequisite:** Part 1 must be applied first (provides `WristbandType`, `CodeSymbology`, `ScanCodeRenderer`).

---

## File map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/…/domain/PrintableRequest.java` | Sealed interface for all wristband request types |
| Modify | `src/main/java/…/domain/WristbandPrintRequest.java` | implements PrintableRequest; add `codeSymbology`, `stockColorCode` |
| Modify | `src/main/java/…/config/WristbandProperties.java` | Add `Map<Integer, String> stockColors` |
| Create | `src/main/java/…/exception/InvalidStockColorException.java` | 400 when stockColorCode not in palette |
| Modify | `src/main/java/…/exception/GlobalExceptionHandler.java` | Handle `InvalidStockColorException` → 400 |
| Modify | `src/main/resources/application.yml` | Add `wristband.stock-colors` palette |
| Create | `src/main/resources/db/migration/V6__permit_wristband.sql` | Add discriminator + nullable permit columns |
| Modify | `src/main/java/…/persistence/PrintJobEntity.java` | Add `wristbandType`, `permitLabel`, `iconName`, `stockColorCode` |
| Modify | `src/main/java/…/persistence/JpaJobStore.java` | Type-aware `save()` and `toDomain()` |
| Modify | `src/main/java/…/domain/PrintJob.java` | `request` field → `PrintableRequest`; update constructors, `toResponse()`, `toDetailResponse()` |
| Modify | `src/main/java/…/domain/PrintJobResponse.java` | Add `wristbandType`; firstName / lastName nullable |
| Modify | `src/main/java/…/domain/PrintJobDetailResponse.java` | Add `wristbandType`, `permitLabel`; several fields nullable |
| Modify | `src/main/java/…/service/PrintQueueService.java` | `enqueue()` takes `PrintableRequest`; remove `layoutService` dep |
| Modify | `src/test/java/…/persistence/JpaJobStoreTest.java` | Cover CREW round-trip + new columns |
| Modify | `src/test/java/…/service/PrintQueueServiceTest.java` | Update constructor call (no layoutService) |
| Modify | `src/test/java/…/controller/WristbandControllerTest.java` | Update `PrintJob` construction + resolver mock sig |

---

## Task 5: PrintableRequest sealed interface + WristbandPrintRequest update

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/domain/PrintableRequest.java`
- Modify: `src/main/java/com/stup/wristbandprinter/domain/WristbandPrintRequest.java`

- [ ] **Step 1: Create PrintableRequest**

```java
// src/main/java/com/stup/wristbandprinter/domain/PrintableRequest.java
package com.stup.wristbandprinter.domain;

/**
 * Sealed interface implemented by every wristband print request type.
 * The two permitted subtypes are {@link WristbandPrintRequest} (crew bands)
 * and {@code PermitWristbandPrintRequest} (permit bands, added in Part 3).
 */
public sealed interface PrintableRequest
        permits WristbandPrintRequest, com.stup.wristbandprinter.domain.PermitWristbandPrintRequest {

    /** Optional printer id; null/blank → default printer. */
    String getPrinterId();

    /** Discriminator used for persistence and routing. */
    WristbandType getWristbandType();

    /** Optional stock-color code (1 = white default). Null means use default. */
    Integer getStockColorCode();

    /**
     * Returns a copy of this request with the printerId replaced.
     * Used by the reprint endpoint to re-target a job to a different printer.
     */
    PrintableRequest withPrinterId(String printerId);
}
```

Note: the `permits` clause references `PermitWristbandPrintRequest` which does not exist yet. The compiler requires all permitted subtypes to be present **at compile time**. Add a temporary stub class so this file compiles now; it will be replaced in Part 3:

```java
// src/main/java/com/stup/wristbandprinter/domain/PermitWristbandPrintRequest.java
// TEMPORARY STUB — replaced in Part 3
package com.stup.wristbandprinter.domain;

public final class PermitWristbandPrintRequest implements PrintableRequest {
    @Override public String getPrinterId()        { return null; }
    @Override public WristbandType getWristbandType() { return WristbandType.PERMIT; }
    @Override public Integer getStockColorCode()  { return null; }
    @Override public PrintableRequest withPrinterId(String id) { return this; }
}
```

- [ ] **Step 2: Update WristbandPrintRequest**

Replace the full file:

```java
// src/main/java/com/stup/wristbandprinter/domain/WristbandPrintRequest.java
package com.stup.wristbandprinter.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Data required to print or preview a crew wristband")
public class WristbandPrintRequest implements PrintableRequest {

    @NotBlank(message = "eventName must not be blank")
    @Schema(example = "Pukkelpop 2026")
    private String eventName;

    @NotBlank(message = "firstName must not be blank")
    @Schema(example = "Annechien")
    private String firstName;

    @NotBlank(message = "lastName must not be blank")
    @Schema(example = "Van De Wall")
    private String lastName;

    @NotBlank(message = "associationName must not be blank")
    @Schema(example = "Chiro Sint-Christina Brustem")
    private String associationName;

    @NotBlank(message = "barcodeValue must not be blank")
    @Schema(example = "12345654245524789")
    private String barcodeValue;

    @Schema(description = "Optional template id; when set the wristband is rendered from that template")
    private java.util.UUID templateId;

    @Schema(description = "Optional scan-code symbology; defaults to CODE128 when omitted")
    private CodeSymbology codeSymbology;

    @Schema(description = "Optional stock-color code (1 = white). Configured palette in wristband.stock-colors")
    private Integer stockColorCode;

    @Schema(description = "Optional id of the printer to use; when omitted the default printer is used")
    private String printerId;

    // ── PrintableRequest ──────────────────────────────────────────────────

    @Override
    public WristbandType getWristbandType() { return WristbandType.CREW; }

    @Override
    public PrintableRequest withPrinterId(String newPrinterId) {
        WristbandPrintRequest copy = new WristbandPrintRequest();
        copy.setEventName(this.eventName);
        copy.setFirstName(this.firstName);
        copy.setLastName(this.lastName);
        copy.setAssociationName(this.associationName);
        copy.setBarcodeValue(this.barcodeValue);
        copy.setTemplateId(this.templateId);
        copy.setCodeSymbology(this.codeSymbology);
        copy.setStockColorCode(this.stockColorCode);
        copy.setPrinterId(newPrinterId);
        return copy;
    }

    // ── getters / setters ─────────────────────────────────────────────────

    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getAssociationName() { return associationName; }
    public void setAssociationName(String associationName) { this.associationName = associationName; }

    public String getBarcodeValue() { return barcodeValue; }
    public void setBarcodeValue(String barcodeValue) { this.barcodeValue = barcodeValue; }

    public java.util.UUID getTemplateId() { return templateId; }
    public void setTemplateId(java.util.UUID templateId) { this.templateId = templateId; }

    public CodeSymbology getCodeSymbology() { return codeSymbology; }
    public void setCodeSymbology(CodeSymbology codeSymbology) { this.codeSymbology = codeSymbology; }

    @Override
    public Integer getStockColorCode() { return stockColorCode; }
    public void setStockColorCode(Integer stockColorCode) { this.stockColorCode = stockColorCode; }

    @Override
    public String getPrinterId() { return printerId; }
    public void setPrinterId(String printerId) { this.printerId = printerId; }
}
```

- [ ] **Step 3: Verify compilation**

```bash
./mvnw compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Run the full test suite**

```bash
./mvnw test -q
```
Expected: all green (existing tests use `WristbandPrintRequest` constructors + getters that are unchanged).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/PrintableRequest.java \
        src/main/java/com/stup/wristbandprinter/domain/PermitWristbandPrintRequest.java \
        src/main/java/com/stup/wristbandprinter/domain/WristbandPrintRequest.java
git commit -m "$(cat <<'EOF'
feat: add PrintableRequest sealed interface; update WristbandPrintRequest

Adds codeSymbology (optional, defaults to CODE128) and stockColorCode
(optional, preview-only). Temporary PermitWristbandPrintRequest stub
satisfies the sealed-interface permits clause; replaced in Part 3.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Stock-colors config + InvalidStockColorException

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/config/WristbandProperties.java`
- Create: `src/main/java/com/stup/wristbandprinter/exception/InvalidStockColorException.java`
- Modify: `src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add stockColors map to WristbandProperties**

Add the field and accessors to `WristbandProperties.java`. Insert after the `barcode` field:

```java
// add this import at the top of WristbandProperties.java:
import java.util.LinkedHashMap;
import java.util.Map;

// add this field inside the class body (after `private Barcode barcode = new Barcode();`):
private Map<Integer, String> stockColors = new LinkedHashMap<>();
```

Add getter + setter:
```java
public Map<Integer, String> getStockColors() { return stockColors; }
public void setStockColors(Map<Integer, String> stockColors) { this.stockColors = stockColors; }
```

- [ ] **Step 2: Create InvalidStockColorException**

```java
// src/main/java/com/stup/wristbandprinter/exception/InvalidStockColorException.java
package com.stup.wristbandprinter.exception;

public class InvalidStockColorException extends RuntimeException {
    public InvalidStockColorException(String message) {
        super(message);
    }
}
```

- [ ] **Step 3: Register handler in GlobalExceptionHandler**

Add this method to `GlobalExceptionHandler.java` (alongside the other `@ExceptionHandler` methods):

```java
@ExceptionHandler(InvalidStockColorException.class)
public ResponseEntity<Map<String, Object>> handleInvalidStockColor(InvalidStockColorException ex) {
    log.warn("Invalid stock color: {}", ex.getMessage());
    return errorResponse(HttpStatus.BAD_REQUEST, "Invalid stock color", ex.getMessage());
}
```

- [ ] **Step 4: Add palette to application.yml**

Add this block inside the existing `wristband:` section, after `barcode:`:

```yaml
  stock-colors:
    1: "#FFFFFF"   # white (default)
    2: "#800080"   # purple
    3: "#FFFF00"   # yellow
    4: "#0000FF"   # blue
    5: "#008000"   # green
    6: "#FF0000"   # red
```

- [ ] **Step 5: Verify compilation and run tests**

```bash
./mvnw test -q
```
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/config/WristbandProperties.java \
        src/main/java/com/stup/wristbandprinter/exception/InvalidStockColorException.java \
        src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java \
        src/main/resources/application.yml
git commit -m "$(cat <<'EOF'
feat: add stock-color palette config and InvalidStockColorException

wristband.stock-colors maps integer codes 1–6 to hex values.
Preview endpoints resolve the code to hex then pass it to
PreviewColorService (wired in Part 4).

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: V6 Flyway migration

**Files:**
- Create: `src/main/resources/db/migration/V6__permit_wristband.sql`

- [ ] **Step 1: Create the migration**

```sql
-- src/main/resources/db/migration/V6__permit_wristband.sql

-- Discriminator: backfill CREW for all existing rows; new permit rows get PERMIT.
ALTER TABLE print_jobs
    ADD COLUMN wristband_type VARCHAR(10) NOT NULL DEFAULT 'CREW';

-- Permit-specific fields (all nullable; absent for CREW jobs).
ALTER TABLE print_jobs
    ADD COLUMN permit_label  VARCHAR(255),
    ADD COLUMN icon_name     VARCHAR(255),
    ADD COLUMN stock_color_code INTEGER,
    ADD COLUMN code_value    VARCHAR(500),
    ADD COLUMN code_symbology VARCHAR(10);

-- first_name, last_name, barcode_value are NOT NULL for crew but absent for permit.
ALTER TABLE print_jobs
    ALTER COLUMN first_name   DROP NOT NULL,
    ALTER COLUMN last_name    DROP NOT NULL,
    ALTER COLUMN barcode_value DROP NOT NULL;
```

- [ ] **Step 2: Run tests — Testcontainers applies migrations automatically**

```bash
./mvnw test -q
```
Expected: all green; Flyway applies V6 against the Testcontainers Postgres and schema validation passes.

If you see `Validate failed: Migrations have failed validation`, check for typos in the SQL. Common cause: a column that already exists in an earlier migration.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V6__permit_wristband.sql
git commit -m "$(cat <<'EOF'
feat: V6 migration — add wristband_type discriminator and permit columns

Backfills wristband_type='CREW' for all existing rows.
first_name, last_name, barcode_value become nullable to accommodate
permit jobs that don't carry personal details.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: PrintJobEntity + JpaJobStore type-awareness

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/persistence/PrintJobEntity.java`
- Modify: `src/main/java/com/stup/wristbandprinter/persistence/JpaJobStore.java`
- Modify: `src/test/java/com/stup/wristbandprinter/persistence/JpaJobStoreTest.java`

- [ ] **Step 1: Update PrintJobEntity — add new columns**

Replace the entire file:

```java
package com.stup.wristbandprinter.persistence;

import com.stup.wristbandprinter.domain.PrintJobStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "print_jobs")
public class PrintJobEntity {

    @Id
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    private PrintJobStatus status;

    private String printerId;
    private String printerName;

    /** Discriminator: 'CREW' or 'PERMIT'. */
    private String wristbandType;

    // ── CREW fields ───────────────────────────────────────────────────────
    private String eventName;
    private String firstName;
    private String lastName;
    private String associationName;
    private String barcodeValue;

    // ── PERMIT fields ─────────────────────────────────────────────────────
    private String permitLabel;
    private String iconName;

    // ── shared optional fields ────────────────────────────────────────────
    private Integer stockColorCode;
    private String  codeValue;
    private String  codeSymbology;

    private Instant submittedAt;
    private Instant completedAt;

    @Column(length = 2000)
    private String error;

    private boolean deleted;

    protected PrintJobEntity() {}

    // Full constructor used by JpaJobStore.save()
    public PrintJobEntity(UUID jobId, PrintJobStatus status, String printerId, String printerName,
                          String wristbandType,
                          String eventName, String firstName, String lastName,
                          String associationName, String barcodeValue,
                          String permitLabel, String iconName,
                          Integer stockColorCode, String codeValue, String codeSymbology,
                          Instant submittedAt, Instant completedAt, String error) {
        this.jobId          = jobId;
        this.status         = status;
        this.printerId      = printerId;
        this.printerName    = printerName;
        this.wristbandType  = wristbandType;
        this.eventName      = eventName;
        this.firstName      = firstName;
        this.lastName       = lastName;
        this.associationName= associationName;
        this.barcodeValue   = barcodeValue;
        this.permitLabel    = permitLabel;
        this.iconName       = iconName;
        this.stockColorCode = stockColorCode;
        this.codeValue      = codeValue;
        this.codeSymbology  = codeSymbology;
        this.submittedAt    = submittedAt;
        this.completedAt    = completedAt;
        this.error          = error;
    }

    public UUID   getJobId()          { return jobId; }
    public PrintJobStatus getStatus() { return status; }
    public String getPrinterId()      { return printerId; }
    public String getPrinterName()    { return printerName; }
    public String getWristbandType()  { return wristbandType; }
    public String getEventName()      { return eventName; }
    public String getFirstName()      { return firstName; }
    public String getLastName()       { return lastName; }
    public String getAssociationName(){ return associationName; }
    public String getBarcodeValue()   { return barcodeValue; }
    public String getPermitLabel()    { return permitLabel; }
    public String getIconName()       { return iconName; }
    public Integer getStockColorCode(){ return stockColorCode; }
    public String getCodeValue()      { return codeValue; }
    public String getCodeSymbology()  { return codeSymbology; }
    public Instant getSubmittedAt()   { return submittedAt; }
    public Instant getCompletedAt()   { return completedAt; }
    public String getError()          { return error; }
    public boolean isDeleted()        { return deleted; }
}
```

- [ ] **Step 2: Update JpaJobStore — type-aware save and toDomain**

Replace the full file:

```java
package com.stup.wristbandprinter.persistence;

import com.stup.wristbandprinter.domain.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Profile("!worker")
@Component
public class JpaJobStore implements JobStore {

    private final PrintJobRepository repository;

    public JpaJobStore(PrintJobRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(PrintJob job) {
        PrintableRequest r = job.getRequest();
        String type = r.getWristbandType().name();

        String eventName = null, firstName = null, lastName = null,
               assocName = null, barcodeValue = null,
               permitLabel = null, iconName = null,
               codeValue = null, codeSymbology = null;

        if (r instanceof WristbandPrintRequest crew) {
            eventName    = crew.getEventName();
            firstName    = crew.getFirstName();
            lastName     = crew.getLastName();
            assocName    = crew.getAssociationName();
            barcodeValue = crew.getBarcodeValue();
            codeSymbology = crew.getCodeSymbology() != null
                ? crew.getCodeSymbology().name() : null;
        } else if (r instanceof PermitWristbandPrintRequest permit) {
            eventName   = permit.getEventName();
            permitLabel = permit.getPermitLabel();
            iconName    = permit.getIconName();
            codeValue   = permit.getCodeValue();
            codeSymbology = permit.getCodeSymbology() != null
                ? permit.getCodeSymbology().name() : null;
        }

        repository.save(new PrintJobEntity(
            job.getJobId(), job.getStatus(), job.getPrinterId(), job.getPrinterName(),
            type,
            eventName, firstName, lastName, assocName, barcodeValue,
            permitLabel, iconName,
            r.getStockColorCode(), codeValue, codeSymbology,
            job.getSubmittedAt(), job.getCompletedAt(), job.getError()
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrintJob> loadActive() {
        return repository.findByDeletedFalse().stream().map(JpaJobStore::toDomain).toList();
    }

    @Override
    @Transactional
    public void deleteById(UUID jobId) {
        repository.deleteById(jobId);
    }

    @Override
    @Transactional
    public void softDeleteCompleted() {
        repository.softDeleteByStatusIn(
            List.of(PrintJobStatus.DONE, PrintJobStatus.FAILED, PrintJobStatus.CANCELLED));
    }

    private static PrintJob toDomain(PrintJobEntity e) {
        PrintableRequest request;

        if ("PERMIT".equals(e.getWristbandType())) {
            PermitWristbandPrintRequest permit = new PermitWristbandPrintRequest();
            permit.setEventName(e.getEventName());
            permit.setPermitLabel(e.getPermitLabel());
            permit.setIconName(e.getIconName());
            permit.setCodeValue(e.getCodeValue());
            if (e.getCodeSymbology() != null) {
                permit.setCodeSymbology(CodeSymbology.valueOf(e.getCodeSymbology()));
            }
            permit.setStockColorCode(e.getStockColorCode());
            request = permit;
        } else {
            WristbandPrintRequest crew = new WristbandPrintRequest();
            crew.setEventName(e.getEventName());
            crew.setFirstName(e.getFirstName());
            crew.setLastName(e.getLastName());
            crew.setAssociationName(e.getAssociationName());
            crew.setBarcodeValue(e.getBarcodeValue());
            if (e.getCodeSymbology() != null) {
                crew.setCodeSymbology(CodeSymbology.valueOf(e.getCodeSymbology()));
            }
            crew.setStockColorCode(e.getStockColorCode());
            request = crew;
        }

        return PrintJob.restore(
            e.getJobId(), request,
            e.getPrinterId(), e.getPrinterName(),
            e.getStatus(), e.getSubmittedAt(), e.getCompletedAt(), e.getError()
        );
    }
}
```

Note: `PermitWristbandPrintRequest` methods `getPermitLabel()`, `getIconName()`, `getCodeValue()`, `setPermitLabel()` etc. are on the stub class from Task 5. The stub is replaced in Part 3 with the real implementation — the JpaJobStore code above already calls the correct methods and will work with the real class.

- [ ] **Step 3: Add a CREW round-trip test to JpaJobStoreTest**

Open `JpaJobStoreTest.java` and add:

```java
@Test
void save_andLoadActive_roundTripsCrewRequest() {
    WristbandPrintRequest req = new WristbandPrintRequest();
    req.setEventName("Pukkelpop 2026");
    req.setFirstName("Jan");
    req.setLastName("Janssens");
    req.setAssociationName("STUP vzw");
    req.setBarcodeValue("123456789");
    req.setCodeSymbology(CodeSymbology.CODE39);
    req.setStockColorCode(2);

    PrintJob job = new PrintJob(UUID.randomUUID(), req, "p1", "Printer 1");
    store.save(job);

    List<PrintJob> loaded = store.loadActive();
    assertThat(loaded).hasSize(1);
    WristbandPrintRequest loaded_req = (WristbandPrintRequest) loaded.get(0).getRequest();
    assertThat(loaded_req.getCodeSymbology()).isEqualTo(CodeSymbology.CODE39);
    assertThat(loaded_req.getStockColorCode()).isEqualTo(2);
    assertThat(loaded_req.getWristbandType()).isEqualTo(WristbandType.CREW);
}
```

Add required imports:
```java
import com.stup.wristbandprinter.domain.CodeSymbology;
import com.stup.wristbandprinter.domain.WristbandType;
```

- [ ] **Step 4: Run persistence tests**

```bash
./mvnw test -Dtest=JpaJobStoreTest -q
```
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/persistence/PrintJobEntity.java \
        src/main/java/com/stup/wristbandprinter/persistence/JpaJobStore.java \
        src/test/java/com/stup/wristbandprinter/persistence/JpaJobStoreTest.java
git commit -m "$(cat <<'EOF'
feat: make PrintJobEntity and JpaJobStore type-aware (CREW/PERMIT)

save() branches on WristbandType to store the correct columns.
toDomain() reads the discriminator to reconstruct the right request type.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: PrintJob + response DTOs + PrintQueueService generalization

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/domain/PrintJob.java`
- Modify: `src/main/java/com/stup/wristbandprinter/domain/PrintJobResponse.java`
- Modify: `src/main/java/com/stup/wristbandprinter/domain/PrintJobDetailResponse.java`
- Modify: `src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java`
- Modify: `src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java`
- Modify: `src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java`

- [ ] **Step 1: Update PrintJob — request field becomes PrintableRequest**

Replace the entire file:

```java
package com.stup.wristbandprinter.domain;

import java.time.Instant;
import java.util.UUID;

public class PrintJob {

    private final UUID jobId;
    private final PrintableRequest request;
    private final String printerId;
    private final String printerName;
    private PrintJobStatus status;
    private final Instant submittedAt;
    private Instant completedAt;
    private String error;

    public PrintJob(UUID jobId, WristbandPrintRequest request) {
        this(jobId, (PrintableRequest) request, null, null);
    }

    public PrintJob(UUID jobId, WristbandPrintRequest request, String printerId, String printerName) {
        this(jobId, (PrintableRequest) request, printerId, printerName);
    }

    public PrintJob(UUID jobId, PrintableRequest request, String printerId, String printerName) {
        this.jobId       = jobId;
        this.request     = request;
        this.printerId   = printerId;
        this.printerName = printerName;
        this.status      = PrintJobStatus.PENDING;
        this.submittedAt = Instant.now();
    }

    private PrintJob(UUID jobId, PrintableRequest request, String printerId, String printerName,
                     PrintJobStatus status, Instant submittedAt, Instant completedAt, String error) {
        this.jobId       = jobId;
        this.request     = request;
        this.printerId   = printerId;
        this.printerName = printerName;
        this.status      = status;
        this.submittedAt = submittedAt;
        this.completedAt = completedAt;
        this.error       = error;
    }

    public static PrintJob restore(UUID jobId, PrintableRequest request, PrintJobStatus status,
                                   Instant submittedAt, Instant completedAt, String error) {
        return restore(jobId, request, null, null, status, submittedAt, completedAt, error);
    }

    public static PrintJob restore(UUID jobId, PrintableRequest request, String printerId,
                                   String printerName, PrintJobStatus status, Instant submittedAt,
                                   Instant completedAt, String error) {
        return new PrintJob(jobId, request, printerId, printerName, status, submittedAt, completedAt, error);
    }

    public UUID           getJobId()     { return jobId; }
    public PrintableRequest getRequest() { return request; }
    public String         getPrinterId() { return printerId; }
    public String         getPrinterName() { return printerName; }

    public synchronized PrintJobStatus  getStatus()                          { return status; }
    public synchronized void            setStatus(PrintJobStatus status)     { this.status = status; }
    public Instant                      getSubmittedAt()                     { return submittedAt; }
    public synchronized Instant         getCompletedAt()                     { return completedAt; }
    public synchronized void            setCompletedAt(Instant completedAt)  { this.completedAt = completedAt; }
    public synchronized String          getError()                           { return error; }
    public synchronized void            setError(String error)               { this.error = error; }

    public synchronized void complete(PrintJobStatus status, String error, Instant completedAt) {
        this.status      = status;
        this.error       = error;
        this.completedAt = completedAt;
    }

    public synchronized PrintJobResponse toResponse() {
        String firstName = null, lastName = null, eventName = null, permitLabel = null;
        if (request instanceof WristbandPrintRequest crew) {
            firstName = crew.getFirstName();
            lastName  = crew.getLastName();
            eventName = crew.getEventName();
        } else if (request instanceof PermitWristbandPrintRequest permit) {
            eventName   = permit.getEventName();
            permitLabel = permit.getPermitLabel();
        }
        return new PrintJobResponse(
            jobId, status, printerId, printerName,
            request.getWristbandType(),
            eventName, firstName, lastName, permitLabel,
            submittedAt, completedAt, error);
    }

    public synchronized PrintJobDetailResponse toDetailResponse() {
        String firstName = null, lastName = null, eventName = null,
               associationName = null, barcodeValue = null, permitLabel = null;
        if (request instanceof WristbandPrintRequest crew) {
            firstName       = crew.getFirstName();
            lastName        = crew.getLastName();
            eventName       = crew.getEventName();
            associationName = crew.getAssociationName();
            barcodeValue    = crew.getBarcodeValue();
        } else if (request instanceof PermitWristbandPrintRequest permit) {
            eventName   = permit.getEventName();
            permitLabel = permit.getPermitLabel();
        }
        return new PrintJobDetailResponse(
            jobId, status, printerId, printerName,
            request.getWristbandType(),
            eventName, firstName, lastName, associationName, barcodeValue,
            permitLabel,
            submittedAt, completedAt, error);
    }
}
```

- [ ] **Step 2: Update PrintJobResponse — add wristbandType + nullable fields**

```java
// src/main/java/com/stup/wristbandprinter/domain/PrintJobResponse.java
package com.stup.wristbandprinter.domain;

import java.time.Instant;
import java.util.UUID;

public record PrintJobResponse(
    UUID jobId,
    PrintJobStatus status,
    String printerId,
    String printerName,
    WristbandType wristbandType,
    String eventName,
    String firstName,       // null for permit jobs
    String lastName,        // null for permit jobs
    String permitLabel,     // null for crew jobs
    Instant submittedAt,
    Instant completedAt,
    String error
) {}
```

- [ ] **Step 3: Update PrintJobDetailResponse — add wristbandType + permitLabel**

```java
// src/main/java/com/stup/wristbandprinter/domain/PrintJobDetailResponse.java
package com.stup.wristbandprinter.domain;

import java.time.Instant;
import java.util.UUID;

public record PrintJobDetailResponse(
    UUID jobId,
    PrintJobStatus status,
    String printerId,
    String printerName,
    WristbandType wristbandType,
    String eventName,
    String firstName,        // null for permit jobs
    String lastName,         // null for permit jobs
    String associationName,  // null for permit jobs
    String barcodeValue,     // null for permit jobs
    String permitLabel,      // null for crew jobs
    Instant submittedAt,
    Instant completedAt,
    String error
) {}
```

- [ ] **Step 4: Update PrintQueueService — enqueue takes PrintableRequest; remove layoutService**

Changes required:
1. Remove `WristbandLayoutService layoutService` field + constructor parameter.
2. Change `enqueue(WristbandPrintRequest request)` → `enqueue(PrintableRequest request)`.
3. Simplify `processQueue()`: remove the `layoutService.buildData()` call (resolver now handles this — see Part 3).
4. Update `queueFull()` private method (uses `request.getEventName()` which exists on both types via the concrete instances — keep using instanceof or use `toString()`).
5. Remove `WristbandLayoutService` import.

Updated constructor signature:
```java
public PrintQueueService(WristbandZplResolver wristbandZplResolver,
                          PrinterRegistry printerRegistry,
                          WorkerClient workerClient,
                          QueueProperties queueProperties,
                          JobStore jobStore,
                          MeterRegistry meterRegistry) {
```

Updated `enqueue()`:
```java
public PrintJob enqueue(PrintableRequest request) {
    Printer printer = (request.getPrinterId() == null || request.getPrinterId().isBlank())
        ? printerRegistry.getDefault()
        : printerRegistry.get(request.getPrinterId());

    java.util.concurrent.BlockingQueue<PrintJob> q = queueFor(printer.id());
    if (q.size() >= queueProperties.getMaxDepth()) {
        throw new QueueFullException(
            "Print queue is full (" + queueProperties.getMaxDepth()
                + " jobs pending). Please retry shortly.");
    }

    PrintJob job = new PrintJob(UUID.randomUUID(), request, printer.id(), printer.displayName());
    jobStore.save(job);
    jobs.put(job.getJobId(), job);

    if (!q.offer(job)) {
        jobs.remove(job.getJobId());
        jobStore.deleteById(job.getJobId());
        throw new QueueFullException(
            "Print queue is full (" + queueProperties.getMaxDepth()
                + " jobs pending). Please retry shortly.");
    }

    submittedCounter.increment();
    broadcastUpdate(job);
    return job;
}
```

Updated `processQueue()` — remove `layoutService.buildData()`:
```java
// Before (inside processQueue):
WristbandData data = layoutService.buildData(job.getRequest());
String zpl = wristbandZplResolver.resolve(job.getRequest(), data);

// After (resolver handles layout internally — see Part 3 Task 10):
String zpl = wristbandZplResolver.resolve(job.getRequest());
```

Note: `wristbandZplResolver.resolve(PrintableRequest)` single-arg signature is introduced in Part 3 Task 10. Until then, keep a temporary two-arg call or leave as-is and know it will fail to compile until Part 3 lands. If you want this to compile now, keep the two-arg call and update in Part 3.

**Recommended approach:** Make the signature change to `resolve(PrintableRequest)` now (as a compile error marker), then fix the test in Part 3. This keeps the diff clean and the intentions obvious.

- [ ] **Step 5: Fix PrintQueueServiceTest — update constructor call**

Find the `new PrintQueueService(...)` call in `PrintQueueServiceTest.java` and remove `layoutService` from the arguments. Also update any mock for `WristbandLayoutService` in that test class:

```java
// Remove this line from the test class:
@MockitoBean WristbandLayoutService layoutService; // or @Mock WristbandLayoutService layoutService;

// Update the PrintQueueService constructor call — remove layoutService argument:
// Before:
service = new PrintQueueService(layoutService, resolver, registry, worker, queue, store, meters);
// After:
service = new PrintQueueService(resolver, registry, worker, queue, store, meters);
```

- [ ] **Step 6: Fix WristbandControllerTest — update PrintJob construction**

`WristbandControllerTest` creates `new PrintJob(jobId, sampleRequest())` and calls `wristbandLayoutService.buildData(any())` / `wristbandZplResolver.resolve(any(), any())` in several tests. Since `PrintJob(UUID, WristbandPrintRequest)` convenience constructor is kept in Step 1, construction calls still compile.

The `@MockitoBean WristbandLayoutService wristbandLayoutService` line needs to stay for now (the controller still injects it at this stage — see Part 4 for controller changes). No changes needed in WristbandControllerTest at this point.

- [ ] **Step 7: Run full suite**

```bash
./mvnw test -q
```
Expected: all green. (If `wristbandZplResolver.resolve(job.getRequest())` causes a compile error because the single-arg method doesn't exist yet, temporarily revert to the two-arg form and mark with a `// TODO Part 3` comment.)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/PrintJob.java \
        src/main/java/com/stup/wristbandprinter/domain/PrintJobResponse.java \
        src/main/java/com/stup/wristbandprinter/domain/PrintJobDetailResponse.java \
        src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java \
        src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java
git commit -m "$(cat <<'EOF'
feat: generalize PrintJob / DTOs / PrintQueueService to PrintableRequest

PrintJob.request is now PrintableRequest. toResponse/toDetailResponse
branch on WristbandType. enqueue() accepts PrintableRequest.
layoutService dependency removed from PrintQueueService.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Self-review

**Spec coverage check:**
- ✅ `PrintableRequest` sealed interface with `getPrinterId()`, `getWristbandType()`, `getStockColorCode()`, `withPrinterId()` — Task 5
- ✅ `WristbandPrintRequest` gains `codeSymbology` (optional), `stockColorCode` (optional), implements `PrintableRequest` — Task 5
- ✅ Stock-color palette in `WristbandProperties.stockColors` — Task 6
- ✅ `InvalidStockColorException` → 400 via `GlobalExceptionHandler` — Task 6
- ✅ `application.yml` palette: 1=white, 2=purple, 3=yellow, 4=blue, 5=green, 6=red — Task 6
- ✅ V6 migration adds discriminator + permit columns; makes first_name/last_name/barcode_value nullable — Task 7
- ✅ `PrintJobEntity` holds new columns — Task 8
- ✅ `JpaJobStore` branches on discriminator in `save()` and `toDomain()` — Task 8
- ✅ `PrintJob.request` typed as `PrintableRequest` — Task 9
- ✅ `PrintJobResponse` / `PrintJobDetailResponse` gain `wristbandType` + `permitLabel` — Task 9
- ✅ `PrintQueueService.enqueue()` takes `PrintableRequest` — Task 9

**Gaps / follow-ons:** `WristbandZplResolver.resolve(PrintableRequest)` single-arg form + the controller URL restructuring land in Parts 3 and 4 respectively.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-09-permit-wristband-part-2-request-persistence.md`.

**Two execution options:**

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks.

**2. Inline Execution** — execute tasks in this session using executing-plans.

Which approach?

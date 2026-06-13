# Dynamic Printer Registry — Part 1: Table + Normalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give printers their own `printers` table, make `print_jobs` reference it by FK, and drop the duplicated `printer_name` column — resolving each job's printer name from the table instead.

**Architecture:** A Flyway V9 migration creates the full `printers` table (per the spec — extra columns `hidden`/`is_default` are created now but unused until Parts 2–3), backfills it from the printers already referenced by historical jobs, adds the FK, and drops `print_jobs.printer_name`. `PrinterRegistry` keeps its config-driven in-memory routing **unchanged**, but additionally **seeds** the table from `cluster.printers` on startup. `JpaJobStore` stops persisting the printer name and instead resolves it from the `printers` table when loading jobs. No change to the print/route/worker pipeline or to any HTTP response shape.

**Tech Stack:** Java 21, Spring Boot 3.4.1 (data-jpa), PostgreSQL + Flyway, JUnit 5 + Testcontainers, Mockito.

**Spec:** `docs/superpowers/specs/2026-06-13-dynamic-printer-registry-design.md` (this is Phase 1 of 3).

---

## File Structure

**Create:**
- `src/main/resources/db/migration/V9__create_printers_table.sql` — schema + backfill + FK + drop column.
- `src/main/java/com/stup/wristbandprinter/persistence/PrinterEntity.java` — JPA entity for the `printers` table.
- `src/main/java/com/stup/wristbandprinter/persistence/PrinterRepository.java` — Spring Data repository.
- `src/test/java/com/stup/wristbandprinter/persistence/PrinterRepositoryTest.java` — round-trips a printer row (proves table + entity).
- `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistrySeedTest.java` — verifies startup seeding (Mockito).

**Modify:**
- `src/main/java/com/stup/wristbandprinter/persistence/PrintJobEntity.java` — remove the `printerName` field/getter/constructor param.
- `src/main/java/com/stup/wristbandprinter/persistence/JpaJobStore.java` — stop writing the name; resolve it from `printers` on load.
- `src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistry.java` — inject `PrinterRepository`; seed the table in `@PostConstruct`.
- `src/test/java/com/stup/wristbandprinter/persistence/JpaJobStoreTest.java` — add a name-resolution test.
- `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryTest.java` — pass a mock `PrinterRepository` to the constructor.

---

## Task 1: `printers` table + entity + repository

**Files:**
- Create: `src/main/resources/db/migration/V9__create_printers_table.sql`
- Create: `src/main/java/com/stup/wristbandprinter/persistence/PrinterEntity.java`
- Create: `src/main/java/com/stup/wristbandprinter/persistence/PrinterRepository.java`
- Test: `src/test/java/com/stup/wristbandprinter/persistence/PrinterRepositoryTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/persistence/PrinterRepositoryTest.java`:

```java
package com.stup.wristbandprinter.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PrinterRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PrinterRepository repository;

    @Test
    void saveAndLoad_roundTripsPrinter() {
        repository.save(new PrinterEntity("printer-7", "Inkom links", "http://printer-7:8080"));

        Optional<PrinterEntity> found = repository.findById("printer-7");

        assertThat(found).isPresent();
        PrinterEntity p = found.get();
        assertThat(p.getDisplayName()).isEqualTo("Inkom links");
        assertThat(p.getBaseUrl()).isEqualTo("http://printer-7:8080");
        assertThat(p.isOnline()).isFalse();
        assertThat(p.isHidden()).isFalse();
        assertThat(p.isDefault()).isFalse();
        assertThat(p.getRegisteredAt()).isNotNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PrinterRepositoryTest`
Expected: COMPILE FAILURE — `PrinterEntity` and `PrinterRepository` do not exist yet.

- [ ] **Step 3: Create the migration**

Create `src/main/resources/db/migration/V9__create_printers_table.sql`:

```sql
-- Dynamic printer registry (docs/superpowers/specs/2026-06-13-dynamic-printer-registry-design.md).
-- Printers become first-class rows; print_jobs.printer_name is normalized into printers.display_name.
-- hidden / is_default columns are created now but only used in parts 2-3.

CREATE TABLE printers (
    id            VARCHAR(255) PRIMARY KEY,
    display_name  VARCHAR(255) NOT NULL,
    base_url      VARCHAR(512) NOT NULL DEFAULT '',
    online        BOOLEAN      NOT NULL DEFAULT FALSE,
    hidden        BOOLEAN      NOT NULL DEFAULT FALSE,
    is_default    BOOLEAN      NOT NULL DEFAULT FALSE,
    last_seen_at  TIMESTAMPTZ,
    registered_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- At most one default printer (partial unique index over the TRUE rows only).
CREATE UNIQUE INDEX printers_one_default ON printers (is_default) WHERE is_default;

-- Backfill from the printers referenced by historical jobs so the FK is valid and
-- their names still resolve. One row per printer_id; display_name falls back to the id.
INSERT INTO printers (id, display_name)
SELECT printer_id, COALESCE(MAX(printer_name), printer_id)
FROM print_jobs
WHERE printer_id IS NOT NULL
GROUP BY printer_id;

-- Jobs now reference a printer by id (FK). The duplicated printer_name column is
-- dropped in V10 (Task 2), together with the matching PrintJobEntity change, so each
-- migration leaves the JPA entity model and the schema consistent (@DataJpaTest
-- validates the whole entity model against the live schema on boot).
ALTER TABLE print_jobs
    ADD CONSTRAINT fk_print_jobs_printer
    FOREIGN KEY (printer_id) REFERENCES printers (id);
```

- [ ] **Step 4: Create the entity**

Create `src/main/java/com/stup/wristbandprinter/persistence/PrinterEntity.java`:

```java
package com.stup.wristbandprinter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "printers")
public class PrinterEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String baseUrl;

    @Column(nullable = false)
    private boolean online;

    @Column(nullable = false)
    private boolean hidden;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    private Instant lastSeenAt;

    @Column(nullable = false)
    private Instant registeredAt;

    protected PrinterEntity() {
    }

    public PrinterEntity(String id, String displayName, String baseUrl) {
        this.id = id;
        this.displayName = displayName;
        this.baseUrl = baseUrl;
        this.registeredAt = Instant.now();
    }

    public String getId()              { return id; }
    public String getDisplayName()     { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getBaseUrl()         { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public boolean isOnline()          { return online; }
    public void setOnline(boolean online) { this.online = online; }
    public boolean isHidden()          { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }
    public boolean isDefault()         { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    public Instant getLastSeenAt()     { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Instant getRegisteredAt()   { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }
}
```

- [ ] **Step 5: Create the repository**

Create `src/main/java/com/stup/wristbandprinter/persistence/PrinterRepository.java`:

```java
package com.stup.wristbandprinter.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PrinterRepository extends JpaRepository<PrinterEntity, String> {
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=PrinterRepositoryTest`
Expected: PASS (Flyway runs V1–V9 against the Testcontainers Postgres; the round-trip succeeds).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V9__create_printers_table.sql \
        src/main/java/com/stup/wristbandprinter/persistence/PrinterEntity.java \
        src/main/java/com/stup/wristbandprinter/persistence/PrinterRepository.java \
        src/test/java/com/stup/wristbandprinter/persistence/PrinterRepositoryTest.java
git commit -m "feat(printers): add printers table, entity, repository (V9)"
```

---

## Task 2: Drop `printer_name` (V10 migration + entity)

**Files:**
- Create: `src/main/resources/db/migration/V10__drop_print_jobs_printer_name.sql`
- Modify: `src/main/java/com/stup/wristbandprinter/persistence/PrintJobEntity.java`
- Modify: `src/main/java/com/stup/wristbandprinter/persistence/JpaJobStore.java:54` (the `repository.save(new PrintJobEntity(...))` call)

The DB column and the entity mapping must change **together** in one commit, because `@DataJpaTest` validates the whole entity model against the live schema on boot. V9 (Task 1) is additive (table + FK); this task drops the column (V10) and removes the entity field in the same commit. Name *resolution* on load is Task 3.

- [ ] **Step 1: Create the V10 migration**

Create `src/main/resources/db/migration/V10__drop_print_jobs_printer_name.sql`:

```sql
-- printers.display_name is now the source of truth and PrintJobEntity no longer maps
-- printer_name (this commit), so drop the duplicated column. The FK on printer_id was
-- already added in V9.
ALTER TABLE print_jobs DROP COLUMN printer_name;
```

- [ ] **Step 2: Remove the field, getter, and constructor param from `PrintJobEntity`**

In `src/main/java/com/stup/wristbandprinter/persistence/PrintJobEntity.java`:

Delete the field line:

```java
    private String printerName;
```

so the printer block reads:

```java
    private String printerId;
```

Change the constructor signature from:

```java
    public PrintJobEntity(UUID jobId, PrintJobStatus status, WristbandType wristbandType,
                          String printerId, String printerName,
                          String eventName, String firstName, String lastName,
```

to (drop `String printerName`):

```java
    public PrintJobEntity(UUID jobId, PrintJobStatus status, WristbandType wristbandType,
                          String printerId,
                          String eventName, String firstName, String lastName,
```

Delete the assignment line inside the constructor:

```java
        this.printerName    = printerName;
```

Delete the getter:

```java
    public String getPrinterName()   { return printerName; }
```

- [ ] **Step 3: Update the single caller in `JpaJobStore.save`**

In `src/main/java/com/stup/wristbandprinter/persistence/JpaJobStore.java`, change the constructor call from:

```java
        repository.save(new PrintJobEntity(
            job.getJobId(),
            job.getStatus(),
            r.getWristbandType(),
            job.getPrinterId(),
            job.getPrinterName(),
            eventName, firstName, lastName, clubName, barcodeValue,
```

to (remove the `job.getPrinterName(),` line):

```java
        repository.save(new PrintJobEntity(
            job.getJobId(),
            job.getStatus(),
            r.getWristbandType(),
            job.getPrinterId(),
            eventName, firstName, lastName, clubName, barcodeValue,
```

- [ ] **Step 4: Compile to verify nothing else references the removed members**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS. (`toDomain` still calls `e.getPrinterName()` — Task 3 fixes that; if compile fails there, proceed to Task 3 in the same change. To keep this task self-contained, temporarily pass `null` for the name in `toDomain`'s `PrintJob.restore(...)` call, which Task 3 replaces.)

Apply the temporary shim in `JpaJobStore.toDomain` — change:

```java
        return PrintJob.restore(
            e.getJobId(),
            request,
            e.getPrinterId(),
            e.getPrinterName(),
            e.getStatus(),
```

to:

```java
        return PrintJob.restore(
            e.getJobId(),
            request,
            e.getPrinterId(),
            null,   // resolved from the printers table in Task 3
            e.getStatus(),
```

Run again: `./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Run the persistence tests**

Run: `./mvnw test -Dtest=JpaJobStoreTest,PrinterRepositoryTest`
Expected: PASS. (Existing `JpaJobStoreTest` saves jobs with a null `printerId`, so the FK — which permits NULLs — is satisfied; the name is not asserted there. V10 has now removed the column, matching the entity.)

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V10__drop_print_jobs_printer_name.sql \
        src/main/java/com/stup/wristbandprinter/persistence/PrintJobEntity.java \
        src/main/java/com/stup/wristbandprinter/persistence/JpaJobStore.java
git commit -m "refactor(persistence): drop denormalized printer_name from print job entity (V10)"
```

---

## Task 3: Resolve the printer name from the table on load

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/persistence/JpaJobStore.java`
- Test: `src/test/java/com/stup/wristbandprinter/persistence/JpaJobStoreTest.java`

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/stup/wristbandprinter/persistence/JpaJobStoreTest.java`. First inject the printer repo by adding this field next to the existing `@Autowired private PrintJobRepository repository;`:

```java
    @Autowired
    private PrinterRepository printerRepository;
```

Then add the test method:

```java
    @Test
    void loadActive_resolvesPrinterNameFromPrintersTable() {
        printerRepository.save(new PrinterEntity("printer-9", "Inkom rechts", "http://printer-9:8080"));

        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        WristbandPrintRequest req = new WristbandPrintRequest();
        req.setEventName("Pukkelpop 2026");
        req.setCopies(1);
        req.setPrinterId("printer-9");
        store.save(PrintJob.restore(id, req, "printer-9", null,
            PrintJobStatus.DONE, now, now, null));

        PrintJob loaded = store.loadActive().stream()
            .filter(j -> j.getJobId().equals(id)).findFirst().orElseThrow();

        assertThat(loaded.getPrinterName()).isEqualTo("Inkom rechts");
    }
```

Also **fix the existing `save_persistsPrinterIdentity` test** in the same file. Since V9 added the FK, it must create the `printers` row first; and now that the name is resolved from the table, the name assertion stays valid. Replace its body so it reads:

```java
    @Test
    void save_persistsPrinterIdentity() {
        printerRepository.save(new PrinterEntity("printer-1", "Inkom links", "http://printer-1:8080"));
        UUID id = UUID.randomUUID();
        store.save(PrintJob.restore(id, request(), "printer-1", "Inkom links",
            PrintJobStatus.DONE, Instant.now(), Instant.now(), null));

        PrintJob loaded = store.loadActive().stream()
            .filter(j -> j.getJobId().equals(id)).findFirst().orElseThrow();
        assertThat(loaded.getPrinterId()).isEqualTo("printer-1");
        assertThat(loaded.getPrinterName()).isEqualTo("Inkom links");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=JpaJobStoreTest#loadActive_resolvesPrinterNameFromPrintersTable`
Expected: FAIL — `getPrinterName()` is `null` (the Task 2 shim), expected `"Inkom rechts"`.

- [ ] **Step 3: Inject `PrinterRepository` and resolve names on load**

In `src/main/java/com/stup/wristbandprinter/persistence/JpaJobStore.java`:

Add imports:

```java
import java.util.Map;
import java.util.stream.Collectors;
```

Change the field + constructor from:

```java
    private final PrintJobRepository repository;

    public JpaJobStore(PrintJobRepository repository) {
        this.repository = repository;
    }
```

to:

```java
    private final PrintJobRepository repository;
    private final PrinterRepository printerRepository;

    public JpaJobStore(PrintJobRepository repository, PrinterRepository printerRepository) {
        this.repository = repository;
        this.printerRepository = printerRepository;
    }
```

Change `loadActive()` from:

```java
    @Override
    @Transactional(readOnly = true)
    public List<PrintJob> loadActive() {
        return repository.findByDeletedFalse().stream().map(JpaJobStore::toDomain).toList();
    }
```

to:

```java
    @Override
    @Transactional(readOnly = true)
    public List<PrintJob> loadActive() {
        Map<String, String> namesById = printerRepository.findAll().stream()
            .collect(Collectors.toMap(PrinterEntity::getId, PrinterEntity::getDisplayName));
        return repository.findByDeletedFalse().stream()
            .map(e -> toDomain(e, namesById.get(e.getPrinterId())))
            .toList();
    }
```

Change the `toDomain` signature and its final `PrintJob.restore` call. From:

```java
    private static PrintJob toDomain(PrintJobEntity e) {
```

to:

```java
    private static PrintJob toDomain(PrintJobEntity e, String printerName) {
```

and from (the Task 2 shim):

```java
        return PrintJob.restore(
            e.getJobId(),
            request,
            e.getPrinterId(),
            null,   // resolved from the printers table in Task 3
            e.getStatus(),
            e.getSubmittedAt(),
            e.getCompletedAt(),
            e.getError()
        );
```

to:

```java
        return PrintJob.restore(
            e.getJobId(),
            request,
            e.getPrinterId(),
            printerName,
            e.getStatus(),
            e.getSubmittedAt(),
            e.getCompletedAt(),
            e.getError()
        );
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=JpaJobStoreTest`
Expected: PASS (all methods, including the new one).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/persistence/JpaJobStore.java \
        src/test/java/com/stup/wristbandprinter/persistence/JpaJobStoreTest.java
git commit -m "feat(persistence): resolve job printer name from printers table on load"
```

---

## Task 4: `PrinterRegistry` seeds the table from config on startup

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistry.java`
- Test: `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryTest.java` (constructor calls)
- Test: `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistrySeedTest.java` (new)

Routing behavior (`get`/`getDefault`/`all`) stays exactly as today. We only add a `@PostConstruct` that upserts the configured printers into the table so live printers have rows (the FK target for new jobs) and their names match config.

- [ ] **Step 1: Write the failing seed test**

Create `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistrySeedTest.java`:

```java
package com.stup.wristbandprinter.cluster;

import com.stup.wristbandprinter.persistence.PrinterEntity;
import com.stup.wristbandprinter.persistence.PrinterRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PrinterRegistrySeedTest {

    private final PrinterRepository repo = mock(PrinterRepository.class);

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
    void seed_insertsNewConfiguredPrinter() {
        when(repo.findById("printer-1")).thenReturn(Optional.empty());
        PrinterRegistry registry = new PrinterRegistry(
            props(entry("printer-1", "Inkom links", "http://printer-1:8080")), repo);

        registry.seed();

        verify(repo).save(argThat(e ->
            e.getId().equals("printer-1")
                && e.getDisplayName().equals("Inkom links")
                && e.getBaseUrl().equals("http://printer-1:8080")));
    }

    @Test
    void seed_updatesExistingPrinterFromConfig() {
        PrinterEntity existing = new PrinterEntity("printer-1", "Old name", "http://old:8080");
        when(repo.findById("printer-1")).thenReturn(Optional.of(existing));
        PrinterRegistry registry = new PrinterRegistry(
            props(entry("printer-1", "New name", "http://new:8080")), repo);

        registry.seed();

        verify(repo).save(eq(existing));
        org.assertj.core.api.Assertions.assertThat(existing.getDisplayName()).isEqualTo("New name");
        org.assertj.core.api.Assertions.assertThat(existing.getBaseUrl()).isEqualTo("http://new:8080");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PrinterRegistrySeedTest`
Expected: COMPILE FAILURE — `PrinterRegistry` has no constructor taking a `PrinterRepository`, and no `seed()` method.

- [ ] **Step 3: Add the repository dependency and the seed method**

Replace `src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistry.java` with:

```java
package com.stup.wristbandprinter.cluster;

import com.stup.wristbandprinter.persistence.PrinterEntity;
import com.stup.wristbandprinter.persistence.PrinterRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only view over the configured printers. Validates config at startup and
 *  seeds the printers table so live printers have a persisted row (phase 1). */
@Component
@Profile("!worker")
public class PrinterRegistry {

    private final Map<String, Printer> byId = new LinkedHashMap<>();
    private final PrinterRepository printerRepository;

    public PrinterRegistry(PrinterRegistryProperties props, PrinterRepository printerRepository) {
        this.printerRepository = printerRepository;
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

    /** Upsert the configured printers into the printers table. Each repository call is
     *  transactional on its own; runs at startup before the web server accepts traffic. */
    @PostConstruct
    public void seed() {
        for (Printer p : byId.values()) {
            PrinterEntity entity = printerRepository.findById(p.id())
                .orElseGet(() -> new PrinterEntity(p.id(), p.displayName(), p.baseUrl()));
            entity.setDisplayName(p.displayName());
            entity.setBaseUrl(p.baseUrl());
            printerRepository.save(entity);
        }
    }

    /** The printer used when a request does not specify one (phase 1: the first configured printer). */
    public Printer getDefault() {
        return byId.values().iterator().next();
    }

    public Printer get(String id) {
        Printer printer = byId.get(id);
        if (printer == null) {
            throw new com.stup.wristbandprinter.exception.UnknownPrinterException("Unknown printer id: " + id);
        }
        return printer;
    }

    public List<Printer> all() {
        return List.copyOf(byId.values());
    }
}
```

- [ ] **Step 4: Run the seed test to verify it passes**

Run: `./mvnw test -Dtest=PrinterRegistrySeedTest`
Expected: PASS.

- [ ] **Step 5: Fix the existing `PrinterRegistryTest` constructor calls**

In `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryTest.java`, add imports near the top:

```java
import com.stup.wristbandprinter.persistence.PrinterRepository;
import static org.mockito.Mockito.mock;
```

Add a mock field at the top of the class body (after the class declaration line `class PrinterRegistryTest {`):

```java
    private final PrinterRepository repo = mock(PrinterRepository.class);
```

Then change every `new PrinterRegistry(props(...))` call to pass `repo` as the second argument. There are five constructions; each becomes `new PrinterRegistry(props(...), repo)`. For example, the first:

```java
        PrinterRegistry registry = new PrinterRegistry(
            props(entry("printer-1", "Inkom links", "http://printer-1:8080"),
                  entry("printer-2", "Inkom rechts", "http://printer-2:8080")), repo);
```

and the two `assertThatThrownBy(() -> new PrinterRegistry(props()))` / `props(...)` cases become `new PrinterRegistry(props(), repo)` and `new PrinterRegistry(props(entry("dup", ...), entry("dup", ...)), repo)` respectively. (The constructor does not touch the repo, so the bare mock is sufficient — validation still happens at construction.)

- [ ] **Step 6: Run the registry tests**

Run: `./mvnw test -Dtest=PrinterRegistryTest,PrinterRegistrySeedTest`
Expected: PASS (all existing validation/getDefault/get/all tests plus the seed tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistry.java \
        src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryTest.java \
        src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistrySeedTest.java
git commit -m "feat(printers): seed printers table from cluster.printers on startup"
```

---

## Task 5: Full-suite + manual verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full test suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS. Watch specifically for any **FK violation** in tests that persist a job with a non-null `printerId` that has no `printers` row. If one appears (e.g. in `PrintQueueServiceTest` or a controller/integration test), fix it by saving the matching `PrinterEntity` first (the app context seeds `printer-1` from config, so full `@SpringBootTest`-style tests already have it; only narrow persistence tests need an explicit printer row). Do not weaken the FK.

- [ ] **Step 2: Manual smoke against the local cluster**

Run:

```bash
docker compose -f docker-compose.local-cluster.yml up --build -d
```

Then verify the migration + backfill landed and old jobs still show their printer name:

```bash
docker exec stup-wristbandprinterservice-postgres-1 \
  psql -U wristbands -d stup_wristband_db -c "\d printers" \
  -c "SELECT id, display_name, base_url FROM printers;" \
  -c "SELECT count(*) FROM information_schema.columns WHERE table_name='print_jobs' AND column_name='printer_name';"
```

Expected: the `printers` table exists with the eight columns; a row for `printer-1` (seeded from config); the `printer_name` count is `0` (column dropped). In the jobs UI (`http://localhost:8080/jobs.html`, admin / local-admin) the **Printer** column still shows the printer's display name for any existing and any newly-submitted job.

- [ ] **Step 3: Commit (docs)**

No code change. If anything in the spec drifted during implementation, note it in the spec's amendments and commit; otherwise nothing to do.

---

## Self-Review

**Spec coverage (Phase 1 slice):**
- `printers` table with all spec columns + partial unique default index → Task 1 (V9). ✓
- Backfill from historical jobs; FK `print_jobs.printer_id → printers(id)`; drop `printer_name` → Task 1 (V9). ✓
- `JpaJobStore` resolves `display_name` via the printers table on load; in-memory `PrintJob` still carries the name for responses → Task 3. ✓ (`PrintJobResponse` shape unchanged — `toResponse()`/`toDetailResponse()` untouched.)
- Registry seeded from `cluster.printers` so behavior is unchanged, no self-registration yet → Task 4. ✓
- Out of Phase-1 scope (correctly deferred): self-registration endpoint, dynamic queues, removing `cluster.printers`, rename/hide/test/default endpoints, `printer` SSE event, the modal. These are Parts 2–3.

**Placeholder scan:** No TBD/TODO; every code step shows complete code. The only deliberate temporary is the Task 2 `null` shim, explicitly replaced in Task 3. ✓

**Type consistency:** `PrinterEntity(String id, String displayName, String baseUrl)` constructor used identically in Tasks 1, 3, 4; `PrinterRepository extends JpaRepository<PrinterEntity, String>` used in Tasks 3–4; `PrinterRegistry(PrinterRegistryProperties, PrinterRepository)` constructor used in Task 4 main + both tests; `seed()` name consistent; `PrintJobEntity` constructor arg count reduced by exactly one (the removed `printerName`) and its sole caller updated in Task 2. ✓

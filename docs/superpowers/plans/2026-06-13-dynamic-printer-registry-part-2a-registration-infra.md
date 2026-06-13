# Dynamic Printer Registry — Part 2a: Registration Infrastructure (management side)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the management-side machinery for dynamic printers — an internal registration endpoint, a DB-backed *mutable* `PrinterRegistry`, on-demand per-printer queues, the redefined default-printer rule, an empty-cluster 503, and a `printer` SSE broadcast — **without** breaking the existing config-driven setup.

**Architecture:** The `printers` table (from Part 1) becomes the source of truth for printer *state*. `PrinterRegistry` keeps a thread-safe in-memory `byId` map purely for *routing* (id → display name + base_url), loaded from the table at startup and mutated by `register()`; all state-dependent queries (`getDefault`, online/hidden) hit the DB. A new `PrinterRegistrationController` (API-key-secured, `/api/internal/printers/...`) coordinates `register()` → `PrintQueueService.ensureQueue()` → `PrintQueueService.broadcastPrinter()`. `PrintQueueService` switches to a cached thread pool so a queue+worker thread can be created for a printer registered after startup. Config seeding from Part 1 stays, so nothing breaks; the endpoint is dormant until Part 2b wires up the workers.

**Tech Stack:** Java 21, Spring Boot 3.4.1 (web, security, data-jpa, validation), PostgreSQL + Flyway, JUnit 5 + Testcontainers + MockMvc + Mockito.

**Spec:** `docs/superpowers/specs/2026-06-13-dynamic-printer-registry-design.md` (Phase 2, management slice — D2/D3/D4/D5/D6). Part 2b adds the worker self-registration runner + heartbeat + compose env and removes `cluster.printers`.

---

## Context for the implementer (current state after Part 1)

- `PrinterRegistry` (`cluster/`, `@Profile("!worker") @Component`): constructor builds an in-memory `Map<String,Printer> byId` from `cluster.printers` config (validates non-empty + no dup ids), a `@PostConstruct seed()` upserts those into the `printers` table, and `getDefault()` returns the first configured printer; `get(id)`/`all()` read `byId`. `Printer` is `record Printer(String id, String displayName, String baseUrl)`.
- `PrinterEntity` (`persistence/`) has: `id`, `displayName`, `baseUrl`, `online`, `hidden`, `isDefault` (`@Column(name="is_default")`), `lastSeenAt`, `registeredAt`; constructor `PrinterEntity(String id, String displayName, String baseUrl)` (sets `registeredAt=now()`); full getters/setters. `PrinterRepository extends JpaRepository<PrinterEntity, String>`.
- `PrintQueueService` (`service/`, `@Profile("!worker")`): holds `ConcurrentHashMap<String,BlockingQueue<PrintJob>> queues` (lazily created via `queueFor(id)`), a `CopyOnWriteArrayList<SseEmitter> emitters`, a fixed `ExecutorService worker` sized to `printerRegistry.all().size()` in `startWorker()`, and `broadcastUpdate(PrintJob)` which sends `SseEmitter.event().data(job.toResponse())` (unnamed event) to every emitter. `enqueue()` resolves the printer via `printerRegistry.getDefault()` / `printerRegistry.get(id)` and routes per printer queue. `processQueue` resolves `base_url` per job via `printerRegistry.get(job.getPrinterId())`.
- Security (`config/SecurityConfig`): every `/api/**` request requires authentication; `ApiKeyAuthFilter` authenticates a request if it carries a valid `X-API-Key` header **or** a valid admin cookie. So a new `/api/internal/printers/...` endpoint is automatically API-key-protected — no SecurityConfig change needed.
- Errors map centrally in `GlobalExceptionHandler` (`{status,error,message}` body). `UnknownPrinterException` → 400 already exists.

---

## File Structure

**Create:**
- `src/main/java/com/stup/wristbandprinter/exception/NoPrintersAvailableException.java`
- `src/main/java/com/stup/wristbandprinter/cluster/dto/RegisterPrinterRequest.java`
- `src/main/java/com/stup/wristbandprinter/cluster/dto/PrinterEvent.java`
- `src/main/java/com/stup/wristbandprinter/controller/PrinterRegistrationController.java`
- `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryStateTest.java` (Testcontainers: register/markOffline/getDefault rules)
- `src/test/java/com/stup/wristbandprinter/controller/PrinterRegistrationControllerTest.java`

**Modify:**
- `src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java` (503 handler)
- `src/main/java/com/stup/wristbandprinter/persistence/PrinterRepository.java` (default-resolution derived queries)
- `src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistry.java` (DB-backed routing map; `register`/`markOffline`; `getDefault` redefinition)
- `src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java` (cached pool; `ensureQueue`; empty-cluster 503; `broadcastPrinter`)
- `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryTest.java` (keep config-validation unit tests; move routing/default assertions to the new Testcontainers test)

---

## Task 1: `NoPrintersAvailableException` → 503

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/exception/NoPrintersAvailableException.java`
- Modify: `src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: Create the exception**

```java
package com.stup.wristbandprinter.exception;

/** Thrown when a print is requested but no printer is registered/eligible to serve it. */
public class NoPrintersAvailableException extends RuntimeException {
    public NoPrintersAvailableException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: Add the handler (write the test first)**

There is no dedicated handler unit test in the codebase; verify via the controller/queue tests that arrive later. Add the handler to `GlobalExceptionHandler` next to `handlePrinterUnavailable`:

```java
    @ExceptionHandler(NoPrintersAvailableException.class)
    public ResponseEntity<Map<String, Object>> handleNoPrinters(NoPrintersAvailableException ex) {
        log.warn("No printers available: {}", ex.getMessage());
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "No printers available", ex.getMessage());
    }
```

- [ ] **Step 3: Compile**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/exception/NoPrintersAvailableException.java \
        src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java
git commit -m "feat(printers): add NoPrintersAvailableException mapped to 503"
```

---

## Task 2: Repository queries for default resolution

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/persistence/PrinterRepository.java`
- Test: `src/test/java/com/stup/wristbandprinter/persistence/PrinterRepositoryTest.java`

- [ ] **Step 1: Add the failing test**

Append to `PrinterRepositoryTest`:

```java
    @Test
    void defaultResolutionQueries_orderByRegisteredAtAndFilter() {
        java.time.Instant t0 = java.time.Instant.parse("2026-01-01T00:00:00Z");
        PrinterEntity a = new PrinterEntity("a", "A", "http://a:8080"); a.setRegisteredAt(t0);
        PrinterEntity b = new PrinterEntity("b", "B", "http://b:8080"); b.setRegisteredAt(t0.plusSeconds(60)); b.setOnline(true);
        repository.saveAll(java.util.List.of(a, b));

        assertThat(repository.findByIsDefaultTrue()).isEmpty();
        assertThat(repository.findFirstByHiddenFalseOrderByRegisteredAtAsc())
            .map(PrinterEntity::getId).contains("a");
        assertThat(repository.findFirstByOnlineTrueAndHiddenFalseOrderByRegisteredAtAsc())
            .map(PrinterEntity::getId).contains("b");

        a.setDefault(true);
        repository.save(a);
        assertThat(repository.findByIsDefaultTrue()).map(PrinterEntity::getId).contains("a");
    }
```

Run: `./mvnw test -Dtest=PrinterRepositoryTest#defaultResolutionQueries_orderByRegisteredAtAndFilter`
Expected: COMPILE FAILURE — the three query methods don't exist.

- [ ] **Step 2: Add the derived queries**

Replace `PrinterRepository.java` with:

```java
package com.stup.wristbandprinter.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PrinterRepository extends JpaRepository<PrinterEntity, String> {

    Optional<PrinterEntity> findByIsDefaultTrue();

    Optional<PrinterEntity> findFirstByOnlineTrueAndHiddenFalseOrderByRegisteredAtAsc();

    Optional<PrinterEntity> findFirstByHiddenFalseOrderByRegisteredAtAsc();
}
```

- [ ] **Step 3: Run the test**

Run: `./mvnw test -Dtest=PrinterRepositoryTest`
Expected: PASS (all methods).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/persistence/PrinterRepository.java \
        src/test/java/com/stup/wristbandprinter/persistence/PrinterRepositoryTest.java
git commit -m "feat(printers): add default-resolution queries to PrinterRepository"
```

---

## Task 3: `PrinterRegistry` — DB-backed routing map, `register`/`markOffline`, redefined `getDefault`

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistry.java`
- Test (new): `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryStateTest.java`
- Test (modify): `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryTest.java`

Design: `byId` (a `ConcurrentHashMap`) is the **routing** map (id → `Printer`), loaded from the table after seeding and mutated by `register()`. `getDefault()` resolves from the DB per D5 (explicit default if present and not hidden, even if offline; else earliest online & not hidden; else earliest not hidden; else throw `NoPrintersAvailableException`). `register()` upserts the row (online=true, hidden=false, refresh base_url + last_seen_at; **display_name set on create only** — operator renames in Part 3 win) and updates `byId`. `markOffline()` flips `online=false`.

- [ ] **Step 1: Write the failing state test (Testcontainers)**

Create `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryStateTest.java`:

```java
package com.stup.wristbandprinter.cluster;

import com.stup.wristbandprinter.exception.NoPrintersAvailableException;
import com.stup.wristbandprinter.persistence.PrinterEntity;
import com.stup.wristbandprinter.persistence.PrinterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PrinterRegistryStateTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PrinterRepository repo;

    /** Build a registry directly against the real repo, with empty config (we register dynamically). */
    private PrinterRegistry registry() {
        PrinterRegistryProperties props = new PrinterRegistryProperties(); // empty: dynamic-only
        PrinterRegistry r = new PrinterRegistry(props, repo);
        r.init();
        return r;
    }

    @Test
    void register_thenGetAndRoute() {
        PrinterRegistry r = registry();
        r.register("printer-1", "Inkom", "http://printer-1:8080");

        assertThat(r.get("printer-1").baseUrl()).isEqualTo("http://printer-1:8080");
        assertThat(repo.findById("printer-1")).get()
            .satisfies(e -> {
                assertThat(e.isOnline()).isTrue();
                assertThat(e.isHidden()).isFalse();
                assertThat(e.getLastSeenAt()).isNotNull();
            });
    }

    @Test
    void register_existing_doesNotOverwriteDisplayNameButUpdatesBaseUrl() {
        PrinterRepositorySeed("printer-1", "Operator Renamed", "http://old:8080", false);
        PrinterRegistry r = registry();

        r.register("printer-1", "Worker Default Name", "http://new:8080");

        PrinterEntity e = repo.findById("printer-1").orElseThrow();
        assertThat(e.getDisplayName()).isEqualTo("Operator Renamed"); // create-only display name
        assertThat(e.getBaseUrl()).isEqualTo("http://new:8080");
        assertThat(e.isOnline()).isTrue();
    }

    @Test
    void markOffline_setsOnlineFalse() {
        PrinterRegistry r = registry();
        r.register("printer-1", "Inkom", "http://printer-1:8080");
        r.markOffline("printer-1");
        assertThat(repo.findById("printer-1")).get().satisfies(e -> assertThat(e.isOnline()).isFalse());
    }

    @Test
    void getDefault_emptyCluster_throws() {
        PrinterRegistry r = registry();
        assertThatThrownBy(r::getDefault).isInstanceOf(NoPrintersAvailableException.class);
    }

    @Test
    void getDefault_prefersExplicitDefaultEvenIfOffline() {
        PrinterRegistry r = registry();
        r.register("a", "A", "http://a:8080");          // online, earliest
        r.register("b", "B", "http://b:8080");
        PrinterEntity b = repo.findById("b").orElseThrow();
        b.setDefault(true); b.setOnline(false); repo.save(b);   // explicit default, offline

        assertThat(r.getDefault().id()).isEqualTo("b");
    }

    @Test
    void getDefault_fallsBackToEarliestOnlineNotHidden_whenNoExplicitDefault() {
        PrinterRegistry r = registry();
        r.register("a", "A", "http://a:8080");
        r.markOffline("a");                              // a offline
        r.register("b", "B", "http://b:8080");           // b online, later
        assertThat(r.getDefault().id()).isEqualTo("b");
    }

    private void PrinterRepositorySeed(String id, String name, String url, boolean online) {
        PrinterEntity e = new PrinterEntity(id, name, url);
        e.setOnline(online);
        repo.save(e);
    }
}
```

Run: `./mvnw test -Dtest=PrinterRegistryStateTest`
Expected: COMPILE FAILURE — `init()`, `register()`, `markOffline()` don't exist and `getDefault()` has the old behaviour.

- [ ] **Step 2: Rewrite `PrinterRegistry.java`**

Replace the whole file with:

```java
package com.stup.wristbandprinter.cluster;

import com.stup.wristbandprinter.exception.NoPrintersAvailableException;
import com.stup.wristbandprinter.exception.UnknownPrinterException;
import com.stup.wristbandprinter.persistence.PrinterEntity;
import com.stup.wristbandprinter.persistence.PrinterRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routing view over the printers. The in-memory {@code byId} map (id → routing info: display name +
 * base URL) is loaded from the printers table at startup and mutated by {@link #register}. Printer
 * <em>state</em> (online/hidden/default) lives only in the table and is queried on demand, so there
 * is a single source of truth and no in-memory/DB drift.
 */
@Component
@Profile("!worker")
public class PrinterRegistry {

    private final Map<String, Printer> byId = new ConcurrentHashMap<>();
    private final PrinterRegistryProperties props;
    private final PrinterRepository printerRepository;

    public PrinterRegistry(PrinterRegistryProperties props, PrinterRepository printerRepository) {
        this.props = props;
        this.printerRepository = printerRepository;
        // Validate config (still the seed source in part 2a; removed in part 2b).
        Map<String, Boolean> seen = new java.util.HashMap<>();
        for (PrinterRegistryProperties.Entry e : props.getPrinters()) {
            if (seen.put(e.getId(), Boolean.TRUE) != null) {
                throw new IllegalStateException("Duplicate printer id in cluster.printers: " + e.getId());
            }
        }
    }

    /**
     * Seed configured printers into the table (insert-if-absent, refresh name/base_url on existing),
     * then load every printer row into the routing map. Flyway runs before any {@code @PostConstruct},
     * so the printers table (V9) exists here; each repository call is its own transaction.
     */
    @PostConstruct
    public void init() {
        for (PrinterRegistryProperties.Entry e : props.getPrinters()) {
            PrinterEntity entity = printerRepository.findById(e.getId())
                .orElseGet(() -> new PrinterEntity(e.getId(), e.getDisplayName(), e.getBaseUrl()));
            entity.setDisplayName(e.getDisplayName());
            entity.setBaseUrl(e.getBaseUrl());
            printerRepository.save(entity);
        }
        for (PrinterEntity e : printerRepository.findAll()) {
            byId.put(e.getId(), new Printer(e.getId(), e.getDisplayName(), e.getBaseUrl()));
        }
    }

    /**
     * Register (or refresh) a printer: upsert the row online, refreshing base_url + last_seen_at and
     * clearing hidden. The display name is set only when the row is first created — an operator rename
     * (part 3) is not overwritten by a re-registering worker. Updates the routing map.
     */
    public void register(String id, String displayName, String baseUrl) {
        java.util.Optional<PrinterEntity> existing = printerRepository.findById(id);
        PrinterEntity entity = existing.orElseGet(() -> new PrinterEntity(id, displayName, baseUrl));
        entity.setBaseUrl(baseUrl);
        entity.setOnline(true);
        entity.setHidden(false);
        entity.setLastSeenAt(Instant.now());
        printerRepository.save(entity);
        byId.put(id, new Printer(id, entity.getDisplayName(), baseUrl));
    }

    /** Mark a printer offline (its row + routing entry persist). */
    public void markOffline(String id) {
        printerRepository.findById(id).ifPresent(e -> {
            e.setOnline(false);
            printerRepository.save(e);
        });
    }

    /**
     * The printer used when a request does not specify one (D5): the explicitly-set default if it
     * exists and is not hidden (even if offline — operator intent wins); else the earliest-registered
     * online, non-hidden printer; else the earliest-registered non-hidden printer; else none → 503.
     */
    public Printer getDefault() {
        PrinterEntity chosen = printerRepository.findByIsDefaultTrue()
            .filter(e -> !e.isHidden())
            .or(printerRepository::findFirstByOnlineTrueAndHiddenFalseOrderByRegisteredAtAsc)
            .or(printerRepository::findFirstByHiddenFalseOrderByRegisteredAtAsc)
            .orElseThrow(() -> new NoPrintersAvailableException(
                "No printers are registered. Start a printer worker (or register one) and retry."));
        return new Printer(chosen.getId(), chosen.getDisplayName(), chosen.getBaseUrl());
    }

    public Printer get(String id) {
        Printer printer = byId.get(id);
        if (printer == null) {
            throw new UnknownPrinterException("Unknown printer id: " + id);
        }
        return printer;
    }

    public List<Printer> all() {
        return List.copyOf(byId.values());
    }
}
```

Run: `./mvnw test -Dtest=PrinterRegistryStateTest`
Expected: PASS.

- [ ] **Step 3: Migrate the existing `PrinterRegistryTest`**

The old unit test asserted config-derived routing/default behaviour by constructing the registry without a DB. Those routing/default assertions now belong to `PrinterRegistryStateTest` (Testcontainers). Keep only what still holds as a pure unit test: **duplicate-id validation** at construction. Replace `PrinterRegistryTest.java` with:

```java
package com.stup.wristbandprinter.cluster;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.stup.wristbandprinter.persistence.PrinterRepository;

class PrinterRegistryTest {

    private final PrinterRepository repo = mock(PrinterRepository.class);

    private static PrinterRegistryProperties props(PrinterRegistryProperties.Entry... entries) {
        PrinterRegistryProperties p = new PrinterRegistryProperties();
        for (PrinterRegistryProperties.Entry e : entries) {
            p.getPrinters().add(e);
        }
        return p;
    }

    private static PrinterRegistryProperties.Entry entry(String id) {
        PrinterRegistryProperties.Entry e = new PrinterRegistryProperties.Entry();
        e.setId(id);
        e.setDisplayName(id);
        e.setBaseUrl("http://" + id + ":8080");
        return e;
    }

    @Test
    void duplicateIds_throwAtConstruction() {
        assertThatThrownBy(() -> new PrinterRegistry(props(entry("dup"), entry("dup")), repo))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dup");
    }

    @Test
    void emptyConfig_isAllowed_dynamicRegistrationOnly() {
        // No longer throws on empty config: printers can be registered dynamically (part 2).
        new PrinterRegistry(props(), repo);
    }
}
```

> Note: the empty-config behaviour intentionally changed — the old `emptyRegistry_throwsAtConstruction` rule is gone because Part 2 allows a management instance with no configured printers (they arrive via registration). The empty-cluster guard now lives in `getDefault()` → `NoPrintersAvailableException` (tested in `PrinterRegistryStateTest`).

Run: `./mvnw test -Dtest=PrinterRegistryTest,PrinterRegistryStateTest,PrinterRegistrySeedTest`
Expected: PASS. (If `PrinterRegistrySeedTest` from Part 1 now conflicts with the renamed lifecycle method `init()` vs `seed()`, update its `registry.seed()` call to `registry.init()` and keep its insert/update assertions — the upsert logic moved verbatim into `init()`.)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistry.java \
        src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryStateTest.java \
        src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryTest.java \
        src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistrySeedTest.java
git commit -m "feat(printers): DB-backed mutable PrinterRegistry with register/markOffline + D5 default"
```

---

## Task 4: `PrintQueueService` — cached pool, `ensureQueue`, empty-cluster, `broadcastPrinter`

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java`
- Create: `src/main/java/com/stup/wristbandprinter/cluster/dto/PrinterEvent.java`
- Test: `src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java` (add cases)

- [ ] **Step 1: Create the `PrinterEvent` DTO**

```java
package com.stup.wristbandprinter.cluster.dto;

import java.time.Instant;

/** Payload of the named "printer" SSE event (D6): a printer's current public state. */
public record PrinterEvent(String id, String displayName, boolean online, boolean hidden,
                           boolean isDefault, Instant lastSeenAt) {
}
```

- [ ] **Step 2: Add a failing test for `ensureQueue` + empty-cluster**

Add to `PrintQueueServiceTest` (follow the existing test's construction of `PrintQueueService` — reuse its existing mocks/fixtures for `WristbandZplResolver`, `WorkerClient`, `QueueProperties`, `PrintProperties`, `JobStore`, `MeterRegistry`; for these cases stub `printerRegistry.getDefault()` to throw / return as noted):

```java
    @Test
    void enqueue_noPrinters_throwsNoPrintersAvailable() {
        when(printerRegistry.getDefault())
            .thenThrow(new com.stup.wristbandprinter.exception.NoPrintersAvailableException("none"));
        WristbandPrintRequest req = new WristbandPrintRequest();
        req.setEventName("E"); req.setCopies(1);
        assertThatThrownBy(() -> service.enqueue(req))
            .isInstanceOf(com.stup.wristbandprinter.exception.NoPrintersAvailableException.class);
    }

    @Test
    void ensureQueue_isIdempotent_andStartsProcessingForNewPrinter() {
        service.ensureQueue("printer-x");
        service.ensureQueue("printer-x"); // second call must not start a second worker
        // No exception, and a queue now exists for routing; depth gauge sees it.
        assertThat(service.queueDepth("printer-x")).isZero();
    }
```

(If the test class lacks a `printerRegistry` mock field, add `@Mock PrinterRegistry printerRegistry;` consistent with how the other collaborators are mocked there.)

Run: `./mvnw test -Dtest=PrintQueueServiceTest#ensureQueue_isIdempotent_andStartsProcessingForNewPrinter`
Expected: COMPILE FAILURE — `ensureQueue`/`queueDepth` don't exist.

- [ ] **Step 3: Implement the changes in `PrintQueueService`**

(a) Add a thread-safe set tracking which printers already have a worker thread, next to the `queues` field:

```java
    private final java.util.Set<String> started = java.util.concurrent.ConcurrentHashMap.newKeySet();
```

(b) Replace `startWorker()` to use a **cached** pool and delegate to `ensureQueue` per printer:

```java
    public void startWorker() {
        worker = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "print-queue-worker");
            t.setDaemon(true);
            return t;
        });
        for (Printer p : printerRegistry.all()) {
            ensureQueue(p.id());
        }
        log.info("Started print-queue workers for {} printer(s)", printerRegistry.all().size());
    }
```

(c) Add `ensureQueue` (idempotent: create queue + submit a `processQueue` task once per printer) and a small `queueDepth` accessor used by the test:

```java
    /** Ensure a queue and a dedicated worker thread exist for this printer. Idempotent. */
    public void ensureQueue(String printerId) {
        java.util.concurrent.BlockingQueue<PrintJob> q = queueFor(printerId);
        if (started.add(printerId)) {
            worker.submit(() -> processQueue(q));
            log.info("Started print-queue worker for printer {}", printerId);
        }
    }

    public int queueDepth(String printerId) {
        return queueFor(printerId).size();
    }
```

(d) In `enqueue(...)`, after resolving `printer` (which may throw `NoPrintersAvailableException` from `getDefault()` or `UnknownPrinterException` from `get()`), make sure the queue/thread exists before offering the job — add `ensureQueue(printer.id());` immediately after the `Printer printer = ...` resolution block (before `queueFor`).

(e) Add the named `printer` SSE broadcaster (uses the existing `emitters` list):

```java
    /** Broadcast a printer state change to all jobs-stream subscribers as a named "printer" event (D6). */
    public void broadcastPrinter(com.stup.wristbandprinter.cluster.dto.PrinterEvent event) {
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("printer").data(event));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }
```

Run: `./mvnw test -Dtest=PrintQueueServiceTest`
Expected: PASS (existing + the two new cases). If `newFixedThreadPool` was referenced elsewhere, it's now the cached pool — verify `stopWorker()` still shuts down `worker` (unchanged).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java \
        src/main/java/com/stup/wristbandprinter/cluster/dto/PrinterEvent.java \
        src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java
git commit -m "feat(printers): dynamic per-printer queues + printer SSE broadcast + empty-cluster 503"
```

---

## Task 5: `PrinterRegistrationController` (register + deregister)

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/cluster/dto/RegisterPrinterRequest.java`
- Create: `src/main/java/com/stup/wristbandprinter/controller/PrinterRegistrationController.java`
- Test: `src/test/java/com/stup/wristbandprinter/controller/PrinterRegistrationControllerTest.java`

The controller is the coordinator (avoids a `PrinterRegistry` ↔ `PrintQueueService` cycle): `register` → `registry.register` → `printQueueService.ensureQueue` → `printQueueService.broadcastPrinter`. It is API-key-protected automatically (SecurityConfig requires auth on `/api/**`; the worker sends `X-API-Key`).

- [ ] **Step 1: Create the request DTO**

```java
package com.stup.wristbandprinter.cluster.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of POST /api/internal/printers/register — a worker announcing itself. */
public record RegisterPrinterRequest(
    @NotBlank String id,
    @NotBlank String displayName,
    @NotBlank String baseUrl) {
}
```

- [ ] **Step 2: Write the failing controller test**

Create `src/test/java/com/stup/wristbandprinter/controller/PrinterRegistrationControllerTest.java`:

```java
package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.cluster.PrinterRegistry;
import com.stup.wristbandprinter.service.PrintQueueService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class PrinterRegistrationControllerTest {

    private final PrinterRegistry registry = mock(PrinterRegistry.class);
    private final PrintQueueService queue = mock(PrintQueueService.class);
    private final PrinterRegistrationController controller =
        new PrinterRegistrationController(registry, queue);

    @Test
    void register_registersEnsuresQueueAndBroadcasts() {
        when(registry.snapshot("printer-1")).thenReturn(
            new com.stup.wristbandprinter.cluster.dto.PrinterEvent(
                "printer-1", "Inkom", true, false, false, java.time.Instant.now()));

        controller.register(new com.stup.wristbandprinter.cluster.dto.RegisterPrinterRequest(
            "printer-1", "Inkom", "http://printer-1:8080"));

        verify(registry).register("printer-1", "Inkom", "http://printer-1:8080");
        verify(queue).ensureQueue("printer-1");
        ArgumentCaptor<com.stup.wristbandprinter.cluster.dto.PrinterEvent> cap =
            ArgumentCaptor.forClass(com.stup.wristbandprinter.cluster.dto.PrinterEvent.class);
        verify(queue).broadcastPrinter(cap.capture());
        assertThat(cap.getValue().id()).isEqualTo("printer-1");
    }

    @Test
    void deregister_marksOfflineAndBroadcasts() {
        when(registry.snapshot("printer-1")).thenReturn(
            new com.stup.wristbandprinter.cluster.dto.PrinterEvent(
                "printer-1", "Inkom", false, false, false, java.time.Instant.now()));

        controller.deregister("printer-1");

        verify(registry).markOffline("printer-1");
        verify(queue).broadcastPrinter(any());
    }
}
```

This requires a `registry.snapshot(id)` helper returning a `PrinterEvent` for the current row. Add it to `PrinterRegistry` (Task 3 file) — append this method and the import:

```java
    /** Current public state of a printer as an SSE event payload; null if unknown. */
    public com.stup.wristbandprinter.cluster.dto.PrinterEvent snapshot(String id) {
        return printerRepository.findById(id)
            .map(e -> new com.stup.wristbandprinter.cluster.dto.PrinterEvent(
                e.getId(), e.getDisplayName(), e.isOnline(), e.isHidden(), e.isDefault(), e.getLastSeenAt()))
            .orElse(null);
    }
```

Run: `./mvnw test -Dtest=PrinterRegistrationControllerTest`
Expected: COMPILE FAILURE — controller (and `snapshot`) don't exist yet.

- [ ] **Step 3: Implement `snapshot` (in PrinterRegistry) and the controller**

Add `snapshot(...)` to `PrinterRegistry` as shown above. Then create `src/main/java/com/stup/wristbandprinter/controller/PrinterRegistrationController.java`:

```java
package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.cluster.PrinterRegistry;
import com.stup.wristbandprinter.cluster.dto.PrinterEvent;
import com.stup.wristbandprinter.cluster.dto.RegisterPrinterRequest;
import com.stup.wristbandprinter.service.PrintQueueService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Internal endpoints a printer-worker calls to announce/retire itself. API-key protected. */
@Profile("!worker")
@RestController
@RequestMapping("/api/internal/printers")
public class PrinterRegistrationController {

    private final PrinterRegistry registry;
    private final PrintQueueService printQueueService;

    public PrinterRegistrationController(PrinterRegistry registry, PrintQueueService printQueueService) {
        this.registry = registry;
        this.printQueueService = printQueueService;
    }

    /** Register or refresh a worker. Idempotent (also serves as the heartbeat). */
    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterPrinterRequest req) {
        registry.register(req.id(), req.displayName(), req.baseUrl());
        printQueueService.ensureQueue(req.id());
        broadcast(req.id());
        return ResponseEntity.ok().build();
    }

    /** Mark a worker offline (best-effort, called on graceful worker shutdown). */
    @PostMapping("/{id}/deregister")
    public ResponseEntity<Void> deregister(@PathVariable String id) {
        registry.markOffline(id);
        broadcast(id);
        return ResponseEntity.ok().build();
    }

    private void broadcast(String id) {
        PrinterEvent event = registry.snapshot(id);
        if (event != null) {
            printQueueService.broadcastPrinter(event);
        }
    }
}
```

Run: `./mvnw test -Dtest=PrinterRegistrationControllerTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/cluster/dto/RegisterPrinterRequest.java \
        src/main/java/com/stup/wristbandprinter/controller/PrinterRegistrationController.java \
        src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistry.java \
        src/test/java/com/stup/wristbandprinter/controller/PrinterRegistrationControllerTest.java
git commit -m "feat(printers): internal register/deregister endpoints coordinating registry, queue, SSE"
```

---

## Task 6: Full-suite + manual smoke

**Files:** none (verification).

- [ ] **Step 1: Full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS. Pay attention to any `@SpringBootTest` context test: the registry now allows empty config and `getDefault()` resolves from the DB. Existing integration tests still configure `printer-1` (so `init()` seeds it; `getDefault()` returns it via the earliest-non-hidden fallback even though `online=false` until a worker registers). If any test asserted the old `IllegalStateException` on empty config or the old fixed-pool sizing, update it to the new behaviour (don't weaken the new contracts).

- [ ] **Step 2: Manual smoke against the local cluster**

```bash
docker compose -f docker-compose.local-cluster.yml up --build -d management
```

Then exercise the new endpoint (management requires the API key `local-dev-key` in this stack):

```bash
# Register a brand-new printer that is NOT in config
curl -fsS -X POST http://localhost:8080/api/internal/printers/register \
  -H 'X-API-Key: local-dev-key' -H 'Content-Type: application/json' \
  -d '{"id":"printer-2","displayName":"Inkom rechts","baseUrl":"http://worker-1:8080"}' -w '\n%{http_code}\n'

# Confirm the row exists, online=true
docker exec stup-wristbandprinterservice-postgres-1 psql -U wristbands -d stup_wristband_db \
  -c "SELECT id, display_name, base_url, online, hidden, is_default FROM printers ORDER BY registered_at;"
```

Expected: `200`; a `printer-2` row with `online=t`. Optionally open `jobs.html` with the network tab on the SSE stream and re-run the curl to see a `printer` event arrive (the consumer/UI handling is Part 3, but the event should be on the wire).

- [ ] **Step 3:** No code change. Note any spec drift in the spec's amendments if it occurred.

---

## Self-Review

**Spec coverage (Part 2a slice):**
- Internal registration endpoint, API-key-secured, idempotent upsert, broadcasts `printer` event → Tasks 4–5. ✓
- DB-backed mutable registry; `register`/`markOffline`; routing map loaded from DB → Task 3. ✓
- `getDefault` redefinition per D5 (explicit default even if offline; else earliest online not hidden; else earliest not hidden) → Task 3. ✓
- Empty cluster → `NoPrintersAvailableException` → 503 → Tasks 1, 3, 4. ✓
- Cached executor + on-demand per-printer queue creation → Task 4. ✓
- `printer` named SSE event (producer) → Task 4 (`broadcastPrinter`), Task 5 (wired on register/deregister). Consumer is Part 3. ✓
- **Deferred to Part 2b (correctly NOT here):** worker self-registration runner + heartbeat, worker env vars, compose changes, and **removing `cluster.printers`** (config seeding stays in `init()` for now). ✓

**Placeholder scan:** No TBD/TODO; full code in every step. The helper method name `PrinterRepositorySeed` in the test is an intentional private helper (capitalized to read like a fixture); harmless.

**Type consistency:** `PrinterEvent(String,String,boolean,boolean,boolean,Instant)` used in `broadcastPrinter`, `snapshot`, and both tests; `register(String id, String displayName, String baseUrl)` and `markOffline(String)` and `ensureQueue(String)` and `broadcastPrinter(PrinterEvent)` and `snapshot(String)` signatures consistent across Tasks 3–5; the registry lifecycle method is `init()` everywhere (and `PrinterRegistrySeedTest` is updated from `seed()` → `init()`); repository query method names match their usage in `getDefault()`.

**Risk note for execution:** Task 3 changes registry construction semantics (empty config now allowed) and `getDefault` to hit the DB — run the FULL suite (Task 6 / and ideally after Task 3) to catch any `@SpringBootTest` context test that relied on the old behaviour, mirroring the Part 1 lesson where a narrow test run missed a broader break.

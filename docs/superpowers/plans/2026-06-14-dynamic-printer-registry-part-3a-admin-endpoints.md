# Dynamic Printer Registry — Part 3a: Printer Admin Endpoints (backend)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the backend an operator needs to manage printers from the UI — rename, soft-hide (offline-only), on-demand liveness test, set-default — plus an enriched `GET /printers`, each broadcasting the `printer` SSE event so the UI updates live.

**Architecture:** A new admin-cookie-protected `PrinterAdminController` under `/api/wristbands/printers/{id}/…` coordinates registry mutations + a `printer` SSE broadcast (mirrors `PrinterRegistrationController`). `PrinterRegistry` gains `rename`/`setHidden`/`setDefault`/`markOnline`/`snapshotAll`; the liveness probe reuses `WorkerClient` (new `isReachable`). `PrinterSummaryResponse` is enriched with `online`/`hidden`/`isDefault`/`lastSeenAt` so the UI (Part 3b) can render indicators and filter hidden. Two new exceptions map to 404/409.

**Tech Stack:** Java 21, Spring Boot 3.4.1 (web, security), PostgreSQL + Flyway, JUnit 5 + Testcontainers + MockRestServiceServer + MockMvc/Mockito.

**Spec:** `docs/superpowers/specs/2026-06-13-dynamic-printer-registry-design.md` (D6/D7/D8/D9, management+API slice of Phase 3). Depends on Part 2a (registry, `PrinterEvent`, `broadcastPrinter`, `snapshot`) — merged. Part 3b consumes these endpoints + the `printer` SSE event in the jobs UI.

---

## Context for the implementer (current state)

- `PrinterRegistry` (`cluster/`, `@Profile("!worker")`): `register`, `markOffline`, `getDefault` (D5), `get`, `all`, `snapshot(id)` → `PrinterEvent(id, displayName, online, hidden, isDefault, lastSeenAt)`. In-memory `byId` for routing; state in the `printers` table via `PrinterRepository`. The table has `online`, `hidden`, `is_default` (partial unique index `printers_one_default WHERE is_default`), `last_seen_at`, `registered_at`.
- `PrintQueueService.broadcastPrinter(PrinterEvent)` sends a named `printer` SSE event to all jobs-stream subscribers.
- `PrinterRegistrationController` (Part 2a) is the pattern to mirror: it calls `registry.X(...)` then `printQueueService.broadcastPrinter(registry.snapshot(id))`.
- `WorkerClient` (`cluster/`, `@Profile("!worker")`): RestClient-based, `print(baseUrl, jobId, zpl)`. The worker exposes `/actuator/health` (permitAll on the worker — no API key needed to GET it).
- `GET /api/wristbands/printers` lives in `WristbandController` and returns `List<PrinterSummaryResponse>` via `printerRegistry.all().stream().map(p -> new PrinterSummaryResponse(p.id(), p.displayName()))`. `PrinterSummaryResponse` is `record (String id, String displayName)`.
- Security: `/api/wristbands/**` requires auth; `ApiKeyAuthFilter` accepts the API key **or** the admin cookie. So new `/api/wristbands/printers/...` endpoints are automatically protected and reachable by the jobs UI's admin cookie — NO SecurityConfig change.
- `GlobalExceptionHandler` maps domain exceptions to HTTP; `errorResponse(HttpStatus, error, message)` helper exists. `UnknownPrinterException` → 400 (keep for routing; admin uses 404 — see Task 1).

## Decisions captured from the spec
- **D7 hide:** offline-only — hiding an online printer → **409**. A `register`/heartbeat or a successful `test` auto-clears `hidden`.
- **D8 test:** probe the worker's `/actuator/health`; reachable → `online=true` + `last_seen` refreshed + `hidden` cleared; unreachable → `online=false`. Returns `{ reachable, online }`. Connectivity probe only (not a print).
- **D9 set-default:** at most one default (DB partial unique index); setting clears others in one transaction; setting a **hidden** printer → **409**; hiding the current default clears its `is_default` (already in `setHidden`).
- **Listing:** `GET /printers` returns ALL printers (incl. hidden) enriched; the UI (Part 3b) excludes hidden from the *filter* but shows them in the *modal*.

---

## File Structure

**Create:**
- `src/main/java/com/stup/wristbandprinter/exception/PrinterNotFoundException.java` (→404)
- `src/main/java/com/stup/wristbandprinter/exception/PrinterStateConflictException.java` (→409)
- `src/main/java/com/stup/wristbandprinter/controller/PrinterAdminController.java`
- `src/main/java/com/stup/wristbandprinter/controller/dto/RenamePrinterRequest.java`
- `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryAdminTest.java`
- `src/test/java/com/stup/wristbandprinter/controller/PrinterAdminControllerTest.java`

**Modify:**
- `src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java` (404/409 handlers)
- `src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistry.java` (`rename`/`setHidden`/`setDefault`/`markOnline`/`snapshotAll`)
- `src/main/java/com/stup/wristbandprinter/cluster/WorkerClient.java` (`isReachable`)
- `src/test/java/com/stup/wristbandprinter/cluster/WorkerClientTest.java` (isReachable cases)
- `src/main/java/com/stup/wristbandprinter/domain/PrinterSummaryResponse.java` (enrich)
- `src/main/java/com/stup/wristbandprinter/controller/WristbandController.java` (map enriched `GET /printers`)
- `src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java` (update the printers-list assertion)

---

## Task 1: 404 / 409 exceptions

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/exception/PrinterNotFoundException.java`
- Create: `src/main/java/com/stup/wristbandprinter/exception/PrinterStateConflictException.java`
- Modify: `src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: Create the exceptions**

`PrinterNotFoundException.java`:
```java
package com.stup.wristbandprinter.exception;

/** An admin op referenced a printer id that does not exist. */
public class PrinterNotFoundException extends RuntimeException {
    public PrinterNotFoundException(String message) {
        super(message);
    }
}
```

`PrinterStateConflictException.java`:
```java
package com.stup.wristbandprinter.exception;

/** An admin op is invalid for the printer's current state (e.g. hide an online printer,
 *  set a hidden printer as default). */
public class PrinterStateConflictException extends RuntimeException {
    public PrinterStateConflictException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: Add handlers in `GlobalExceptionHandler`** (next to `handleUnknownPrinter`):

```java
    @ExceptionHandler(PrinterNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handlePrinterNotFound(PrinterNotFoundException ex) {
        log.warn("Printer not found: {}", ex.getMessage());
        return errorResponse(HttpStatus.NOT_FOUND, "Printer not found", ex.getMessage());
    }

    @ExceptionHandler(PrinterStateConflictException.class)
    public ResponseEntity<Map<String, Object>> handlePrinterStateConflict(PrinterStateConflictException ex) {
        log.warn("Printer state conflict: {}", ex.getMessage());
        return errorResponse(HttpStatus.CONFLICT, "Printer state conflict", ex.getMessage());
    }
```

- [ ] **Step 3: Compile** → `./mvnw -q compile` → BUILD SUCCESS.

- [ ] **Step 4: Commit**
```bash
git add src/main/java/com/stup/wristbandprinter/exception/PrinterNotFoundException.java \
        src/main/java/com/stup/wristbandprinter/exception/PrinterStateConflictException.java \
        src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java
git commit -m "feat(printers): add PrinterNotFound(404) + PrinterStateConflict(409) exceptions"
```

---

## Task 2: Registry admin mutations + `snapshotAll`

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistry.java`
- Test: `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryAdminTest.java`

- [ ] **Step 1: Write the failing test (Testcontainers)**

Create `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryAdminTest.java`:

```java
package com.stup.wristbandprinter.cluster;

import com.stup.wristbandprinter.exception.PrinterNotFoundException;
import com.stup.wristbandprinter.exception.PrinterStateConflictException;
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
class PrinterRegistryAdminTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PrinterRepository repo;

    private PrinterRegistry registry() {
        PrinterRegistry r = new PrinterRegistry(repo);
        r.init();
        return r;
    }

    @Test
    void rename_updatesNameInDbAndRouting() {
        PrinterRegistry r = registry();
        r.register("p1", "Old", "http://p1:8080");
        r.rename("p1", "New name");
        assertThat(repo.findById("p1")).get().extracting(PrinterEntity::getDisplayName).isEqualTo("New name");
        assertThat(r.get("p1").displayName()).isEqualTo("New name");
    }

    @Test
    void rename_unknown_throwsNotFound() {
        assertThatThrownBy(() -> registry().rename("nope", "X"))
            .isInstanceOf(PrinterNotFoundException.class);
    }

    @Test
    void setHidden_offlinePrinter_hides() {
        PrinterRegistry r = registry();
        r.register("p1", "P1", "http://p1:8080");
        r.markOffline("p1");
        r.setHidden("p1", true);
        assertThat(repo.findById("p1")).get().extracting(PrinterEntity::isHidden).isEqualTo(true);
    }

    @Test
    void setHidden_onlinePrinter_throwsConflict() {
        PrinterRegistry r = registry();
        r.register("p1", "P1", "http://p1:8080"); // online
        assertThatThrownBy(() -> r.setHidden("p1", true))
            .isInstanceOf(PrinterStateConflictException.class);
    }

    @Test
    void setHidden_clearsDefaultWhenHidingCurrentDefault() {
        PrinterRegistry r = registry();
        r.register("p1", "P1", "http://p1:8080");
        r.setDefault("p1");
        r.markOffline("p1");
        r.setHidden("p1", true);
        assertThat(repo.findById("p1")).get().extracting(PrinterEntity::isDefault).isEqualTo(false);
    }

    @Test
    void setDefault_clearsOthers_andRejectsHidden() {
        PrinterRegistry r = registry();
        r.register("a", "A", "http://a:8080");
        r.register("b", "B", "http://b:8080");
        r.setDefault("a");
        r.setDefault("b");
        assertThat(repo.findById("a")).get().extracting(PrinterEntity::isDefault).isEqualTo(false);
        assertThat(repo.findById("b")).get().extracting(PrinterEntity::isDefault).isEqualTo(true);

        r.markOffline("a");
        r.setHidden("a", true);
        assertThatThrownBy(() -> r.setDefault("a")).isInstanceOf(PrinterStateConflictException.class);
    }

    @Test
    void markOnline_setsOnlineAndClearsHidden() {
        PrinterRegistry r = registry();
        r.register("p1", "P1", "http://p1:8080");
        r.markOffline("p1");
        r.setHidden("p1", true);
        r.markOnline("p1");
        assertThat(repo.findById("p1")).get()
            .satisfies(e -> { assertThat(e.isOnline()).isTrue(); assertThat(e.isHidden()).isFalse(); });
    }

    @Test
    void snapshotAll_returnsAllOrderedByRegisteredAtThenId() {
        PrinterRegistry r = registry();
        r.register("a", "A", "http://a:8080");
        r.register("b", "B", "http://b:8080");
        assertThat(r.snapshotAll()).extracting(com.stup.wristbandprinter.cluster.dto.PrinterEvent::id)
            .containsExactly("a", "b");
    }
}
```

Run `./mvnw test -Dtest=PrinterRegistryAdminTest` → COMPILE FAILURE.

- [ ] **Step 2: Add the methods to `PrinterRegistry`**

Add these methods (and ensure `import com.stup.wristbandprinter.exception.PrinterNotFoundException;` + `PrinterStateConflictException;` + `import org.springframework.data.domain.Sort;` if not present):

```java
    /** Rename (operator). Updates the table and the routing map. 404 if unknown. */
    public void rename(String id, String displayName) {
        PrinterEntity e = printerRepository.findById(id)
            .orElseThrow(() -> new PrinterNotFoundException("Unknown printer id: " + id));
        e.setDisplayName(displayName);
        printerRepository.save(e);
        byId.computeIfPresent(id, (k, p) -> new Printer(id, displayName, p.baseUrl()));
    }

    /** Soft-hide an OFFLINE printer (D7). Hiding the current default also clears its default flag.
     *  404 if unknown; 409 if hiding while online. Unhide (hidden=false) is always allowed. */
    public void setHidden(String id, boolean hidden) {
        PrinterEntity e = printerRepository.findById(id)
            .orElseThrow(() -> new PrinterNotFoundException("Unknown printer id: " + id));
        if (hidden && e.isOnline()) {
            throw new PrinterStateConflictException("Cannot hide an online printer: " + id);
        }
        e.setHidden(hidden);
        if (hidden && e.isDefault()) {
            e.setDefault(false);
        }
        printerRepository.save(e);
    }

    /** Set the single default printer (D9). 404 if unknown; 409 if hidden. Clears others in one tx. */
    @org.springframework.transaction.annotation.Transactional
    public void setDefault(String id) {
        PrinterEntity target = printerRepository.findById(id)
            .orElseThrow(() -> new PrinterNotFoundException("Unknown printer id: " + id));
        if (target.isHidden()) {
            throw new PrinterStateConflictException("Cannot set a hidden printer as default: " + id);
        }
        printerRepository.findByIsDefaultTrue().ifPresent(current -> {
            if (!current.getId().equals(id)) {
                current.setDefault(false);
                printerRepository.save(current);
            }
        });
        target.setDefault(true);
        printerRepository.save(target);
    }

    /** Mark online (e.g. a successful liveness probe — D8): online=true, hidden cleared, last_seen refreshed. */
    public void markOnline(String id) {
        printerRepository.findById(id).ifPresent(e -> {
            e.setOnline(true);
            e.setHidden(false);
            e.setLastSeenAt(java.time.Instant.now());
            printerRepository.save(e);
        });
    }

    /** All printers as SSE-shaped views, ordered (registration time, then id) — for GET /printers. */
    public List<PrinterEvent> snapshotAll() {
        return printerRepository.findAll(Sort.by(Sort.Order.asc("registeredAt"), Sort.Order.asc("id"))).stream()
            .map(e -> new PrinterEvent(e.getId(), e.getDisplayName(), e.isOnline(), e.isHidden(),
                e.isDefault(), e.getLastSeenAt()))
            .toList();
    }
```

> Note on `@Transactional` on `setDefault`: it is called from the controller (a Spring proxy boundary), so the annotation applies — the clear-others + set-this happen atomically, satisfying the `printers_one_default` partial unique index. (Methods called only from `@PostConstruct` could not rely on this, but `setDefault` is request-driven.)

Run `./mvnw test -Dtest=PrinterRegistryAdminTest` → PASS.

- [ ] **Step 3: Commit**
```bash
git add src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistry.java \
        src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryAdminTest.java
git commit -m "feat(printers): registry rename/setHidden/setDefault/markOnline/snapshotAll"
```

---

## Task 3: `WorkerClient.isReachable` (liveness probe)

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/cluster/WorkerClient.java`
- Test: `src/test/java/com/stup/wristbandprinter/cluster/WorkerClientTest.java`

- [ ] **Step 1: Add failing tests** to `WorkerClientTest` (it already has `builder`/`server`/`client` set up in `@BeforeEach`):

```java
    @Test
    void isReachable_trueOn2xxHealth() {
        server.expect(requestTo("http://worker:8080/actuator/health"))
            .andExpect(method(org.springframework.http.HttpMethod.GET))
            .andRespond(withSuccess());
        assertThat(client.isReachable("http://worker:8080")).isTrue();
    }

    @Test
    void isReachable_falseOnError() {
        server.expect(requestTo("http://worker:8080/actuator/health"))
            .andRespond(request -> { throw new ResourceAccessException("down"); });
        assertThat(client.isReachable("http://worker:8080")).isFalse();
    }
```

(Add `import static org.assertj.core.api.Assertions.assertThat;` if missing.)

Run `./mvnw test -Dtest=WorkerClientTest` → COMPILE FAILURE.

- [ ] **Step 2: Add `isReachable` to `WorkerClient`**:

```java
    /** On-demand liveness probe (D8): GET the worker's health endpoint; true iff it responds 2xx. */
    public boolean isReachable(String baseUrl) {
        try {
            restClient.get()
                .uri(baseUrl + "/actuator/health")
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.debug("Liveness probe to {} failed: {}", baseUrl, e.getMessage());
            return false;
        }
    }
```

Run `./mvnw test -Dtest=WorkerClientTest` → PASS.

- [ ] **Step 3: Commit**
```bash
git add src/main/java/com/stup/wristbandprinter/cluster/WorkerClient.java \
        src/test/java/com/stup/wristbandprinter/cluster/WorkerClientTest.java
git commit -m "feat(printers): WorkerClient.isReachable liveness probe"
```

---

## Task 4: Enrich `GET /printers`

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/domain/PrinterSummaryResponse.java`
- Modify: `src/main/java/com/stup/wristbandprinter/controller/WristbandController.java`
- Modify: `src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java`

- [ ] **Step 1: Enrich the DTO**

```java
package com.stup.wristbandprinter.domain;

import java.time.Instant;

/** A printer as exposed to the UI (no internal base URL). */
public record PrinterSummaryResponse(String id, String displayName, boolean online,
                                     boolean hidden, boolean isDefault, Instant lastSeenAt) {}
```

- [ ] **Step 2: Map the enriched listing in `WristbandController.printers()`**

Change the method body to map from `snapshotAll()`:

```java
    @GetMapping("/printers")
    @Operation(summary = "List the printers this service can route to", tags = {"Printers & Gallery"})
    public ResponseEntity<List<PrinterSummaryResponse>> printers() {
        return ResponseEntity.ok(printerRegistry.snapshotAll().stream()
            .map(e -> new PrinterSummaryResponse(e.id(), e.displayName(), e.online(),
                e.hidden(), e.isDefault(), e.lastSeenAt()))
            .toList());
    }
```

- [ ] **Step 3: Update `WristbandControllerTest`**

Read the test; find the `GET /printers` test. Its mock of `printerRegistry` likely stubs `all()`. Re-stub `printerRegistry.snapshotAll()` to return a `List<PrinterEvent>` and assert the JSON now includes `online`/`hidden`/`isDefault`. Example shape (adapt to the test's existing style/mocking):

```java
when(printerRegistry.snapshotAll()).thenReturn(java.util.List.of(
    new com.stup.wristbandprinter.cluster.dto.PrinterEvent("printer-1", "Inkom", true, false, true, java.time.Instant.now())));
// ... perform GET /api/wristbands/printers ...
// assert jsonPath $[0].id == printer-1, $[0].displayName == Inkom, $[0].online == true, $[0].isDefault == true
```

If the existing test stubbed `printerRegistry.all()` for this case, remove that stub (the endpoint no longer calls `all()`).

Run `./mvnw test -Dtest=WristbandControllerTest` → PASS.

- [ ] **Step 4: Commit**
```bash
git add src/main/java/com/stup/wristbandprinter/domain/PrinterSummaryResponse.java \
        src/main/java/com/stup/wristbandprinter/controller/WristbandController.java \
        src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java
git commit -m "feat(printers): enrich GET /printers with online/hidden/isDefault/lastSeenAt"
```

---

## Task 5: `PrinterAdminController` (rename / hide / test / default)

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/controller/dto/RenamePrinterRequest.java`
- Create: `src/main/java/com/stup/wristbandprinter/controller/PrinterAdminController.java`
- Test: `src/test/java/com/stup/wristbandprinter/controller/PrinterAdminControllerTest.java`

- [ ] **Step 1: Create the rename request DTO**

```java
package com.stup.wristbandprinter.controller.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of PATCH /api/wristbands/printers/{id} — operator rename. */
public record RenamePrinterRequest(@NotBlank String displayName) {}
```

- [ ] **Step 2: Write the failing controller test (Mockito unit)**

Create `src/test/java/com/stup/wristbandprinter/controller/PrinterAdminControllerTest.java`:

```java
package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.cluster.Printer;
import com.stup.wristbandprinter.cluster.PrinterRegistry;
import com.stup.wristbandprinter.cluster.WorkerClient;
import com.stup.wristbandprinter.cluster.dto.PrinterEvent;
import com.stup.wristbandprinter.controller.dto.RenamePrinterRequest;
import com.stup.wristbandprinter.service.PrintQueueService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PrinterAdminControllerTest {

    private final PrinterRegistry registry = mock(PrinterRegistry.class);
    private final PrintQueueService queue = mock(PrintQueueService.class);
    private final WorkerClient workerClient = mock(WorkerClient.class);
    private final PrinterAdminController controller =
        new PrinterAdminController(registry, queue, workerClient);

    private void stubSnapshot(String id, boolean online) {
        when(registry.snapshot(id)).thenReturn(new PrinterEvent(id, "N", online, false, false, Instant.now()));
    }

    @Test
    void rename_renamesAndBroadcasts() {
        stubSnapshot("p1", true);
        controller.rename("p1", new RenamePrinterRequest("New"));
        verify(registry).rename("p1", "New");
        verify(queue).broadcastPrinter(any());
    }

    @Test
    void hide_hidesAndBroadcasts() {
        stubSnapshot("p1", false);
        controller.hide("p1");
        verify(registry).setHidden("p1", true);
        verify(queue).broadcastPrinter(any());
    }

    @Test
    void setDefault_setsAndBroadcasts() {
        stubSnapshot("p1", true);
        controller.setDefault("p1");
        verify(registry).setDefault("p1");
        verify(queue).broadcastPrinter(any());
    }

    @Test
    void test_reachable_marksOnlineAndReturnsReachable() {
        when(registry.get("p1")).thenReturn(new Printer("p1", "N", "http://p1:8080"));
        when(workerClient.isReachable("http://p1:8080")).thenReturn(true);
        stubSnapshot("p1", true);

        ResponseEntityLike body = new ResponseEntityLike(controller.test("p1").getBody());
        verify(registry).markOnline("p1");
        verify(queue).broadcastPrinter(any());
        assertThat(body.reachable()).isTrue();
    }

    @Test
    void test_unreachable_marksOfflineAndReturnsNotReachable() {
        when(registry.get("p1")).thenReturn(new Printer("p1", "N", "http://p1:8080"));
        when(workerClient.isReachable("http://p1:8080")).thenReturn(false);
        stubSnapshot("p1", false);

        controller.test("p1");
        verify(registry).markOffline("p1");
        verify(queue).broadcastPrinter(any());
    }

    /** tiny helper to read the Map body returned by test(). */
    private record ResponseEntityLike(Object raw) {
        @SuppressWarnings("unchecked")
        boolean reachable() { return (boolean) ((Map<String, Object>) raw).get("reachable"); }
    }
}
```

Run `./mvnw test -Dtest=PrinterAdminControllerTest` → COMPILE FAILURE.

- [ ] **Step 3: Create the controller**

```java
package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.cluster.Printer;
import com.stup.wristbandprinter.cluster.PrinterRegistry;
import com.stup.wristbandprinter.cluster.WorkerClient;
import com.stup.wristbandprinter.cluster.dto.PrinterEvent;
import com.stup.wristbandprinter.controller.dto.RenamePrinterRequest;
import com.stup.wristbandprinter.service.PrintQueueService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Operator (admin-cookie) printer management: rename, hide, on-demand test, set-default.
 *  Each mutation broadcasts a `printer` SSE event so connected jobs UIs update live (D6). */
@Profile("!worker")
@RestController
@RequestMapping("/api/wristbands/printers")
public class PrinterAdminController {

    private final PrinterRegistry registry;
    private final PrintQueueService printQueueService;
    private final WorkerClient workerClient;

    public PrinterAdminController(PrinterRegistry registry, PrintQueueService printQueueService,
                                 WorkerClient workerClient) {
        this.registry = registry;
        this.printQueueService = printQueueService;
        this.workerClient = workerClient;
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> rename(@PathVariable String id, @Valid @RequestBody RenamePrinterRequest req) {
        registry.rename(id, req.displayName());
        broadcast(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/hide")
    public ResponseEntity<Void> hide(@PathVariable String id) {
        registry.setHidden(id, true);
        broadcast(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/default")
    public ResponseEntity<Void> setDefault(@PathVariable String id) {
        registry.setDefault(id);
        broadcast(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String id) {
        Printer printer = registry.get(id);   // UnknownPrinterException -> 400 if absent in routing map
        boolean reachable = workerClient.isReachable(printer.baseUrl());
        if (reachable) {
            registry.markOnline(id);
        } else {
            registry.markOffline(id);
        }
        broadcast(id);
        return ResponseEntity.ok(Map.of("reachable", reachable, "online", reachable));
    }

    private void broadcast(String id) {
        PrinterEvent event = registry.snapshot(id);
        if (event != null) {
            printQueueService.broadcastPrinter(event);
        }
    }
}
```

Run `./mvnw test -Dtest=PrinterAdminControllerTest` → PASS.

- [ ] **Step 4: Commit**
```bash
git add src/main/java/com/stup/wristbandprinter/controller/dto/RenamePrinterRequest.java \
        src/main/java/com/stup/wristbandprinter/controller/PrinterAdminController.java \
        src/test/java/com/stup/wristbandprinter/controller/PrinterAdminControllerTest.java
git commit -m "feat(printers): admin endpoints — rename/hide/test/set-default with SSE broadcast"
```

---

## Task 6: Full-suite + manual smoke

**Files:** none (verification).

- [ ] **Step 1: Full suite** → `./mvnw test` → BUILD SUCCESS.

- [ ] **Step 2: Manual smoke** (local cluster up, admin cookie or API key):

```bash
# rename
curl -fsS -X PATCH http://localhost:8080/api/wristbands/printers/printer-1 \
  -H 'X-API-Key: local-dev-key' -H 'Content-Type: application/json' \
  -d '{"displayName":"Inkom links"}' -w '\n%{http_code}\n'
# test (liveness probe — worker-1 is up)
curl -fsS -X POST http://localhost:8080/api/wristbands/printers/printer-1/test \
  -H 'X-API-Key: local-dev-key' -w '\n%{http_code}\n'
# set default
curl -fsS -X POST http://localhost:8080/api/wristbands/printers/printer-1/default \
  -H 'X-API-Key: local-dev-key' -w '\n%{http_code}\n'
# hide an ONLINE printer -> expect 409
curl -s -o /dev/null -X POST http://localhost:8080/api/wristbands/printers/printer-1/hide \
  -H 'X-API-Key: local-dev-key' -w '%{http_code}\n'
# verify
docker exec stup-wristbandprinterservice-postgres-1 psql -U wristbands -d stup_wristband_db \
  -c "SELECT id, display_name, online, hidden, is_default FROM printers;"
curl -fsS http://localhost:8080/api/wristbands/printers -H 'X-API-Key: local-dev-key'
```

Expected: rename → 200 + name changed; test → 200 `{"reachable":true,"online":true}`; default → 200 + `is_default=t`; hide-while-online → **409**; `GET /printers` shows the enriched fields.

- [ ] **Step 3:** No code change.

---

## Deferred to Part 3b (frontend — NOT here)

- `jobs.js` `printersById` map (seed from `GET /printers`, upsert on the `printer` SSE event), render the printer-name column from it, exclude hidden from the filter, online/offline + default indicators.
- The "Manage printers" modal (Menu dropdown) with rename / Test / Hide / Set-default actions, styled with `app.css`.
- Verified via the preview tooling + manual clicks (no JS test harness).

## Self-Review

**Spec coverage:** PATCH rename (D6), POST hide offline-only→409 + clears default-if-default (D7), POST test liveness→online/offline + clear-hidden-on-success returning `{reachable,online}` (D8), POST default single+409-if-hidden (D9), all broadcasting `printer` (D6); `GET /printers` enriched. ✓ Frontend consumer deferred to 3b.

**Placeholder scan:** complete code/edits each step; the `WristbandControllerTest` update says "adapt to the test's existing style" but gives the concrete stub/assertions — the implementer must read that one test to match its mocking setup. ✓

**Type consistency:** `snapshotAll(): List<PrinterEvent>` used by both `WristbandController` and the admin test; `rename/setHidden/setDefault/markOnline/markOffline/get/snapshot` signatures used identically in registry, controller, and tests; `PrinterSummaryResponse` enriched shape matches the controller mapping; `WorkerClient.isReachable(String): boolean` used by the admin controller `test`; new exceptions map 404/409. ✓

**Auth:** endpoints under `/api/wristbands/**` inherit API-key-or-admin-cookie auth (no SecurityConfig change); the jobs UI uses the cookie. ✓

**Risk:** the `test` endpoint resolves base-url via `registry.get(id)` which throws `UnknownPrinterException` (400) for an id not in the routing map — acceptable, though for consistency a future tweak could map admin "unknown id" uniformly to 404; left as-is to avoid changing the routing exception's existing 400 contract.

# Dynamic Printer Registry — Part 2c: Remove `cluster.printers` config seeding

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the DB-backed registry the *sole* source of printers — remove the `cluster.printers` config and its startup seeding now that workers self-register (Part 2b). Printers come entirely from registration.

**Architecture:** Delete `PrinterRegistryProperties`; `PrinterRegistry` takes only `PrinterRepository`, and `init()` just loads existing rows from the `printers` table into the routing map (no config seed). Printers appear via `register()` (worker self-registration). Tests that relied on the config-seeded printer register one explicitly through the registry instead. This is sequenced so every commit stays green: the integration test is made config-independent **first**, then the config is removed.

**Tech Stack:** Java 21, Spring Boot 3.4.1, PostgreSQL + Flyway, JUnit 5 + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-06-13-dynamic-printer-registry-design.md` (D2 — "Remove `cluster.printers` config as the source of truth"). Depends on Part 2a (registration endpoint + DB-backed registry) and Part 2b (worker self-registration), both merged.

---

## Context for the implementer (current state)

- `PrinterRegistry` (`cluster/`, `@Profile("!worker")`) currently: constructor `(PrinterRegistryProperties props, PrinterRepository printerRepository)`; validates duplicate ids from config; `@PostConstruct init()` upserts each `cluster.printers` entry into the `printers` table **then** loads all rows into the in-memory `byId` routing map; `register()`/`markOffline()`/`getDefault()` (D5)/`get()`/`all()`/`snapshot()` work off the DB + `byId`.
- `PrinterRegistryProperties` (`cluster/`) binds `cluster.printers[*]` (`@ConfigurationProperties(prefix="cluster")`, `@Profile("!worker")`).
- `application.yml` has a `cluster:` block (printer-1 with a sentinel base-url); `application-local.yml` has a `cluster:` block (printer-1 → http://localhost:8089). Both compose files set `cluster.printers` via `SPRING_APPLICATION_JSON`.
- Tests constructing the registry: `PrinterRegistryStateTest` (`new PrinterRegistry(new PrinterRegistryProperties(), repo)` then `.init()`), `PrinterRegistryTest` (config-validation unit tests: duplicate-id throws, empty-config allowed). `PrinterRegistrySeedTest` (verifies `init()` upserts config into the table via a mock repo).
- `WristbandIntegrationTest` (`@SpringBootTest(RANDOM_PORT)` + Testcontainers): a static fake worker `HttpServer` on `localhost:<random>` serves `/api/internal/print`; a `@DynamicPropertySource` sets `security.api-key` plus `cluster.printers[0]=printer-1` and `cluster.printers[1]=printer-2`, both base-url → the fake worker. Test methods POST prints and await DONE / inspect jobs. **This is the only full-context test that depends on a configured printer.** (`WristbandControllerTest`/`PermitWristbandControllerTest` are slice tests with mocked services — unaffected.)
- Worker self-registration (Part 2b) is live: in the local cluster, `worker-1` registers itself as `printer-1` on startup.

## Worker self-registration recap (why this is safe locally)
Removing config seeding means a fresh management starts with **zero** printers until a worker registers. In the local cluster that's fine — `worker-1` self-registers `printer-1`. The existing DB also already holds rows, which `init()` loads. **Production caveat:** prod management is HTTPS-only (8443); prod workers cannot self-register until the worker→management transport is resolved (internal HTTP connector or a cert-trusting client). Prod is not live; this plan documents that prerequisite and does NOT wire prod workers to HTTPS (see Task 3 + "Deferred").

---

## File Structure

**Delete:**
- `src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistryProperties.java`
- `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryTest.java` (config-validation unit tests — obsolete)
- `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistrySeedTest.java` (config-seeding test — obsolete)

**Modify:**
- `src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistry.java` (drop props; `init()` loads from DB only)
- `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryStateTest.java` (`new PrinterRegistry(repo)`; add an init-loads-from-DB test)
- `src/test/java/com/stup/wristbandprinter/WristbandIntegrationTest.java` (register printers via the registry, not config)
- `src/main/resources/application.yml` + `src/main/resources/application-local.yml` (remove `cluster:`)
- `docker-compose.local-cluster.yml` (remove management's `cluster.printers`)
- `docker-compose.prod.yml` + `docs/production-deployment.md` (remove `cluster.printers`; document self-registration + the TLS prerequisite)

---

## Task 1: Make `WristbandIntegrationTest` config-independent (green with config still present)

Do this BEFORE removing config so the build never goes red. The test stops relying on `cluster.printers` and instead registers its two printers through the autowired `PrinterRegistry`, pointing base-url at its fake worker.

**Files:**
- Modify: `src/test/java/com/stup/wristbandprinter/WristbandIntegrationTest.java`

- [ ] **Step 1: Read the whole test** to learn its fields, the static `workerServer` (fake worker `HttpServer`), the `@DynamicPropertySource`, and the test methods (which printers they exercise — at least `printer-1`; confirm whether any method targets `printer-2`).

- [ ] **Step 2: Replace config-based printers with registry registration**

In the `@DynamicPropertySource` method, REMOVE the four `cluster.printers[0|1].*` lines, keeping the api-key line:

```java
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("security.api-key", () -> API_KEY);
    }
```

Autowire the registry (add a field next to the other `@Autowired` fields):

```java
    @Autowired
    private com.stup.wristbandprinter.cluster.PrinterRegistry printerRegistry;
```

Add a `@BeforeEach` that registers the two printers at the fake worker (idempotent upsert — safe to run before each test). Place it with the test methods:

```java
    @org.junit.jupiter.api.BeforeEach
    void registerPrinters() {
        String workerUrl = "http://localhost:" + workerServer.getAddress().getPort();
        printerRegistry.register("printer-1", "Integration printer", workerUrl);
        printerRegistry.register("printer-2", "Second printer", workerUrl);
    }
```

Leave every test method and assertion unchanged. (Routing still works: `enqueue` calls `ensureQueue(printer.id())`, so the per-printer queue/thread is created on first use; `get("printer-1")`/`getDefault()` resolve from the now-registered `byId`/DB.)

- [ ] **Step 3: Run it (config still present, so this proves the new path works independently)**

Run: `./mvnw test -Dtest=WristbandIntegrationTest`
Expected: PASS. (Config seeding still happens at startup, but the test no longer depends on it — it registers explicitly.)

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/stup/wristbandprinter/WristbandIntegrationTest.java
git commit -m "test(printers): register integration-test printers via registry, not config"
```

---

## Task 2: Remove `cluster.printers` config seeding

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistry.java`
- Delete: `src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistryProperties.java`
- Delete: `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryTest.java`
- Delete: `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistrySeedTest.java`
- Modify: `src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryStateTest.java`
- Modify: `src/main/resources/application.yml`, `src/main/resources/application-local.yml`

- [ ] **Step 1: Update `PrinterRegistryStateTest` first (it constructs the registry)**

Its `registry()` helper currently does `new PrinterRegistry(new PrinterRegistryProperties(), repo)`. Change it to the new single-arg constructor:

```java
    private PrinterRegistry registry() {
        PrinterRegistry r = new PrinterRegistry(repo);
        r.init();
        return r;
    }
```

Also add a test that `init()` loads existing DB rows into the routing map (replacing the deleted seed test's coverage):

```java
    @Test
    void init_loadsExistingPrintersFromDb() {
        repo.save(new PrinterEntity("printer-1", "Inkom", "http://printer-1:8080"));
        PrinterRegistry r = new PrinterRegistry(repo);
        r.init();
        assertThat(r.get("printer-1").displayName()).isEqualTo("Inkom");
    }
```

- [ ] **Step 2: Rewrite `PrinterRegistry`** — remove the config dependency and the seed loop:

```java
package com.stup.wristbandprinter.cluster;

import com.stup.wristbandprinter.cluster.dto.PrinterEvent;
import com.stup.wristbandprinter.exception.NoPrintersAvailableException;
import com.stup.wristbandprinter.exception.UnknownPrinterException;
import com.stup.wristbandprinter.persistence.PrinterEntity;
import com.stup.wristbandprinter.persistence.PrinterRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routing view over the printers. The in-memory {@code byId} map (id -> routing info: display name +
 * base URL) is loaded from the printers table at startup and mutated by {@link #register}. Printer
 * state (online/hidden/default) lives only in the table and is queried on demand. Printers are
 * created exclusively by worker self-registration (no static config).
 */
@Component
@Profile("!worker")
public class PrinterRegistry {

    private final Map<String, Printer> byId = new ConcurrentHashMap<>();
    private final PrinterRepository printerRepository;

    public PrinterRegistry(PrinterRepository printerRepository) {
        this.printerRepository = printerRepository;
    }

    /** Load every persisted printer into the routing map at startup (Flyway has already run). */
    @PostConstruct
    public void init() {
        for (PrinterEntity e : printerRepository.findAll()) {
            byId.put(e.getId(), new Printer(e.getId(), e.getDisplayName(), e.getBaseUrl()));
        }
    }

    public void register(String id, String displayName, String baseUrl) {
        Optional<PrinterEntity> existing = printerRepository.findById(id);
        PrinterEntity entity = existing.orElseGet(() -> new PrinterEntity(id, displayName, baseUrl));
        entity.setBaseUrl(baseUrl);
        entity.setOnline(true);
        entity.setHidden(false);   // D7: coming back online auto-unhides (an operator hide only sticks while offline)
        entity.setLastSeenAt(Instant.now());
        printerRepository.save(entity);
        byId.put(id, new Printer(id, entity.getDisplayName(), baseUrl));
    }

    public void markOffline(String id) {
        printerRepository.findById(id).ifPresent(e -> {
            e.setOnline(false);
            printerRepository.save(e);
        });
    }

    public Printer getDefault() {
        PrinterEntity chosen = printerRepository.findByIsDefaultTrue()
            .filter(e -> !e.isHidden())
            .or(printerRepository::findFirstByOnlineTrueAndHiddenFalseOrderByRegisteredAtAscIdAsc)
            .or(printerRepository::findFirstByHiddenFalseOrderByRegisteredAtAscIdAsc)
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

    /** All printers, in a stable order (registration time, then id) for deterministic UI listing. */
    public List<Printer> all() {
        return printerRepository.findAll(Sort.by(Sort.Order.asc("registeredAt"), Sort.Order.asc("id"))).stream()
            .map(e -> new Printer(e.getId(), e.getDisplayName(), e.getBaseUrl()))
            .toList();
    }

    public PrinterEvent snapshot(String id) {
        return printerRepository.findById(id)
            .map(e -> new PrinterEvent(e.getId(), e.getDisplayName(), e.isOnline(), e.isHidden(),
                e.isDefault(), e.getLastSeenAt()))
            .orElse(null);
    }
}
```

- [ ] **Step 3: Delete the obsolete files**

```bash
git rm src/main/java/com/stup/wristbandprinter/cluster/PrinterRegistryProperties.java \
       src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistryTest.java \
       src/test/java/com/stup/wristbandprinter/cluster/PrinterRegistrySeedTest.java
```

- [ ] **Step 4: Remove the `cluster:` blocks from config**

In `src/main/resources/application.yml`, delete the entire `cluster:` block (the `cluster:` key, its comment lines, and the `printers:` list under it). In `src/main/resources/application-local.yml`, delete its `cluster:` block likewise. Leave all other config intact.

- [ ] **Step 5: Compile + run the affected tests**

Run: `./mvnw test -Dtest=PrinterRegistryStateTest,WristbandIntegrationTest,PrintQueueServiceTest,SecurityConfigTest`
Expected: PASS. (`PrinterRegistryStateTest` uses the new constructor; `WristbandIntegrationTest` registers via the registry from Task 1; nothing reads `cluster.printers` anymore.)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(printers): remove cluster.printers config; registry is DB/registration-only"
```

---

## Task 3: Compose + docs

**Files:**
- Modify: `docker-compose.local-cluster.yml`
- Modify: `docker-compose.prod.yml`
- Modify: `docs/production-deployment.md`

- [ ] **Step 1: Local cluster — drop management's now-dead `cluster.printers`**

In `docker-compose.local-cluster.yml`, in the `management` service `environment`, remove the line that sets `SPRING_APPLICATION_JSON={"cluster":{"printers":[...]}}` (and its preceding comment). `worker-1` self-registers `printer-1`, so management needs no seed. Leave everything else (DB url, api key) intact. Validate: `docker compose -f docker-compose.local-cluster.yml config -q`.

- [ ] **Step 2: Prod compose — remove dead config + document the self-registration prerequisite**

In `docker-compose.prod.yml`, in `management.environment`, remove the `SPRING_APPLICATION_JSON` `cluster.printers` block (the code no longer reads it). Replace it with a comment block documenting that printers now self-register, and that **each `printer-worker-N` must be given** `WORKER_ID`, `WORKER_DISPLAY_NAME`, `WORKER_BASE_URL`, `WORKER_MANAGEMENT_BASE_URL` — AND that this requires resolving worker→management transport first (management is HTTPS-only on 8443; either add an internal HTTP connector for `/api/internal/**` or configure the worker RestClient to trust the internal cert). Do NOT add live `WORKER_MANAGEMENT_BASE_URL=https://...` worker env yet — that would fail TLS. Example comment to add in place of the removed block:

```yaml
      # Printers self-register (no static registry). Each printer-worker below must set:
      #   WORKER_ID, WORKER_DISPLAY_NAME, WORKER_BASE_URL, WORKER_MANAGEMENT_BASE_URL
      # PREREQUISITE (not yet wired): worker -> management is HTTPS-only here; before
      # enabling prod self-registration, add an internal HTTP connector for /api/internal/**
      # on management OR make the worker RestClient trust the internal cert. See
      # docs/production-deployment.md.
```

Validate the file parses: `docker compose -f docker-compose.prod.yml config -q` (provide any required env via a throwaway `--env-file` or inline as the existing prod docs show; if that's impractical, re-read and confirm YAML correctness instead).

- [ ] **Step 3: Update `docs/production-deployment.md`**

Find the section describing the printer registry / `SPRING_APPLICATION_JSON` and replace it with: printers now self-register (workers carry `WORKER_*` env); management holds no static list; and the **TLS prerequisite** note from Step 2. Keep it concise and consistent with the doc's style.

- [ ] **Step 4: Commit**

```bash
git add docker-compose.local-cluster.yml docker-compose.prod.yml docs/production-deployment.md
git commit -m "chore(compose,docs): drop cluster.printers; document worker self-registration + prod TLS prereq"
```

---

## Task 4: Full-suite + manual smoke (fresh volume)

**Files:** none (verification).

- [ ] **Step 1: Full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS. Watch for any remaining reference to `cluster.printers` / `PrinterRegistryProperties` (there should be none): `grep -rn "cluster.printers\|PrinterRegistryProperties" src/` must return nothing.

- [ ] **Step 2: Manual smoke — empty management + self-registration on a FRESH volume**

The point is to prove management no longer needs config: start from an empty DB so the only way `printer-1` appears is the worker registering itself.

```bash
docker compose -f docker-compose.local-cluster.yml down -v
docker compose -f docker-compose.local-cluster.yml up --build -d
# wait for management health, then (worker self-registers within the heartbeat):
docker exec stup-wristbandprinterservice-postgres-1 psql -U wristbands -d stup_wristband_db \
  -c "SELECT id, display_name, online FROM printers;"
```

Expected: `printer-1` exists with `online = t` — created purely by `worker-1` self-registering against a management that had **no** `cluster.printers`. (If you submit a crew print with no `printerId`, it routes to `printer-1` via the D5 default.)

- [ ] **Step 3:** No code change.

---

## Deferred (NOT in this plan)

- **Production worker→management transport.** Prod management is HTTPS-only (8443). Before prod can use self-registration, add an internal HTTP connector for `/api/internal/**` on management, or configure the worker RestClient to trust the internal cert, then set the prod workers' `WORKER_*` env. Documented in Task 3; implement at prod stand-up (prod is not live).

## Self-Review

**Spec coverage (D2):** `cluster.printers` config + seeding removed from code (`PrinterRegistryProperties` deleted, `PrinterRegistry` is `(PrinterRepository)` + DB-only `init()`), config files, and compose; registry is registration/DB-only. ✓

**Green-at-every-commit:** Task 1 makes the integration test config-independent while config still exists (green); Task 2 removes config (integration test already independent; registry-construction tests updated/deleted) (green); Task 3 is compose/docs only. ✓

**Placeholder scan:** complete code/edits in every step; the prod compose comment is intentional documentation, not a placeholder (the prod *wiring* is explicitly deferred with rationale). ✓

**Type consistency:** new `PrinterRegistry(PrinterRepository)` constructor used in `PrinterRegistryStateTest` and as the Spring bean; `register/markOffline/getDefault/get/all/snapshot` signatures unchanged from Part 2a (so `PrintQueueService`, `PrinterRegistrationController`, `WristbandController` still compile); the D5 queries keep their Part-2a `…RegisteredAtAscIdAsc` names. ✓

**Risk:** removing config means `getDefault()` on a truly empty cluster throws `NoPrintersAvailableException` (503) — correct per D5, and exercised by `PrinterRegistryStateTest.getDefault_emptyCluster_throws`. Confirm no management `@SpringBootTest` other than `WristbandIntegrationTest` relies on a default printer (verified during planning: it's the only one).

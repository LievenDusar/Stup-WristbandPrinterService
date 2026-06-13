# Dynamic Printer Registry — Part 2b: Worker Self-Registration

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make each printer-worker register itself with management on startup, re-assert via a heartbeat, and deregister on graceful shutdown — so a printer comes online automatically when its container starts.

**Architecture:** A `@Profile("worker")` `ManagementClient` (RestClient, X-API-Key) POSTs to the management endpoint built in Part 2a (`/api/internal/printers/register` and `/{id}/deregister`). A `WorkerRegistrationRunner` calls `register()` on startup and on a `@Scheduled` heartbeat, and `deregister()` from `@PreDestroy`. Worker identity/address come from `worker.*` config (env vars). This is **additive**: `cluster.printers` config seeding (from Parts 1/2a) stays, so management still works; once a worker registers, its printer flips `online=true`. Removing `cluster.printers` and the prod HTTPS wiring are explicitly deferred (see "Deferred").

**Tech Stack:** Java 21, Spring Boot 3.4.1 (web, scheduling), RestClient + MockRestServiceServer, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-06-13-dynamic-printer-registry-design.md` (Phase 2, worker slice — D3). Part 2a (merged) built the management endpoint, DB-backed registry, dynamic queues, and the `printer` SSE event.

---

## Context for the implementer (current state)

- The worker is a thin `@Profile("worker")` service (no DB/UI). Its beans live in `com.stup.wristbandprinter.worker`: `WorkerPrintController` (`POST /api/internal/print`), `WorkerSecurityConfig`, `WorkerApiKeyFilter`. `application-worker.yml` excludes JPA/Flyway autoconfig. It already reads `security.api-key` and `printer.*` (PRINTER_HOST/PRINTER_PORT).
- Management (Part 2a, merged) exposes `POST /api/internal/printers/register` (body `{id, displayName, baseUrl}`) and `POST /api/internal/printers/{id}/deregister`, both API-key protected. `RegisterPrinterRequest` is `record (@NotBlank String id, @NotBlank String displayName, @NotBlank String baseUrl)` in `com.stup.wristbandprinter.cluster.dto` (a plain record — the worker may reuse it as the request body).
- HTTP client pattern (mirror `WorkerClient`): inject `RestClient.Builder`, `restClient.post().uri(url).header("X-API-Key", apiKey).contentType(APPLICATION_JSON).body(dto).retrieve().toBodilessEntity()`, catching `RestClientException`.
- Test pattern for an outbound RestClient (mirror `WorkerClientTest`): `RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();` then construct the client with `builder`.
- `WorkerProfileContextTest` is a `@SpringBootTest @ActiveProfiles("worker")` that asserts which beans load in the worker context.
- Spring Boot autoconfigures `RestClient.Builder` (spring-web present in the worker).
- `@ConfigurationPropertiesScan` is on the main application class; a `@ConfigurationProperties` class with `@Profile("worker")` is only instantiated under the worker profile (mirrors `PrinterRegistryProperties` which uses `@Profile("!worker")`).

## Env var / config naming (decision)

Use a single `worker.*` config prefix (cleaner than the spec's tentative `PRINTER_ID`/`MANAGEMENT_BASE_URL` mix):

| Property | Env var | Meaning |
|---|---|---|
| `worker.id` | `WORKER_ID` | this printer's public id (e.g. `printer-1`) |
| `worker.display-name` | `WORKER_DISPLAY_NAME` | human label (used only on first registration) |
| `worker.base-url` | `WORKER_BASE_URL` | this worker's in-network address (e.g. `http://worker-1:8080`) |
| `worker.management-base-url` | `WORKER_MANAGEMENT_BASE_URL` | where to reach management (e.g. `http://management:8080`) |
| `worker.heartbeat-millis` | `WORKER_HEARTBEAT_MILLIS` | heartbeat interval (default `30000`) |

**Resilience rule:** if `worker.id` or `worker.management-base-url` is blank, registration is skipped with a warning (don't crash). This keeps `WorkerProfileContextTest` (which sets none of them) clean and tolerates incomplete config.

---

## File Structure

**Create:**
- `src/main/java/com/stup/wristbandprinter/worker/WorkerRegistrationProperties.java`
- `src/main/java/com/stup/wristbandprinter/worker/ManagementClient.java`
- `src/main/java/com/stup/wristbandprinter/worker/WorkerRegistrationRunner.java`
- `src/main/java/com/stup/wristbandprinter/worker/WorkerSchedulingConfig.java`
- `src/test/java/com/stup/wristbandprinter/worker/ManagementClientTest.java`
- `src/test/java/com/stup/wristbandprinter/worker/WorkerRegistrationRunnerTest.java`

**Modify:**
- `src/main/resources/application-worker.yml` (heartbeat default + a comment listing the worker.* env vars)
- `src/test/java/com/stup/wristbandprinter/worker/WorkerProfileContextTest.java` (assert the new worker beans load)
- `docker-compose.local-cluster.yml` (worker-1 env vars)

---

## Task 1: `WorkerRegistrationProperties` + config

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/worker/WorkerRegistrationProperties.java`
- Modify: `src/main/resources/application-worker.yml`

- [ ] **Step 1: Create the properties class**

```java
package com.stup.wristbandprinter.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

/** This worker's self-registration identity + where to reach management. Worker-only. */
@ConfigurationProperties(prefix = "worker")
@Profile("worker")
public class WorkerRegistrationProperties {

    private String id;
    private String displayName;
    private String baseUrl;
    private String managementBaseUrl;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getManagementBaseUrl() { return managementBaseUrl; }
    public void setManagementBaseUrl(String managementBaseUrl) { this.managementBaseUrl = managementBaseUrl; }
}
```

- [ ] **Step 2: Add the heartbeat default + env-var doc to `application-worker.yml`**

Append to `src/main/resources/application-worker.yml`:

```yaml

# Self-registration: this worker announces itself to management on startup + heartbeat.
# id / display-name / base-url / management-base-url are per-worker (set via env:
# WORKER_ID, WORKER_DISPLAY_NAME, WORKER_BASE_URL, WORKER_MANAGEMENT_BASE_URL).
worker:
  heartbeat-millis: 30000
```

- [ ] **Step 3: Compile**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/worker/WorkerRegistrationProperties.java \
        src/main/resources/application-worker.yml
git commit -m "feat(worker): add worker self-registration config properties"
```

---

## Task 2: `ManagementClient` (worker → management)

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/worker/ManagementClient.java`
- Test: `src/test/java/com/stup/wristbandprinter/worker/ManagementClientTest.java`

- [ ] **Step 1: Write the failing test (mirror `WorkerClientTest`)**

Create `src/test/java/com/stup/wristbandprinter/worker/ManagementClientTest.java`:

```java
package com.stup.wristbandprinter.worker;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ManagementClientTest {

    private ManagementClient client(WorkerRegistrationProperties props, MockRestServiceServer[] out) {
        RestClient.Builder builder = RestClient.builder();
        out[0] = MockRestServiceServer.bindTo(builder).build();
        return new ManagementClient("test-key", builder, props);
    }

    private static WorkerRegistrationProperties props(String id, String mgmt) {
        WorkerRegistrationProperties p = new WorkerRegistrationProperties();
        p.setId(id);
        p.setDisplayName("Inkom");
        p.setBaseUrl("http://worker-1:8080");
        p.setManagementBaseUrl(mgmt);
        return p;
    }

    @Test
    void register_postsToManagementWithApiKeyAndBody() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        ManagementClient client = client(props("printer-1", "http://management:8080"), server);
        server[0].expect(requestTo("http://management:8080/api/internal/printers/register"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(header("X-API-Key", "test-key"))
            .andExpect(jsonPath("$.id").value("printer-1"))
            .andExpect(jsonPath("$.displayName").value("Inkom"))
            .andExpect(jsonPath("$.baseUrl").value("http://worker-1:8080"))
            .andRespond(withSuccess());

        assertThatCode(client::register).doesNotThrowAnyException();
        server[0].verify();
    }

    @Test
    void deregister_postsToDeregisterEndpoint() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        ManagementClient client = client(props("printer-1", "http://management:8080"), server);
        server[0].expect(requestTo("http://management:8080/api/internal/printers/printer-1/deregister"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(header("X-API-Key", "test-key"))
            .andRespond(withSuccess());

        assertThatCode(client::deregister).doesNotThrowAnyException();
        server[0].verify();
    }

    @Test
    void register_blankManagementUrl_skipsWithoutCalling() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        ManagementClient client = client(props("printer-1", "   "), server);
        // No expectations set; the client must not perform any request.
        assertThatCode(client::register).doesNotThrowAnyException();
        server[0].verify(); // verifies zero unexpected calls
    }

    @Test
    void register_swallowsTransportErrors() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        ManagementClient client = client(props("printer-1", "http://management:8080"), server);
        server[0].expect(requestTo("http://management:8080/api/internal/printers/register"))
            .andRespond(request -> { throw new org.springframework.web.client.ResourceAccessException("down"); });
        // Registration failure must never propagate (the worker keeps serving prints).
        assertThatCode(client::register).doesNotThrowAnyException();
    }
}
```

Run: `./mvnw test -Dtest=ManagementClientTest` → COMPILE FAILURE (`ManagementClient` missing).

- [ ] **Step 2: Create `ManagementClient`**

```java
package com.stup.wristbandprinter.worker;

import com.stup.wristbandprinter.cluster.dto.RegisterPrinterRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Worker → management self-registration calls. Failures are logged, never thrown
 *  (a worker that can't reach management must still serve prints). */
@Component
@Profile("worker")
public class ManagementClient {

    private static final Logger log = LoggerFactory.getLogger(ManagementClient.class);

    private final String apiKey;
    private final RestClient restClient;
    private final WorkerRegistrationProperties props;

    public ManagementClient(@Value("${security.api-key}") String apiKey,
                            RestClient.Builder builder,
                            WorkerRegistrationProperties props) {
        this.apiKey = apiKey;
        this.restClient = builder.build();
        this.props = props;
    }

    public void register() {
        if (!StringUtils.hasText(props.getId()) || !StringUtils.hasText(props.getManagementBaseUrl())) {
            log.warn("Worker self-registration skipped: worker.id / worker.management-base-url not configured");
            return;
        }
        try {
            restClient.post()
                .uri(props.getManagementBaseUrl() + "/api/internal/printers/register")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RegisterPrinterRequest(props.getId(), props.getDisplayName(), props.getBaseUrl()))
                .retrieve()
                .toBodilessEntity();
            log.debug("Registered worker {} with management", props.getId());
        } catch (RestClientException e) {
            log.warn("Worker self-registration to {} failed: {}", props.getManagementBaseUrl(), e.getMessage());
        }
    }

    public void deregister() {
        if (!StringUtils.hasText(props.getId()) || !StringUtils.hasText(props.getManagementBaseUrl())) {
            return;
        }
        try {
            restClient.post()
                .uri(props.getManagementBaseUrl() + "/api/internal/printers/" + props.getId() + "/deregister")
                .header("X-API-Key", apiKey)
                .retrieve()
                .toBodilessEntity();
            log.debug("Deregistered worker {} from management", props.getId());
        } catch (RestClientException e) {
            log.warn("Worker deregistration to {} failed: {}", props.getManagementBaseUrl(), e.getMessage());
        }
    }
}
```

Run: `./mvnw test -Dtest=ManagementClientTest` → PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/worker/ManagementClient.java \
        src/test/java/com/stup/wristbandprinter/worker/ManagementClientTest.java
git commit -m "feat(worker): ManagementClient for self-registration (resilient, api-key)"
```

---

## Task 3: Registration runner + scheduling + profile test

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/worker/WorkerRegistrationRunner.java`
- Create: `src/main/java/com/stup/wristbandprinter/worker/WorkerSchedulingConfig.java`
- Test: `src/test/java/com/stup/wristbandprinter/worker/WorkerRegistrationRunnerTest.java`
- Modify: `src/test/java/com/stup/wristbandprinter/worker/WorkerProfileContextTest.java`

- [ ] **Step 1: Write the failing runner test**

Create `src/test/java/com/stup/wristbandprinter/worker/WorkerRegistrationRunnerTest.java`:

```java
package com.stup.wristbandprinter.worker;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class WorkerRegistrationRunnerTest {

    private final ManagementClient client = mock(ManagementClient.class);
    private final WorkerRegistrationRunner runner = new WorkerRegistrationRunner(client);

    @Test
    void heartbeat_registers() {
        runner.registerHeartbeat();
        verify(client).register();
    }

    @Test
    void shutdown_deregisters() {
        runner.deregister();
        verify(client).deregister();
    }
}
```

Run: `./mvnw test -Dtest=WorkerRegistrationRunnerTest` → COMPILE FAILURE.

- [ ] **Step 2: Create the runner**

```java
package com.stup.wristbandprinter.worker;

import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Registers this worker with management on startup (initial delay 0) and re-asserts on a heartbeat;
 * best-effort deregister on graceful shutdown. All HTTP failures are swallowed by {@link ManagementClient}.
 */
@Component
@Profile("worker")
public class WorkerRegistrationRunner {

    private final ManagementClient client;

    public WorkerRegistrationRunner(ManagementClient client) {
        this.client = client;
    }

    @Scheduled(initialDelayString = "0", fixedDelayString = "${worker.heartbeat-millis:30000}")
    public void registerHeartbeat() {
        client.register();
    }

    @PreDestroy
    public void deregister() {
        client.deregister();
    }
}
```

- [ ] **Step 3: Enable scheduling for the worker profile**

Create `src/main/java/com/stup/wristbandprinter/worker/WorkerSchedulingConfig.java`:

```java
package com.stup.wristbandprinter.worker;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables @Scheduled (the registration heartbeat) only in the worker role. */
@Configuration
@Profile("worker")
@EnableScheduling
public class WorkerSchedulingConfig {
}
```

Run: `./mvnw test -Dtest=WorkerRegistrationRunnerTest` → PASS.

- [ ] **Step 4: Assert the new beans load in the worker context**

In `src/test/java/com/stup/wristbandprinter/worker/WorkerProfileContextTest.java`, add to the `workerContextBootsWithoutManagementBeans` test (after the existing present-bean assertions):

```java
        assertThat(ctx.getBeanNamesForType(ManagementClient.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(WorkerRegistrationRunner.class)).isNotEmpty();
```

(The context sets only `security.api-key=test-key`; `worker.*` is unset, so the heartbeat that fires during the test calls `register()` → blank-config skip → no HTTP. That's the resilience rule working.)

Run: `./mvnw test -Dtest=WorkerProfileContextTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/worker/WorkerRegistrationRunner.java \
        src/main/java/com/stup/wristbandprinter/worker/WorkerSchedulingConfig.java \
        src/test/java/com/stup/wristbandprinter/worker/WorkerRegistrationRunnerTest.java \
        src/test/java/com/stup/wristbandprinter/worker/WorkerProfileContextTest.java
git commit -m "feat(worker): self-registration runner + heartbeat + graceful deregister"
```

---

## Task 4: Wire the local cluster

**Files:**
- Modify: `docker-compose.local-cluster.yml`

- [ ] **Step 1: Add the worker env vars to `worker-1`**

In `docker-compose.local-cluster.yml`, the `worker-1` service's `environment` block currently is:

```yaml
    environment:
      - SPRING_PROFILES_ACTIVE=worker
      - SECURITY_API_KEY=local-dev-key
      - PRINTER_HOST=192.168.242.77
      - PRINTER_PORT=9100
```

Add the four self-registration vars so it registers as `printer-1`:

```yaml
    environment:
      - SPRING_PROFILES_ACTIVE=worker
      - SECURITY_API_KEY=local-dev-key
      - PRINTER_HOST=192.168.242.77
      - PRINTER_PORT=9100
      - WORKER_ID=printer-1
      - WORKER_DISPLAY_NAME=Secretariaat
      - WORKER_BASE_URL=http://worker-1:8080
      - WORKER_MANAGEMENT_BASE_URL=http://management:8080
```

(Leave management's `SPRING_APPLICATION_JSON` `cluster.printers` as-is — config seeding stays in 2b; the worker registering `printer-1` simply flips it `online=true`.)

- [ ] **Step 2: Commit**

```bash
git add docker-compose.local-cluster.yml
git commit -m "chore(compose): worker-1 self-registers as printer-1 in local cluster"
```

---

## Task 5: Full-suite + manual smoke

**Files:** none (verification).

- [ ] **Step 1: Full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS. The new tests pass; management tests are unaffected (the worker beans are `@Profile("worker")`).

- [ ] **Step 2: Manual smoke — worker self-registers**

```bash
docker compose -f docker-compose.local-cluster.yml up --build -d
# wait for management health, then:
docker exec stup-wristbandprinterservice-postgres-1 psql -U wristbands -d stup_wristband_db \
  -c "SELECT id, display_name, online, last_seen_at FROM printers ORDER BY id;"
```

Expected: `printer-1` shows `online = t` with a recent `last_seen_at` (the worker registered itself on startup — no manual curl needed). Tail `docker compose -f docker-compose.local-cluster.yml logs worker-1` to see the registration.

- [ ] **Step 3: Manual smoke — graceful deregister**

```bash
docker compose -f docker-compose.local-cluster.yml stop worker-1
# give management a moment, then:
docker exec stup-wristbandprinterservice-postgres-1 psql -U wristbands -d stup_wristband_db \
  -c "SELECT id, online FROM printers WHERE id='printer-1';"
```

Expected: `printer-1` → `online = f` (the worker's `@PreDestroy` deregistered on SIGTERM). Restart it (`up -d worker-1`) and confirm it flips back to `t`.

- [ ] **Step 4:** No code change.

---

## Deferred (NOT in this plan)

- **Removing `cluster.printers`** (config seeding) and the consequent test updates (integration tests currently rely on the config-seeded `printer-1`; once removed they must register/seed a printer). This is the next sub-part (2c) — kept separate because it changes registry construction again and reworks several `@SpringBootTest` tests.
- **Production wiring.** In prod, management listens only on **HTTPS 8443**, so a worker→management call needs either (a) an internal HTTP connector on management for `/api/internal/**`, or (b) a worker RestClient that trusts management's self-signed cert. Prod isn't live yet; this decision + `docker-compose.prod.yml` worker env wiring is a prod stand-up task. Do not add prod worker env vars pointing at HTTPS without resolving the TLS trust, or workers will fail to register.

## Out of scope (YAGNI)

- Staleness sweep that flips a printer offline if heartbeats stop (the modal's "Test" in Part 3 covers on-demand liveness; a background sweep can come later if needed).
- Worker-side retry/backoff beyond the heartbeat (the next heartbeat is the retry).

## Self-Review

**Spec coverage (Part 2b slice):** worker carries its own identity + management URL (Task 1); registers on startup and via heartbeat (Task 3, `@Scheduled` initialDelay 0); best-effort deregister on shutdown (Task 3, `@PreDestroy`); resilient to management being unreachable (Task 2, swallow + skip-if-blank); local cluster wired (Task 4). Removing `cluster.printers` and prod wiring are explicitly deferred. ✓

**Placeholder scan:** complete code in every step; the `MockRestServiceServer[] out` array is a deliberate test idiom to return both the client and its bound server from a helper. ✓

**Type consistency:** `WorkerRegistrationProperties` getters (`getId/getDisplayName/getBaseUrl/getManagementBaseUrl`) used in `ManagementClient`; `RegisterPrinterRequest(id, displayName, baseUrl)` matches the Part 2a record; `ManagementClient.register()/deregister()` used by `WorkerRegistrationRunner` and both tests; `worker.heartbeat-millis` referenced in `@Scheduled` and `application-worker.yml`. ✓

**Risk note:** `@EnableScheduling` + the `initialDelay=0` heartbeat fires inside `WorkerProfileContextTest`; the skip-if-blank rule keeps it from making an HTTP call there. Confirm that test stays green (it's in Task 3 Step 4).

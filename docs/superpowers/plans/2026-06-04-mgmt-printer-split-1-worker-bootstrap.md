# Management/Printer Split — Sub-plan 1: Worker Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a `worker` Spring profile that boots a thin, database-free, UI-free print-only service exposing an authenticated internal endpoint that sends ZPL to the physical printer — without changing existing (management) behavior.

**Architecture:** The single codebase gains a `worker` profile. Every management-only bean is gated with `@Profile("!worker")` so the existing default behavior is unchanged and the full existing test suite stays green. Under the `worker` profile, JPA/Flyway autoconfiguration is excluded, management beans are absent, and a minimal API-key security chain plus a `POST /api/internal/print` controller drive the existing `PrinterService`. This is the first half of design phase 1; sub-plan 2 makes management forward to the worker with status callbacks.

**Tech Stack:** Java 21, Spring Boot (servlet, `spring-boot-starter-web`), Spring Security, JUnit 5, Spring MockMvc, Maven (`./mvnw`).

**Spec:** `docs/superpowers/specs/2026-06-04-management-printer-split-design.md`

---

## File Structure

- `cluster/dto/PrintForwardRequest.java` (new) — DTO for the management→worker print payload. Shared package, used by the worker endpoint now and by management's forward client in sub-plan 2.
- `worker/WorkerApiKeyFilter.java` (new, `@Profile("worker")`) — API-key-only auth filter (no admin cookie).
- `worker/WorkerSecurityConfig.java` (new, `@Profile("worker")`) — minimal stateless security chain for the worker.
- `worker/WorkerPrintController.java` (new, `@Profile("worker")`) — `POST /api/internal/print`, sends ZPL via `PrinterService`.
- `src/main/resources/application-worker.yml` (new) — excludes DB/JPA/Flyway autoconfig; disables swagger.
- Management-only beans across `config/`, `controller/`, `service/`, `persistence/`, `security/`, `editor/` (modify) — add `@Profile("!worker")`.
- Tests (new): `cluster/dto/PrintForwardRequestTest.java`, `worker/WorkerPrintControllerTest.java`, `worker/WorkerProfileContextTest.java`.

Unchanged and shared by both profiles: `WristbandPrinterApplication`, `service/PrinterService`, `config/PrinterProperties`, `exception/GlobalExceptionHandler` (already maps `PrinterUnavailableException` → 503), all `@ConfigurationProperties` holders (inert under worker), all `domain/` records and `@Entity` classes.

---

### Task 1: PrintForwardRequest DTO

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/cluster/dto/PrintForwardRequest.java`
- Test: `src/test/java/com/stup/wristbandprinter/cluster/dto/PrintForwardRequestTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.stup.wristbandprinter.cluster.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PrintForwardRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesAndDeserializesRoundTrip() throws Exception {
        UUID jobId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        PrintForwardRequest original = new PrintForwardRequest(jobId, "^XA^FDhi^FS^XZ");

        String json = mapper.writeValueAsString(original);
        PrintForwardRequest parsed = mapper.readValue(json, PrintForwardRequest.class);

        assertThat(parsed.jobId()).isEqualTo(jobId);
        assertThat(parsed.zpl()).isEqualTo("^XA^FDhi^FS^XZ");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=PrintForwardRequestTest`
Expected: FAIL — compilation error, `PrintForwardRequest` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.stup.wristbandprinter.cluster.dto;

import java.util.UUID;

/** Payload sent from the management service to a printer-worker to print one job. */
public record PrintForwardRequest(UUID jobId, String zpl) {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=PrintForwardRequestTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/cluster/dto/PrintForwardRequest.java \
        src/test/java/com/stup/wristbandprinter/cluster/dto/PrintForwardRequestTest.java
git commit -m "feat: add PrintForwardRequest DTO for management->worker print payload"
```

---

### Task 2: Worker API-key auth filter

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/worker/WorkerApiKeyFilter.java`

This filter mirrors `ApiKeyAuthFilter`'s header check but drops the admin-cookie path (the worker has no UI/login). It is tested via the controller slice test in Task 4, so no standalone test here.

- [ ] **Step 1: Write the implementation**

```java
package com.stup.wristbandprinter.worker;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** API-key-only authentication for the printer-worker. Constant-time compare; no cookie path. */
@Component
@Profile("worker")
public class WorkerApiKeyFilter extends OncePerRequestFilter {

    private final byte[] apiKeyBytes;

    public WorkerApiKeyFilter(@Value("${security.api-key}") String apiKey) {
        this.apiKeyBytes = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = request.getHeader("X-API-Key");
        if (key != null
            && MessageDigest.isEqual(apiKeyBytes, key.getBytes(StandardCharsets.UTF_8))) {
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("worker-client", null, List.of()));
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/worker/WorkerApiKeyFilter.java
git commit -m "feat: add worker API-key auth filter"
```

---

### Task 3: Worker security config

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/worker/WorkerSecurityConfig.java`

- [ ] **Step 1: Write the implementation**

```java
package com.stup.wristbandprinter.worker;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** Stateless API-key security for the worker profile. Only the health endpoint is public. */
@Configuration
@EnableWebSecurity
@Profile("worker")
public class WorkerSecurityConfig {

    private final WorkerApiKeyFilter workerApiKeyFilter;

    public WorkerSecurityConfig(WorkerApiKeyFilter workerApiKeyFilter) {
        this.workerApiKeyFilter = workerApiKeyFilter;
    }

    @Bean
    public SecurityFilterChain workerFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                .accessDeniedHandler((req, res, e) ->
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
            .addFilterBefore(workerApiKeyFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/worker/WorkerSecurityConfig.java
git commit -m "feat: add worker security config (API key only)"
```

---

### Task 4: Worker print controller (with security)

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/worker/WorkerPrintController.java`
- Test: `src/test/java/com/stup/wristbandprinter/worker/WorkerPrintControllerTest.java`

Note: the controller blocks until the print completes and returns `200 OK`. (Sub-plan 2 introduces the worker's local queue + async `202` + status callbacks.) `PrinterUnavailableException` is mapped to `503` by the existing `GlobalExceptionHandler`, which `@WebMvcTest` includes.

- [ ] **Step 1: Write the failing test**

```java
package com.stup.wristbandprinter.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stup.wristbandprinter.cluster.dto.PrintForwardRequest;
import com.stup.wristbandprinter.exception.GlobalExceptionHandler;
import com.stup.wristbandprinter.exception.PrinterUnavailableException;
import com.stup.wristbandprinter.service.PrinterService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkerPrintController.class)
@Import({WorkerSecurityConfig.class, WorkerApiKeyFilter.class, GlobalExceptionHandler.class})
@ActiveProfiles("worker")
@TestPropertySource(properties = "security.api-key=test-key")
class WorkerPrintControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    PrinterService printerService;

    @Autowired
    ObjectMapper mapper;

    private String body() throws Exception {
        return mapper.writeValueAsString(
            new PrintForwardRequest(UUID.randomUUID(), "^XA^FDhi^FS^XZ"));
    }

    @Test
    void printsAndReturns200WithValidApiKey() throws Exception {
        mvc.perform(post("/api/internal/print")
                .header("X-API-Key", "test-key")
                .contentType("application/json")
                .content(body()))
            .andExpect(status().isOk());
        verify(printerService).send("^XA^FDhi^FS^XZ");
    }

    @Test
    void rejectsRequestWithoutApiKey() throws Exception {
        mvc.perform(post("/api/internal/print")
                .contentType("application/json")
                .content(body()))
            .andExpect(status().isUnauthorized());
        Mockito.verifyNoInteractions(printerService);
    }

    @Test
    void mapsPrinterUnavailableTo503() throws Exception {
        doThrow(new PrinterUnavailableException("printer down"))
            .when(printerService).send(Mockito.anyString());
        mvc.perform(post("/api/internal/print")
                .header("X-API-Key", "test-key")
                .contentType("application/json")
                .content(body()))
            .andExpect(status().isServiceUnavailable());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=WorkerPrintControllerTest`
Expected: FAIL — compilation error, `WorkerPrintController` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.stup.wristbandprinter.worker;

import com.stup.wristbandprinter.cluster.dto.PrintForwardRequest;
import com.stup.wristbandprinter.service.PrinterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal endpoint a printer-worker exposes to receive ready-to-print ZPL from management. */
@RestController
@RequestMapping("/api/internal")
@Profile("worker")
public class WorkerPrintController {

    private static final Logger log = LoggerFactory.getLogger(WorkerPrintController.class);

    private final PrinterService printerService;

    public WorkerPrintController(PrinterService printerService) {
        this.printerService = printerService;
    }

    @PostMapping("/print")
    public ResponseEntity<Void> print(@RequestBody PrintForwardRequest request) {
        log.info("Worker received print for job {}", request.jobId());
        printerService.send(request.zpl());
        log.info("Worker completed print for job {}", request.jobId());
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=WorkerPrintControllerTest`
Expected: PASS (all 3 cases)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/worker/WorkerPrintController.java \
        src/test/java/com/stup/wristbandprinter/worker/WorkerPrintControllerTest.java
git commit -m "feat: add worker print controller for /api/internal/print"
```

---

### Task 5: Worker profile configuration file

**Files:**
- Create: `src/main/resources/application-worker.yml`

- [ ] **Step 1: Write the configuration**

```yaml
# Worker profile: a thin print-only service. No database, no UI, no templates.
# Activate alongside an environment profile, e.g. SPRING_PROFILES_ACTIVE=prod,worker
# (prod) or local,worker (dev). Reuses printer.* and security.api-key from
# application.yml; PRINTER_HOST/PRINTER_PORT point at this worker's physical printer.
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
      - org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
      - org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
      - org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
      - org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration

springdoc:
  swagger-ui:
    enabled: false
  api-docs:
    enabled: false
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application-worker.yml
git commit -m "feat: add application-worker.yml excluding DB/JPA autoconfig"
```

---

### Task 6: Gate management-only beans behind `@Profile("!worker")`

**Files (modify — add `import org.springframework.context.annotation.Profile;` and a `@Profile("!worker")` annotation on the class):**
- `src/main/java/com/stup/wristbandprinter/config/SecurityConfig.java`
- `src/main/java/com/stup/wristbandprinter/security/ApiKeyAuthFilter.java`
- `src/main/java/com/stup/wristbandprinter/security/AuthCookieService.java`
- `src/main/java/com/stup/wristbandprinter/controller/WristbandController.java`
- `src/main/java/com/stup/wristbandprinter/controller/AuthController.java`
- `src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java`
- `src/main/java/com/stup/wristbandprinter/service/LabelaryPreviewService.java`
- `src/main/java/com/stup/wristbandprinter/service/LogoConversionService.java`
- `src/main/java/com/stup/wristbandprinter/service/WristbandLayoutService.java`
- `src/main/java/com/stup/wristbandprinter/service/WristbandZplResolver.java`
- `src/main/java/com/stup/wristbandprinter/service/ZplGeneratorService.java`
- `src/main/java/com/stup/wristbandprinter/persistence/JpaJobStore.java`
- `src/main/java/com/stup/wristbandprinter/editor/controller/TemplateController.java`
- `src/main/java/com/stup/wristbandprinter/editor/service/TemplateService.java`
- `src/main/java/com/stup/wristbandprinter/editor/service/TemplateAssetService.java`
- `src/main/java/com/stup/wristbandprinter/editor/service/TemplateZplRenderer.java`
- `src/main/java/com/stup/wristbandprinter/editor/service/GfImageEncoder.java`
- `src/main/java/com/stup/wristbandprinter/editor/service/PreviewColorService.java`
- `src/main/java/com/stup/wristbandprinter/editor/service/SampleData.java` (only if it carries a Spring stereotype such as `@Component`/`@Service`; skip if it is a plain class)

Do NOT annotate: `WristbandPrinterApplication`, `service/PrinterService`, `config/PrinterProperties`, `config/ApiKeyValidator`, `exception/GlobalExceptionHandler`, any `@ConfigurationProperties` holder, any `domain/` record, any `@Entity`, or Spring Data repository interfaces (those are removed under `worker` by the autoconfig exclusions in Task 5).

- [ ] **Step 1: Add the annotation to each class listed above**

For each file, add the import (alphabetically among existing `org.springframework.context.annotation.*` imports if present) and place `@Profile("!worker")` directly above the existing class-level stereotype. Example for `service/PrintQueueService.java`:

```java
import org.springframework.context.annotation.Profile;
// ...
@Service
@Profile("!worker")
public class PrintQueueService {
```

Example for `config/SecurityConfig.java` (annotation goes on the class, below the existing `@SecurityScheme`):

```java
@Configuration
@EnableWebSecurity
@Profile("!worker")
@SecurityScheme(
    name = "ApiKeyAuth",
    // ...
)
public class SecurityConfig {
```

- [ ] **Step 2: Verify the project still compiles**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Run the full existing test suite to confirm no regression**

Run: `./mvnw -q test`
Expected: BUILD SUCCESS — the default (no `worker` profile) behavior is unchanged, so every existing test still passes.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter
git commit -m "refactor: gate management-only beans behind @Profile(\"!worker\")"
```

---

### Task 7: Worker profile context test

**Files:**
- Test: `src/test/java/com/stup/wristbandprinter/worker/WorkerProfileContextTest.java`

Verifies the `worker` profile boots with no database and that management-only beans are absent while the worker beans and shared `PrinterService` are present.

- [ ] **Step 1: Write the test**

```java
package com.stup.wristbandprinter.worker;

import com.stup.wristbandprinter.controller.WristbandController;
import com.stup.wristbandprinter.persistence.JobStore;
import com.stup.wristbandprinter.service.PrinterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("worker")
@TestPropertySource(properties = "security.api-key=test-key")
class WorkerProfileContextTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void workerContextBootsWithoutManagementBeans() {
        assertThat(ctx.getBeanNamesForType(PrinterService.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(WorkerPrintController.class)).isNotEmpty();

        assertThat(ctx.getBeanNamesForType(JobStore.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(WristbandController.class)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=WorkerProfileContextTest`
Expected: PASS — context loads without a datasource (autoconfig excluded), worker beans present, management beans absent.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/stup/wristbandprinter/worker/WorkerProfileContextTest.java
git commit -m "test: worker profile boots without management beans or DB"
```

---

### Task 8: Full verification

- [ ] **Step 1: Run the complete test suite**

Run: `./mvnw -q test`
Expected: BUILD SUCCESS — all existing tests plus the four new tests pass.

- [ ] **Step 2: Manually boot the worker profile (smoke test)**

Run:
```bash
SPRING_PROFILES_ACTIVE=worker SECURITY_API_KEY=test-key \
  PRINTER_HOST=127.0.0.1 PRINTER_PORT=9100 \
  ./mvnw -q spring-boot:run
```
Expected: the application starts on HTTP 8080 with no datasource/Flyway in the logs. `curl -s localhost:8080/actuator/health` returns `{"status":"UP"}`. A `POST /api/internal/print` without `X-API-Key` returns 401. Stop with Ctrl-C.

- [ ] **Step 3: Confirm the branch is clean and all work committed**

Run: `git status`
Expected: nothing to commit, working tree clean.

---

## Self-Review

- **Spec coverage:** This sub-plan delivers the `worker` profile, the database/UI/template-free boot (autoconfig exclusions + `@Profile("!worker")` gating), the API-key-secured internal print endpoint, and the worker→printer send path. The registry, management→worker forwarding, status callbacks, cancel forwarding, persistence columns and UI are explicitly deferred to later sub-plans (sub-plan 2 and design phases 2–3).
- **Placeholder scan:** No TBD/TODO; every code and test step contains complete, compilable content.
- **Type consistency:** `PrintForwardRequest(jobId, zpl)` is defined in Task 1 and used unchanged in Tasks 4. `WorkerApiKeyFilter`, `WorkerSecurityConfig`, `WorkerPrintController` names are consistent across creation and test imports. `security.api-key` property name matches the existing `ApiKeyAuthFilter`/`WorkerApiKeyFilter` `@Value`.

## Out of scope (this sub-plan)

- Management forwarding ZPL to the worker and removing local printing (sub-plan 2).
- Worker local queue + async `202` + status callbacks to management (sub-plan 2).
- Printer registry, `printer_id`/`printer_name` persistence, cancel forwarding (sub-plan 2).
- Docker Compose worker services and deploy wiring (sub-plan 2).
- `printerId` in the public print API and the Symfony contract (design phase 2).
- Jobs-page printer column, drawer row, filter chips, reprint picker (design phase 3).

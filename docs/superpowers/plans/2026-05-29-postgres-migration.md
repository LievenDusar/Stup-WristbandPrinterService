# PostgreSQL Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the embedded H2 file database with PostgreSQL, manage the schema with Flyway, and test against a real Postgres via Testcontainers (clean cutover, no data migration).

**Architecture:** Persistence stays behind Spring Data JPA (`PrintJobRepository` + `JpaJobStore`), so the swap is dependency + config + a Flyway baseline migration. Hibernate moves from `ddl-auto=update` to `validate` (Flyway owns the schema). Pure unit tests remain DB-free via the existing in-memory `JobStore` fake; only `JpaJobStoreTest` and `WristbandIntegrationTest` touch a real Postgres, provided by Testcontainers with Spring Boot's `@ServiceConnection`.

**Tech Stack:** Java 21, Spring Boot 3.4.1, Spring Data JPA, PostgreSQL, Flyway, Testcontainers, Maven.

---

## Spec reference

`docs/superpowers/specs/2026-05-29-postgres-migration-design.md`

## Scope check

Single subsystem (persistence engine swap). One plan.

## File structure

- `pom.xml` — drop H2; add postgres driver, Flyway, Testcontainers.
- `src/main/resources/application.yml` — `ddl-auto: validate`, remove H2 datasource.
- `src/main/resources/application-local.yml` — local Postgres datasource.
- `src/main/resources/db/migration/V1__create_print_jobs.sql` — baseline schema (new).
- `src/test/java/com/stup/wristbandprinter/persistence/JpaJobStoreTest.java` — Testcontainers Postgres.
- `src/test/java/com/stup/wristbandprinter/WristbandIntegrationTest.java` — Testcontainers Postgres.
- `docker-compose.yml` — Postgres service + volume; drop H2 volume.
- `.gitignore` — drop `data/`.
- `README.md` — persistence + test prerequisites.

## Important sequencing note

A database swap cannot keep the full test suite green at every intermediate commit.
Tasks 1–3 are prep and are verified by **compilation only** (`-DskipTests`). The full
test suite is restored to green at **Task 5**. Do not run the full suite expecting
green before Task 5.

---

### Task 1: Swap Maven dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Remove the H2 dependency**

Delete this block from `pom.xml`:

```xml
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>runtime</scope>
        </dependency>
```

- [ ] **Step 2: Add Postgres, Flyway, and Testcontainers dependencies**

Insert these immediately after the `spring-boot-starter-data-jpa` dependency in `pom.xml` (versions are managed by the Spring Boot parent — do not add explicit versions):

```xml
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 3: Verify dependencies resolve and the project compiles**

Run: `./mvnw -q -DskipTests package`
Expected: `BUILD SUCCESS` (tests skipped — they are not green until Task 5).

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "build: swap H2 for PostgreSQL, Flyway and Testcontainers deps"
```

---

### Task 2: Datasource and JPA configuration

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.yml`

- [ ] **Step 1: Replace the H2 datasource block in `application.yml`**

Find this block:

```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/printjobs;AUTO_SERVER=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
```

Replace it with (no datasource here — prod supplies it via `SPRING_DATASOURCE_*`
environment variables, which Spring Boot binds automatically; Flyway is auto-enabled
by the presence of `flyway-core`):

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
```

- [ ] **Step 2: Add a local Postgres datasource to `application-local.yml`**

Add this block (merge into the existing `spring:` key if one exists, otherwise add it):

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/wristbands
    username: wristbands
    password: wristbands
```

- [ ] **Step 3: Verify the project still compiles**

Run: `./mvnw -q -DskipTests package`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application.yml src/main/resources/application-local.yml
git commit -m "config: point persistence at PostgreSQL, validate schema via Flyway"
```

---

### Task 3: Flyway baseline migration

**Files:**
- Create: `src/main/resources/db/migration/V1__create_print_jobs.sql`

- [ ] **Step 1: Create the baseline migration**

Create `src/main/resources/db/migration/V1__create_print_jobs.sql` with the schema
matching `PrintJobEntity` under Spring Boot's default `CamelCaseToUnderscores` naming:

```sql
CREATE TABLE print_jobs (
    job_id           UUID PRIMARY KEY,
    status           VARCHAR(255),
    event_name       VARCHAR(255),
    first_name       VARCHAR(255),
    last_name        VARCHAR(255),
    association_name VARCHAR(255),
    barcode_value    VARCHAR(255),
    submitted_at     TIMESTAMP(6) WITH TIME ZONE,
    completed_at     TIMESTAMP(6) WITH TIME ZONE,
    error            VARCHAR(2000)
);
```

Note on the timestamp type: Hibernate 6 maps `java.time.Instant` to a
timestamp-with-time-zone JDBC type, so the columns use `TIMESTAMP(6) WITH TIME ZONE`.
If `ddl-auto: validate` fails on `submitted_at`/`completed_at` in Task 4 with a type
mismatch, change those two columns to `TIMESTAMP(6)` (without time zone) and re-run —
the validation error is the authoritative signal.

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/db/migration/V1__create_print_jobs.sql
git commit -m "db: add Flyway baseline migration for print_jobs"
```

---

### Task 4: Run JpaJobStoreTest against Testcontainers Postgres

**Files:**
- Modify: `src/test/java/com/stup/wristbandprinter/persistence/JpaJobStoreTest.java`

- [ ] **Step 1: Rewrite the test to use a Postgres Testcontainer**

Replace the entire contents of `JpaJobStoreTest.java` with:

```java
package com.stup.wristbandprinter.persistence;

import com.stup.wristbandprinter.domain.PrintJob;
import com.stup.wristbandprinter.domain.PrintJobStatus;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaJobStore.class)
@Testcontainers
class JpaJobStoreTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JpaJobStore store;

    @Test
    void saveAndLoad_roundTripsAllFields() {
        UUID id = UUID.randomUUID();
        Instant submitted = Instant.now();
        store.save(PrintJob.restore(id, request(), PrintJobStatus.DONE, submitted, submitted, null));

        List<PrintJob> loaded = store.loadAll();

        assertThat(loaded).hasSize(1);
        PrintJob job = loaded.get(0);
        assertThat(job.getJobId()).isEqualTo(id);
        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.DONE);
        assertThat(job.getRequest().getEventName()).isEqualTo("Pukkelpop 2026");
        assertThat(job.getRequest().getBarcodeValue()).isEqualTo("123456789");
    }

    @Test
    void deleteCompleted_removesDoneAndFailedButKeepsPending() {
        store.save(PrintJob.restore(UUID.randomUUID(), request(), PrintJobStatus.DONE, Instant.now(), Instant.now(), null));
        store.save(PrintJob.restore(UUID.randomUUID(), request(), PrintJobStatus.FAILED, Instant.now(), Instant.now(), "boom"));
        store.save(PrintJob.restore(UUID.randomUUID(), request(), PrintJobStatus.PENDING, Instant.now(), null, null));

        store.deleteCompleted();

        List<PrintJob> remaining = store.loadAll();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getStatus()).isEqualTo(PrintJobStatus.PENDING);
    }

    private WristbandPrintRequest request() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setAssociationName("STUP vzw");
        r.setBarcodeValue("123456789");
        return r;
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./mvnw test -Dtest=JpaJobStoreTest`
Expected: `BUILD SUCCESS`, `Tests run: 2`. Flyway creates the schema in the container,
then `ddl-auto: validate` confirms the entity matches.

If it fails with a Hibernate schema-validation error on `submitted_at`/`completed_at`
(timestamp type mismatch), apply the fallback from Task 3 (change those columns to
`TIMESTAMP(6)`), then re-run until green.

Requires a running Docker daemon.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/stup/wristbandprinter/persistence/JpaJobStoreTest.java
git commit -m "test: run JpaJobStore against PostgreSQL via Testcontainers"
```

---

### Task 5: Run WristbandIntegrationTest against Testcontainers Postgres

**Files:**
- Modify: `src/test/java/com/stup/wristbandprinter/WristbandIntegrationTest.java`

- [ ] **Step 1: Add the Testcontainers imports**

In `WristbandIntegrationTest.java`, add these imports alongside the existing ones:

```java
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
```

- [ ] **Step 2: Annotate the class and add the container**

Add `@Testcontainers` to the class annotations (alongside the existing
`@SpringBootTest(...)`), and add the container field at the top of the class body,
just before the existing `private static final ServerSocket printerSocket;` field:

```java
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
```

- [ ] **Step 3: Remove the H2 datasource override**

In the `@DynamicPropertySource` method `properties(...)`, delete this line:

```java
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:itest;DB_CLOSE_DELAY=-1");
```

Leave the other registrations (`printer.host`, `printer.port`, `printer.max-retries`,
`security.api-key`) unchanged — the datasource now comes from `@ServiceConnection`.

- [ ] **Step 4: Run the integration test**

Run: `./mvnw test -Dtest=WristbandIntegrationTest`
Expected: `BUILD SUCCESS`, `Tests run: 3`.

- [ ] **Step 5: Run the full suite to confirm green is restored**

Run: `./mvnw test`
Expected: `BUILD SUCCESS`, all tests pass (the unit tests use the in-memory `JobStore`
fake and are unaffected).

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/stup/wristbandprinter/WristbandIntegrationTest.java
git commit -m "test: run integration suite against PostgreSQL via Testcontainers"
```

---

### Task 6: Docker Compose, gitignore, and docs

**Files:**
- Modify: `docker-compose.yml`
- Modify: `.gitignore`
- Modify: `README.md`

- [ ] **Step 1: Add a Postgres service and wire the app to it in `docker-compose.yml`**

Replace the entire `docker-compose.yml` with:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      - POSTGRES_DB=wristbands
      - POSTGRES_USER=wristbands
      - POSTGRES_PASSWORD=${DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U wristbands -d wristbands"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  wristband-printer:
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - SECURITY_API_KEY=${API_KEY}
      - PRINTER_HOST=${PRINTER_HOST}
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/wristbands
      - SPRING_DATASOURCE_USERNAME=wristbands
      - SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}
    depends_on:
      postgres:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 20s
    deploy:
      resources:
        limits:
          memory: 512m
          cpus: '1.0'
    restart: unless-stopped

volumes:
  pgdata:
```

- [ ] **Step 2: Remove the H2 data dir from `.gitignore`**

Delete this block from `.gitignore`:

```
# H2 embedded database (persisted print-job history)
data/
```

- [ ] **Step 3: Update the README persistence and testing notes**

In `README.md`, replace the "Job persistence" section body with:

```markdown
Print jobs are persisted to **PostgreSQL**; the schema is managed by **Flyway**
(`src/main/resources/db/migration`). On startup, any job left `PENDING` or `PRINTING`
by a previous run is marked `FAILED` ("Interrupted by service restart") — a
half-printed wristband is never reprinted automatically; the operator can reprint
deliberately.

Under Docker Compose a `postgres` service is started automatically and the app
connects to it via `SPRING_DATASOURCE_*` (see `docker-compose.yml`). For local dev,
run the `local` profile against a Postgres on `localhost:5432` (database `wristbands`,
user/password `wristbands`).
```

Then replace the "Running tests" section body with:

```markdown
```bash
mvn test
```

Tests run the persistence and integration layers against a real PostgreSQL started
automatically via Testcontainers — a running **Docker** daemon is required. The
printer and Labelary are still mocked (a fake TCP socket stands in for the printer).
```

- [ ] **Step 4: Verify compose file syntax**

Run: `docker compose config -q`
Expected: no output and exit code 0 (valid compose file). If `docker` is unavailable,
skip this check.

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml .gitignore README.md
git commit -m "ops: add Postgres to docker-compose; update docs for Postgres + Testcontainers"
```

---

## Self-review

**Spec coverage:**
- Dependency swap (remove H2; add postgres/flyway/flyway-database-postgresql/testcontainers) → Task 1. ✓
- `ddl-auto: validate`, datasource per profile, drop H2 config → Task 2. ✓
- Flyway baseline migration → Task 3. ✓
- Testcontainers for JpaJobStoreTest → Task 4; for WristbandIntegrationTest → Task 5. ✓
- Unit tests stay DB-free → unchanged (no task needed; noted). ✓
- docker-compose Postgres service + volume; drop H2 volume → Task 6. ✓
- `.gitignore` drop `data/` → Task 6. ✓
- README persistence + Docker-for-tests note → Task 6. ✓
- Clean cutover, no data migration → no task (by design). ✓

**Placeholder scan:** No TBD/TODO; every code step has full content; the timestamp-type
contingency in Tasks 3/4 gives the exact alternative SQL, not a vague "adjust as needed".

**Type consistency:** `PostgreSQLContainer`, `@ServiceConnection`, `@Container`,
`@Testcontainers` used identically in Tasks 4 and 5; image `postgres:16-alpine`
consistent across tests and compose; datasource credentials (`wristbands`/`wristbands`)
consistent between `application-local.yml` and docker-compose Postgres service.

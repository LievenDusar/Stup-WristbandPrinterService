# PostgreSQL Migration — Design

**Date:** 2026-05-29
**Status:** Approved (pending spec review)

## Goal

Replace the embedded H2 file database with **PostgreSQL** as the persistence store,
manage the schema with **Flyway**, and test against a real Postgres via
**Testcontainers**. This is a foundational change made now (while the dataset is
small) to support the planned roadmap: multiple printers and an admin wristband
template editor, and eventual multi-instance scaling.

## Decisions (agreed)

- **Engine:** PostgreSQL (replaces H2 entirely).
- **Schema management:** Flyway migrations; stop using Hibernate `ddl-auto=update`.
- **Test strategy:** Testcontainers (real Postgres in Docker) for the JPA and
  integration tests. Pure unit tests stay DB-free via the existing `JobStore` fake.
- **Data migration:** none — clean cutover. No production data exists yet.

## Scope

In scope: dependency swap, datasource config per profile, Flyway baseline migration,
Testcontainers wiring, docker-compose Postgres service, docs.

Out of scope: the jobs-page redesign (separate spec); multi-printer routing and the
template editor (future); any data migration from H2.

## Dependencies (`pom.xml`)

- **Remove:** `com.h2database:h2`.
- **Add (runtime):** `org.postgresql:postgresql`.
- **Add:** `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql`
  (the latter is required for Postgres on Flyway 10+, which Spring Boot 3.4 manages).
- **Add (test):** `org.springframework.boot:spring-boot-testcontainers`,
  `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`.

## Configuration

- **`spring.jpa.hibernate.ddl-auto: validate`** — Flyway owns the schema; `validate`
  fails fast at startup if the entity mapping and the migrated schema diverge.
- **`spring.jpa.open-in-view: false`** — unchanged.
- **Flyway** enabled by default (migrations on the classpath run at startup, before
  the JPA EntityManagerFactory is created).
- **Datasource by profile:**
  - **base/prod:** from environment —
    `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.
    No hard-coded credentials in committed config.
  - **local:** `jdbc:postgresql://localhost:5432/wristbands`, dev username/password,
    matching the docker-compose Postgres so `mvn spring-boot:run -Plocal` works.
  - **tests:** datasource supplied by Testcontainers via `@ServiceConnection`
    (no fixed URL).
- Remove the H2 `jdbc:h2:file:...` datasource and the H2 `data/` artifacts
  (delete the `.gitignore` `data/` entry and the docker-compose `printjobs-data` volume).

## Flyway migration

- **`src/main/resources/db/migration/V1__create_print_jobs.sql`** — creates the
  `print_jobs` table matching `PrintJobEntity` under Spring Boot's default
  `CamelCaseToUnderscores` physical naming:
  `job_id` (UUID, PK), `status` (varchar), `event_name`, `first_name`, `last_name`,
  `association_name`, `barcode_value` (varchar), `submitted_at`, `completed_at`
  (timestamp), `error` (varchar(2000), nullable).
- Exact column types are finalized against `ddl-auto: validate` during the test run —
  if Hibernate's expected type differs (e.g. timestamp vs timestamptz), the failing
  validation tells us and the migration is adjusted. (TDD: the Testcontainers test
  failing on a type mismatch is the signal.)

## docker-compose

- Add a **`postgres`** service (e.g. `postgres:16`), with `POSTGRES_DB`,
  `POSTGRES_USER`, `POSTGRES_PASSWORD`, a named volume `pgdata:/var/lib/postgresql/data`,
  and a healthcheck.
- The app service gains `depends_on: postgres` (wait for healthy) and its
  `SPRING_DATASOURCE_*` env vars point at the `postgres` service.
- Remove the H2 `printjobs-data` volume.

## Testing (TDD)

- **`JpaJobStoreTest`:** switch from `@DataJpaTest` (which substitutes an embedded DB)
  to a Postgres Testcontainer. Use a static `@Container` `PostgreSQLContainer` with
  Spring Boot's `@ServiceConnection`, and `@AutoConfigureTestDatabase(replace = NONE)`
  so the real container datasource is used. Flyway creates the schema; the existing
  round-trip and `deleteCompleted` assertions are unchanged.
- **`WristbandIntegrationTest`:** add the same Postgres `@ServiceConnection` container;
  remove the H2 `spring.datasource.url` dynamic property. Existing print-path,
  concurrency, and SSE tests remain.
- **Unit tests** (`PrintQueueServiceTest`, etc.): unchanged — they use the in-memory
  `JobStore` fake and never touch a database.
- Container reuse: a single shared static container across the test class (and ideally
  across test classes via a small base) keeps the suite fast.

## Operational / docs impact

- **README:** update the persistence section (Postgres + Flyway, local docker-compose
  setup, required `SPRING_DATASOURCE_*` env vars in prod). Update the
  "no external dependencies for tests" note — tests now require **Docker** (Testcontainers).
- **Prerequisite:** Docker must be available in dev and CI for the test suite.

## Affected files

- `pom.xml` — dependency swap (remove H2; add postgres, flyway-core,
  flyway-database-postgresql; add testcontainers).
- `src/main/resources/application.yml` + `application-local.yml` /
  `application-prod.yml` — datasource per profile; `ddl-auto: validate`; drop H2.
- `src/main/resources/db/migration/V1__create_print_jobs.sql` — new baseline schema.
- `docker-compose.yml` — add Postgres service + volume; wire app datasource; drop H2 volume.
- `.gitignore` — remove the `data/` entry.
- `src/test/java/.../persistence/JpaJobStoreTest.java` — Testcontainers Postgres.
- `src/test/java/.../WristbandIntegrationTest.java` — Testcontainers Postgres.
- `README.md` — persistence + test prerequisites.

## Risks / notes

- **Docker dependency for tests.** Testcontainers needs a Docker daemon; document it
  and ensure CI provides one.
- **Type/dialect drift caught early.** `ddl-auto: validate` against a real Postgres is
  the safety net that the H2-for-tests approach would have hidden.
- **Sequencing:** do this migration **before** the jobs-page work, so the jobs-page
  changes (CANCELLED status, any detail fields) land on Postgres + Flyway. The
  jobs-page status enum is stored as a string, so adding `CANCELLED` needs no schema
  migration.

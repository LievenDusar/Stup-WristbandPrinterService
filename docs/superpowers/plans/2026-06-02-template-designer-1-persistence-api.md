# Wristband Template Designer — Plan 1: Persistence & Catalog/CRUD API

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Store user-designed wristband templates (a declarative JSON element model + a saved ZPL snapshot column) in PostgreSQL and expose a CRUD + catalog REST API that Symfony and the (later) editor consume.

**Architecture:** A `TemplateDefinition` record tree (canvas + elements) is persisted as a `jsonb` column on a `wristband_template` entity via Hibernate's native JSON mapping. `TemplateService` handles create/update/list/get/soft-delete and slug generation; `TemplateController` exposes them under `/api/templates`, reusing the existing API-key / admin-cookie security. ZPL rendering, assets, preview and `/print` integration are deliberately deferred to Plan 2 (the `generated_zpl` column stays null until then).

**Tech Stack:** Java 21, Spring Boot 3 (Web, Data JPA, Validation), Hibernate 6 (`@JdbcTypeCode(SqlTypes.JSON)`), Flyway, PostgreSQL 16, JUnit 5 + AssertJ + Mockito, Testcontainers.

**Scope of this plan (Plan 1 of 3):**
- IN: domain model, DB migration, entity/repository, `TemplateService`, `TemplateController`, tests, README note.
- OUT (Plan 2): `TemplateZplRenderer`, `TemplateAssetService`, generated-ZPL snapshots, preview PNG endpoints, `/print` `templateId` routing.
- OUT (Plan 3): the Konva.js editor page.

**Conventions to follow (verified against the codebase):**
- Records for DTOs/value objects; constructor injection; AssertJ assertions.
- `ddl-auto: validate` — the Flyway migration is the source of truth for column types. `Instant` columns must be `TIMESTAMP(6) WITH TIME ZONE` (see `V1__create_print_jobs.sql`).
- Soft delete via a `deleted` boolean (see `V2__add_deleted_flag.sql`).
- Lookups that may miss return `Optional`; the controller maps empty → 404 (mirrors `WristbandController.getJob`). No new exception type needed.
- `/api/**` is already `authenticated()` in `SecurityConfig` (API key **or** admin cookie) — no security change required in this plan.

---

## File Structure

**Create:**
- `src/main/java/com/stup/wristbandprinter/domain/template/Canvas.java` — canvas size + DPI value object
- `src/main/java/com/stup/wristbandprinter/domain/template/ElementType.java` — element kind enum
- `src/main/java/com/stup/wristbandprinter/domain/template/DataBinding.java` — data-field binding enum
- `src/main/java/com/stup/wristbandprinter/domain/template/ShapeType.java` — box/line enum
- `src/main/java/com/stup/wristbandprinter/domain/template/TemplateElement.java` — one positioned element
- `src/main/java/com/stup/wristbandprinter/domain/template/TemplateDefinition.java` — canvas + element list
- `src/main/java/com/stup/wristbandprinter/domain/template/UpsertTemplateRequest.java` — create/update request DTO
- `src/main/java/com/stup/wristbandprinter/domain/template/TemplateSummaryResponse.java` — catalog list item DTO
- `src/main/java/com/stup/wristbandprinter/domain/template/TemplateDetailResponse.java` — full template DTO
- `src/main/resources/db/migration/V3__create_wristband_templates.sql` — schema
- `src/main/java/com/stup/wristbandprinter/persistence/WristbandTemplateEntity.java` — JPA entity
- `src/main/java/com/stup/wristbandprinter/persistence/WristbandTemplateRepository.java` — Spring Data repo
- `src/main/java/com/stup/wristbandprinter/service/TemplateService.java` — CRUD + slug logic
- `src/main/java/com/stup/wristbandprinter/controller/TemplateController.java` — REST endpoints
- `src/test/java/com/stup/wristbandprinter/domain/template/TemplateDefinitionJsonTest.java`
- `src/test/java/com/stup/wristbandprinter/persistence/WristbandTemplateRepositoryTest.java`
- `src/test/java/com/stup/wristbandprinter/service/TemplateServiceTest.java`
- `src/test/java/com/stup/wristbandprinter/controller/TemplateControllerTest.java`

**Modify:**
- `README.md` — document the new endpoints (Task 6).

---

## Task 1: Domain model (records + enums) and JSON round-trip test

**Files:**
- Create: all six files under `domain/template/` listed above except the three DTOs (those are Task 4).
- Test: `src/test/java/com/stup/wristbandprinter/domain/template/TemplateDefinitionJsonTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/domain/template/TemplateDefinitionJsonTest.java`:

```java
package com.stup.wristbandprinter.domain.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateDefinitionJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesAndDeserializesAllElementTypes() throws Exception {
        TemplateElement text = new TemplateElement(
            "el-text", ElementType.TEXT, 40, 120, 28, 600, 90,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
        TemplateElement barcode = new TemplateElement(
            "el-bc", ElementType.BARCODE, 10, 200, 100, 400, 90,
            DataBinding.BARCODE_VALUE, null, null, null, "CODE128", false, null, null, null);
        TemplateElement shape = new TemplateElement(
            "el-box", ElementType.SHAPE, 0, 0, 203, 4, 0,
            null, null, null, null, null, null, null, ShapeType.LINE, 4);

        TemplateDefinition def = new TemplateDefinition(
            new Canvas(203, 2233, 300), List.of(text, barcode, shape));

        String json = mapper.writeValueAsString(def);
        TemplateDefinition back = mapper.readValue(json, TemplateDefinition.class);

        assertThat(back).isEqualTo(def);
        assertThat(back.canvas().widthDots()).isEqualTo(203);
        assertThat(back.elements()).hasSize(3);
        assertThat(back.elements().get(0).binding()).isEqualTo(DataBinding.FULL_NAME);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=TemplateDefinitionJsonTest`
Expected: FAIL — compilation error, the `domain.template` types do not exist yet.

- [ ] **Step 3: Create the enums and records**

`src/main/java/com/stup/wristbandprinter/domain/template/ElementType.java`:

```java
package com.stup.wristbandprinter.domain.template;

public enum ElementType {
    TEXT, STATIC_TEXT, BARCODE, IMAGE, SHAPE
}
```

`src/main/java/com/stup/wristbandprinter/domain/template/DataBinding.java`:

```java
package com.stup.wristbandprinter.domain.template;

public enum DataBinding {
    EVENT_NAME, FIRST_NAME, LAST_NAME, FULL_NAME, ASSOCIATION_NAME, BARCODE_VALUE
}
```

`src/main/java/com/stup/wristbandprinter/domain/template/ShapeType.java`:

```java
package com.stup.wristbandprinter.domain.template;

public enum ShapeType {
    BOX, LINE
}
```

`src/main/java/com/stup/wristbandprinter/domain/template/Canvas.java`:

```java
package com.stup.wristbandprinter.domain.template;

/** Wristband print area in printer dots, plus the printer DPI. */
public record Canvas(int widthDots, int lengthDots, int dpi) {
}
```

`src/main/java/com/stup/wristbandprinter/domain/template/TemplateElement.java`:

```java
package com.stup.wristbandprinter.domain.template;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * One positioned element on the wristband. All coordinates and sizes are in printer dots.
 * {@code rotation} is one of 0, 90, 180, 270 (the only orientations ZPL supports).
 * Fields not relevant to a given {@link ElementType} are null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TemplateElement(
    String id,
    ElementType type,
    int x,
    int y,
    int widthDots,
    int heightDots,
    int rotation,
    DataBinding binding,        // TEXT, BARCODE
    String value,               // STATIC_TEXT
    Integer fontSize,           // TEXT, STATIC_TEXT
    String font,                // TEXT, STATIC_TEXT (ZPL font id, e.g. "0")
    String symbology,           // BARCODE (e.g. CODE128)
    Boolean showHumanReadable,  // BARCODE
    UUID assetId,               // IMAGE
    ShapeType shape,            // SHAPE
    Integer thicknessDots       // SHAPE
) {
}
```

`src/main/java/com/stup/wristbandprinter/domain/template/TemplateDefinition.java`:

```java
package com.stup.wristbandprinter.domain.template;

import java.util.List;

/** The full declarative description of a wristband layout. Stored as jsonb. */
public record TemplateDefinition(Canvas canvas, List<TemplateElement> elements) {
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=TemplateDefinitionJsonTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/template src/test/java/com/stup/wristbandprinter/domain/template
git commit -m "feat: add wristband template domain model"
```

---

## Task 2: Flyway migration, JPA entity, repository (Testcontainers round-trip)

**Files:**
- Create: `src/main/resources/db/migration/V3__create_wristband_templates.sql`
- Create: `src/main/java/com/stup/wristbandprinter/persistence/WristbandTemplateEntity.java`
- Create: `src/main/java/com/stup/wristbandprinter/persistence/WristbandTemplateRepository.java`
- Test: `src/test/java/com/stup/wristbandprinter/persistence/WristbandTemplateRepositoryTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/persistence/WristbandTemplateRepositoryTest.java`:

```java
package com.stup.wristbandprinter.persistence;

import com.stup.wristbandprinter.domain.template.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class WristbandTemplateRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WristbandTemplateRepository repository;

    @Test
    void persistsAndReadsBackJsonDefinition() {
        WristbandTemplateEntity saved = repository.save(entity("Festival Band", "festival-band", "festival"));

        WristbandTemplateEntity loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("Festival Band");
        assertThat(loaded.getDefinition().canvas().widthDots()).isEqualTo(203);
        assertThat(loaded.getDefinition().elements()).hasSize(1);
        assertThat(loaded.getDefinition().elements().get(0).binding()).isEqualTo(DataBinding.FULL_NAME);
    }

    @Test
    void findByProjectTypeAndDeletedFalse_filtersCorrectly() {
        repository.save(entity("A", "a", "festival"));
        repository.save(entity("B", "b", "conference"));
        WristbandTemplateEntity deleted = entity("C", "c", "festival");
        deleted.setDeleted(true);
        repository.save(deleted);

        List<WristbandTemplateEntity> result =
            repository.findByProjectTypeAndDeletedFalseOrderByUpdatedAtDesc("festival");

        assertThat(result).extracting(WristbandTemplateEntity::getName).containsExactly("A");
    }

    @Test
    void findBySlugAndDeletedFalse_returnsActiveOnly() {
        repository.save(entity("A", "my-slug", null));
        assertThat(repository.findBySlugAndDeletedFalse("my-slug")).isPresent();
        assertThat(repository.findBySlugAndDeletedFalse("missing")).isEmpty();
    }

    private WristbandTemplateEntity entity(String name, String slug, String projectType) {
        TemplateElement el = new TemplateElement(
            "el-1", ElementType.TEXT, 40, 120, 28, 600, 90,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
        TemplateDefinition def = new TemplateDefinition(new Canvas(203, 2233, 300), List.of(el));

        WristbandTemplateEntity e = new WristbandTemplateEntity();
        e.setId(UUID.randomUUID());
        e.setSlug(slug);
        e.setName(name);
        e.setProjectType(projectType);
        e.setDefaultPreviewColor("white");
        e.setDefinition(def);
        e.setGeneratedZpl(null);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        e.setDeleted(false);
        return e;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=WristbandTemplateRepositoryTest`
Expected: FAIL — `WristbandTemplateEntity` / `WristbandTemplateRepository` do not exist.

- [ ] **Step 3: Create the migration**

`src/main/resources/db/migration/V3__create_wristband_templates.sql`:

```sql
CREATE TABLE wristband_template (
    id                    UUID PRIMARY KEY,
    slug                  VARCHAR(255) NOT NULL UNIQUE,
    name                  VARCHAR(255) NOT NULL,
    project_type          VARCHAR(255),
    default_preview_color VARCHAR(255) NOT NULL DEFAULT 'white',
    definition            JSONB NOT NULL,
    generated_zpl         TEXT,
    created_at            TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    deleted               BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_wristband_template_project_type
    ON wristband_template (project_type) WHERE deleted = FALSE;
```

- [ ] **Step 4: Create the entity**

`src/main/java/com/stup/wristbandprinter/persistence/WristbandTemplateEntity.java`:

```java
package com.stup.wristbandprinter.persistence;

import com.stup.wristbandprinter.domain.template.TemplateDefinition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wristband_template")
public class WristbandTemplateEntity {

    @Id
    private UUID id;

    private String slug;
    private String name;
    private String projectType;
    private String defaultPreviewColor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private TemplateDefinition definition;

    @Column(columnDefinition = "text")
    private String generatedZpl;

    private Instant createdAt;
    private Instant updatedAt;
    private boolean deleted;

    protected WristbandTemplateEntity() {
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProjectType() { return projectType; }
    public void setProjectType(String projectType) { this.projectType = projectType; }

    public String getDefaultPreviewColor() { return defaultPreviewColor; }
    public void setDefaultPreviewColor(String defaultPreviewColor) { this.defaultPreviewColor = defaultPreviewColor; }

    public TemplateDefinition getDefinition() { return definition; }
    public void setDefinition(TemplateDefinition definition) { this.definition = definition; }

    public String getGeneratedZpl() { return generatedZpl; }
    public void setGeneratedZpl(String generatedZpl) { this.generatedZpl = generatedZpl; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
}
```

- [ ] **Step 5: Create the repository**

`src/main/java/com/stup/wristbandprinter/persistence/WristbandTemplateRepository.java`:

```java
package com.stup.wristbandprinter.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WristbandTemplateRepository extends JpaRepository<WristbandTemplateEntity, UUID> {

    List<WristbandTemplateEntity> findByDeletedFalseOrderByUpdatedAtDesc();

    List<WristbandTemplateEntity> findByProjectTypeAndDeletedFalseOrderByUpdatedAtDesc(String projectType);

    Optional<WristbandTemplateEntity> findByIdAndDeletedFalse(UUID id);

    Optional<WristbandTemplateEntity> findBySlugAndDeletedFalse(String slug);

    boolean existsBySlug(String slug);
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=WristbandTemplateRepositoryTest`
Expected: PASS (3 tests). This proves the `jsonb` mapping and the Flyway migration validate against Hibernate.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V3__create_wristband_templates.sql \
        src/main/java/com/stup/wristbandprinter/persistence/WristbandTemplateEntity.java \
        src/main/java/com/stup/wristbandprinter/persistence/WristbandTemplateRepository.java \
        src/test/java/com/stup/wristbandprinter/persistence/WristbandTemplateRepositoryTest.java
git commit -m "feat: persist wristband templates as jsonb"
```

---

## Task 3: DTOs

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/domain/template/UpsertTemplateRequest.java`
- Create: `src/main/java/com/stup/wristbandprinter/domain/template/TemplateSummaryResponse.java`
- Create: `src/main/java/com/stup/wristbandprinter/domain/template/TemplateDetailResponse.java`

> No standalone test — these records are exercised by the `TemplateService` and `TemplateController` tests in Tasks 4 and 5.

- [ ] **Step 1: Create the request DTO**

`src/main/java/com/stup/wristbandprinter/domain/template/UpsertTemplateRequest.java`:

```java
package com.stup.wristbandprinter.domain.template;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Create or update a wristband template")
public record UpsertTemplateRequest(
    @NotBlank(message = "name must not be blank")
    @Schema(example = "Festival Band")
    String name,

    @Schema(example = "festival", description = "Optional, non-unique grouping tag")
    String projectType,

    @Schema(example = "white", description = "Default preview background colour")
    String defaultPreviewColor,

    @NotNull(message = "definition must not be null")
    TemplateDefinition definition
) {
}
```

- [ ] **Step 2: Create the summary DTO**

`src/main/java/com/stup/wristbandprinter/domain/template/TemplateSummaryResponse.java`:

```java
package com.stup.wristbandprinter.domain.template;

import java.time.Instant;
import java.util.UUID;

/** Catalog list item — what Symfony fetches to show the template picker. */
public record TemplateSummaryResponse(
    UUID id,
    String slug,
    String name,
    String projectType,
    Instant updatedAt
) {
}
```

- [ ] **Step 3: Create the detail DTO**

`src/main/java/com/stup/wristbandprinter/domain/template/TemplateDetailResponse.java`:

```java
package com.stup.wristbandprinter.domain.template;

import java.time.Instant;
import java.util.UUID;

/** Full template, including the element model and the saved ZPL snapshot (null until Plan 2). */
public record TemplateDetailResponse(
    UUID id,
    String slug,
    String name,
    String projectType,
    String defaultPreviewColor,
    TemplateDefinition definition,
    String generatedZpl,
    Instant updatedAt
) {
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/template
git commit -m "feat: add template request/response DTOs"
```

---

## Task 4: TemplateService (CRUD + slug generation)

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/service/TemplateService.java`
- Test: `src/test/java/com/stup/wristbandprinter/service/TemplateServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/service/TemplateServiceTest.java`:

```java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.template.*;
import com.stup.wristbandprinter.persistence.WristbandTemplateEntity;
import com.stup.wristbandprinter.persistence.WristbandTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TemplateServiceTest {

    @Mock
    private WristbandTemplateRepository repository;

    private TemplateService service;

    @BeforeEach
    void setUp() {
        service = new TemplateService(repository);
        when(repository.save(any(WristbandTemplateEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_generatesKebabSlugFromName_andPersistsDefinition() {
        when(repository.existsBySlug("festival-band")).thenReturn(false);

        TemplateDetailResponse result = service.create(request("Festival Band!", "festival"));

        ArgumentCaptor<WristbandTemplateEntity> captor = ArgumentCaptor.forClass(WristbandTemplateEntity.class);
        verify(repository).save(captor.capture());
        WristbandTemplateEntity saved = captor.getValue();
        assertThat(saved.getSlug()).isEqualTo("festival-band");
        assertThat(saved.getName()).isEqualTo("Festival Band!");
        assertThat(saved.getProjectType()).isEqualTo("festival");
        assertThat(saved.getDefaultPreviewColor()).isEqualTo("white");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.isDeleted()).isFalse();
        assertThat(result.slug()).isEqualTo("festival-band");
    }

    @Test
    void create_deduplicatesSlugWhenTaken() {
        when(repository.existsBySlug("festival-band")).thenReturn(true);
        when(repository.existsBySlug("festival-band-2")).thenReturn(false);

        TemplateDetailResponse result = service.create(request("Festival Band", null));

        assertThat(result.slug()).isEqualTo("festival-band-2");
    }

    @Test
    void create_defaultsBlankPreviewColorToWhite() {
        when(repository.existsBySlug(any())).thenReturn(false);
        TemplateDetailResponse result = service.create(
            new UpsertTemplateRequest("X", null, "  ", sampleDefinition()));
        assertThat(result.defaultPreviewColor()).isEqualTo("white");
    }

    @Test
    void update_returnsEmptyWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndDeletedFalse(id)).thenReturn(Optional.empty());
        assertThat(service.update(id, request("X", null))).isEmpty();
    }

    @Test
    void update_mutatesFieldsAndBumpsUpdatedAt() {
        UUID id = UUID.randomUUID();
        WristbandTemplateEntity existing = existing(id, "old", "Old Name");
        when(repository.findByIdAndDeletedFalse(id)).thenReturn(Optional.of(existing));

        Optional<TemplateDetailResponse> result = service.update(id, request("New Name", "vip"));

        assertThat(result).isPresent();
        assertThat(existing.getName()).isEqualTo("New Name");
        assertThat(existing.getProjectType()).isEqualTo("vip");
        assertThat(existing.getSlug()).isEqualTo("old"); // slug is stable across updates
    }

    @Test
    void list_withProjectType_filtersByProjectType() {
        when(repository.findByProjectTypeAndDeletedFalseOrderByUpdatedAtDesc("festival"))
            .thenReturn(List.of(existing(UUID.randomUUID(), "a", "A")));
        assertThat(service.list("festival")).hasSize(1);
        verify(repository).findByProjectTypeAndDeletedFalseOrderByUpdatedAtDesc("festival");
        verify(repository, never()).findByDeletedFalseOrderByUpdatedAtDesc();
    }

    @Test
    void list_withoutProjectType_returnsAllActive() {
        when(repository.findByDeletedFalseOrderByUpdatedAtDesc())
            .thenReturn(List.of(existing(UUID.randomUUID(), "a", "A")));
        assertThat(service.list(null)).hasSize(1);
    }

    @Test
    void softDelete_setsDeletedFlag_andReturnsTrue() {
        UUID id = UUID.randomUUID();
        WristbandTemplateEntity existing = existing(id, "a", "A");
        when(repository.findByIdAndDeletedFalse(id)).thenReturn(Optional.of(existing));

        assertThat(service.softDelete(id)).isTrue();
        assertThat(existing.isDeleted()).isTrue();
    }

    @Test
    void softDelete_returnsFalseWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndDeletedFalse(id)).thenReturn(Optional.empty());
        assertThat(service.softDelete(id)).isFalse();
    }

    private UpsertTemplateRequest request(String name, String projectType) {
        return new UpsertTemplateRequest(name, projectType, "white", sampleDefinition());
    }

    private TemplateDefinition sampleDefinition() {
        TemplateElement el = new TemplateElement(
            "el-1", ElementType.TEXT, 40, 120, 28, 600, 90,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
        return new TemplateDefinition(new Canvas(203, 2233, 300), List.of(el));
    }

    private WristbandTemplateEntity existing(UUID id, String slug, String name) {
        WristbandTemplateEntity e = new WristbandTemplateEntity();
        e.setId(id);
        e.setSlug(slug);
        e.setName(name);
        e.setDefaultPreviewColor("white");
        e.setDefinition(sampleDefinition());
        e.setCreatedAt(java.time.Instant.now());
        e.setUpdatedAt(java.time.Instant.now());
        return e;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=TemplateServiceTest`
Expected: FAIL — `TemplateService` does not exist.

- [ ] **Step 3: Implement TemplateService**

`src/main/java/com/stup/wristbandprinter/service/TemplateService.java`:

```java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.template.TemplateDetailResponse;
import com.stup.wristbandprinter.domain.template.TemplateSummaryResponse;
import com.stup.wristbandprinter.domain.template.UpsertTemplateRequest;
import com.stup.wristbandprinter.persistence.WristbandTemplateEntity;
import com.stup.wristbandprinter.persistence.WristbandTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class TemplateService {

    private final WristbandTemplateRepository repository;

    public TemplateService(WristbandTemplateRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public TemplateDetailResponse create(UpsertTemplateRequest request) {
        Instant now = Instant.now();
        WristbandTemplateEntity entity = new WristbandTemplateEntity();
        entity.setId(UUID.randomUUID());
        entity.setSlug(uniqueSlug(request.name()));
        entity.setName(request.name());
        entity.setProjectType(blankToNull(request.projectType()));
        entity.setDefaultPreviewColor(previewColorOrDefault(request.defaultPreviewColor()));
        entity.setDefinition(request.definition());
        entity.setGeneratedZpl(null); // populated in Plan 2 once the renderer exists
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setDeleted(false);
        return toDetail(repository.save(entity));
    }

    @Transactional
    public Optional<TemplateDetailResponse> update(UUID id, UpsertTemplateRequest request) {
        return repository.findByIdAndDeletedFalse(id).map(entity -> {
            entity.setName(request.name());
            entity.setProjectType(blankToNull(request.projectType()));
            entity.setDefaultPreviewColor(previewColorOrDefault(request.defaultPreviewColor()));
            entity.setDefinition(request.definition());
            entity.setUpdatedAt(Instant.now());
            return toDetail(repository.save(entity));
        });
    }

    @Transactional(readOnly = true)
    public List<TemplateSummaryResponse> list(String projectType) {
        List<WristbandTemplateEntity> entities = (projectType == null || projectType.isBlank())
            ? repository.findByDeletedFalseOrderByUpdatedAtDesc()
            : repository.findByProjectTypeAndDeletedFalseOrderByUpdatedAtDesc(projectType);
        return entities.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public Optional<TemplateDetailResponse> getById(UUID id) {
        return repository.findByIdAndDeletedFalse(id).map(this::toDetail);
    }

    @Transactional(readOnly = true)
    public Optional<TemplateDetailResponse> getBySlug(String slug) {
        return repository.findBySlugAndDeletedFalse(slug).map(this::toDetail);
    }

    @Transactional
    public boolean softDelete(UUID id) {
        return repository.findByIdAndDeletedFalse(id).map(entity -> {
            entity.setDeleted(true);
            entity.setUpdatedAt(Instant.now());
            repository.save(entity);
            return true;
        }).orElse(false);
    }

    private String uniqueSlug(String name) {
        String base = name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
        if (base.isEmpty()) {
            base = "template";
        }
        String candidate = base;
        int suffix = 2;
        while (repository.existsBySlug(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private String previewColorOrDefault(String color) {
        return (color == null || color.isBlank()) ? "white" : color.trim();
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private TemplateDetailResponse toDetail(WristbandTemplateEntity e) {
        return new TemplateDetailResponse(e.getId(), e.getSlug(), e.getName(), e.getProjectType(),
            e.getDefaultPreviewColor(), e.getDefinition(), e.getGeneratedZpl(), e.getUpdatedAt());
    }

    private TemplateSummaryResponse toSummary(WristbandTemplateEntity e) {
        return new TemplateSummaryResponse(e.getId(), e.getSlug(), e.getName(),
            e.getProjectType(), e.getUpdatedAt());
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=TemplateServiceTest`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/TemplateService.java \
        src/test/java/com/stup/wristbandprinter/service/TemplateServiceTest.java
git commit -m "feat: add TemplateService CRUD with slug generation"
```

---

## Task 5: TemplateController (CRUD + catalog endpoints)

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/controller/TemplateController.java`
- Test: `src/test/java/com/stup/wristbandprinter/controller/TemplateControllerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/controller/TemplateControllerTest.java`:

```java
package com.stup.wristbandprinter.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stup.wristbandprinter.config.AdminProperties;
import com.stup.wristbandprinter.config.SecurityConfig;
import com.stup.wristbandprinter.domain.template.*;
import com.stup.wristbandprinter.security.ApiKeyAuthFilter;
import com.stup.wristbandprinter.security.AuthCookieService;
import com.stup.wristbandprinter.service.TemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TemplateController.class)
@Import({SecurityConfig.class, ApiKeyAuthFilter.class, AuthCookieService.class})
@EnableConfigurationProperties(AdminProperties.class)
@TestPropertySource(properties = {"security.api-key=test-key", "security.admin.password=pw"})
class TemplateControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean TemplateService templateService;

    private static final String API_KEY = "test-key";

    @Test
    void create_returns201WithDetail() throws Exception {
        TemplateDetailResponse detail = detail(UUID.randomUUID(), "festival-band");
        when(templateService.create(any())).thenReturn(detail);

        mockMvc.perform(post("/api/templates")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request("Festival Band"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.slug").value("festival-band"));
    }

    @Test
    void create_returns400_whenNameBlank() throws Exception {
        mockMvc.perform(post("/api/templates")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request(""))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void create_returns401_withoutApiKey() throws Exception {
        mockMvc.perform(post("/api/templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request("X"))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void get_returns404_whenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.getById(id)).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/templates/" + id).header("X-API-Key", API_KEY))
            .andExpect(status().isNotFound());
    }

    @Test
    void get_returns200WithDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.getById(id)).thenReturn(Optional.of(detail(id, "slug-1")));
        mockMvc.perform(get("/api/templates/" + id).header("X-API-Key", API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void list_passesProjectTypeFilter() throws Exception {
        when(templateService.list("festival"))
            .thenReturn(List.of(new TemplateSummaryResponse(
                UUID.randomUUID(), "a", "A", "festival", Instant.now())));

        mockMvc.perform(get("/api/templates?projectType=festival").header("X-API-Key", API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].projectType").value("festival"));

        verify(templateService).list("festival");
    }

    @Test
    void update_returns404_whenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.update(eq(id), any())).thenReturn(Optional.empty());
        mockMvc.perform(put("/api/templates/" + id)
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request("New"))))
            .andExpect(status().isNotFound());
    }

    @Test
    void delete_returns204_whenDeleted() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.softDelete(id)).thenReturn(true);
        mockMvc.perform(delete("/api/templates/" + id).header("X-API-Key", API_KEY))
            .andExpect(status().isNoContent());
    }

    @Test
    void delete_returns404_whenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.softDelete(id)).thenReturn(false);
        mockMvc.perform(delete("/api/templates/" + id).header("X-API-Key", API_KEY))
            .andExpect(status().isNotFound());
    }

    private UpsertTemplateRequest request(String name) {
        TemplateElement el = new TemplateElement(
            "el-1", ElementType.TEXT, 40, 120, 28, 600, 90,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
        return new UpsertTemplateRequest(name, "festival", "white",
            new TemplateDefinition(new Canvas(203, 2233, 300), List.of(el)));
    }

    private TemplateDetailResponse detail(UUID id, String slug) {
        TemplateElement el = new TemplateElement(
            "el-1", ElementType.TEXT, 40, 120, 28, 600, 90,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
        return new TemplateDetailResponse(id, slug, "Festival Band", "festival", "white",
            new TemplateDefinition(new Canvas(203, 2233, 300), List.of(el)), null, Instant.now());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=TemplateControllerTest`
Expected: FAIL — `TemplateController` does not exist.

- [ ] **Step 3: Implement TemplateController**

`src/main/java/com/stup/wristbandprinter/controller/TemplateController.java`:

```java
package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.domain.template.TemplateDetailResponse;
import com.stup.wristbandprinter.domain.template.TemplateSummaryResponse;
import com.stup.wristbandprinter.domain.template.UpsertTemplateRequest;
import com.stup.wristbandprinter.service.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/templates")
@Tag(name = "Templates", description = "Create, manage and browse wristband templates")
@SecurityRequirement(name = "ApiKeyAuth")
public class TemplateController {

    private final TemplateService templateService;

    public TemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @PostMapping
    @Operation(summary = "Create a wristband template")
    public ResponseEntity<TemplateDetailResponse> create(@Valid @RequestBody UpsertTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templateService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing template")
    public ResponseEntity<TemplateDetailResponse> update(@PathVariable UUID id,
                                                         @Valid @RequestBody UpsertTemplateRequest request) {
        return templateService.update(id, request)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    @Operation(summary = "List templates (catalog), optionally filtered by project type")
    public ResponseEntity<List<TemplateSummaryResponse>> list(
            @RequestParam(required = false) String projectType) {
        return ResponseEntity.ok(templateService.list(projectType));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a template's full definition")
    public ResponseEntity<TemplateDetailResponse> get(@PathVariable UUID id) {
        return templateService.getById(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete a template")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return templateService.softDelete(id)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=TemplateControllerTest`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/controller/TemplateController.java \
        src/test/java/com/stup/wristbandprinter/controller/TemplateControllerTest.java
git commit -m "feat: add /api/templates CRUD and catalog endpoints"
```

---

## Task 6: Document endpoints and run the full suite

**Files:**
- Modify: `README.md` (the "API endpoints" table)

- [ ] **Step 1: Add the template rows to the README endpoint table**

In `README.md`, under the `## API endpoints` table (after the existing wristband rows), add:

```markdown
| `POST` | `/api/templates` | Create a wristband template → `201 + detail` |
| `PUT` | `/api/templates/{id}` | Update a template → `200` / `404` |
| `GET` | `/api/templates` | List templates (catalog); `?projectType=` filters |
| `GET` | `/api/templates/{id}` | Get a template's full definition → `200` / `404` |
| `DELETE` | `/api/templates/{id}` | Soft-delete a template → `204` / `404` |
```

Also add a short note below the table:

```markdown
> **Templates (Plan 1):** Templates are stored as a declarative JSON element model. ZPL
> rendering, logo assets, PNG previews and `/print` template selection arrive in Plan 2.
```

- [ ] **Step 2: Run the entire test suite**

Run: `./mvnw -q test`
Expected: PASS — all existing tests plus the four new test classes (`TemplateDefinitionJsonTest`, `WristbandTemplateRepositoryTest`, `TemplateServiceTest`, `TemplateControllerTest`). Requires Docker running for the Testcontainers test.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: document template CRUD/catalog endpoints"
```

---

## Done — Plan 1 deliverable

Templates can be created, updated, listed (filtered by project type), fetched, and soft-deleted
via `/api/templates`, persisted as `jsonb` and validated against the Flyway schema. The
`generated_zpl` column exists but stays null until **Plan 2** adds `TemplateZplRenderer`,
`TemplateAssetService`, PNG preview endpoints, and the `/print` `templateId` routing.

**Self-review notes (verified while writing):**
- Spec coverage: §4 (data model) ✓, §7 catalog/CRUD rows ✓; §5/§6 (renderer, assets) and §8 (editor) intentionally deferred to Plans 2–3.
- Type consistency: the 16-arg `TemplateElement` canonical constructor is used identically in every test and service method; `findByIdAndDeletedFalse`, `findBySlugAndDeletedFalse`, `existsBySlug` names match between repository, service, and tests.
- No placeholders: every step contains complete, compilable code.

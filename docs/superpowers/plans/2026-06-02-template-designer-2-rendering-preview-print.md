# Wristband Template Designer — Plan 2: Rendering, Assets, Preview & Print

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn a stored `TemplateDefinition` into ZPL and a rendered PNG, store uploaded logo assets, save the generated-ZPL snapshot on every template save, expose template preview + asset endpoints, and let `/print` select a template by id — all without changing the existing fixed-layout path.

**Architecture:** A new `TemplateZplRenderer` walks `definition.elements` and emits ZPL, substituting either real data or `${BINDING}` placeholders. The `^GF` graphic encoding in `LogoConversionService` is extracted into a reusable `GfImageEncoder`; `TemplateAssetService` stores uploaded PNGs (`template_asset` table) and converts any asset to a sized/rotated `^GF`. `TemplateService` gains the renderer to persist `generated_zpl` on save. Previews reuse `LabelaryPreviewService` (with a dimensioned overload) plus a `PreviewColorService` that tints the white Labelary PNG to the chosen stock colour. `PrintQueueService` routes through the renderer when a `templateId` is present, else the legacy `ZplGeneratorService`.

**Code organization:** All new code stays in the `com.stup.wristbandprinter.editor` feature package (`service`, `persistence`, `controller`). The two cross-cutting touch-points outside it are `service.LogoConversionService` (refactor to use the new encoder) and `service.PrintQueueService` + `domain.WristbandPrintRequest` (the print seam).

**Tech Stack:** Java 21, Spring Boot 3 (Web, Data JPA, Validation), Hibernate 6, Flyway, PostgreSQL 16, `java.awt`/`ImageIO` for image work, JUnit 5 + AssertJ + Mockito, Testcontainers, `MockRestServiceServer` for Labelary.

**Scope of this plan (Plan 2 of 3):**
- IN: `GfImageEncoder`, `TemplateAssetService` + `template_asset` table, `TemplateZplRenderer`, save-time ZPL snapshot, `PreviewColorService`, dimensioned Labelary overload, template preview endpoints, asset upload/fetch endpoints, `/print` `templateId` routing, docs.
- OUT (Plan 3): the Konva.js editor UI.

**Conventions (verified against the codebase):**
- `ddl-auto: validate` — the Flyway migration is authoritative for column types. `byte[]` → `bytea`, `Instant` → `TIMESTAMP(6) WITH TIME ZONE`.
- ZPL setup mirrors `ZplGeneratorService`: `^XA`, `^PW<width>`, `^LL<length>`, `^CI28`, body, `^XZ`. User text is sanitized by stripping `^` and `~`.
- ZPL field orientation letters: `N`=0°, `R`=90°, `I`=180°, `B`=270° (bottom-up). Map `rotation` → letter.
- Labelary URL: `/v1/printers/{dpmm}dpmm/labels/{w}x{h}/0/`; `w`/`h` are inches = dots / dpi; `dpmm` = round(dpi / 25.4).
- Service unit tests use Mockito + AssertJ; Labelary tests use `MockRestServiceServer`; persistence tests use Testcontainers `postgres:16-alpine` with `@DataJpaTest`; controller tests use `@WebMvcTest` + `@Import({SecurityConfig.class, ApiKeyAuthFilter.class, AuthCookieService.class})` + `@TestPropertySource(security.api-key=test-key, security.admin.password=pw)` and the `X-API-Key` header.

---

## File Structure

**Create:**
- `src/main/java/com/stup/wristbandprinter/editor/service/GfImageEncoder.java` — PNG/BufferedImage → `^GF` string
- `src/main/java/com/stup/wristbandprinter/editor/service/TemplateZplRenderer.java` — definition → ZPL
- `src/main/java/com/stup/wristbandprinter/editor/service/TemplateAssetService.java` — store assets + asset → `^GF`
- `src/main/java/com/stup/wristbandprinter/editor/service/PreviewColorService.java` — tint a white PNG to a stock colour
- `src/main/java/com/stup/wristbandprinter/editor/service/SampleData.java` — canned `WristbandData` for catalog previews
- `src/main/java/com/stup/wristbandprinter/editor/persistence/TemplateAssetEntity.java`
- `src/main/java/com/stup/wristbandprinter/editor/persistence/TemplateAssetRepository.java`
- `src/main/java/com/stup/wristbandprinter/editor/domain/AssetResponse.java` — asset upload response DTO
- `src/main/resources/db/migration/V4__create_template_assets.sql`
- Tests mirroring each of the above under `src/test/java/com/stup/wristbandprinter/editor/...`

**Modify:**
- `src/main/java/com/stup/wristbandprinter/service/LogoConversionService.java` — delegate `^GF` encoding to `GfImageEncoder`
- `src/main/java/com/stup/wristbandprinter/service/LabelaryPreviewService.java` — add dimensioned `renderPreview` overload
- `src/main/java/com/stup/wristbandprinter/editor/service/TemplateService.java` — inject renderer, set `generated_zpl` on create/update
- `src/main/java/com/stup/wristbandprinter/editor/controller/TemplateController.java` — preview + asset endpoints
- `src/main/java/com/stup/wristbandprinter/domain/WristbandPrintRequest.java` — optional `templateId`
- `src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java` — render via template when `templateId` present
- `docs/template-designer.md`, `README.md` — status updates

---

## Task 1: Extract `GfImageEncoder` and refactor `LogoConversionService`

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/editor/service/GfImageEncoder.java`
- Modify: `src/main/java/com/stup/wristbandprinter/service/LogoConversionService.java`
- Test: `src/test/java/com/stup/wristbandprinter/editor/service/GfImageEncoderTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/editor/service/GfImageEncoderTest.java`:

```java
package com.stup.wristbandprinter.editor.service;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class GfImageEncoderTest {

    private final GfImageEncoder encoder = new GfImageEncoder();

    @Test
    void encode_emitsGfaHeaderWithCorrectByteCounts() {
        // 8x1 all-black image → 1 byte per row, 1 row → totalBytes=1, bytesPerRow=1
        BufferedImage img = new BufferedImage(8, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 8, 1);
        g.dispose();

        String gf = encoder.encode(img);

        assertThat(gf).startsWith("^GFA,1,1,1,");
        assertThat(gf).endsWith("FF"); // 8 black bits = 0xFF
    }

    @Test
    void encode_whitePixelsProduceZeroBytes() {
        BufferedImage img = new BufferedImage(8, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 8, 1);
        g.dispose();

        assertThat(encoder.encode(img)).isEqualTo("^GFA,1,1,1,00");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=GfImageEncoderTest`
Expected: FAIL — `GfImageEncoder` does not exist.

- [ ] **Step 3: Create `GfImageEncoder`** (logic lifted verbatim from `LogoConversionService.encodeAsGf`)

`src/main/java/com/stup/wristbandprinter/editor/service/GfImageEncoder.java`:

```java
package com.stup.wristbandprinter.editor.service;

import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;

/**
 * Encodes a 1-bit representation of an image as a ZPL {@code ^GFA} graphic field.
 * A pixel is "on" (printed) when its luminance is below the mid-point.
 */
@Component
public class GfImageEncoder {

    public String encode(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();
        int bytesPerRow = (width + 7) / 8;
        StringBuilder hex = new StringBuilder();

        for (int y = 0; y < height; y++) {
            for (int bx = 0; bx < bytesPerRow; bx++) {
                int b = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int x = bx * 8 + bit;
                    if (x < width) {
                        int rgb = img.getRGB(x, y);
                        int r = (rgb >> 16) & 0xFF;
                        int gv = (rgb >> 8) & 0xFF;
                        int bv = rgb & 0xFF;
                        int luminance = (int) (0.299 * r + 0.587 * gv + 0.114 * bv);
                        if (luminance < 128) {
                            b |= (1 << (7 - bit));
                        }
                    }
                }
                hex.append(String.format("%02X", b));
            }
        }

        int totalBytes = bytesPerRow * height;
        return String.format("^GFA,%d,%d,%d,%s", totalBytes, totalBytes, bytesPerRow, hex);
    }
}
```

- [ ] **Step 4: Refactor `LogoConversionService` to delegate to the encoder**

In `src/main/java/com/stup/wristbandprinter/service/LogoConversionService.java`:

1. Add an import: `import com.stup.wristbandprinter.editor.service.GfImageEncoder;`
2. Add a field and constructor parameter:

```java
    private final WristbandProperties props;
    private final GfImageEncoder gfImageEncoder;
    private String cachedGfCommand;
    private int logoHeightDots;

    public LogoConversionService(WristbandProperties props, GfImageEncoder gfImageEncoder) {
        this.props = props;
        this.gfImageEncoder = gfImageEncoder;
    }
```

3. Replace the call `this.cachedGfCommand = encodeAsGf(rotated);` with `this.cachedGfCommand = gfImageEncoder.encode(rotated);`
4. Delete the now-unused private `encodeAsGf(BufferedImage img)` method entirely.

- [ ] **Step 5: Run the encoder test and the existing logo test**

Run: `./mvnw -q test -Dtest=GfImageEncoderTest,LogoConversionServiceTest`
Expected: PASS. `LogoConversionServiceTest` (constructed via `new LogoConversionService(props)`) will now FAIL to compile because the constructor changed — update its two `new LogoConversionService(props)` calls to `new LogoConversionService(props, new GfImageEncoder())`.

- [ ] **Step 6: Re-run to confirm both pass**

Run: `./mvnw -q test -Dtest=GfImageEncoderTest,LogoConversionServiceTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/editor/service/GfImageEncoder.java \
        src/main/java/com/stup/wristbandprinter/service/LogoConversionService.java \
        src/test/java/com/stup/wristbandprinter/editor/service/GfImageEncoderTest.java \
        src/test/java/com/stup/wristbandprinter/service/LogoConversionServiceTest.java
git commit -m "refactor: extract GfImageEncoder from LogoConversionService"
```

---

## Task 2: `template_asset` migration, entity, repository

**Files:**
- Create: `src/main/resources/db/migration/V4__create_template_assets.sql`
- Create: `src/main/java/com/stup/wristbandprinter/editor/persistence/TemplateAssetEntity.java`
- Create: `src/main/java/com/stup/wristbandprinter/editor/persistence/TemplateAssetRepository.java`
- Test: `src/test/java/com/stup/wristbandprinter/editor/persistence/TemplateAssetRepositoryTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/editor/persistence/TemplateAssetRepositoryTest.java`:

```java
package com.stup.wristbandprinter.editor.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TemplateAssetRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TemplateAssetRepository repository;

    @Test
    void persistsAndReadsBackPngBytes() {
        TemplateAssetEntity e = new TemplateAssetEntity();
        e.setId(UUID.randomUUID());
        e.setName("logo.png");
        e.setPng(new byte[]{1, 2, 3, 4});
        e.setWidth(100);
        e.setHeight(50);
        e.setCreatedAt(Instant.now());

        UUID id = repository.save(e).getId();

        TemplateAssetEntity loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("logo.png");
        assertThat(loaded.getPng()).containsExactly(1, 2, 3, 4);
        assertThat(loaded.getWidth()).isEqualTo(100);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=TemplateAssetRepositoryTest`
Expected: FAIL — entity/repository missing.

- [ ] **Step 3: Create the migration**

`src/main/resources/db/migration/V4__create_template_assets.sql`:

```sql
CREATE TABLE template_asset (
    id         UUID PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    png        BYTEA NOT NULL,
    width      INTEGER NOT NULL,
    height     INTEGER NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
```

- [ ] **Step 4: Create the entity**

`src/main/java/com/stup/wristbandprinter/editor/persistence/TemplateAssetEntity.java`:

```java
package com.stup.wristbandprinter.editor.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "template_asset")
public class TemplateAssetEntity {

    @Id
    private UUID id;

    private String name;

    @Column(columnDefinition = "bytea")
    private byte[] png;

    private int width;
    private int height;
    private Instant createdAt;

    public TemplateAssetEntity() {
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public byte[] getPng() { return png; }
    public void setPng(byte[] png) { this.png = png; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 5: Create the repository**

`src/main/java/com/stup/wristbandprinter/editor/persistence/TemplateAssetRepository.java`:

```java
package com.stup.wristbandprinter.editor.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TemplateAssetRepository extends JpaRepository<TemplateAssetEntity, UUID> {
}
```

- [ ] **Step 6: Run to verify it passes**

Run: `./mvnw -q test -Dtest=TemplateAssetRepositoryTest`
Expected: PASS (requires Docker).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V4__create_template_assets.sql \
        src/main/java/com/stup/wristbandprinter/editor/persistence/TemplateAssetEntity.java \
        src/main/java/com/stup/wristbandprinter/editor/persistence/TemplateAssetRepository.java \
        src/test/java/com/stup/wristbandprinter/editor/persistence/TemplateAssetRepositoryTest.java
git commit -m "feat: persist template logo assets"
```

---

## Task 3: `TemplateAssetService` (store + asset → `^GF`)

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/editor/domain/AssetResponse.java`
- Create: `src/main/java/com/stup/wristbandprinter/editor/service/TemplateAssetService.java`
- Test: `src/test/java/com/stup/wristbandprinter/editor/service/TemplateAssetServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/editor/service/TemplateAssetServiceTest.java`:

```java
package com.stup.wristbandprinter.editor.service;

import com.stup.wristbandprinter.editor.persistence.TemplateAssetEntity;
import com.stup.wristbandprinter.editor.persistence.TemplateAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateAssetServiceTest {

    @Mock
    private TemplateAssetRepository repository;

    private TemplateAssetService service;

    @BeforeEach
    void setUp() {
        service = new TemplateAssetService(repository, new GfImageEncoder());
    }

    @Test
    void store_decodesDimensionsAndPersists() throws Exception {
        byte[] png = blackPng(40, 20);
        when(repository.save(any(TemplateAssetEntity.class))).thenAnswer(i -> i.getArgument(0));

        var response = service.store("logo.png", png);

        assertThat(response.width()).isEqualTo(40);
        assertThat(response.height()).isEqualTo(20);
        assertThat(response.id()).isNotNull();
    }

    @Test
    void store_rejectsUndecodableBytes() {
        assertThatThrownBy(() -> service.store("x.png", new byte[]{0, 1, 2}))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gfCommand_returnsGfaForStoredAsset() throws Exception {
        UUID id = UUID.randomUUID();
        TemplateAssetEntity e = new TemplateAssetEntity();
        e.setId(id);
        e.setName("logo.png");
        e.setPng(blackPng(40, 20));
        e.setWidth(40);
        e.setHeight(20);
        when(repository.findById(id)).thenReturn(Optional.of(e));

        String gf = service.gfCommand(id, 80, 40, 0);

        assertThat(gf).startsWith("^GFA,");
    }

    @Test
    void gfCommand_returnsEmptyWhenAssetMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThat(service.gfCommand(id, 80, 40, 0)).isEmpty();
    }

    private byte[] blackPng(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=TemplateAssetServiceTest`
Expected: FAIL — `AssetResponse` / `TemplateAssetService` missing.

- [ ] **Step 3: Create the response DTO**

`src/main/java/com/stup/wristbandprinter/editor/domain/AssetResponse.java`:

```java
package com.stup.wristbandprinter.editor.domain;

import java.util.UUID;

public record AssetResponse(UUID id, String name, int width, int height) {
}
```

- [ ] **Step 4: Implement the service**

`src/main/java/com/stup/wristbandprinter/editor/service/TemplateAssetService.java`:

```java
package com.stup.wristbandprinter.editor.service;

import com.stup.wristbandprinter.editor.domain.AssetResponse;
import com.stup.wristbandprinter.editor.persistence.TemplateAssetEntity;
import com.stup.wristbandprinter.editor.persistence.TemplateAssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class TemplateAssetService {

    private final TemplateAssetRepository repository;
    private final GfImageEncoder gfImageEncoder;

    public TemplateAssetService(TemplateAssetRepository repository, GfImageEncoder gfImageEncoder) {
        this.repository = repository;
        this.gfImageEncoder = gfImageEncoder;
    }

    @Transactional
    public AssetResponse store(String name, byte[] png) {
        BufferedImage img = decode(png);
        TemplateAssetEntity e = new TemplateAssetEntity();
        e.setId(UUID.randomUUID());
        e.setName(name);
        e.setPng(png);
        e.setWidth(img.getWidth());
        e.setHeight(img.getHeight());
        e.setCreatedAt(Instant.now());
        TemplateAssetEntity saved = repository.save(e);
        return new AssetResponse(saved.getId(), saved.getName(), saved.getWidth(), saved.getHeight());
    }

    @Transactional(readOnly = true)
    public Optional<byte[]> rawPng(UUID id) {
        return repository.findById(id).map(TemplateAssetEntity::getPng);
    }

    /**
     * Returns the {@code ^GF...} graphic command for the asset scaled to the target size and
     * rotated by the given multiple of 90°, or an empty string if the asset is unknown.
     */
    @Transactional(readOnly = true)
    public String gfCommand(UUID assetId, int targetWidthDots, int targetHeightDots, int rotation) {
        return repository.findById(assetId)
            .map(e -> {
                BufferedImage img = decode(e.getPng());
                BufferedImage scaled = scale(img, Math.max(1, targetWidthDots), Math.max(1, targetHeightDots));
                BufferedImage rotated = rotate(scaled, ((rotation % 360) + 360) % 360);
                return gfImageEncoder.encode(rotated);
            })
            .orElse("");
    }

    private BufferedImage decode(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) {
                throw new IllegalArgumentException("Could not decode image bytes as a supported format");
            }
            return img;
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read image bytes: " + e.getMessage(), e);
        }
    }

    private BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return result;
    }

    private BufferedImage rotate(BufferedImage img, int degrees) {
        if (degrees == 0) {
            return img;
        }
        boolean swap = degrees == 90 || degrees == 270;
        int w = swap ? img.getHeight() : img.getWidth();
        int h = swap ? img.getWidth() : img.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.translate((w - img.getWidth()) / 2.0, (h - img.getHeight()) / 2.0);
        g.rotate(Math.toRadians(degrees), img.getWidth() / 2.0, img.getHeight() / 2.0);
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return result;
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./mvnw -q test -Dtest=TemplateAssetServiceTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/editor/domain/AssetResponse.java \
        src/main/java/com/stup/wristbandprinter/editor/service/TemplateAssetService.java \
        src/test/java/com/stup/wristbandprinter/editor/service/TemplateAssetServiceTest.java
git commit -m "feat: store logo assets and convert them to ZPL graphics"
```

---

## Task 4: `TemplateZplRenderer`

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/editor/service/TemplateZplRenderer.java`
- Test: `src/test/java/com/stup/wristbandprinter/editor/service/TemplateZplRendererTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/editor/service/TemplateZplRendererTest.java`:

```java
package com.stup.wristbandprinter.editor.service;

import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.editor.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateZplRendererTest {

    @Mock
    private TemplateAssetService assetService;

    private TemplateZplRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new TemplateZplRenderer(assetService);
    }

    private final WristbandData data = new WristbandData(
        "Pukkelpop 2026", "Jan", "Janssens", "STUP vzw", "12345");

    private TemplateDefinition def(TemplateElement... els) {
        return new TemplateDefinition(new Canvas(203, 2233, 300), List.of(els));
    }

    @Test
    void render_wrapsWithLabelSetupAndDimensions() {
        String zpl = renderer.render(def(), data);
        assertThat(zpl).startsWith("^XA");
        assertThat(zpl).contains("^PW203").contains("^LL2233").contains("^CI28");
        assertThat(zpl).endsWith("^XZ");
    }

    @Test
    void render_boundText_substitutesDataAndMapsRotationToOrientation() {
        TemplateElement el = new TemplateElement("t", ElementType.TEXT, 40, 120, 28, 600, 90,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
        String zpl = renderer.render(def(el), data);
        assertThat(zpl).contains("^FO40,120");
        assertThat(zpl).contains("^A0R,28,28");      // 90° → R
        assertThat(zpl).contains("^FDJan Janssens^FS");
    }

    @Test
    void render_staticText_usesLiteralValue() {
        TemplateElement el = new TemplateElement("s", ElementType.STATIC_TEXT, 10, 10, 20, 100, 0,
            null, "STAFF", 24, "0", null, null, null, null, null);
        assertThat(renderer.render(def(el), data)).contains("^A0N,24,24").contains("^FDSTAFF^FS");
    }

    @Test
    void render_barcode_emitsBcWithValue() {
        TemplateElement el = new TemplateElement("b", ElementType.BARCODE, 0, 0, 100, 400, 270,
            DataBinding.BARCODE_VALUE, null, null, null, "CODE128", false, null, null, null);
        String zpl = renderer.render(def(el), data);
        assertThat(zpl).contains("^BCB,400,N,N,N");   // 270° → B, height 400, no HRI
        assertThat(zpl).contains("^FD12345^FS");
    }

    @Test
    void render_shape_emitsGraphicBox() {
        TemplateElement el = new TemplateElement("g", ElementType.SHAPE, 5, 6, 180, 4, 0,
            null, null, null, null, null, null, null, ShapeType.LINE, 4);
        assertThat(renderer.render(def(el), data)).contains("^FO5,6").contains("^GB180,4,4");
    }

    @Test
    void render_image_delegatesToAssetService() {
        UUID assetId = UUID.randomUUID();
        when(assetService.gfCommand(eq(assetId), eq(150), eq(80), eq(0))).thenReturn("^GFA,1,1,1,FF");
        TemplateElement el = new TemplateElement("i", ElementType.IMAGE, 12, 14, 150, 80, 0,
            null, null, null, null, null, null, assetId, null, null);
        assertThat(renderer.render(def(el), data)).contains("^FO12,14").contains("^GFA,1,1,1,FF");
    }

    @Test
    void renderTemplate_withNullData_emitsPlaceholders() {
        TemplateElement el = new TemplateElement("t", ElementType.TEXT, 40, 120, 28, 600, 0,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
        assertThat(renderer.renderTemplate(def(el))).contains("^FD${FULL_NAME}^FS");
    }

    @Test
    void render_sanitizesCaretAndTilde() {
        WristbandData dirty = new WristbandData("E", "a^b", "c~d", "A", "1");
        TemplateElement el = new TemplateElement("t", ElementType.TEXT, 0, 0, 20, 100, 0,
            DataBinding.FULL_NAME, null, 20, "0", null, null, null, null, null);
        assertThat(renderer.render(def(el), dirty)).contains("^FDab cd^FS");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=TemplateZplRendererTest`
Expected: FAIL — `TemplateZplRenderer` missing.

- [ ] **Step 3: Implement the renderer**

`src/main/java/com/stup/wristbandprinter/editor/service/TemplateZplRenderer.java`:

```java
package com.stup.wristbandprinter.editor.service;

import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.editor.domain.DataBinding;
import com.stup.wristbandprinter.editor.domain.TemplateDefinition;
import com.stup.wristbandprinter.editor.domain.TemplateElement;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

/**
 * Renders a {@link TemplateDefinition} to ZPL. With a {@link WristbandData} the bound fields
 * carry real values; with {@link #renderTemplate} they carry {@code ${BINDING}} placeholders
 * (used for the saved snapshot).
 */
@Service
public class TemplateZplRenderer {

    private final TemplateAssetService assetService;

    public TemplateZplRenderer(TemplateAssetService assetService) {
        this.assetService = assetService;
    }

    /** Render with real data substituted into bound fields. */
    public String render(TemplateDefinition def, WristbandData data) {
        return render(def, toMap(data));
    }

    /** Render with {@code ${BINDING}} placeholders instead of data (for the saved snapshot). */
    public String renderTemplate(TemplateDefinition def) {
        return render(def, null);
    }

    private String render(TemplateDefinition def, Map<DataBinding, String> data) {
        StringBuilder zpl = new StringBuilder();
        zpl.append("^XA");
        zpl.append("^PW").append(def.canvas().widthDots());
        zpl.append("^LL").append(def.canvas().lengthDots());
        zpl.append("^CI28");

        for (TemplateElement el : def.elements()) {
            switch (el.type()) {
                case TEXT, STATIC_TEXT -> appendText(zpl, el, data);
                case BARCODE -> appendBarcode(zpl, el, data);
                case IMAGE -> appendImage(zpl, el);
                case SHAPE -> appendShape(zpl, el);
            }
        }

        zpl.append("^XZ");
        return zpl.toString();
    }

    private void appendText(StringBuilder zpl, TemplateElement el, Map<DataBinding, String> data) {
        int size = el.fontSize() == null ? 24 : el.fontSize();
        String font = el.font() == null ? "0" : el.font();
        String text = el.type() == com.stup.wristbandprinter.editor.domain.ElementType.STATIC_TEXT
            ? sanitize(el.value())
            : valueFor(el.binding(), data);
        zpl.append(String.format("^FO%d,%d", el.x(), el.y()));
        zpl.append(String.format("^A%s%s,%d,%d", font, orientation(el.rotation()), size, size));
        zpl.append(String.format("^FD%s^FS", text));
    }

    private void appendBarcode(StringBuilder zpl, TemplateElement el, Map<DataBinding, String> data) {
        String hri = Boolean.TRUE.equals(el.showHumanReadable()) ? "Y" : "N";
        zpl.append(String.format("^FO%d,%d", el.x(), el.y()));
        zpl.append(String.format("^BC%s,%d,%s,N,N", orientation(el.rotation()), el.heightDots(), hri));
        zpl.append(String.format("^FD%s^FS", valueFor(el.binding(), data)));
    }

    private void appendImage(StringBuilder zpl, TemplateElement el) {
        if (el.assetId() == null) {
            return;
        }
        String gf = assetService.gfCommand(el.assetId(), el.widthDots(), el.heightDots(), el.rotation());
        if (!gf.isEmpty()) {
            zpl.append(String.format("^FO%d,%d", el.x(), el.y()));
            zpl.append(gf);
        }
    }

    private void appendShape(StringBuilder zpl, TemplateElement el) {
        int thickness = el.thicknessDots() == null ? 1 : el.thicknessDots();
        zpl.append(String.format("^FO%d,%d", el.x(), el.y()));
        zpl.append(String.format("^GB%d,%d,%d^FS", el.widthDots(), el.heightDots(), thickness));
    }

    /** Bound value: real data when present, otherwise a ${BINDING} placeholder. */
    private String valueFor(DataBinding binding, Map<DataBinding, String> data) {
        if (binding == null) {
            return "";
        }
        if (data == null) {
            return "${" + binding.name() + "}";
        }
        return sanitize(data.getOrDefault(binding, ""));
    }

    private Map<DataBinding, String> toMap(WristbandData d) {
        Map<DataBinding, String> m = new EnumMap<>(DataBinding.class);
        m.put(DataBinding.EVENT_NAME, d.eventName());
        m.put(DataBinding.FIRST_NAME, d.firstName());
        m.put(DataBinding.LAST_NAME, d.lastName());
        m.put(DataBinding.FULL_NAME, d.firstName() + " " + d.lastName());
        m.put(DataBinding.ASSOCIATION_NAME, d.associationName());
        m.put(DataBinding.BARCODE_VALUE, d.barcodeValue());
        return m;
    }

    private char orientation(int rotation) {
        return switch (((rotation % 360) + 360) % 360) {
            case 90 -> 'R';
            case 180 -> 'I';
            case 270 -> 'B';
            default -> 'N';
        };
    }

    private String sanitize(String text) {
        return text == null ? "" : text.replaceAll("[\\^~]", "");
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=TemplateZplRendererTest`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/editor/service/TemplateZplRenderer.java \
        src/test/java/com/stup/wristbandprinter/editor/service/TemplateZplRendererTest.java
git commit -m "feat: render template definitions to ZPL"
```

---

## Task 5: Save the generated-ZPL snapshot in `TemplateService`

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/editor/service/TemplateService.java`
- Modify: `src/test/java/com/stup/wristbandprinter/editor/service/TemplateServiceTest.java`

- [ ] **Step 1: Add a failing test**

In `TemplateServiceTest`, add a `@Mock TemplateZplRenderer renderer;` field, change `setUp()` to `service = new TemplateService(repository, renderer);`, and in `setUp()` add:

```java
        lenient().when(renderer.renderTemplate(any())).thenReturn("^XA^FD${FULL_NAME}^FS^XZ");
```

Then add this test:

```java
    @Test
    void create_savesGeneratedZplSnapshot() {
        when(repository.existsBySlug(any())).thenReturn(false);
        TemplateDetailResponse result = service.create(request("Festival Band", "festival"));
        assertThat(result.generatedZpl()).isEqualTo("^XA^FD${FULL_NAME}^FS^XZ");
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=TemplateServiceTest`
Expected: FAIL — `TemplateService` constructor still takes one arg; `generatedZpl()` is null.

- [ ] **Step 3: Wire the renderer into `TemplateService`**

In `TemplateService`:

1. Add the field and constructor parameter:

```java
    private final WristbandTemplateRepository repository;
    private final TemplateZplRenderer renderer;

    public TemplateService(WristbandTemplateRepository repository, TemplateZplRenderer renderer) {
        this.repository = repository;
        this.renderer = renderer;
    }
```

2. In `create`, replace `entity.setGeneratedZpl(null);` with:

```java
        entity.setGeneratedZpl(renderer.renderTemplate(request.definition()));
```

3. In `update`, after `entity.setDefinition(request.definition());` add:

```java
            entity.setGeneratedZpl(renderer.renderTemplate(request.definition()));
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=TemplateServiceTest`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/editor/service/TemplateService.java \
        src/test/java/com/stup/wristbandprinter/editor/service/TemplateServiceTest.java
git commit -m "feat: persist generated ZPL snapshot on template save"
```

---

## Task 6: `PreviewColorService` (tint the Labelary PNG)

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/editor/service/PreviewColorService.java`
- Test: `src/test/java/com/stup/wristbandprinter/editor/service/PreviewColorServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/editor/service/PreviewColorServiceTest.java`:

```java
package com.stup.wristbandprinter.editor.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewColorServiceTest {

    private final PreviewColorService service = new PreviewColorService();

    @Test
    void tint_whiteOrBlank_returnsBytesUnchanged() throws Exception {
        byte[] png = png(Color.WHITE, Color.BLACK);
        assertThat(service.tint(png, "white")).isEqualTo(png);
        assertThat(service.tint(png, "  ")).isEqualTo(png);
        assertThat(service.tint(png, null)).isEqualTo(png);
    }

    @Test
    void tint_namedColour_recoloursWhitePixelsButKeepsBlack() throws Exception {
        byte[] png = png(Color.WHITE, Color.BLACK);
        BufferedImage out = read(service.tint(png, "red"));

        // pixel (0,0) was white → becomes red; pixel (1,0) was black → stays dark
        assertThat(new Color(out.getRGB(0, 0))).isEqualTo(Color.RED);
        Color dark = new Color(out.getRGB(1, 0));
        assertThat(dark.getRed() + dark.getGreen() + dark.getBlue()).isLessThan(60);
    }

    @Test
    void tint_hexColour_isAccepted() throws Exception {
        byte[] png = png(Color.WHITE, Color.BLACK);
        BufferedImage out = read(service.tint(png, "#00FF00"));
        assertThat(new Color(out.getRGB(0, 0))).isEqualTo(Color.GREEN);
    }

    private byte[] png(Color left, Color right) throws Exception {
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, left.getRGB());
        img.setRGB(1, 0, right.getRGB());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private BufferedImage read(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=PreviewColorServiceTest`
Expected: FAIL — `PreviewColorService` missing.

- [ ] **Step 3: Implement the service**

`src/main/java/com/stup/wristbandprinter/editor/service/PreviewColorService.java`:

```java
package com.stup.wristbandprinter.editor.service;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Map;

/**
 * Tints a Labelary preview PNG (black content on white) so the background reflects the
 * physical wristband stock colour. White content blends to the chosen colour; dark content
 * stays dark. White/blank/null is a no-op (Labelary already renders on white).
 */
@Service
public class PreviewColorService {

    private static final Map<String, Color> PALETTE = Map.of(
        "white", Color.WHITE,
        "red", Color.RED,
        "blue", new Color(0x1E, 0x88, 0xE5),
        "green", new Color(0x43, 0xA0, 0x47),
        "yellow", new Color(0xFD, 0xD8, 0x35),
        "orange", new Color(0xFB, 0x8C, 0x00),
        "pink", new Color(0xEC, 0x40, 0x7A),
        "black", Color.BLACK
    );

    public byte[] tint(byte[] png, String color) {
        if (color == null || color.isBlank() || color.trim().equalsIgnoreCase("white")) {
            return png;
        }
        Color bg = resolve(color.trim());
        if (bg == null) {
            return png; // unknown colour name → leave unchanged rather than fail the preview
        }
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
            if (src == null) {
                return png;
            }
            BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < src.getHeight(); y++) {
                for (int x = 0; x < src.getWidth(); x++) {
                    int rgb = src.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                    double darkness = 1.0 - ((0.299 * r + 0.587 * g + 0.114 * b) / 255.0);
                    int nr = (int) Math.round(bg.getRed() * (1 - darkness));
                    int ng = (int) Math.round(bg.getGreen() * (1 - darkness));
                    int nb = (int) Math.round(bg.getBlue() * (1 - darkness));
                    out.setRGB(x, y, (nr << 16) | (ng << 8) | nb);
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(out, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            return png;
        }
    }

    private Color resolve(String color) {
        if (color.startsWith("#")) {
            try {
                return Color.decode(color);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return PALETTE.get(color.toLowerCase(Locale.ROOT));
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=PreviewColorServiceTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/editor/service/PreviewColorService.java \
        src/test/java/com/stup/wristbandprinter/editor/service/PreviewColorServiceTest.java
git commit -m "feat: tint preview PNG to wristband stock colour"
```

---

## Task 7: Dimensioned Labelary preview overload

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/service/LabelaryPreviewService.java`
- Test: `src/test/java/com/stup/wristbandprinter/service/LabelaryPreviewServiceTest.java`

- [ ] **Step 1: Add a failing test**

In `LabelaryPreviewServiceTest`, add:

```java
    @Test
    void renderPreview_withDimensions_usesGivenSizeAndDpmm() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(template);

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/printers/12dpmm/labels/0.68x7.44/0/")))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(new byte[]{9}, MediaType.IMAGE_PNG));

        LabelaryPreviewService service = buildService(template, "http://fake.labelary.com");
        byte[] result = service.renderPreview("^XA^XZ", 0.68, 7.44, 12);

        assertThat(result).containsExactly(9);
        server.verify();
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=LabelaryPreviewServiceTest`
Expected: FAIL — the 4-arg `renderPreview` does not exist.

- [ ] **Step 3: Add the overload**

In `LabelaryPreviewService`, refactor so the existing method delegates to a new dimensioned one. Replace the current `renderPreview(String zpl)` method with:

```java
    public byte[] renderPreview(String zpl) {
        return renderPreview(zpl, 1.0, 11.0, dpmm);
    }

    public byte[] renderPreview(String zpl, double widthInches, double heightInches, int dpmm) {
        String url = labelaryProps.getBaseUrl()
            + "/v1/printers/{dpmm}dpmm/labels/{width}x{height}/0/";

        log.info("Requesting Labelary preview at {}dpmm ({}x{} in)", dpmm, widthInches, heightInches);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<String> entity = new HttpEntity<>(zpl, headers);

            byte[] result = restTemplate.postForObject(url, entity, byte[].class,
                dpmm, trim(widthInches), trim(heightInches));

            if (result == null) {
                throw new LabelaryUnavailableException("Labelary returned empty response");
            }
            return result;
        } catch (RestClientException e) {
            throw new LabelaryUnavailableException("Labelary API unavailable: " + e.getMessage(), e);
        }
    }

    /** Labelary wants compact inch values: "1" not "1.0", "0.68" not "0.680000". */
    private static String trim(double inches) {
        if (inches == Math.rint(inches)) {
            return Integer.toString((int) inches);
        }
        return java.math.BigDecimal.valueOf(inches)
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString();
    }
```

> Note: the existing `renderPreview_returnsImageBytes` test expects `/labels/1x11/0/` — `trim(1.0)`→"1" and `trim(11.0)`→"11" keep that passing.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=LabelaryPreviewServiceTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/LabelaryPreviewService.java \
        src/test/java/com/stup/wristbandprinter/service/LabelaryPreviewServiceTest.java
git commit -m "feat: support custom label dimensions in Labelary preview"
```

---

## Task 8: Template preview + asset endpoints

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/editor/service/SampleData.java` (create)
- Modify: `src/main/java/com/stup/wristbandprinter/editor/service/TemplateService.java` (preview helpers + asset delegation)
- Modify: `src/main/java/com/stup/wristbandprinter/editor/controller/TemplateController.java`
- Modify: `src/test/java/com/stup/wristbandprinter/editor/controller/TemplateControllerTest.java`

- [ ] **Step 1: Create the sample-data holder**

`src/main/java/com/stup/wristbandprinter/editor/service/SampleData.java`:

```java
package com.stup.wristbandprinter.editor.service;

import com.stup.wristbandprinter.domain.WristbandData;

/** Canned data used to render catalog/thumbnail previews. */
public final class SampleData {

    private SampleData() {
    }

    public static final WristbandData WRISTBAND = new WristbandData(
        "Pukkelpop 2026", "Annechien", "Van De Wall",
        "Chiro Sint-Christina Brustem", "12345654245524789");
}
```

- [ ] **Step 2: Add preview/asset methods to `TemplateService`**

Add these imports to `TemplateService`:

```java
import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.editor.domain.AssetResponse;
import com.stup.wristbandprinter.editor.domain.TemplateDefinition;
```

Add `TemplateAssetService`, `TemplateZplRenderer` (already injected), `LabelaryPreviewService`, and `PreviewColorService` to the constructor. Update the constructor to:

```java
    private final WristbandTemplateRepository repository;
    private final TemplateZplRenderer renderer;
    private final com.stup.wristbandprinter.service.LabelaryPreviewService labelaryPreviewService;
    private final PreviewColorService previewColorService;
    private final TemplateAssetService assetService;

    public TemplateService(WristbandTemplateRepository repository,
                           TemplateZplRenderer renderer,
                           com.stup.wristbandprinter.service.LabelaryPreviewService labelaryPreviewService,
                           PreviewColorService previewColorService,
                           TemplateAssetService assetService) {
        this.repository = repository;
        this.renderer = renderer;
        this.labelaryPreviewService = labelaryPreviewService;
        this.previewColorService = previewColorService;
        this.assetService = assetService;
    }
```

Add these methods:

```java
    /** Render a PNG preview of a template with the given data (or sample data when null). */
    @Transactional(readOnly = true)
    public Optional<byte[]> renderPreview(UUID id, WristbandData data, String color) {
        return repository.findByIdAndDeletedFalse(id).map(e -> {
            TemplateDefinition def = e.getDefinition();
            WristbandData effective = data != null ? data : SampleData.WRISTBAND;
            String zpl = renderer.render(def, effective);
            double w = (double) def.canvas().widthDots() / def.canvas().dpi();
            double h = (double) def.canvas().lengthDots() / def.canvas().dpi();
            int dpmm = Math.round(def.canvas().dpi() / 25.4f);
            byte[] png = labelaryPreviewService.renderPreview(zpl, w, h, dpmm);
            String effectiveColor = (color == null || color.isBlank()) ? e.getDefaultPreviewColor() : color;
            return previewColorService.tint(png, effectiveColor);
        });
    }

    @Transactional
    public AssetResponse storeAsset(String name, byte[] png) {
        return assetService.store(name, png);
    }

    @Transactional(readOnly = true)
    public Optional<byte[]> rawAsset(UUID id) {
        return assetService.rawPng(id);
    }
```

- [ ] **Step 3: Update the `TemplateService` constructor call in `TemplateServiceTest`**

In `TemplateServiceTest.setUp()`, add mocks and pass them:

```java
    @Mock private TemplateZplRenderer renderer;
    @Mock private com.stup.wristbandprinter.service.LabelaryPreviewService labelaryPreviewService;
    @Mock private PreviewColorService previewColorService;
    @Mock private TemplateAssetService assetService;
```

```java
        service = new TemplateService(repository, renderer, labelaryPreviewService,
            previewColorService, assetService);
```

Run `./mvnw -q test -Dtest=TemplateServiceTest` and confirm the existing 10 tests still PASS (the new collaborators are unused by them, so no extra stubbing is needed beyond the `lenient` renderer stub from Task 5).

- [ ] **Step 4: Add failing controller tests**

In `TemplateControllerTest`, add mocks for the new collaborators the controller uses. The controller still depends only on `TemplateService`, so just add these tests:

```java
    @Test
    void preview_returnsPngWithSampleData() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.renderPreview(eq(id), isNull(), eq("red")))
            .thenReturn(Optional.of(new byte[]{1, 2, 3}));

        mockMvc.perform(get("/api/templates/" + id + "/preview?color=red").header("X-API-Key", API_KEY))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void preview_returns404_whenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.renderPreview(eq(id), isNull(), any())).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/templates/" + id + "/preview").header("X-API-Key", API_KEY))
            .andExpect(status().isNotFound());
    }

    @Test
    void uploadAsset_returns201WithAssetId() throws Exception {
        UUID assetId = UUID.randomUUID();
        when(templateService.storeAsset(eq("logo.png"), any()))
            .thenReturn(new com.stup.wristbandprinter.editor.domain.AssetResponse(assetId, "logo.png", 40, 20));

        var file = new org.springframework.mock.web.MockMultipartFile(
            "file", "logo.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/templates/assets").file(file).header("X-API-Key", API_KEY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(assetId.toString()));
    }
```

Add the static import `import static org.mockito.ArgumentMatchers.isNull;` to the test.

- [ ] **Step 5: Run to verify the new controller tests fail**

Run: `./mvnw -q test -Dtest=TemplateControllerTest`
Expected: FAIL — the preview/asset endpoints don't exist yet.

- [ ] **Step 6: Add the endpoints to `TemplateController`**

Add imports:

```java
import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.editor.domain.AssetResponse;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
```

Add these handler methods:

```java
    @GetMapping(value = "/{id}/preview", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Render a PNG preview of a template using sample data")
    public ResponseEntity<byte[]> preview(@PathVariable UUID id,
                                          @RequestParam(required = false) String color) {
        return templateService.renderPreview(id, null, color)
            .map(png -> ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping(value = "/{id}/preview", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Render a PNG preview of a template using supplied data")
    public ResponseEntity<byte[]> previewWithData(@PathVariable UUID id,
                                                  @RequestParam(required = false) String color,
                                                  @org.springframework.web.bind.annotation.RequestBody WristbandData data) {
        return templateService.renderPreview(id, data, color)
            .map(png -> ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/assets")
    @Operation(summary = "Upload a logo image, returning its asset id")
    public ResponseEntity<AssetResponse> uploadAsset(@RequestParam("file") MultipartFile file) throws IOException {
        AssetResponse response = templateService.storeAsset(file.getOriginalFilename(), file.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping(value = "/assets/{id}", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Fetch a stored logo image")
    public ResponseEntity<byte[]> getAsset(@PathVariable UUID id) {
        return templateService.rawAsset(id)
            .map(png -> ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
```

> `WristbandData` is a record with all-args constructor; Jackson deserializes the POST body fine.

- [ ] **Step 7: Run to verify it passes**

Run: `./mvnw -q test -Dtest=TemplateControllerTest`
Expected: PASS (12 tests).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/editor/service/SampleData.java \
        src/main/java/com/stup/wristbandprinter/editor/service/TemplateService.java \
        src/main/java/com/stup/wristbandprinter/editor/controller/TemplateController.java \
        src/test/java/com/stup/wristbandprinter/editor/service/TemplateServiceTest.java \
        src/test/java/com/stup/wristbandprinter/editor/controller/TemplateControllerTest.java
git commit -m "feat: add template preview and asset upload endpoints"
```

---

## Task 9: `/print` template selection

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/domain/WristbandPrintRequest.java`
- Modify: `src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java`
- Test: `src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java`

- [ ] **Step 1: Add the optional `templateId` field to the request**

In `WristbandPrintRequest`, add (with the other fields):

```java
    @Schema(example = "8f3e...", description = "Optional template id; when set the wristband is rendered from that template")
    private java.util.UUID templateId;

    public java.util.UUID getTemplateId() { return templateId; }
    public void setTemplateId(java.util.UUID templateId) { this.templateId = templateId; }
```

(No validation annotation — it's optional.)

- [ ] **Step 2: Add a failing test**

In `PrintQueueServiceTest`, locate how the service is constructed (it currently takes `WristbandLayoutService, ZplGeneratorService, PrinterService, QueueProperties, JobStore, MeterRegistry`). Add a `@Mock`/stub for the two new collaborators (`WristbandTemplateRepository templateRepository`, `TemplateZplRenderer templateRenderer`) and pass them to the constructor. Then add:

```java
    @Test
    void worker_rendersViaTemplate_whenTemplateIdPresent() throws Exception {
        UUID templateId = UUID.randomUUID();
        WristbandPrintRequest req = sampleRequest();
        req.setTemplateId(templateId);

        com.stup.wristbandprinter.editor.persistence.WristbandTemplateEntity entity =
            new com.stup.wristbandprinter.editor.persistence.WristbandTemplateEntity();
        entity.setDefinition(new com.stup.wristbandprinter.editor.domain.TemplateDefinition(
            new com.stup.wristbandprinter.editor.domain.Canvas(203, 2233, 300), java.util.List.of()));
        when(templateRepository.findByIdAndDeletedFalse(templateId)).thenReturn(Optional.of(entity));
        when(templateRenderer.render(any(), any())).thenReturn("^XA^XZ-template");

        service.enqueue(req);
        // allow the single-threaded worker to process
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2))
            .untilAsserted(() -> verify(printerService).send("^XA^XZ-template"));
    }
```

> If Awaitility is not already a test dependency, instead poll with a short loop:
> `for (int i=0;i<20 && Mockito.mockingDetails(printerService).getInvocations().isEmpty();i++) Thread.sleep(50);`
> then `verify(printerService).send("^XA^XZ-template");`. Check `PrintQueueServiceTest` for the pattern it already uses to await the worker and follow that.

- [ ] **Step 3: Run to verify it fails**

Run: `./mvnw -q test -Dtest=PrintQueueServiceTest`
Expected: FAIL — constructor arity and template routing missing.

- [ ] **Step 4: Route rendering in `PrintQueueService`**

1. Add imports:

```java
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateRepository;
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateEntity;
import com.stup.wristbandprinter.editor.service.TemplateZplRenderer;
```

2. Add two fields and extend the constructor (append the two parameters at the end):

```java
    private final WristbandTemplateRepository templateRepository;
    private final TemplateZplRenderer templateRenderer;
```

```java
    public PrintQueueService(WristbandLayoutService layoutService,
                             ZplGeneratorService zplGeneratorService,
                             PrinterService printerService,
                             QueueProperties queueProperties,
                             JobStore jobStore,
                             MeterRegistry meterRegistry,
                             WristbandTemplateRepository templateRepository,
                             TemplateZplRenderer templateRenderer) {
        // ... existing assignments ...
        this.templateRepository = templateRepository;
        this.templateRenderer = templateRenderer;
    }
```

3. In `processQueue`, replace the two lines:

```java
                        WristbandData data = layoutService.buildData(job.getRequest());
                        String zpl = zplGeneratorService.generate(data);
```

with:

```java
                        WristbandData data = layoutService.buildData(job.getRequest());
                        String zpl = resolveZpl(job.getRequest(), data);
```

4. Add the helper:

```java
    private String resolveZpl(WristbandPrintRequest request, WristbandData data) {
        if (request.getTemplateId() == null) {
            return zplGeneratorService.generate(data);
        }
        WristbandTemplateEntity template = templateRepository
            .findByIdAndDeletedFalse(request.getTemplateId())
            .orElseThrow(() -> new IllegalStateException(
                "Template not found: " + request.getTemplateId()));
        return templateRenderer.render(template.getDefinition(), data);
    }
```

> A missing template surfaces as a job failure (caught by the existing `catch (Exception e)` in `processQueue`, which marks the job FAILED with the message) — consistent with how other print errors are handled.

- [ ] **Step 5: Run to verify it passes**

Run: `./mvnw -q test -Dtest=PrintQueueServiceTest`
Expected: PASS (existing tests + the new one).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/WristbandPrintRequest.java \
        src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java \
        src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java
git commit -m "feat: render print jobs from a template when templateId is supplied"
```

---

## Task 10: Permit asset/preview content and document; full suite

**Files:**
- Modify: `docs/template-designer.md`, `README.md`

- [ ] **Step 1: Flip the Plan 2 status rows**

In `docs/template-designer.md`:
- In the API table, change the four `⏳ Plan 2` rows (preview ×2, assets, `/print` templateId) to `✅ Plan 2`.
- In the Roadmap table, change Plan 2's status from `⏳ Planned` to `✅ Done`.

In `README.md`, add to the API endpoints table:

```markdown
| `GET` | `/api/templates/{id}/preview` | PNG preview with sample data (`?color=` tints stock) |
| `POST` | `/api/templates/{id}/preview` | PNG preview with supplied `WristbandData` body |
| `POST` | `/api/templates/assets` | Upload a logo (multipart `file`) → `201 + assetId` |
| `GET` | `/api/templates/assets/{id}` | Fetch a stored logo PNG |
```

Add a note that `POST /api/wristbands/print` now accepts an optional `templateId`.

- [ ] **Step 2: Run the entire suite**

Run: `./mvnw test`
Expected: PASS — all existing tests plus the new ones (`GfImageEncoderTest`, `TemplateAssetRepositoryTest`, `TemplateAssetServiceTest`, `TemplateZplRendererTest`, `PreviewColorServiceTest`, plus the additions to `TemplateServiceTest`, `TemplateControllerTest`, `LabelaryPreviewServiceTest`, `PrintQueueServiceTest`, `LogoConversionServiceTest`). Requires Docker.

- [ ] **Step 3: Commit**

```bash
git add docs/template-designer.md README.md
git commit -m "docs: document template preview/asset endpoints and template printing"
```

---

## Done — Plan 2 deliverable

A saved template renders to ZPL (`TemplateZplRenderer`), its ZPL snapshot is persisted on every
save, logos upload and convert to `^GF` (`TemplateAssetService`), templates expose colour-tinted
PNG previews (sample or supplied data), and `/print` prints from a template when `templateId` is
supplied — with the legacy fixed-layout path unchanged. Plan 3 adds the Konva.js editor UI that
authors these definitions.

**Self-review notes (verified while writing):**
- Spec coverage: §5 renderer ✓, §6 assets ✓, §7 preview + `/print` rows ✓, colour preview (§2 decision) ✓.
- Constructor changes are propagated to every caller/test: `LogoConversionService` (+`GfImageEncoder`), `TemplateService` (+renderer, Labelary, colour, asset services), `PrintQueueService` (+template repo, renderer). Each is updated in the same task that changes it.
- Type/name consistency: `renderPreview(zpl, w, h, dpmm)`, `gfCommand(assetId, w, h, rotation)`, `render(def, data)`/`renderTemplate(def)`, `tint(png, color)`, `renderPreview(id, data, color)`, `storeAsset`/`rawAsset` used identically in services, controllers, and tests.
- No placeholders: every code step shows complete code. The one conditional is the Awaitility-vs-poll note in Task 9 Step 2, which instructs the implementer to follow the await pattern already present in `PrintQueueServiceTest`.

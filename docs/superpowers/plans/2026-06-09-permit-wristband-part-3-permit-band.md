# Permit Wristband – Part 3: Permit Band Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the complete permit band domain objects, config, logo service, ZPL generator, and refactor `WristbandZplResolver` to route to the correct generator based on request type — making the permit band printable and previewable end-to-end (controller wiring comes in Part 4).

**Architecture:** `PermitWristbandPrintRequest` and `PermitWristbandData` are parallel to their crew equivalents. `PermitWristbandProperties` (prefix `wristband.permit`) holds permit-specific layout config. `PermitEventLogoService` follows the exact `LogoConversionService` pattern: `@PostConstruct` reads the event logo PNG, scales it, pre-rotates 180°, caches the `^GF` command. `PermitZplGeneratorService` produces ZPL for the 4-block permit layout using `ScanCodeRenderer` for the optional scan code. `WristbandZplResolver` gains a `WristbandLayoutService` dependency, its signature changes to `resolve(PrintableRequest)`, and it routes by `WristbandType`.

**Permit band layout (300 × 3300 dots, all blocks vertically centered):**
```
┌─────────────────────────────┐  ← Y = topY
│  Block 1: STUP logo (180°)  │
├─────────────────────────────┤  ← betweenBlocks gap
│  Block 2: permit text       │
│    "Toelating [label]"  ^A0B│
│    ← writingGapDots blank   │
│    "- - - - - - - -"    ^A0B│
├─────────────────────────────┤  ← betweenBlocks gap
│  Block 3 (optional):        │
│    scan code  (ScanCode…)   │
├─────────────────────────────┤  ← betweenBlocks gap (if block 3 present)
│  Block 4: event section     │
│    eventName           ^A0B │
│    ← innerGap              │
│    event logo (180° GF)     │
└─────────────────────────────┘
```

**Tech Stack:** Java 21, Spring Boot 3.4.1, `@ConfigurationProperties`, `@PostConstruct`, `GfImageEncoder`, `ScanCodeRenderer`, JUnit 5.

**Prerequisite:** Parts 1 and 2 must be applied first.

---

## File map

| Action | Path | Responsibility |
|--------|------|----------------|
| Replace | `src/main/java/…/domain/PermitWristbandPrintRequest.java` | Full permit request DTO (replaces Part 2 stub) |
| Create | `src/main/java/…/domain/PermitWristbandData.java` | Permit layout data record |
| Create | `src/main/java/…/config/PermitWristbandProperties.java` | `@ConfigurationProperties(prefix="wristband.permit")` |
| Modify | `src/main/resources/application.yml` | Add `wristband.permit.*` block |
| Create | `src/main/java/…/service/PermitEventLogoService.java` | Load, scale, rotate 180°, cache `^GF` for the event logo |
| Create | `src/main/java/…/service/PermitZplGeneratorService.java` | Generate ZPL for the permit 4-block layout |
| Modify | `src/main/java/…/service/WristbandZplResolver.java` | Add `WristbandLayoutService` dep; new `resolve(PrintableRequest)` single-arg method |
| Create | `src/test/java/…/service/PermitZplGeneratorServiceTest.java` | Layout math + ZPL structure tests |
| Modify | `src/test/java/…/service/WristbandZplResolverTest.java` | Update to single-arg `resolve()` |
| Modify | `src/test/java/…/worker/WorkerProfileContextTest.java` | Assert permit-band beans absent in worker profile |

---

## Task 10: PermitWristbandPrintRequest + PermitWristbandData + PermitWristbandProperties

**Files:**
- Replace: `src/main/java/com/stup/wristbandprinter/domain/PermitWristbandPrintRequest.java`
- Create: `src/main/java/com/stup/wristbandprinter/domain/PermitWristbandData.java`
- Create: `src/main/java/com/stup/wristbandprinter/config/PermitWristbandProperties.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Replace PermitWristbandPrintRequest stub with full implementation**

```java
// src/main/java/com/stup/wristbandprinter/domain/PermitWristbandPrintRequest.java
package com.stup.wristbandprinter.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Data required to print or preview a permit wristband")
public final class PermitWristbandPrintRequest implements PrintableRequest {

    @NotBlank(message = "eventName must not be blank")
    @Schema(example = "Pukkelpop 2026")
    private String eventName;

    @NotBlank(message = "permitLabel must not be blank")
    @Schema(example = "ELEKTRICITEIT",
            description = "Printed as 'Toelating [permitLabel]' on the band")
    private String permitLabel;

    @Schema(description = "Font Awesome icon name; stored for future rendering, not printed yet",
            example = "bolt")
    private String iconName;

    @Schema(description = "Optional scan-code value; when present a barcode/QR is printed on the band")
    private String codeValue;

    @Schema(description = "Scan-code symbology; defaults to CODE128 when omitted")
    private CodeSymbology codeSymbology;

    @Schema(description = "Optional stock-color code (1 = white). Configured palette in wristband.stock-colors")
    private Integer stockColorCode;

    @Schema(description = "Optional id of the printer to use; when omitted the default printer is used")
    private String printerId;

    // ── PrintableRequest ──────────────────────────────────────────────────

    @Override
    public WristbandType getWristbandType() { return WristbandType.PERMIT; }

    @Override
    public PrintableRequest withPrinterId(String newPrinterId) {
        PermitWristbandPrintRequest copy = new PermitWristbandPrintRequest();
        copy.setEventName(this.eventName);
        copy.setPermitLabel(this.permitLabel);
        copy.setIconName(this.iconName);
        copy.setCodeValue(this.codeValue);
        copy.setCodeSymbology(this.codeSymbology);
        copy.setStockColorCode(this.stockColorCode);
        copy.setPrinterId(newPrinterId);
        return copy;
    }

    // ── getters / setters ─────────────────────────────────────────────────

    public String getEventName()    { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }

    public String getPermitLabel()  { return permitLabel; }
    public void setPermitLabel(String permitLabel) { this.permitLabel = permitLabel; }

    public String getIconName()     { return iconName; }
    public void setIconName(String iconName) { this.iconName = iconName; }

    public String getCodeValue()    { return codeValue; }
    public void setCodeValue(String codeValue) { this.codeValue = codeValue; }

    public CodeSymbology getCodeSymbology() { return codeSymbology; }
    public void setCodeSymbology(CodeSymbology codeSymbology) { this.codeSymbology = codeSymbology; }

    @Override
    public Integer getStockColorCode() { return stockColorCode; }
    public void setStockColorCode(Integer stockColorCode) { this.stockColorCode = stockColorCode; }

    @Override
    public String getPrinterId() { return printerId; }
    public void setPrinterId(String printerId) { this.printerId = printerId; }
}
```

- [ ] **Step 2: Create PermitWristbandData**

```java
// src/main/java/com/stup/wristbandprinter/domain/PermitWristbandData.java
package com.stup.wristbandprinter.domain;

/**
 * Layout-time data for a permit wristband.
 *
 * @param eventName    printed in block 4 (event section)
 * @param permitLabel  printed as "Toelating [permitLabel]" in block 2
 * @param codeValue    optional; when non-null a scan code is printed in block 3
 * @param codeSymbology symbology for the optional scan code; defaults to CODE128
 */
public record PermitWristbandData(
    String eventName,
    String permitLabel,
    String codeValue,           // nullable
    CodeSymbology codeSymbology
) {
    /** Convenience constructor without scan code (block 3 omitted). */
    public PermitWristbandData(String eventName, String permitLabel) {
        this(eventName, permitLabel, null, CodeSymbology.CODE128);
    }
}
```

- [ ] **Step 3: Create PermitWristbandProperties**

```java
// src/main/java/com/stup/wristbandprinter/config/PermitWristbandProperties.java
package com.stup.wristbandprinter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Layout and asset configuration for permit wristbands.
 * Shares the same physical dimensions as crew bands (wristband.width-dots /
 * length-dots), so those values are read from {@link WristbandProperties}.
 */
@ConfigurationProperties(prefix = "wristband.permit")
public class PermitWristbandProperties {

    /** Path to the event logo image (PNG). Supports classpath: prefix. */
    private String eventLogoPath = "classpath:images/stup-logo.png";

    /** Margin on each side of the event logo (dots). */
    private int eventLogoSideMarginDots = 30;

    /** Font size for "Toelating [label]" line (dots; maps to band-width direction). */
    private int permitTextFontSize = 74;

    /** Font size for the writing/dashes line below the permit text (dots). */
    private int writingLineFontSize = 45;

    /** Font size for the event name in block 4 (dots). */
    private int eventNameFontSize = 45;

    /** Uniform gap between all top-level blocks (dots). */
    private int betweenBlocks = 60;

    /** Blank writing space between the permit-text line and the dashes line (dots). */
    private int writingGapDots = 120;

    /** Bar height for the optional scan code (dots; maps to band-width direction). */
    private int scanCodeHeightDots = 270;

    /** Narrow-bar module width for the optional scan code (dots). */
    private int scanCodeModuleWidthDots = 3;

    /** Gap between event name and event logo within block 4 (dots). */
    private int innerBlockGapDots = 40;

    public String getEventLogoPath()            { return eventLogoPath; }
    public void   setEventLogoPath(String p)    { this.eventLogoPath = p; }

    public int  getEventLogoSideMarginDots()    { return eventLogoSideMarginDots; }
    public void setEventLogoSideMarginDots(int v){ this.eventLogoSideMarginDots = v; }

    public int  getPermitTextFontSize()         { return permitTextFontSize; }
    public void setPermitTextFontSize(int v)    { this.permitTextFontSize = v; }

    public int  getWritingLineFontSize()        { return writingLineFontSize; }
    public void setWritingLineFontSize(int v)   { this.writingLineFontSize = v; }

    public int  getEventNameFontSize()          { return eventNameFontSize; }
    public void setEventNameFontSize(int v)     { this.eventNameFontSize = v; }

    public int  getBetweenBlocks()              { return betweenBlocks; }
    public void setBetweenBlocks(int v)         { this.betweenBlocks = v; }

    public int  getWritingGapDots()             { return writingGapDots; }
    public void setWritingGapDots(int v)        { this.writingGapDots = v; }

    public int  getScanCodeHeightDots()         { return scanCodeHeightDots; }
    public void setScanCodeHeightDots(int v)    { this.scanCodeHeightDots = v; }

    public int  getScanCodeModuleWidthDots()    { return scanCodeModuleWidthDots; }
    public void setScanCodeModuleWidthDots(int v){ this.scanCodeModuleWidthDots = v; }

    public int  getInnerBlockGapDots()          { return innerBlockGapDots; }
    public void setInnerBlockGapDots(int v)     { this.innerBlockGapDots = v; }
}
```

- [ ] **Step 4: Register PermitWristbandProperties in Spring Boot config**

Find the `@SpringBootApplication` class (or any `@Configuration` class that enables config properties) and add:

```java
// Typically in WristbandPrinterApplication.java or a config class — add this annotation:
@EnableConfigurationProperties({WristbandProperties.class, PermitWristbandProperties.class, ...})
```

Or, if the project uses `@ConfigurationPropertiesScan` (check `WristbandPrinterApplication.java`), it is auto-picked up — verify the scan base package covers `com.stup.wristbandprinter.config`.

- [ ] **Step 5: Add wristband.permit block to application.yml**

Add inside the `wristband:` section, after `stock-colors:`:

```yaml
  permit:
    event-logo-path: classpath:images/stup-logo.png   # placeholder; override per event
    event-logo-side-margin-dots: 30
    permit-text-font-size: 74
    writing-line-font-size: 45
    event-name-font-size: 45
    between-blocks: 60
    writing-gap-dots: 120
    scan-code-height-dots: 270
    scan-code-module-width-dots: 3
    inner-block-gap-dots: 40
```

- [ ] **Step 6: Compile and run full suite**

```bash
./mvnw test -q
```
Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/PermitWristbandPrintRequest.java \
        src/main/java/com/stup/wristbandprinter/domain/PermitWristbandData.java \
        src/main/java/com/stup/wristbandprinter/config/PermitWristbandProperties.java \
        src/main/resources/application.yml
git commit -m "$(cat <<'EOF'
feat: add PermitWristbandPrintRequest, PermitWristbandData, PermitWristbandProperties

Replaces the Part-2 stub with the full permit request DTO.
All permit layout parameters are config-driven via wristband.permit.*.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: PermitEventLogoService

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/service/PermitEventLogoService.java`

This service is an exact copy of `LogoConversionService` with the logo path sourced from `PermitWristbandProperties.getEventLogoPath()` instead of `WristbandProperties.getLogoPath()`. It shares the `WristbandProperties` dimensions (width, margin) because the physical wristband is the same.

- [ ] **Step 1: Write a failing test**

```java
// src/test/java/com/stup/wristbandprinter/service/PermitEventLogoServiceTest.java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.PermitWristbandProperties;
import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.editor.service.GfImageEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermitEventLogoServiceTest {

    @Mock GfImageEncoder encoder;

    @Test
    void loadAndConvert_populatesCachedFields() throws Exception {
        when(encoder.encode(any())).thenReturn("^GFA,10,10,1,ABC");

        WristbandProperties props = new WristbandProperties();
        props.setWidthDots(300);
        props.setLogoSideMarginDots(30);

        PermitWristbandProperties permitProps = new PermitWristbandProperties();
        // stup-logo.png exists on classpath — reuse it as a stand-in event logo
        permitProps.setEventLogoPath("classpath:images/stup-logo.png");

        PermitEventLogoService svc = new PermitEventLogoService(props, permitProps, encoder);
        svc.loadAndConvertEventLogo();

        assertThat(svc.getGfCommand()).isNotBlank();
        assertThat(svc.getLogoHeightDots()).isGreaterThan(0);
    }
}
```

- [ ] **Step 2: Run test — expect compile error (class doesn't exist)**

```bash
./mvnw test -Dtest=PermitEventLogoServiceTest -q 2>&1 | tail -5
```
Expected: compilation error.

- [ ] **Step 3: Create PermitEventLogoService**

```java
// src/main/java/com/stup/wristbandprinter/service/PermitEventLogoService.java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.PermitWristbandProperties;
import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.editor.service.GfImageEncoder;
import com.stup.wristbandprinter.exception.LogoNotFoundException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Loads the per-event logo configured at {@code wristband.permit.event-logo-path},
 * scales it to fit the wristband width, pre-rotates it 180° (matching the
 * {@link LogoConversionService} convention), and caches the ZPL {@code ^GF} command.
 */
@Profile("!worker")
@Service
public class PermitEventLogoService {

    private static final Logger log = LoggerFactory.getLogger(PermitEventLogoService.class);

    private final WristbandProperties props;
    private final PermitWristbandProperties permitProps;
    private final GfImageEncoder gfImageEncoder;
    private String cachedGfCommand;
    private int logoHeightDots;

    public PermitEventLogoService(WristbandProperties props,
                                   PermitWristbandProperties permitProps,
                                   GfImageEncoder gfImageEncoder) {
        this.props        = props;
        this.permitProps  = permitProps;
        this.gfImageEncoder = gfImageEncoder;
    }

    @PostConstruct
    public void loadAndConvertEventLogo() {
        String logoPath = permitProps.getEventLogoPath();
        log.info("Loading permit event logo from: {}", logoPath);
        try {
            Resource resource = resolveResource(logoPath);
            if (!resource.exists()) {
                throw new LogoNotFoundException("Permit event logo not found at: " + logoPath);
            }
            BufferedImage original = ImageIO.read(resource.getInputStream());
            if (original == null) {
                throw new LogoNotFoundException("Could not decode permit event logo at: " + logoPath);
            }

            int sideMargin   = permitProps.getEventLogoSideMarginDots();
            int targetWidth  = props.getWidthDots() - 2 * sideMargin;
            int targetHeight = (int) ((double) original.getHeight() / original.getWidth() * targetWidth);

            BufferedImage scaled  = scaleImage(original, targetWidth, targetHeight);
            BufferedImage rotated = rotate180(scaled);
            this.logoHeightDots   = rotated.getHeight();
            this.cachedGfCommand  = gfImageEncoder.encode(rotated);

            log.info("Permit event logo converted. Dimensions: {}x{} dots", targetWidth, targetHeight);
        } catch (IOException e) {
            throw new LogoNotFoundException("Failed to load permit event logo: " + e.getMessage(), e);
        }
    }

    public String getGfCommand()      { return cachedGfCommand; }
    public int    getLogoHeightDots() { return logoHeightDots; }

    private BufferedImage scaleImage(BufferedImage src, int w, int h) {
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return result;
    }

    private BufferedImage rotate180(BufferedImage img) {
        BufferedImage result = new BufferedImage(img.getWidth(), img.getHeight(), img.getType());
        Graphics2D g = result.createGraphics();
        g.rotate(Math.PI, img.getWidth() / 2.0, img.getHeight() / 2.0);
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return result;
    }

    private Resource resolveResource(String path) {
        if (path.startsWith("classpath:")) {
            return new ClassPathResource(path.substring("classpath:".length()));
        }
        return new FileSystemResource(path);
    }
}
```

- [ ] **Step 4: Run test — expect green**

```bash
./mvnw test -Dtest=PermitEventLogoServiceTest -q
```
Expected: `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/PermitEventLogoService.java \
        src/test/java/com/stup/wristbandprinter/service/PermitEventLogoServiceTest.java
git commit -m "$(cat <<'EOF'
feat: add PermitEventLogoService (event logo loader for permit bands)

Follows LogoConversionService pattern: @PostConstruct load, scale, 180°
rotate, cache ^GF command. Config-driven via wristband.permit.event-logo-path.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: PermitZplGeneratorService

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/service/PermitZplGeneratorService.java`
- Create: `src/test/java/com/stup/wristbandprinter/service/PermitZplGeneratorServiceTest.java`

Layout math reference (all Y positions count from top of label):
```
block1Height = stuplogoH  (from LogoConversionService)
block2Height = permitTextLen + writingGapDots + writingLineLen
block3Height = (codeValue != null) ? ScanCodeRenderer.estimateYLength(...) : 0
block4Height = eventNameLen + innerBlockGapDots + eventLogoH

betweenBlocks = permitProps.getBetweenBlocks()
gapCount = 2 + (codeValue != null ? 1 : 0)   // gaps between the N blocks

totalHeight = block1Height + betweenBlocks
            + block2Height + betweenBlocks
            + block3Height + (codeValue != null ? betweenBlocks : 0)
            + block4Height

topY      = (lengthDots - totalHeight) / 2
block1Y   = topY
block2Y   = block1Y + block1Height + betweenBlocks
block3Y   = block2Y + block2Height + betweenBlocks
block4Y   = block3Y + (codeValue != null ? block3Height + betweenBlocks : 0)
```

Character length estimate for ^A0B text:
`lineLen(text, fontSize) = (int)(text.length() * fontSize * 0.46)` — same CHAR_ADVANCE_RATIO as crew band.

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/com/stup/wristbandprinter/service/PermitZplGeneratorServiceTest.java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.PermitWristbandProperties;
import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.CodeSymbology;
import com.stup.wristbandprinter.domain.PermitWristbandData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermitZplGeneratorServiceTest {

    @Mock LogoConversionService stuplogoService;
    @Mock PermitEventLogoService eventLogoService;

    WristbandProperties props;
    PermitWristbandProperties permitProps;
    PermitZplGeneratorService svc;

    @BeforeEach
    void setUp() {
        when(stuplogoService.getGfCommand()).thenReturn("^GFA,1,1,1,A");
        when(stuplogoService.getLogoHeightDots()).thenReturn(100);
        when(eventLogoService.getGfCommand()).thenReturn("^GFB,1,1,1,B");
        when(eventLogoService.getLogoHeightDots()).thenReturn(80);

        props = new WristbandProperties();
        props.setWidthDots(300);
        props.setLengthDots(3300);
        props.setLogoSideMarginDots(75);

        permitProps = new PermitWristbandProperties();

        svc = new PermitZplGeneratorService(props, permitProps, stuplogoService, eventLogoService);
    }

    @Test
    void generate_containsStartAndEnd() {
        String zpl = svc.generate(new PermitWristbandData("Pukkelpop 2026", "ELEKTRICITEIT"));
        assertThat(zpl).startsWith("^XA");
        assertThat(zpl).endsWith("^XZ");
    }

    @Test
    void generate_containsStupLogo() {
        String zpl = svc.generate(new PermitWristbandData("Pukkelpop 2026", "ELEKTRICITEIT"));
        // STUP logo GF command appears at least once (block 1)
        assertThat(zpl).contains("^GFA,1,1,1,A");
    }

    @Test
    void generate_containsEventLogo() {
        String zpl = svc.generate(new PermitWristbandData("Pukkelpop 2026", "ELEKTRICITEIT"));
        assertThat(zpl).contains("^GFB,1,1,1,B");
    }

    @Test
    void generate_containsPermitLabel() {
        String zpl = svc.generate(new PermitWristbandData("Pukkelpop", "PARKING"));
        assertThat(zpl).contains("Toelating PARKING");
    }

    @Test
    void generate_containsEventName() {
        String zpl = svc.generate(new PermitWristbandData("Pukkelpop 2026", "ELEKTRICITEIT"));
        assertThat(zpl).contains("Pukkelpop 2026");
    }

    @Test
    void generate_withCodeValue_containsBarcode() {
        PermitWristbandData data = new PermitWristbandData(
            "Pukkelpop 2026", "ELEKTRICITEIT", "ELEC-001", CodeSymbology.CODE128);
        String zpl = svc.generate(data);
        assertThat(zpl).contains("^BCB");
        assertThat(zpl).contains("ELEC-001");
    }

    @Test
    void generate_withQrCode_containsBQN() {
        PermitWristbandData data = new PermitWristbandData(
            "Pukkelpop 2026", "PARKING", "LOT-A-42", CodeSymbology.QR);
        String zpl = svc.generate(data);
        assertThat(zpl).contains("^BQN");
        assertThat(zpl).contains("LOT-A-42");
    }

    @Test
    void generate_withoutCodeValue_noBarcode() {
        String zpl = svc.generate(new PermitWristbandData("Pukkelpop 2026", "ELEKTRICITEIT"));
        assertThat(zpl).doesNotContain("^BCB");
        assertThat(zpl).doesNotContain("^BQN");
    }

    @Test
    void generate_containsWritingDashes() {
        String zpl = svc.generate(new PermitWristbandData("Pukkelpop 2026", "ELEKTRICITEIT"));
        // The dashes writing line should appear
        assertThat(zpl).contains("- -");
    }

    @Test
    void generate_sanitizesControlChars() {
        PermitWristbandData data = new PermitWristbandData("Event^Bad", "PERMIT~X");
        String zpl = svc.generate(data);
        assertThat(zpl).doesNotContain("^Bad");
        assertThat(zpl).doesNotContain("PERMIT~X");
        assertThat(zpl).contains("EventBad");
        assertThat(zpl).contains("PERMITX");
    }

    @Test
    void generate_setsLabelDimensions() {
        String zpl = svc.generate(new PermitWristbandData("Pukkelpop 2026", "ELEKTRICITEIT"));
        assertThat(zpl).contains("^PW300");
        assertThat(zpl).contains("^LL3300");
    }
}
```

- [ ] **Step 2: Run tests — expect compile error (class doesn't exist)**

```bash
./mvnw test -Dtest=PermitZplGeneratorServiceTest -q 2>&1 | tail -5
```

- [ ] **Step 3: Create PermitZplGeneratorService**

```java
// src/main/java/com/stup/wristbandprinter/service/PermitZplGeneratorService.java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.PermitWristbandProperties;
import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.CodeSymbology;
import com.stup.wristbandprinter.domain.PermitWristbandData;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Generates ZPL for a permit wristband.
 *
 * <p>Layout (blocks, vertically centered on the 3300-dot band length):
 * <pre>
 *   Block 1 – STUP logo (180° pre-rotated ^GF)
 *   [betweenBlocks gap]
 *   Block 2 – "Toelating [label]" ^A0B + writingGap + "- - -" dashes ^A0B
 *   [betweenBlocks gap]
 *   Block 3 – optional scan code (CODE128 / CODE39 / QR) via ScanCodeRenderer
 *   [betweenBlocks gap if block 3 present]
 *   Block 4 – eventName ^A0B + innerBlockGap + event logo (180° pre-rotated ^GF)
 * </pre>
 */
@Profile("!worker")
@Service
public class PermitZplGeneratorService {

    // Shared with ZplGeneratorService — same font calibration.
    static final double CHAR_ADVANCE_RATIO = 0.46;

    /** Writing-line text: a dashed line to provide a visual writing guide. */
    private static final String WRITING_LINE =
        "  - - - - - - - - - - - - - - - - - - - - - - - -  ";

    private final WristbandProperties       props;
    private final PermitWristbandProperties permitProps;
    private final LogoConversionService     stuplogoService;
    private final PermitEventLogoService    eventLogoService;

    public PermitZplGeneratorService(WristbandProperties props,
                                      PermitWristbandProperties permitProps,
                                      LogoConversionService stuplogoService,
                                      PermitEventLogoService eventLogoService) {
        this.props           = props;
        this.permitProps     = permitProps;
        this.stuplogoService = stuplogoService;
        this.eventLogoService = eventLogoService;
    }

    public String generate(PermitWristbandData data) {
        int stuplogoH   = stuplogoService.getLogoHeightDots();
        int eventLogoH  = eventLogoService.getLogoHeightDots();
        int btw         = permitProps.getBetweenBlocks();
        int h1          = permitProps.getPermitTextFontSize();
        int h2          = permitProps.getWritingLineFontSize();
        int h4          = permitProps.getEventNameFontSize();
        int writingGap  = permitProps.getWritingGapDots();
        int innerGap    = permitProps.getInnerBlockGapDots();
        int scanH       = permitProps.getScanCodeHeightDots();
        int scanMod     = permitProps.getScanCodeModuleWidthDots();

        String permitText   = sanitize("Toelating " + data.permitLabel());
        String eventName    = sanitize(data.eventName());
        String writingLine  = WRITING_LINE;
        CodeSymbology symbology = data.codeSymbology() != null
            ? data.codeSymbology() : CodeSymbology.CODE128;

        // ── block heights ────────────────────────────────────────────────
        int permitTextLen  = lineLen(permitText, h1);
        int writingLineLen = lineLen(writingLine, h2);
        int block2H = permitTextLen + writingGap + writingLineLen;

        boolean hasCode = data.codeValue() != null && !data.codeValue().isBlank();
        int block3H = hasCode
            ? ScanCodeRenderer.estimateYLength(data.codeValue(), symbology, scanMod, scanH)
            : 0;

        int eventNameLen = lineLen(eventName, h4);
        int block4H = eventNameLen + innerGap + eventLogoH;

        // ── total + vertical centering ───────────────────────────────────
        int totalH = stuplogoH + btw
            + block2H + btw
            + (hasCode ? block3H + btw : 0)
            + block4H;

        int topY    = (props.getLengthDots() - totalH) / 2;
        int block1Y = topY;
        int block2Y = block1Y + stuplogoH + btw;
        int block3Y = block2Y + block2H + btw;
        int block4Y = hasCode ? block3Y + block3H + btw : block3Y;

        // ── ZPL ──────────────────────────────────────────────────────────
        StringBuilder zpl = new StringBuilder();
        zpl.append("^XA");
        zpl.append(String.format("^PW%d", props.getWidthDots()));
        zpl.append(String.format("^LL%d", props.getLengthDots()));
        zpl.append("^CI28");

        // Block 1: STUP logo
        appendLogo(zpl, stuplogoService.getGfCommand(),
            props.getLogoSideMarginDots(), block1Y);

        // Block 2: permit text + writing gap + dashes
        appendPermitTextBlock(zpl, permitText, writingLine, block2Y, h1, h2,
            permitTextLen, writingGap);

        // Block 3 (optional): scan code
        if (hasCode) {
            int crossExtent = ScanCodeRenderer.estimateCrossBandExtent(
                symbology, scanH, data.codeValue());
            int scanX = (props.getWidthDots() - crossExtent) / 2;
            ScanCodeRenderer.appendTo(zpl, data.codeValue(), symbology,
                scanX, block3Y, scanH, scanMod, false);
        }

        // Block 4: event name + event logo
        appendEventBlock(zpl, eventName, block4Y, h4, eventNameLen,
            innerGap, eventLogoH);

        zpl.append("^XZ");
        return zpl.toString();
    }

    // ── private helpers ──────────────────────────────────────────────────

    private void appendLogo(StringBuilder zpl, String gfCommand, int x, int y) {
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(gfCommand);
    }

    private void appendPermitTextBlock(StringBuilder zpl,
                                        String permitText, String writingLine,
                                        int block2Y, int h1, int h2,
                                        int permitTextLen, int writingGap) {
        // "Toelating [label]" — centered across band width
        int x1 = centerX(h1);
        zpl.append(String.format("^FO%d,%d", x1, block2Y));
        zpl.append(String.format("^A0B,%d,%d", h1, h1));
        zpl.append(String.format("^FD%s^FS", permitText));

        // Dashes writing line — centered across band width
        int dashY = block2Y + permitTextLen + writingGap;
        int x2 = centerX(h2);
        zpl.append(String.format("^FO%d,%d", x2, dashY));
        zpl.append(String.format("^A0B,%d,%d", h2, h2));
        zpl.append(String.format("^FD%s^FS", writingLine));
    }

    private void appendEventBlock(StringBuilder zpl, String eventName, int block4Y,
                                   int h4, int eventNameLen, int innerGap, int eventLogoH) {
        // Event name (^A0B — same reading direction as permit text)
        int x4 = centerX(h4);
        zpl.append(String.format("^FO%d,%d", x4, block4Y));
        zpl.append(String.format("^A0B,%d,%d", h4, h4));
        zpl.append(String.format("^FD%s^FS", eventName));

        // Event logo (180° pre-rotated by PermitEventLogoService)
        int eventLogoY = block4Y + eventNameLen + innerGap;
        appendLogo(zpl, eventLogoService.getGfCommand(),
            permitProps.getEventLogoSideMarginDots(), eventLogoY);
    }

    /**
     * Centers a text field of the given font height across the band width.
     * With ^A0B rotation, font height maps to the X axis (across the band).
     */
    private int centerX(int fontHeight) {
        return (props.getWidthDots() - fontHeight) / 2;
    }

    /** Estimated Y length of a single ^A0B text line. */
    private static int lineLen(String text, int fontSize) {
        return (int) (text.length() * fontSize * CHAR_ADVANCE_RATIO);
    }

    private static String sanitize(String text) {
        return text.replaceAll("[\\^~]", "");
    }
}
```

- [ ] **Step 4: Run tests — expect all green**

```bash
./mvnw test -Dtest=PermitZplGeneratorServiceTest -q
```
Expected: `Tests run: 10, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/PermitZplGeneratorService.java \
        src/test/java/com/stup/wristbandprinter/service/PermitZplGeneratorServiceTest.java
git commit -m "$(cat <<'EOF'
feat: add PermitZplGeneratorService (4-block permit band layout)

Vertically centered layout: STUP logo → permit text + writing dashes →
optional scan code (CODE128/CODE39/QR) → event name + event logo.
All spacing config-driven via wristband.permit.*.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: WristbandZplResolver refactor — resolve(PrintableRequest)

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/service/WristbandZplResolver.java`
- Modify: `src/test/java/com/stup/wristbandprinter/service/WristbandZplResolverTest.java`
- Modify: `src/test/java/com/stup/wristbandprinter/worker/WorkerProfileContextTest.java`

- [ ] **Step 1: Update WristbandZplResolver**

Replace the full file:

```java
// src/main/java/com/stup/wristbandprinter/service/WristbandZplResolver.java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.*;
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateEntity;
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateRepository;
import com.stup.wristbandprinter.editor.service.TemplateZplRenderer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Resolves ZPL for any {@link PrintableRequest}.
 *
 * <ul>
 *   <li>CREW + templateId → {@link TemplateZplRenderer}</li>
 *   <li>CREW (no template) → {@link ZplGeneratorService} (legacy fixed layout)</li>
 *   <li>PERMIT → {@link PermitZplGeneratorService}</li>
 * </ul>
 *
 * This is the single entry point shared by the print queue and every preview
 * endpoint, ensuring "what you preview is exactly what prints."
 */
@Profile("!worker")
@Service
public class WristbandZplResolver {

    private final ZplGeneratorService        zplGeneratorService;
    private final WristbandLayoutService     wristbandLayoutService;
    private final PermitZplGeneratorService  permitZplGeneratorService;
    private final WristbandTemplateRepository templateRepository;
    private final TemplateZplRenderer        templateRenderer;

    public WristbandZplResolver(ZplGeneratorService zplGeneratorService,
                                 WristbandLayoutService wristbandLayoutService,
                                 PermitZplGeneratorService permitZplGeneratorService,
                                 WristbandTemplateRepository templateRepository,
                                 TemplateZplRenderer templateRenderer) {
        this.zplGeneratorService      = zplGeneratorService;
        this.wristbandLayoutService   = wristbandLayoutService;
        this.permitZplGeneratorService = permitZplGeneratorService;
        this.templateRepository       = templateRepository;
        this.templateRenderer         = templateRenderer;
    }

    /**
     * Resolves ZPL for the given request.
     *
     * @param request a crew or permit wristband request
     * @return ZPL string ready to send to the printer
     */
    public String resolve(PrintableRequest request) {
        return switch (request.getWristbandType()) {
            case CREW   -> resolveCrew((WristbandPrintRequest) request);
            case PERMIT -> resolvePermit((PermitWristbandPrintRequest) request);
        };
    }

    // ── private dispatch ─────────────────────────────────────────────────

    private String resolveCrew(WristbandPrintRequest request) {
        WristbandData data = wristbandLayoutService.buildData(request);
        if (request.getTemplateId() == null) {
            return zplGeneratorService.generate(data);
        }
        WristbandTemplateEntity template = templateRepository
            .findByIdAndDeletedFalse(request.getTemplateId())
            .orElseThrow(() -> new IllegalStateException(
                "Template not found: " + request.getTemplateId()));
        return templateRenderer.render(template.getDefinition(), data);
    }

    private String resolvePermit(PermitWristbandPrintRequest request) {
        CodeSymbology symbology = request.getCodeSymbology() != null
            ? request.getCodeSymbology() : CodeSymbology.CODE128;
        PermitWristbandData data = new PermitWristbandData(
            request.getEventName(),
            request.getPermitLabel(),
            request.getCodeValue(),
            symbology
        );
        return permitZplGeneratorService.generate(data);
    }
}
```

Note: the old `resolve(WristbandPrintRequest, WristbandData)` two-arg method is removed. All callers must switch to `resolve(PrintableRequest)`.

- [ ] **Step 2: Update WristbandZplResolverTest**

Replace the test class with a version using the new single-arg signature:

```java
// src/test/java/com/stup/wristbandprinter/service/WristbandZplResolverTest.java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.*;
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateEntity;
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateRepository;
import com.stup.wristbandprinter.editor.service.TemplateZplRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WristbandZplResolverTest {

    @Mock ZplGeneratorService        zplGeneratorService;
    @Mock WristbandLayoutService     wristbandLayoutService;
    @Mock PermitZplGeneratorService  permitZplGeneratorService;
    @Mock WristbandTemplateRepository templateRepository;
    @Mock TemplateZplRenderer        templateRenderer;

    WristbandZplResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new WristbandZplResolver(
            zplGeneratorService, wristbandLayoutService, permitZplGeneratorService,
            templateRepository, templateRenderer);
    }

    @Test
    void crew_noTemplate_usesZplGeneratorService() {
        WristbandData data = new WristbandData("E", "F", "L", "A", "123");
        when(wristbandLayoutService.buildData(any())).thenReturn(data);
        when(zplGeneratorService.generate(data)).thenReturn("^XA^XZ");

        String zpl = resolver.resolve(crewRequest());

        assertThat(zpl).isEqualTo("^XA^XZ");
        verify(zplGeneratorService).generate(data);
        verifyNoInteractions(templateRenderer);
    }

    @Test
    void crew_withTemplate_usesTemplateRenderer() {
        WristbandData data = new WristbandData("E", "F", "L", "A", "123");
        when(wristbandLayoutService.buildData(any())).thenReturn(data);

        UUID templateId = UUID.randomUUID();
        WristbandTemplateEntity tpl = new WristbandTemplateEntity();
        when(templateRepository.findByIdAndDeletedFalse(templateId)).thenReturn(Optional.of(tpl));
        when(templateRenderer.render(any(), eq(data))).thenReturn("^XA_TEMPLATE^XZ");

        WristbandPrintRequest req = crewRequest();
        req.setTemplateId(templateId);

        assertThat(resolver.resolve(req)).isEqualTo("^XA_TEMPLATE^XZ");
        verifyNoInteractions(zplGeneratorService);
    }

    @Test
    void crew_withUnknownTemplate_throwsIllegalState() {
        WristbandData data = new WristbandData("E", "F", "L", "A", "123");
        when(wristbandLayoutService.buildData(any())).thenReturn(data);

        UUID templateId = UUID.randomUUID();
        when(templateRepository.findByIdAndDeletedFalse(templateId)).thenReturn(Optional.empty());

        WristbandPrintRequest req = crewRequest();
        req.setTemplateId(templateId);

        assertThatThrownBy(() -> resolver.resolve(req))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Template not found");
    }

    @Test
    void permit_routesToPermitZplGenerator() {
        when(permitZplGeneratorService.generate(any())).thenReturn("^XA_PERMIT^XZ");

        PermitWristbandPrintRequest req = new PermitWristbandPrintRequest();
        req.setEventName("Pukkelpop 2026");
        req.setPermitLabel("ELEKTRICITEIT");

        assertThat(resolver.resolve(req)).isEqualTo("^XA_PERMIT^XZ");
        verifyNoInteractions(zplGeneratorService, templateRenderer, wristbandLayoutService);
    }

    @Test
    void permit_withCodeValue_passesCodeToGenerator() {
        when(permitZplGeneratorService.generate(any())).thenReturn("^XA^XZ");

        PermitWristbandPrintRequest req = new PermitWristbandPrintRequest();
        req.setEventName("Pukkelpop 2026");
        req.setPermitLabel("PARKING");
        req.setCodeValue("LOT-A-42");
        req.setCodeSymbology(CodeSymbology.QR);

        resolver.resolve(req);

        var captor = org.mockito.ArgumentCaptor.forClass(PermitWristbandData.class);
        verify(permitZplGeneratorService).generate(captor.capture());
        assertThat(captor.getValue().codeValue()).isEqualTo("LOT-A-42");
        assertThat(captor.getValue().codeSymbology()).isEqualTo(CodeSymbology.QR);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private WristbandPrintRequest crewRequest() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("E"); r.setFirstName("F"); r.setLastName("L");
        r.setAssociationName("A"); r.setBarcodeValue("123");
        return r;
    }
}
```

- [ ] **Step 3: Fix any remaining callers of the old two-arg resolve() signature**

Search the codebase:
```bash
grep -rn "wristbandZplResolver.resolve\|resolver.resolve" \
    src/main/java src/test/java --include="*.java"
```

Fix each remaining `resolve(request, data)` call to `resolve(request)`:
- `PrintQueueService.processQueue()` — remove the `layoutService.buildData()` call, pass just `job.getRequest()`
- `WristbandController` methods — update in Part 4; for now they may still compile if the old two-arg signature is still present. If the old method is gone, update the controller now with the single-arg form.

If `WristbandControllerTest` stubs `wristbandZplResolver.resolve(any(), any())`, change to `resolve(any())`:
```java
// Before:
when(wristbandZplResolver.resolve(any(), any())).thenReturn("^XA^XZ");
verify(wristbandZplResolver).resolve(captor.capture(), any());

// After:
when(wristbandZplResolver.resolve(any())).thenReturn("^XA^XZ");
verify(wristbandZplResolver).resolve(captor.capture());
```

- [ ] **Step 4: Update WorkerProfileContextTest — verify permit beans absent in worker**

Add assertions for the new management-only beans:

```java
// In WorkerProfileContextTest — add to the existing "management beans absent in worker" test:
assertThat(ctx.containsBean("permitZplGeneratorService")).isFalse();
assertThat(ctx.containsBean("permitEventLogoService")).isFalse();
assertThat(ctx.containsBean("permitWristbandController")).isFalse(); // added in Part 4
```

- [ ] **Step 5: Run full test suite**

```bash
./mvnw test -q
```
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/WristbandZplResolver.java \
        src/test/java/com/stup/wristbandprinter/service/WristbandZplResolverTest.java \
        src/test/java/com/stup/wristbandprinter/worker/WorkerProfileContextTest.java
git commit -m "$(cat <<'EOF'
refactor: WristbandZplResolver accepts PrintableRequest; routes by WristbandType

CREW → ZplGeneratorService or TemplateZplRenderer (layout built internally).
PERMIT → PermitZplGeneratorService.
Single resolve(PrintableRequest) entry point replaces the old two-arg form.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Self-review

**Spec coverage check:**
- ✅ `PermitWristbandPrintRequest` with `permitLabel` (@NotBlank), `iconName` (stored, not rendered), `codeValue` (optional), `codeSymbology` (optional), `stockColorCode` (optional), `printerId` (optional) — Task 10
- ✅ `withPrinterId()` for reprint support — Task 10
- ✅ `PermitWristbandData` record — Task 10
- ✅ `PermitWristbandProperties` all layout params config-driven — Task 10
- ✅ `wristband.permit.*` YAML block — Task 10
- ✅ `PermitEventLogoService` follows `LogoConversionService` pattern exactly — Task 11
- ✅ `PermitZplGeneratorService` — 4-block layout, vertically centered — Task 12
- ✅ Optional scan code in block 3 (CODE128/CODE39/QR) — Task 12
- ✅ `WristbandZplResolver.resolve(PrintableRequest)` single-arg routing — Task 13
- ✅ `WorkerProfileContextTest` updated — Task 13

**Gaps / follow-ons:**
- Controller endpoints (`/crew/print`, `/permit/print`, color tinting, gallery) — Part 4
- `iconName` field is stored in the DB and passed through but not rendered (by design — Font Awesome follow-up)
- Visual calibration of permit layout: run Labelary preview via `POST /api/wristbands/permit/preview/image` after Part 4 and adjust `wristband.permit.*` YAML values to taste

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-09-permit-wristband-part-3-permit-band.md`.

**Two execution options:**

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks.

**2. Inline Execution** — execute tasks in this session using executing-plans.

Which approach?

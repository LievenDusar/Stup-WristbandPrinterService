# Permit Wristband – Part 1: Foundation (Enums, ScanCodeRenderer, WristbandData) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce `WristbandType` and `CodeSymbology` enums, a shared `ScanCodeRenderer` static utility, and thread `CodeSymbology` through `WristbandData` / `WristbandLayoutService` / `ZplGeneratorService` — all without changing existing behavior.

**Architecture:** New enums land in `domain/`; `ScanCodeRenderer` is a package-private final class in `service/` with only static methods. `WristbandData` gains a 6th field (`codeSymbology`) but keeps a 5-arg backward-compatible constructor so none of the existing callers break. `ZplGeneratorService` delegates its hardcoded Code 128 block to `ScanCodeRenderer` — same ZPL output for the default case, new symbology support for free.

**Tech Stack:** Java 21, Spring Boot 3.4.1, JUnit 5, existing `./mvnw test` suite.

**Prerequisite:** Parts 2–4 build on top of this part. Merge this before starting Part 2.

---

## File map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/…/domain/WristbandType.java` | Band-type discriminator enum |
| Create | `src/main/java/…/domain/CodeSymbology.java` | Scan-code symbology enum |
| Create | `src/main/java/…/service/ScanCodeRenderer.java` | Static ZPL generation + length estimation for CODE128 / CODE39 / QR |
| Modify | `src/main/java/…/domain/WristbandData.java` | Add `codeSymbology` field; add 5-arg backward-compat constructor |
| Modify | `src/main/java/…/service/WristbandLayoutService.java` | Pass `codeSymbology` (defaulting to CODE128) when building `WristbandData` |
| Modify | `src/main/java/…/service/ZplGeneratorService.java` | Delegate barcode rendering to `ScanCodeRenderer`; remove inlined Code 128 constants |
| Create | `src/test/java/…/service/ScanCodeRendererTest.java` | Unit tests for all three symbologies |
| Modify | `src/test/java/…/service/ZplGeneratorServiceTest.java` | Adapt any direct `estimateBarcodeYLength` / constant references |
| Modify | `src/test/java/…/service/ZplGeneratorServiceLayoutTest.java` | Adapt `WristbandData` construction (add CODE128 arg or rely on 5-arg ctor) |
| Modify | `src/test/java/…/service/WristbandLayoutServiceTest.java` | Assert `codeSymbology` is populated |
| Modify | `src/test/java/…/controller/WristbandControllerTest.java` | Adapt `WristbandData` construction |

---

## Task 1: WristbandType + CodeSymbology enums

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/domain/WristbandType.java`
- Create: `src/main/java/com/stup/wristbandprinter/domain/CodeSymbology.java`

- [ ] **Step 1: Create WristbandType**

```java
// src/main/java/com/stup/wristbandprinter/domain/WristbandType.java
package com.stup.wristbandprinter.domain;

public enum WristbandType {
    CREW,
    PERMIT
}
```

- [ ] **Step 2: Create CodeSymbology**

```java
// src/main/java/com/stup/wristbandprinter/domain/CodeSymbology.java
package com.stup.wristbandprinter.domain;

public enum CodeSymbology {
    CODE128,
    CODE39,
    QR
}
```

- [ ] **Step 3: Verify compilation**

```bash
./mvnw compile -q
```
Expected: BUILD SUCCESS, no errors.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/WristbandType.java \
        src/main/java/com/stup/wristbandprinter/domain/CodeSymbology.java
git commit -m "$(cat <<'EOF'
feat: add WristbandType and CodeSymbology enums

Foundation for the permit wristband and multi-symbology scan codes.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: ScanCodeRenderer

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/service/ScanCodeRenderer.java`
- Create: `src/test/java/com/stup/wristbandprinter/service/ScanCodeRendererTest.java`

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/com/stup/wristbandprinter/service/ScanCodeRendererTest.java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.CodeSymbology;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScanCodeRendererTest {

    // ── CODE128 ─────────────────────────────────────────────────────────────

    @Test
    void code128_appendTo_containsBCB() {
        StringBuilder zpl = new StringBuilder();
        ScanCodeRenderer.appendTo(zpl, "ABC123", CodeSymbology.CODE128, 10, 100, 270, 3, false);
        String out = zpl.toString();
        assertThat(out).contains("^BCB,270,N,N,N");
        assertThat(out).contains("^BY3");
        assertThat(out).contains("^FDABC123^FS");
        assertThat(out).contains("^FO10,100");
    }

    @Test
    void code128_appendTo_showHumanReadable_usesY() {
        StringBuilder zpl = new StringBuilder();
        ScanCodeRenderer.appendTo(zpl, "123", CodeSymbology.CODE128, 0, 0, 270, 3, true);
        assertThat(zpl.toString()).contains("^BCB,270,Y,N,N");
    }

    @Test
    void code128_estimateYLength_growsWithDataLength() {
        int short_ = ScanCodeRenderer.estimateYLength("A", CodeSymbology.CODE128, 3, 270);
        int long_  = ScanCodeRenderer.estimateYLength("ABCDEFGHIJ", CodeSymbology.CODE128, 3, 270);
        assertThat(long_).isGreaterThan(short_);
    }

    @Test
    void code128_quietZone_isModuleWidthTimes20() {
        assertThat(ScanCodeRenderer.quietZoneDots(CodeSymbology.CODE128, 3)).isEqualTo(60);
        assertThat(ScanCodeRenderer.quietZoneDots(CodeSymbology.CODE128, 2)).isEqualTo(40);
    }

    @Test
    void code128_crossBandExtent_equalsHeightDots() {
        assertThat(ScanCodeRenderer.estimateCrossBandExtent(CodeSymbology.CODE128, 270, "ABC")).isEqualTo(270);
    }

    // ── CODE39 ──────────────────────────────────────────────────────────────

    @Test
    void code39_appendTo_containsB3B() {
        StringBuilder zpl = new StringBuilder();
        ScanCodeRenderer.appendTo(zpl, "HELLO", CodeSymbology.CODE39, 15, 200, 270, 3, false);
        String out = zpl.toString();
        assertThat(out).contains("^B3B,N,270,N,N");
        assertThat(out).contains("^BY3");
        assertThat(out).contains("^FDHELLO^FS");
        assertThat(out).contains("^FO15,200");
    }

    @Test
    void code39_estimateYLength_growsWithDataLength() {
        int short_ = ScanCodeRenderer.estimateYLength("A",      CodeSymbology.CODE39, 3, 270);
        int long_  = ScanCodeRenderer.estimateYLength("ABCDEFG", CodeSymbology.CODE39, 3, 270);
        assertThat(long_).isGreaterThan(short_);
    }

    @Test
    void code39_crossBandExtent_equalsHeightDots() {
        assertThat(ScanCodeRenderer.estimateCrossBandExtent(CodeSymbology.CODE39, 270, "ABC")).isEqualTo(270);
    }

    // ── QR ──────────────────────────────────────────────────────────────────

    @Test
    void qr_appendTo_containsBQN() {
        StringBuilder zpl = new StringBuilder();
        ScanCodeRenderer.appendTo(zpl, "https://stup.be", CodeSymbology.QR, 50, 300, 270, 3, false);
        String out = zpl.toString();
        assertThat(out).contains("^BQN,2,");
        assertThat(out).contains("^FDMA,https://stup.be^FS");
        assertThat(out).contains("^FO50,300");
    }

    @Test
    void qr_crossBandExtent_isSquare_lessThanBandWidth300() {
        int extent = ScanCodeRenderer.estimateCrossBandExtent(CodeSymbology.QR, 270, "ABC123");
        assertThat(extent).isLessThanOrEqualTo(300);
        assertThat(extent).isGreaterThan(0);
    }

    // ── sanitize ────────────────────────────────────────────────────────────

    @Test
    void appendTo_sanitizesCaretAndTilde() {
        StringBuilder zpl = new StringBuilder();
        ScanCodeRenderer.appendTo(zpl, "A^B~C", CodeSymbology.CODE128, 0, 0, 270, 3, false);
        assertThat(zpl.toString()).contains("^FDABC^FS");
    }
}
```

- [ ] **Step 2: Run tests — expect compile failure (class doesn't exist yet)**

```bash
./mvnw test -Dtest=ScanCodeRendererTest -q 2>&1 | tail -5
```
Expected: compilation error `cannot find symbol … ScanCodeRenderer`.

- [ ] **Step 3: Implement ScanCodeRenderer**

```java
// src/main/java/com/stup/wristbandprinter/service/ScanCodeRenderer.java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.CodeSymbology;

/**
 * Static utility for generating ZPL scan-code commands and estimating their
 * physical size in dots. Supports CODE128 (rotated bottom-up ^BCB), CODE39
 * (rotated bottom-up ^B3B), and QR (normal orientation ^BQN).
 *
 * <p>All rotated barcodes (CODE128 / CODE39) are printed 90° CCW (bottom-up):
 * their "height" maps to the X axis (across the band), and the barcode body
 * grows in the +Y direction (along the band length).  QR is printed in normal
 * orientation; both dimensions are square and grow in +X/+Y.</p>
 */
public final class ScanCodeRenderer {

    // Code 128: fixed modules = start(11) + check(11) + stop(13) = 35
    static final int CODE128_FIXED_INK_MODULES = 35;
    static final int CODE128_DATA_MODULES_PER_CHAR = 11;

    // Code 39: each character = 15 narrow-bar modules; start + stop = 2 extra chars
    static final int CODE39_CHAR_MODULES = 15;

    // Quiet zone shared by CODE128 and CODE39 (blank modules on each side)
    static final int BARCODE_QUIET_MODULES = 20;

    // QR caps
    private static final int QR_MAX_MAGNIFICATION = 10;
    private static final int QR_MIN_MAGNIFICATION = 2;

    private ScanCodeRenderer() {}

    /**
     * Appends the ZPL commands for a scan code at position {@code (x, y)}.
     *
     * @param zpl             target buffer
     * @param value           raw data (will be sanitized of ^ and ~)
     * @param symbology       CODE128, CODE39, or QR
     * @param x               field origin X (use {@link #estimateCrossBandExtent} for centering)
     * @param y               field origin Y (top of scan-code block along band)
     * @param heightDots      bar height for rotated codes; approximate side length for QR
     * @param moduleWidthDots narrow-bar width (^BY param); ignored for QR
     * @param showHumanReadable whether to print the human-readable line (ignored for QR)
     */
    public static void appendTo(StringBuilder zpl, String value, CodeSymbology symbology,
                                  int x, int y, int heightDots, int moduleWidthDots,
                                  boolean showHumanReadable) {
        switch (symbology) {
            case CODE128 -> appendCode128(zpl, sanitize(value), x, y, heightDots, moduleWidthDots, showHumanReadable);
            case CODE39  -> appendCode39(zpl,  sanitize(value), x, y, heightDots, moduleWidthDots, showHumanReadable);
            case QR      -> appendQr(zpl,      sanitize(value), x, y, heightDots);
        }
    }

    /**
     * Estimates the Y-axis footprint (along the band length) of a scan code in dots.
     * Used for vertical layout math.
     */
    public static int estimateYLength(String value, CodeSymbology symbology,
                                       int moduleWidthDots, int heightDots) {
        return switch (symbology) {
            case CODE128 -> (CODE128_FIXED_INK_MODULES
                             + value.length() * CODE128_DATA_MODULES_PER_CHAR
                             + BARCODE_QUIET_MODULES) * moduleWidthDots;
            case CODE39  -> (CODE39_CHAR_MODULES * (value.length() + 2)
                             + BARCODE_QUIET_MODULES) * moduleWidthDots;
            case QR      -> qrMagnification(heightDots) * qrModuleCount(value.length());
        };
    }

    /**
     * Returns the blank quiet-zone portion of the scan-code footprint in dots.
     * The crew-band layout subtracts this from one side so the text block
     * appears visually centered between the barcode and the logo.
     */
    public static int quietZoneDots(CodeSymbology symbology, int moduleWidthDots) {
        return switch (symbology) {
            case CODE128, CODE39 -> BARCODE_QUIET_MODULES * moduleWidthDots;
            case QR              -> 4 * qrMagnification(270); // typical quiet zone
        };
    }

    /**
     * Returns the X-axis extent (across the band width) of the scan code in dots.
     * Use this to center the code: {@code x = (bandWidth - extent) / 2}.
     */
    public static int estimateCrossBandExtent(CodeSymbology symbology, int heightDots, String value) {
        return switch (symbology) {
            case CODE128, CODE39 -> heightDots;
            case QR              -> qrMagnification(heightDots) * qrModuleCount(value.length());
        };
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private static void appendCode128(StringBuilder zpl, String value, int x, int y,
                                        int heightDots, int moduleWidthDots,
                                        boolean showHumanReadable) {
        String hri = showHumanReadable ? "Y" : "N";
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(String.format("^BY%d", moduleWidthDots));
        zpl.append(String.format("^BCB,%d,%s,N,N", heightDots, hri));
        zpl.append(String.format("^FD%s^FS", value));
    }

    private static void appendCode39(StringBuilder zpl, String value, int x, int y,
                                       int heightDots, int moduleWidthDots,
                                       boolean showHumanReadable) {
        String hri = showHumanReadable ? "Y" : "N";
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(String.format("^BY%d", moduleWidthDots));
        // ^B3B = Code 39, B = bottom-up, N = normal check digit, height, hri, line
        zpl.append(String.format("^B3B,N,%d,%s,N", heightDots, hri));
        zpl.append(String.format("^FD%s^FS", value));
    }

    private static void appendQr(StringBuilder zpl, String value, int x, int y, int heightDots) {
        int mag = qrMagnification(heightDots);
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(String.format("^BQN,2,%d", mag));
        zpl.append(String.format("^FDMA,%s^FS", value));
    }

    /**
     * Derives QR magnification from the desired side length in dots.
     * Clamped to [2, 10] per the ZPL spec.
     */
    static int qrMagnification(int heightDots) {
        return Math.max(QR_MIN_MAGNIFICATION, Math.min(QR_MAX_MAGNIFICATION, heightDots / 25));
    }

    /**
     * Minimum QR module grid side for the given data length (simplified).
     * Version 1 = 21, version 2 = 25, etc.; each version adds 4 modules per side.
     */
    static int qrModuleCount(int dataLength) {
        if (dataLength <= 25) return 25;
        if (dataLength <= 47) return 29;
        if (dataLength <= 77) return 33;
        return 41;
    }

    /** Removes ZPL control characters. */
    private static String sanitize(String text) {
        return text.replaceAll("[\\^~]", "");
    }
}
```

- [ ] **Step 4: Run tests — expect all green**

```bash
./mvnw test -Dtest=ScanCodeRendererTest -q
```
Expected: `Tests run: 14, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/ScanCodeRenderer.java \
        src/test/java/com/stup/wristbandprinter/service/ScanCodeRendererTest.java
git commit -m "$(cat <<'EOF'
feat: add ScanCodeRenderer static utility (CODE128, CODE39, QR)

Encapsulates ZPL generation and length estimation for all three
scan-code symbologies. ZplGeneratorService will delegate to this
in the next commit; PermitZplGeneratorService will use it directly.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: WristbandData + WristbandLayoutService — add CodeSymbology

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/domain/WristbandData.java`
- Modify: `src/main/java/com/stup/wristbandprinter/service/WristbandLayoutService.java`
- Modify: `src/test/java/com/stup/wristbandprinter/service/WristbandLayoutServiceTest.java`

- [ ] **Step 1: Read the existing tests to understand what needs updating**

Read:
- `src/test/java/com/stup/wristbandprinter/service/WristbandLayoutServiceTest.java`

- [ ] **Step 2: Update WristbandData — add codeSymbology with backward-compat 5-arg constructor**

Replace the entire file:

```java
// src/main/java/com/stup/wristbandprinter/domain/WristbandData.java
package com.stup.wristbandprinter.domain;

public record WristbandData(
    String eventName,
    String firstName,
    String lastName,
    String associationName,
    String barcodeValue,
    CodeSymbology codeSymbology
) {
    /** Backward-compatible constructor; defaults to CODE128. */
    public WristbandData(String eventName, String firstName, String lastName,
                         String associationName, String barcodeValue) {
        this(eventName, firstName, lastName, associationName, barcodeValue, CodeSymbology.CODE128);
    }
}
```

- [ ] **Step 3: Update WristbandLayoutService**

```java
// src/main/java/com/stup/wristbandprinter/service/WristbandLayoutService.java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.CodeSymbology;
import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Profile("!worker")
@Service
public class WristbandLayoutService {

    public WristbandData buildData(WristbandPrintRequest request) {
        CodeSymbology symbology = request.getCodeSymbology() != null
            ? request.getCodeSymbology()
            : CodeSymbology.CODE128;
        return new WristbandData(
            request.getEventName(),
            request.getFirstName(),
            request.getLastName(),
            request.getAssociationName(),
            request.getBarcodeValue(),
            symbology
        );
    }
}
```

Note: `WristbandPrintRequest.getCodeSymbology()` will be added in Part 2. Until then this file will fail to compile — that is expected; compile after Part 2 Task 1 lands. **Skip the compile check in this task if Part 2 is not yet applied.** If you want a clean compile now, add a temporary stub getter to `WristbandPrintRequest` and remove it in Part 2:

```java
// Temporary: add to WristbandPrintRequest.java until Part 2 lands
public CodeSymbology getCodeSymbology() { return null; }
```

- [ ] **Step 4: Update WristbandLayoutServiceTest to assert codeSymbology is set**

Find the test method that calls `buildData(request)` and add:

```java
// Example test to add / adapt in WristbandLayoutServiceTest
@Test
void buildData_defaultsCodeSymbologyToCode128_whenNullOnRequest() {
    WristbandPrintRequest req = new WristbandPrintRequest();
    req.setEventName("E"); req.setFirstName("F"); req.setLastName("L");
    req.setAssociationName("A"); req.setBarcodeValue("123");
    // codeSymbology left null

    WristbandLayoutService svc = new WristbandLayoutService();
    WristbandData data = svc.buildData(req);

    assertThat(data.codeSymbology()).isEqualTo(CodeSymbology.CODE128);
}

@Test
void buildData_preservesCodeSymbologyFromRequest() {
    WristbandPrintRequest req = new WristbandPrintRequest();
    req.setEventName("E"); req.setFirstName("F"); req.setLastName("L");
    req.setAssociationName("A"); req.setBarcodeValue("123");
    req.setCodeSymbology(CodeSymbology.QR);

    WristbandLayoutService svc = new WristbandLayoutService();
    WristbandData data = svc.buildData(req);

    assertThat(data.codeSymbology()).isEqualTo(CodeSymbology.QR);
}
```

Also add the `CodeSymbology` import:
```java
import com.stup.wristbandprinter.domain.CodeSymbology;
```

And add a `setCodeSymbology` stub to `WristbandPrintRequest` if Part 2 is not yet applied (same as above temporary stub).

- [ ] **Step 5: Run WristbandLayoutServiceTest**

```bash
./mvnw test -Dtest=WristbandLayoutServiceTest -q
```
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/WristbandData.java \
        src/main/java/com/stup/wristbandprinter/service/WristbandLayoutService.java \
        src/test/java/com/stup/wristbandprinter/service/WristbandLayoutServiceTest.java
git commit -m "$(cat <<'EOF'
feat: add codeSymbology to WristbandData; WristbandLayoutService defaults to CODE128

5-arg constructor kept for backward compatibility. Part 2 will add the
matching getCodeSymbology() to WristbandPrintRequest.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: ZplGeneratorService — delegate barcode to ScanCodeRenderer

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/service/ZplGeneratorService.java`
- Modify: `src/test/java/com/stup/wristbandprinter/service/ZplGeneratorServiceTest.java`
- Modify: `src/test/java/com/stup/wristbandprinter/service/ZplGeneratorServiceLayoutTest.java`

- [ ] **Step 1: Read existing tests to understand what to keep**

Run to see current pass rate:
```bash
./mvnw test -Dtest="ZplGeneratorServiceTest,ZplGeneratorServiceLayoutTest" -q
```

- [ ] **Step 2: Update ZplGeneratorService**

Replace the body of `generate()`, `appendBarcode()`, `estimateBarcodeYLength()`, and `barcodeQuietZoneDots()`. Remove the old private constants `BARCODE_FIXED_INK_MODULES` and `BARCODE_QUIET_MODULES` (they now live in `ScanCodeRenderer`). Full updated file:

```java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.WristbandData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Slf4j
@Profile("!worker")
@Service
public class ZplGeneratorService {

    // Gap between text lines in dots (across band width, with ^A0B rotation)
    private static final int INTER_LINE_GAP = 12;

    // ^A0 font 0: actual Y advance per character ≈ fontSize × 0.46.
    // Calibrated against Labelary (ZP font 0, 12 dpmm): 33.5 dots/char at font 74.
    // Package-private so layout tests can assert against the same constant.
    static final double CHAR_ADVANCE_RATIO = 0.46;

    private final WristbandProperties props;
    private final LogoConversionService logoConversionService;

    public ZplGeneratorService(WristbandProperties props, LogoConversionService logoConversionService) {
        this.props = props;
        this.logoConversionService = logoConversionService;
    }

    public String generate(WristbandData data) {
        WristbandProperties.Margins margins  = props.getMargins();
        WristbandProperties.Text   textBlock = props.getText();
        WristbandProperties.Barcode barCode  = props.getBarcode();

        int logoH      = logoConversionService.getLogoHeightDots();
        int sideMargin = props.getLogoSideMarginDots();
        int barcodeLen = ScanCodeRenderer.estimateYLength(
                data.barcodeValue(), data.codeSymbology(),
                barCode.getModuleWidthDots(), barCode.getHeightDots());
        int textLen    = textBlockYLength(data, textBlock);
        int quietZone  = ScanCodeRenderer.quietZoneDots(
                data.codeSymbology(), barCode.getModuleWidthDots());

        // Layout: logo → barcode → text → logo (vertically centered on band)
        int totalHeight = logoH
            + margins.getBetweenLogoAndBarcode()
            + barcodeLen
            + margins.getBetweenBarcodeAndText()
            + textLen
            + margins.getBetweenTextAndLogo()
            + quietZone
            + logoH;
        int topLogoY    = (props.getLengthDots() - totalHeight) / 2;
        int barcodeY    = topLogoY + logoH + margins.getBetweenLogoAndBarcode();
        int textBlockY  = barcodeY + barcodeLen + margins.getBetweenBarcodeAndText();
        int bottomLogoY = textBlockY + textLen + margins.getBetweenTextAndLogo() + quietZone;

        StringBuilder zpl = new StringBuilder();
        zpl.append("^XA");
        zpl.append(String.format("^PW%d", props.getWidthDots()));
        zpl.append(String.format("^LL%d", props.getLengthDots()));
        zpl.append("^CI28");

        appendLogo(zpl, sideMargin, topLogoY);
        appendBarcode(zpl, data, barcodeY, barCode);
        appendTextBlock(zpl, data, textBlockY, textBlock);
        appendLogo(zpl, sideMargin, bottomLogoY);

        zpl.append("^XZ");
        return zpl.toString();
    }

    private void appendLogo(StringBuilder zpl, int x, int y) {
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(logoConversionService.getGfCommand());
    }

    private void appendBarcode(StringBuilder zpl, WristbandData data, int y,
                                 WristbandProperties.Barcode b) {
        int crossExtent = ScanCodeRenderer.estimateCrossBandExtent(
                data.codeSymbology(), b.getHeightDots(), data.barcodeValue());
        int x = (props.getWidthDots() - crossExtent) / 2;
        ScanCodeRenderer.appendTo(zpl, data.barcodeValue(), data.codeSymbology(),
                x, y, b.getHeightDots(), b.getModuleWidthDots(), b.isShowHumanReadable());
    }

    private void appendTextBlock(StringBuilder zpl, WristbandData data,
                                   int startY, WristbandProperties.Text t) {
        int h1 = t.getFontSizeEvent();
        int h2 = t.getFontSizeName();
        int h3 = t.getFontSizeAssociation();

        int totalXWidth = h1 + INTER_LINE_GAP + h2 + INTER_LINE_GAP + h3;
        int groupX = (props.getWidthDots() - totalXWidth) / 2;

        String eventText = sanitize(data.eventName());
        String nameText  = sanitize(data.firstName()) + " " + sanitize(data.lastName());
        String assocText = sanitize(data.associationName());

        int blockHeight = textBlockYLength(data, t);
        int centerY = startY + blockHeight / 2;
        int eventY  = centerY - lineExtent(eventText.length(), h1) / 2;
        int nameY   = centerY - lineExtent(nameText.length(),  h2) / 2;
        int assocY  = centerY - lineExtent(assocText.length(), h3) / 2;

        zpl.append(String.format("^FO%d,%d", groupX, eventY));
        zpl.append(String.format("^A0B,%d,%d", h1, h1));
        zpl.append(String.format("^FD%s^FS", eventText));

        zpl.append(String.format("^FO%d,%d", groupX + h1 + INTER_LINE_GAP, nameY));
        zpl.append(String.format("^A0B,%d,%d", h2, h2));
        zpl.append(String.format("^FD%s^FS", nameText));

        zpl.append(String.format("^FO%d,%d", groupX + h1 + INTER_LINE_GAP + h2 + INTER_LINE_GAP, assocY));
        zpl.append(String.format("^A0B,%d,%d", h3, h3));
        zpl.append(String.format("^FD%s^FS", assocText));
    }

    /** Centers a field of the given height across the label width. */
    private int centerX(int fieldHeight) {
        return (props.getWidthDots() - fieldHeight) / 2;
    }

    private int textBlockYLength(WristbandData data, WristbandProperties.Text t) {
        int eventLen = lineExtent(sanitize(data.eventName()).length(),       t.getFontSizeEvent());
        int nameLen  = lineExtent((sanitize(data.firstName()) + " " + sanitize(data.lastName())).length(),
                                   t.getFontSizeName());
        int assocLen = lineExtent(sanitize(data.associationName()).length(), t.getFontSizeAssociation());
        return Math.max(eventLen, Math.max(nameLen, assocLen));
    }

    private int lineExtent(int charCount, int fontSize) {
        return (int) (charCount * fontSize * CHAR_ADVANCE_RATIO);
    }

    /** Removes ZPL control characters from user-supplied text. */
    private String sanitize(String text) {
        return text.replaceAll("[\\^~]", "");
    }
}
```

Key changes:
- Removed `BARCODE_FIXED_INK_MODULES`, `BARCODE_QUIET_MODULES` constants (moved to `ScanCodeRenderer`).
- `appendBarcode(StringBuilder, String, int, Barcode)` → `appendBarcode(StringBuilder, WristbandData, int, Barcode)` — reads `data.codeSymbology()` and calls `ScanCodeRenderer`.
- `estimateBarcodeYLength(String)` replaced inline by `ScanCodeRenderer.estimateYLength(...)`.
- `barcodeQuietZoneDots()` replaced inline by `ScanCodeRenderer.quietZoneDots(...)`.
- `centerX()` method kept (used by text block).

- [ ] **Step 3: Fix any test compilation errors**

If `ZplGeneratorServiceLayoutTest` or `ZplGeneratorServiceTest` reference the removed constants (`BARCODE_FIXED_INK_MODULES`, `BARCODE_QUIET_MODULES`, `estimateBarcodeYLength`) switch them to `ScanCodeRenderer` equivalents:

```java
// Before (if present in tests):
int expected = ZplGeneratorService.BARCODE_FIXED_INK_MODULES * 3;

// After:
int expected = ScanCodeRenderer.CODE128_FIXED_INK_MODULES * 3;
```

If tests call `generate()` with a 5-field `WristbandData`, the backward-compatible constructor handles this automatically — no change needed.

- [ ] **Step 4: Run the full test suite**

```bash
./mvnw test -q
```
Expected: all existing tests pass. Zero failures, zero errors.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/ZplGeneratorService.java \
        src/test/java/com/stup/wristbandprinter/service/ZplGeneratorServiceTest.java \
        src/test/java/com/stup/wristbandprinter/service/ZplGeneratorServiceLayoutTest.java
git commit -m "$(cat <<'EOF'
refactor: delegate ZplGeneratorService barcode rendering to ScanCodeRenderer

No behavior change for CODE128 (default). CODE39 and QR are now
available through the codeSymbology field on WristbandData.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Self-review

**Spec coverage check:**
- ✅ `WristbandType` enum (CREW, PERMIT) — Task 1
- ✅ `CodeSymbology` enum (CODE128, CODE39, QR) — Task 1
- ✅ `ScanCodeRenderer` static utility — Task 2
- ✅ `WristbandData.codeSymbology` field — Task 3
- ✅ Backward-compatible 5-arg WristbandData constructor — Task 3
- ✅ `WristbandLayoutService` defaults to CODE128 — Task 3
- ✅ `ZplGeneratorService` uses ScanCodeRenderer — Task 4
- ✅ Full test coverage for ScanCodeRenderer — Task 2
- ✅ Existing crew-band tests continue to pass — Task 4

**Gaps / follow-ons:** `WristbandPrintRequest.getCodeSymbology()` is needed for WristbandLayoutService — this lands in Part 2 Task 1 (PrintableRequest + WristbandPrintRequest update).

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-09-permit-wristband-part-1-foundation.md`.

**Two execution options:**

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks.

**2. Inline Execution** — execute tasks in this session using executing-plans.

Which approach?

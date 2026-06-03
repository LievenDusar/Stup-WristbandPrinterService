# Template Grouping — Plan 1 (Backend): Model + Recursive Renderer

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the template model with nested groups (auto-stacking with a choosable direction, an inter-item margin, and cross-axis alignment) plus an optional per-element `sampleText`, and make `TemplateZplRenderer` flatten that tree to absolute ZPL positions — without changing how existing flat templates render.

**Architecture:** `elements` becomes a tree: a `TemplateElement` may be `type == GROUP` with `children`. The renderer recurses, laying a group's children along `stackDirection` using each child's stored bounding box (`widthDots`/`heightDots`) plus `marginDots`, offset on the cross-axis by `crossAlign`, then translated by the group's origin. Leaf rendering is unchanged. New record fields are nullable and `@JsonInclude(NON_NULL)`, so existing JSON and existing call-sites keep working (a 16-arg convenience constructor preserves current leaf construction).

**Tech Stack:** Java 21, Spring Boot 3, Jackson (records), JUnit 5 + AssertJ + Mockito. No DB migration (JSONB column).

**Scope (Plan 1 of 2):** domain model + renderer + tests. The editor UI is Plan 2. `sampleText` is stored but ignored by the renderer (it drives the editor canvas + the POST-preview from the client).

**Conventions (verified against the code):**
- `TemplateElement` is a single `record` with `@JsonInclude(NON_NULL)`; current canonical order is `id,type,x,y,widthDots,heightDots,rotation,binding,value,fontSize,font,symbology,showHumanReadable,assetId,shape,thicknessDots`.
- Renderer wraps `^XA^PW<w>^LL<l>^CI28 … ^XZ`; orientation letters N/R/I/B; user text sanitized via `replaceAll("[\\^~]", "")`.
- Renderer tests live in `TemplateZplRendererTest` (Mockito `TemplateAssetService`); JSON round-trips in `TemplateDefinitionJsonTest`.

---

## File Structure

**Create:**
- `src/main/java/com/stup/wristbandprinter/editor/domain/StackDirection.java`
- `src/main/java/com/stup/wristbandprinter/editor/domain/CrossAlign.java`

**Modify:**
- `src/main/java/com/stup/wristbandprinter/editor/domain/ElementType.java` — add `GROUP`
- `src/main/java/com/stup/wristbandprinter/editor/domain/TemplateElement.java` — 5 new fields + 16-arg convenience constructor + `group(...)` factory
- `src/main/java/com/stup/wristbandprinter/editor/service/TemplateZplRenderer.java` — recursive layout
- `src/test/java/com/stup/wristbandprinter/editor/domain/TemplateDefinitionJsonTest.java` — nested-group round-trip
- `src/test/java/com/stup/wristbandprinter/editor/service/TemplateZplRendererTest.java` — group layout golden tests

---

## Task 1: Model — enums, GROUP, group fields, convenience constructor, JSON round-trip

**Files:**
- Create: `StackDirection.java`, `CrossAlign.java`
- Modify: `ElementType.java`, `TemplateElement.java`
- Test: `TemplateDefinitionJsonTest.java`

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/com/stup/wristbandprinter/editor/domain/TemplateDefinitionJsonTest.java` a new test (inside the class):

```java
    @Test
    void serializesAndDeserializesNestedGroupAndSampleText() throws Exception {
        TemplateElement first = new TemplateElement(
            "first", ElementType.TEXT, 0, 0, 28, 200, 90,
            DataBinding.FIRST_NAME, null, 28, "0", null, null, null, null, null);
        TemplateElement last = new TemplateElement(
            "last", ElementType.TEXT, 0, 0, 28, 220, 90,
            DataBinding.LAST_NAME, null, 28, "0", null, null, null, null, null);
        // sampleText set via the canonical constructor on a static-text leaf
        TemplateElement free = new TemplateElement(
            "free", ElementType.STATIC_TEXT, 0, 0, 24, 120, 0,
            null, "STAFF", 24, "0", null, null, null, null, null,
            null, null, null, null, "Crew");

        TemplateElement nameGroup = TemplateElement.group(
            "g-name", 0, 0, StackDirection.LENGTH, 10, CrossAlign.CENTER, java.util.List.of(first, last));
        TemplateElement outer = TemplateElement.group(
            "g-outer", 20, 40, StackDirection.LENGTH, 30, CrossAlign.START, java.util.List.of(nameGroup, free));

        TemplateDefinition def = new TemplateDefinition(new Canvas(203, 2233, 300), java.util.List.of(outer));

        String json = mapper.writeValueAsString(def);
        TemplateDefinition back = mapper.readValue(json, TemplateDefinition.class);

        assertThat(back).isEqualTo(def);
        TemplateElement o = back.elements().get(0);
        assertThat(o.type()).isEqualTo(ElementType.GROUP);
        assertThat(o.children()).hasSize(2);
        assertThat(o.children().get(0).children()).hasSize(2); // nested group preserved
        assertThat(o.children().get(1).sampleText()).isEqualTo("Crew");
        assertThat(o.children().get(0).crossAlign()).isEqualTo(CrossAlign.CENTER);
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=TemplateDefinitionJsonTest`
Expected: FAIL — `StackDirection`, `CrossAlign`, `ElementType.GROUP`, `TemplateElement.group(...)`, the 21-arg constructor, and `sampleText()`/`children()` don't exist.

- [ ] **Step 3: Create the enums**

`src/main/java/com/stup/wristbandprinter/editor/domain/StackDirection.java`:

```java
package com.stup.wristbandprinter.editor.domain;

/** Axis along which a group stacks its children. */
public enum StackDirection {
    LENGTH, // down the long axis of the wristband
    WIDTH   // across the short axis
}
```

`src/main/java/com/stup/wristbandprinter/editor/domain/CrossAlign.java`:

```java
package com.stup.wristbandprinter.editor.domain;

/** How a group's children line up on the cross-axis (perpendicular to the stack). */
public enum CrossAlign {
    START, CENTER, END
}
```

- [ ] **Step 4: Add `GROUP` to `ElementType`**

`src/main/java/com/stup/wristbandprinter/editor/domain/ElementType.java`:

```java
package com.stup.wristbandprinter.editor.domain;

public enum ElementType {
    TEXT, STATIC_TEXT, BARCODE, IMAGE, SHAPE, GROUP
}
```

- [ ] **Step 5: Extend `TemplateElement`**

Replace `src/main/java/com/stup/wristbandprinter/editor/domain/TemplateElement.java` with:

```java
package com.stup.wristbandprinter.editor.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * One element on the wristband. Coordinates/sizes are in printer dots; {@code rotation} is one of
 * 0/90/180/270. A {@code GROUP} carries {@code children} plus layout fields and ignores its own
 * leaf fields; its children's {@code x}/{@code y} are computed by the layout. Leaf fields not
 * relevant to a type are null.
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
    String font,                // TEXT, STATIC_TEXT
    String symbology,           // BARCODE
    Boolean showHumanReadable,  // BARCODE
    UUID assetId,               // IMAGE
    ShapeType shape,            // SHAPE
    Integer thicknessDots,      // SHAPE
    List<TemplateElement> children,  // GROUP
    StackDirection stackDirection,   // GROUP
    Integer marginDots,              // GROUP
    CrossAlign crossAlign,           // GROUP
    String sampleText                // TEXT / STATIC_TEXT (design + preview only)
) {

    /** Backwards-compatible 16-arg leaf constructor (group/sample fields default to null). */
    public TemplateElement(String id, ElementType type, int x, int y, int widthDots, int heightDots,
                           int rotation, DataBinding binding, String value, Integer fontSize, String font,
                           String symbology, Boolean showHumanReadable, UUID assetId, ShapeType shape,
                           Integer thicknessDots) {
        this(id, type, x, y, widthDots, heightDots, rotation, binding, value, fontSize, font,
            symbology, showHumanReadable, assetId, shape, thicknessDots,
            null, null, null, null, null);
    }

    /** Factory for a group element. Leaf fields are null; {@code x}/{@code y} are the group origin. */
    public static TemplateElement group(String id, int x, int y, StackDirection stackDirection,
                                        int marginDots, CrossAlign crossAlign,
                                        List<TemplateElement> children) {
        return new TemplateElement(id, ElementType.GROUP, x, y, 0, 0, 0,
            null, null, null, null, null, null, null, null, null,
            children, stackDirection, marginDots, crossAlign, null);
    }
}
```

- [ ] **Step 6: Run to verify it passes**

Run: `./mvnw -q test -Dtest=TemplateDefinitionJsonTest`
Expected: PASS (existing round-trip + the new nested-group test). The 16-arg calls in other tests still compile via the convenience constructor.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/editor/domain/StackDirection.java \
        src/main/java/com/stup/wristbandprinter/editor/domain/CrossAlign.java \
        src/main/java/com/stup/wristbandprinter/editor/domain/ElementType.java \
        src/main/java/com/stup/wristbandprinter/editor/domain/TemplateElement.java \
        src/test/java/com/stup/wristbandprinter/editor/domain/TemplateDefinitionJsonTest.java
git commit -m "feat: add nested group + sampleText to the template model"
```

---

## Task 2: Recursive renderer

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/editor/service/TemplateZplRenderer.java`
- Test: `src/test/java/com/stup/wristbandprinter/editor/service/TemplateZplRendererTest.java`

- [ ] **Step 1: Add failing group-layout tests**

Append these tests inside `TemplateZplRendererTest` (the class already has `renderer`, mocked `assetService`, the `data` field, and the `def(...)` helper). Add a small leaf helper and the tests:

```java
    private TemplateElement staticLeaf(String id, int w, int h) {
        return new TemplateElement(id, ElementType.STATIC_TEXT, 0, 0, w, h, 0,
            null, id.toUpperCase(), 20, "0", null, null, null, null, null);
    }

    @Test
    void render_lengthStack_placesChildrenWithMarginDownTheBand() {
        TemplateElement g = TemplateElement.group("g", 0, 0, StackDirection.LENGTH, 10,
            CrossAlign.START, List.of(staticLeaf("a", 40, 100), staticLeaf("b", 40, 50)));
        String zpl = renderer.render(def(g), data);
        // a at y=0; b at y = 100 + 10 margin = 110; both at x=0 (START)
        assertThat(zpl).contains("^FO0,0");
        assertThat(zpl).contains("^FO0,110");
    }

    @Test
    void render_widthStack_placesChildrenAcrossTheBand() {
        TemplateElement g = TemplateElement.group("g", 0, 0, StackDirection.WIDTH, 5,
            CrossAlign.START, List.of(staticLeaf("a", 30, 80), staticLeaf("b", 20, 80)));
        String zpl = renderer.render(def(g), data);
        // a at x=0; b at x = 30 + 5 margin = 35
        assertThat(zpl).contains("^FO0,0");
        assertThat(zpl).contains("^FO35,0");
    }

    @Test
    void render_crossAlignCenter_centersNarrowerChildOnCrossAxis() {
        // LENGTH stack → cross-axis is width; crossSize = max(40, 20) = 40.
        // The 20-wide child is offset by (40-20)/2 = 10.
        TemplateElement g = TemplateElement.group("g", 0, 0, StackDirection.LENGTH, 0,
            CrossAlign.CENTER, List.of(staticLeaf("wide", 40, 50), staticLeaf("narrow", 20, 50)));
        String zpl = renderer.render(def(g), data);
        assertThat(zpl).contains("^FO0,0");     // wide child, no offset, y=0
        assertThat(zpl).contains("^FO10,50");   // narrow child, +10 cross, y=50
    }

    @Test
    void render_groupOrigin_offsetsAllChildren() {
        TemplateElement g = TemplateElement.group("g", 100, 200, StackDirection.LENGTH, 0,
            CrossAlign.START, List.of(staticLeaf("a", 40, 50)));
        assertThat(renderer.render(def(g), data)).contains("^FO100,200");
    }

    @Test
    void render_nestedGroup_flattensRecursively() {
        TemplateElement inner = TemplateElement.group("in", 0, 0, StackDirection.LENGTH, 0,
            CrossAlign.START, List.of(staticLeaf("a", 40, 50), staticLeaf("b", 40, 50)));
        // outer LENGTH stack: inner group (height 100) then c at y=100
        TemplateElement outer = TemplateElement.group("out", 0, 0, StackDirection.LENGTH, 0,
            CrossAlign.START, List.of(inner, staticLeaf("c", 40, 30)));
        String zpl = renderer.render(def(outer), data);
        assertThat(zpl).contains("^FO0,0");    // a
        assertThat(zpl).contains("^FO0,50");   // b
        assertThat(zpl).contains("^FO0,100");  // c, after the inner group's 100-dot height
    }

    @Test
    void render_flatDefinition_unchanged_isBackCompatible() {
        TemplateElement el = new TemplateElement("t", ElementType.TEXT, 40, 120, 28, 600, 90,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
        String zpl = renderer.render(def(el), data);
        assertThat(zpl).contains("^FO40,120^A0R,28,28").contains("^FDJan Janssens^FS");
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=TemplateZplRendererTest`
Expected: FAIL — the renderer doesn't handle `GROUP` (it falls through the switch; group children never render, so the `^FO` assertions fail).

- [ ] **Step 3: Rewrite the renderer with recursion**

Replace `src/main/java/com/stup/wristbandprinter/editor/service/TemplateZplRenderer.java` with:

```java
package com.stup.wristbandprinter.editor.service;

import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.editor.domain.CrossAlign;
import com.stup.wristbandprinter.editor.domain.DataBinding;
import com.stup.wristbandprinter.editor.domain.ElementType;
import com.stup.wristbandprinter.editor.domain.StackDirection;
import com.stup.wristbandprinter.editor.domain.TemplateDefinition;
import com.stup.wristbandprinter.editor.domain.TemplateElement;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link TemplateDefinition} to ZPL. With a {@link WristbandData} the bound fields
 * carry real values; with {@link #renderTemplate} they carry {@code ${BINDING}} placeholders.
 * Groups are flattened recursively to absolute positions using each child's stored bounding box.
 */
@Service
public class TemplateZplRenderer {

    private static final int MAX_DEPTH = 20;

    private final TemplateAssetService assetService;

    public TemplateZplRenderer(TemplateAssetService assetService) {
        this.assetService = assetService;
    }

    public String render(TemplateDefinition def, WristbandData data) {
        return renderWith(def, toMap(data));
    }

    public String renderTemplate(TemplateDefinition def) {
        return renderWith(def, null);
    }

    private String renderWith(TemplateDefinition def, Map<DataBinding, String> data) {
        StringBuilder zpl = new StringBuilder();
        zpl.append("^XA");
        zpl.append("^PW").append(def.canvas().widthDots());
        zpl.append("^LL").append(def.canvas().lengthDots());
        zpl.append("^CI28");
        for (TemplateElement el : def.elements()) {
            renderNode(el, el.x(), el.y(), data, zpl, 0);
        }
        zpl.append("^XZ");
        return zpl.toString();
    }

    private void renderNode(TemplateElement el, int absX, int absY,
                            Map<DataBinding, String> data, StringBuilder zpl, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("Template group nesting exceeds " + MAX_DEPTH);
        }
        if (el.type() == ElementType.GROUP) {
            layoutGroup(el, absX, absY, data, zpl, depth);
            return;
        }
        switch (el.type()) {
            case TEXT, STATIC_TEXT -> appendText(zpl, el, absX, absY, data);
            case BARCODE -> appendBarcode(zpl, el, absX, absY, data);
            case IMAGE -> appendImage(zpl, el, absX, absY);
            case SHAPE -> appendShape(zpl, el, absX, absY);
            default -> { /* GROUP handled above */ }
        }
    }

    private void layoutGroup(TemplateElement group, int originX, int originY,
                             Map<DataBinding, String> data, StringBuilder zpl, int depth) {
        StackDirection dir = group.stackDirection() == null ? StackDirection.LENGTH : group.stackDirection();
        int margin = group.marginDots() == null ? 0 : group.marginDots();
        CrossAlign align = group.crossAlign() == null ? CrossAlign.START : group.crossAlign();
        List<TemplateElement> children = group.children() == null ? List.of() : group.children();

        int crossSize = 0;
        for (TemplateElement c : children) {
            crossSize = Math.max(crossSize, crossExtent(c, dir));
        }

        int cursor = 0;
        for (TemplateElement c : children) {
            int axis = axisExtent(c, dir);
            int cross = crossExtent(c, dir);
            int crossOffset = switch (align) {
                case START -> 0;
                case CENTER -> (crossSize - cross) / 2;
                case END -> crossSize - cross;
            };
            int cx, cy;
            if (dir == StackDirection.LENGTH) { cx = originX + crossOffset; cy = originY + cursor; }
            else { cx = originX + cursor; cy = originY + crossOffset; }
            renderNode(c, cx, cy, data, zpl, depth + 1);
            cursor += axis + margin;
        }
    }

    // ---- size helpers (dot-space bounding boxes) -----------------------------

    private int axisExtent(TemplateElement el, StackDirection parentDir) {
        int[] wh = sizeOf(el);
        return parentDir == StackDirection.LENGTH ? wh[1] : wh[0];
    }

    private int crossExtent(TemplateElement el, StackDirection parentDir) {
        int[] wh = sizeOf(el);
        return parentDir == StackDirection.LENGTH ? wh[0] : wh[1];
    }

    /** {width, height} in dots. Leaves use their stored box; groups compute theirs. */
    private int[] sizeOf(TemplateElement el) {
        if (el.type() != ElementType.GROUP) {
            return new int[]{el.widthDots(), el.heightDots()};
        }
        StackDirection dir = el.stackDirection() == null ? StackDirection.LENGTH : el.stackDirection();
        int margin = el.marginDots() == null ? 0 : el.marginDots();
        List<TemplateElement> children = el.children() == null ? List.of() : el.children();
        int along = 0, cross = 0;
        for (int i = 0; i < children.size(); i++) {
            int[] wh = sizeOf(children.get(i));
            int a = dir == StackDirection.LENGTH ? wh[1] : wh[0];
            int cr = dir == StackDirection.LENGTH ? wh[0] : wh[1];
            along += a;
            if (i < children.size() - 1) along += margin;
            cross = Math.max(cross, cr);
        }
        return dir == StackDirection.LENGTH ? new int[]{cross, along} : new int[]{along, cross};
    }

    // ---- leaf rendering (absolute positions) --------------------------------

    private void appendText(StringBuilder zpl, TemplateElement el, int x, int y, Map<DataBinding, String> data) {
        int size = el.fontSize() == null ? 24 : el.fontSize();
        String font = el.font() == null ? "0" : el.font();
        String text = el.type() == ElementType.STATIC_TEXT ? sanitize(el.value()) : valueFor(el.binding(), data);
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(String.format("^A%s%s,%d,%d", font, orientation(el.rotation()), size, size));
        zpl.append(String.format("^FD%s^FS", text));
    }

    private void appendBarcode(StringBuilder zpl, TemplateElement el, int x, int y, Map<DataBinding, String> data) {
        String hri = Boolean.TRUE.equals(el.showHumanReadable()) ? "Y" : "N";
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(String.format("^BC%s,%d,%s,N,N", orientation(el.rotation()), el.heightDots(), hri));
        zpl.append(String.format("^FD%s^FS", valueFor(el.binding(), data)));
    }

    private void appendImage(StringBuilder zpl, TemplateElement el, int x, int y) {
        if (el.assetId() == null) return;
        String gf = assetService.gfCommand(el.assetId(), el.widthDots(), el.heightDots(), el.rotation());
        if (!gf.isEmpty()) {
            zpl.append(String.format("^FO%d,%d", x, y));
            zpl.append(gf);
        }
    }

    private void appendShape(StringBuilder zpl, TemplateElement el, int x, int y) {
        int thickness = el.thicknessDots() == null ? 1 : el.thicknessDots();
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(String.format("^GB%d,%d,%d^FS", el.widthDots(), el.heightDots(), thickness));
    }

    private String valueFor(DataBinding binding, Map<DataBinding, String> data) {
        if (binding == null) return "";
        if (data == null) return "${" + binding.name() + "}";
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
Expected: PASS — the 8 original leaf tests plus the 6 new group tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/editor/service/TemplateZplRenderer.java \
        src/test/java/com/stup/wristbandprinter/editor/service/TemplateZplRendererTest.java
git commit -m "feat: flatten nested groups to ZPL in the renderer"
```

---

## Task 3: Full suite + doc note

**Files:**
- Modify: `docs/template-designer.md`

- [ ] **Step 1: Note groups in the data-model section**

In `docs/template-designer.md`, under the `## Data model` element-types table, add a row and a short note:

```markdown
| `GROUP` | `children` (items or groups), `stackDirection` (LENGTH/WIDTH), `marginDots`, `crossAlign` (START/CENTER/END) |
```

```markdown
> Groups stack their children along `stackDirection` with `marginDots` between them, aligned on the
> cross-axis by `crossAlign`; the renderer flattens groups to absolute positions. Data-bound and
> static blocks may carry an optional `sampleText` used by the editor canvas and the live preview.
```

- [ ] **Step 2: Run the whole suite**

Run: `./mvnw test`
Expected: PASS — all existing tests (no 16-arg call-site broke) plus the new model/renderer tests. Requires Docker for the Testcontainers tests.

- [ ] **Step 3: Commit**

```bash
git add docs/template-designer.md
git commit -m "docs: document template groups in the data model"
```

---

## Done — Plan 1 deliverable

The model supports nested, auto-stacking groups (choosable direction, margin, cross-align) plus
per-element `sampleText`; the renderer flattens them to absolute ZPL while existing flat templates
render byte-for-byte as before. Plan 2 adds the editor UI that authors these groups.

**Self-review notes:**
- Spec coverage: model fields ✓ (Task 1), recursive renderer with direction/margin/cross-align ✓ (Task 2), back-compat ✓ (test), `sampleText` stored ✓. `sampleText` is intentionally renderer-inert (editor/preview use it) — matches the spec.
- No call-site churn: the 16-arg convenience constructor keeps every existing `new TemplateElement(...)` compiling; only new group tests use `group(...)` / the 21-arg canonical.
- Type consistency: `StackDirection {LENGTH,WIDTH}`, `CrossAlign {START,CENTER,END}`, `ElementType.GROUP`, `group(id,x,y,dir,margin,align,children)` used identically in model, tests, and renderer.
- No placeholders: every step has complete code.
```

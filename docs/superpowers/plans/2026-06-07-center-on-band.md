# Center-on-Band Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-element `centerOnBand` flag so the ZPL renderer centers the element across the band width using ZPL-exact positioning, fixing rotated-text centering drift between the editor canvas (Poppins) and the print (Zebra font 0).

**Architecture:** A nullable `centerOnBand` boolean is added to the `TemplateElement` record (stored in the existing `jsonb`, no migration). `TemplateZplRenderer` centers flagged elements: non-rotated text via ZPL `^FB,…,C` (metric-free, exact); rotated text via a calibrated font-0 cell ratio; image/shape/barcode via their stored cross dimension; groups via their rotation-aware cross-extent. The editor's "Center on band" button becomes a persistent toggle that live-centers the node and locks horizontal drag.

**Tech Stack:** Java 21 record + Jackson jsonb; JUnit 5 (renderer unit tests, no Postgres needed for `TemplateZplRendererTest`); vanilla-JS Konva editor (no JS test runner — run-the-app checklist).

---

## File Structure

| File | Responsibility | Change |
|------|----------------|--------|
| `…/editor/domain/TemplateElement.java` | Element model record | Add `Boolean centerOnBand` (22nd field); keep 16-arg + `group()` delegating |
| `…/editor/service/TemplateZplRenderer.java` | ZPL generation | Thread `bandWidth`; center flagged elements per type; add `crossExtentDots`, `centeredOriginX`, `FONT0_CELL_RATIO` |
| `…/editor/service/TemplateZplRendererTest.java` | Renderer unit tests | New cases for each centered type + back-compat |
| `static/js/editor/canvas.js` | Konva model/serialize | Add `centerOnBand` attr to serialize/restore; `centerNodeOnBand`; apply in layout |
| `static/js/editor/groupops.js` | Center button logic | `centerSelectedOnBand` → `toggleCenterOnBand` (sets flag + drag lock) |
| `static/js/editor/main.js` | Button wiring + active state | Toggle wiring; reflect active state on selection |
| `docs/template-designer.md` | Feature docs | Document the toggle + the barcode limitation |

No DB migration (jsonb is schemaless). No new files.

---

## Task 1: Add `centerOnBand` to the model

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/editor/domain/TemplateElement.java`
- Test: `src/test/java/com/stup/wristbandprinter/editor/domain/TemplateDefinitionJsonTest.java`

- [ ] **Step 1: Write the failing JSON round-trip test**

Add to `TemplateDefinitionJsonTest`:
```java
@Test
void centerOnBand_roundTripsAndOmitsWhenNull() throws Exception {
    com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
    TemplateElement centered = new TemplateElement(
        "c", ElementType.TEXT, 0, 0, 28, 200, 90,
        DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null)
        .withCenterOnBand(true);
    String json = m.writeValueAsString(centered);
    assertThat(json).contains("\"centerOnBand\":true");

    TemplateElement plain = new TemplateElement(
        "p", ElementType.TEXT, 0, 0, 28, 200, 0,
        DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
    assertThat(m.writeValueAsString(plain)).doesNotContain("centerOnBand"); // NON_NULL

    TemplateElement back = m.readValue(json, TemplateElement.class);
    assertThat(back.centerOnBand()).isTrue();
}
```

- [ ] **Step 2: Run it — verify it fails to compile**

Run: `./mvnw -q test -Dtest=TemplateDefinitionJsonTest#centerOnBand_roundTripsAndOmitsWhenNull`
Expected: compile error — `centerOnBand()` / `withCenterOnBand` do not exist.

- [ ] **Step 3: Add the field + helper, keep back-compat constructors**

In `TemplateElement.java`, add `Boolean centerOnBand` as the **last** record component:
```java
    CrossAlign crossAlign,           // GROUP
    String sampleText,               // TEXT / STATIC_TEXT (design + preview only)
    Boolean centerOnBand             // any leaf/group: renderer centers across band width
) {
```
(Note the added comma after `sampleText`.)

Update the 16-arg back-compat constructor so it delegates with the five trailing `null`s (group/sample/centerOnBand). Replace the existing delegate with:
```java
    public TemplateElement(String id, ElementType type, int x, int y, int widthDots, int heightDots,
                           int rotation, DataBinding binding, String value, Integer fontSize, String font,
                           String symbology, Boolean showHumanReadable, UUID assetId, ShapeType shape,
                           Integer thicknessDots) {
        this(id, type, x, y, widthDots, heightDots, rotation, binding, value, fontSize, font,
            symbology, showHumanReadable, assetId, shape, thicknessDots,
            null, null, null, null, null, null);
    }
```

Update the `group(...)` factory's `new TemplateElement(...)` to pass a trailing `null`:
```java
        return new TemplateElement(id, ElementType.GROUP, x, y, 0, 0, 0,
            null, null, null, null, null, null, null, null, null,
            children, stackDirection, marginDots, crossAlign, null, null);
```

Add a copy helper (records are immutable; the editor never uses it but tests/clarity do):
```java
    /** Returns a copy with centerOnBand set. */
    public TemplateElement withCenterOnBand(boolean v) {
        return new TemplateElement(id, type, x, y, widthDots, heightDots, rotation, binding, value,
            fontSize, font, symbology, showHumanReadable, assetId, shape, thicknessDots,
            children, stackDirection, marginDots, crossAlign, sampleText, v);
    }
```

- [ ] **Step 4: Run the test — verify pass**

Run: `./mvnw -q test -Dtest=TemplateDefinitionJsonTest`
Expected: PASS (all cases, including the pre-existing ones — they use the 16-arg constructor and are unaffected).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/editor/domain/TemplateElement.java \
        src/test/java/com/stup/wristbandprinter/editor/domain/TemplateDefinitionJsonTest.java
git commit -m "feat(editor): add centerOnBand flag to TemplateElement model"
```

---

## Task 2: Renderer — center image/shape/barcode/group on stored cross dimension

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/editor/service/TemplateZplRenderer.java`
- Test: `src/test/java/com/stup/wristbandprinter/editor/service/TemplateZplRendererTest.java`

- [ ] **Step 1: Write failing tests for centered shape + back-compat**

Add to `TemplateZplRendererTest` (canvas width is 203 in `def(...)`):
```java
@Test
void render_centeredShape_centersOnBandWidth() {
    // shape width 100 on a 203-wide band → x = (203-100)/2 = 51
    TemplateElement el = new TemplateElement("g", ElementType.SHAPE, 999, 6, 100, 4, 0,
        null, null, null, null, null, null, null, ShapeType.LINE, 4).withCenterOnBand(true);
    assertThat(renderer.render(def(el), data)).contains("^FO51,6").contains("^GB100,4,4");
}

@Test
void render_centeredShape_rotated90_usesHeightAsCross() {
    // rotated 90 → cross extent = heightDots (40); x = (203-40)/2 = 81
    TemplateElement el = new TemplateElement("g", ElementType.SHAPE, 999, 6, 100, 40, 90,
        null, null, null, null, null, null, null, ShapeType.LINE, 4).withCenterOnBand(true);
    assertThat(renderer.render(def(el), data)).contains("^FO81,6");
}

@Test
void render_notCentered_isByteIdentical_backCompat() {
    TemplateElement el = new TemplateElement("g", ElementType.SHAPE, 5, 6, 180, 4, 0,
        null, null, null, null, null, null, null, ShapeType.LINE, 4);
    assertThat(renderer.render(def(el), data)).contains("^FO5,6").contains("^GB180,4,4");
}
```

- [ ] **Step 2: Run — verify failure**

Run: `./mvnw -q test -Dtest=TemplateZplRendererTest#render_centeredShape_centersOnBandWidth`
Expected: FAIL — output contains `^FO999,6`, not `^FO51,6`.

- [ ] **Step 3: Thread bandWidth and add centering helpers**

In `TemplateZplRenderer.java`:

Change `renderWith` to pass band width into `renderNode`:
```java
    private String renderWith(TemplateDefinition def, Map<DataBinding, String> data) {
        StringBuilder zpl = new StringBuilder();
        int bandWidth = def.canvas().widthDots();
        zpl.append("^XA");
        zpl.append("^PW").append(bandWidth);
        zpl.append("^LL").append(def.canvas().lengthDots());
        zpl.append("^CI28");
        for (TemplateElement el : def.elements()) {
            renderNode(el, el.x(), el.y(), data, zpl, 0, bandWidth);
        }
        zpl.append("^XZ");
        return zpl.toString();
    }
```

Add `bandWidth` to `renderNode` and the group path, and apply group centering:
```java
    private void renderNode(TemplateElement el, int absX, int absY,
                            Map<DataBinding, String> data, StringBuilder zpl, int depth, int bandWidth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("Template group nesting exceeds " + MAX_DEPTH);
        }
        if (el.type() == ElementType.GROUP) {
            int originX = Boolean.TRUE.equals(el.centerOnBand())
                ? (bandWidth - sizeOf(el)[0]) / 2 : absX;
            layoutGroup(el, originX, absY, data, zpl, depth, bandWidth);
            return;
        }
        switch (el.type()) {
            case TEXT, STATIC_TEXT -> appendText(zpl, el, absX, absY, data, bandWidth);
            case BARCODE -> appendBarcode(zpl, el, centeredOriginX(el, absX, bandWidth), absY, data);
            case IMAGE -> appendImage(zpl, el, centeredOriginX(el, absX, bandWidth), absY);
            case SHAPE -> appendShape(zpl, el, centeredOriginX(el, absX, bandWidth), absY);
            default -> { }
        }
    }
```

Add `bandWidth` to `layoutGroup`'s signature and its recursive `renderNode` call:
```java
    private void layoutGroup(TemplateElement group, int originX, int originY,
                             Map<DataBinding, String> data, StringBuilder zpl, int depth, int bandWidth) {
        // … unchanged body until the child render call …
            renderNode(c, cx, cy, data, zpl, depth + 1, bandWidth);
        // … unchanged …
    }
```

Add the helpers near the size helpers:
```java
    /** Width-axis extent (dots) used for band-centering: rotation-aware footprint. */
    private int crossExtentDots(TemplateElement el) {
        int rot = ((el.rotation() % 360) + 360) % 360;
        return (rot == 90 || rot == 270) ? el.heightDots() : el.widthDots();
    }

    /** Centered x if flagged (band-width minus cross extent, halved); else the stored x. */
    private int centeredOriginX(TemplateElement el, int absX, int bandWidth) {
        return Boolean.TRUE.equals(el.centerOnBand())
            ? (bandWidth - crossExtentDots(el)) / 2 : absX;
    }
```

- [ ] **Step 4: Run — verify pass**

Run: `./mvnw -q test -Dtest=TemplateZplRendererTest`
Expected: PASS (new cases + all existing cases unchanged — they have no flag, so `centeredOriginX` returns `absX`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/editor/service/TemplateZplRenderer.java \
        src/test/java/com/stup/wristbandprinter/editor/service/TemplateZplRendererTest.java
git commit -m "feat(editor): renderer centers flagged image/shape/barcode/group on band width"
```

---

## Task 3: Renderer — center non-rotated text with `^FB`

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/editor/service/TemplateZplRenderer.java`
- Test: `src/test/java/com/stup/wristbandprinter/editor/service/TemplateZplRendererTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void render_centeredText_nonRotated_usesFieldBlockCenter() {
    TemplateElement el = new TemplateElement("t", ElementType.STATIC_TEXT, 999, 50, 20, 100, 0,
        null, "STAFF", 24, "0", null, null, null, null, null).withCenterOnBand(true);
    String zpl = renderer.render(def(el), data); // band width 203
    assertThat(zpl).contains("^FO0,50");
    assertThat(zpl).contains("^FB203,1,0,C");
    assertThat(zpl).contains("^A0N,24,24");
    assertThat(zpl).contains("^FDSTAFF^FS");
}
```

- [ ] **Step 2: Run — verify failure**

Run: `./mvnw -q test -Dtest=TemplateZplRendererTest#render_centeredText_nonRotated_usesFieldBlockCenter`
Expected: FAIL — no `^FB` emitted; `^FO999,50` present.

- [ ] **Step 3: Implement in `appendText`**

Replace `appendText` with the centering-aware version (note new `bandWidth` param, already passed from Task 2):
```java
    private void appendText(StringBuilder zpl, TemplateElement el, int x, int y,
                            Map<DataBinding, String> data, int bandWidth) {
        int size = el.fontSize() == null ? 24 : el.fontSize();
        String font = el.font() == null ? "0" : el.font();
        String text = el.type() == ElementType.STATIC_TEXT ? sanitize(el.value()) : valueFor(el.binding(), data);
        boolean centered = Boolean.TRUE.equals(el.centerOnBand());
        int rot = ((el.rotation() % 360) + 360) % 360;

        if (centered && (rot == 0 || rot == 180)) {
            // ZPL field block centers the line across the full width, metric-free.
            zpl.append(String.format("^FO0,%d", y));
            zpl.append(String.format("^FB%d,1,0,C", bandWidth));
            zpl.append(String.format("^A%s%s,%d,%d", font, orientation(el.rotation()), size, size));
            zpl.append(String.format("^FD%s^FS", text));
            return;
        }
        zpl.append(String.format("^FO%d,%d", x, y)); // rotated-centered handled in Task 4
        zpl.append(String.format("^A%s%s,%d,%d", font, orientation(el.rotation()), size, size));
        zpl.append(String.format("^FD%s^FS", text));
    }
```

- [ ] **Step 4: Run — verify pass**

Run: `./mvnw -q test -Dtest=TemplateZplRendererTest`
Expected: PASS (new + existing).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/editor/service/TemplateZplRenderer.java \
        src/test/java/com/stup/wristbandprinter/editor/service/TemplateZplRendererTest.java
git commit -m "feat(editor): center non-rotated text via ZPL field block"
```

---

## Task 4: Renderer — center rotated text with calibrated font-0 cell ratio

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/editor/service/TemplateZplRenderer.java`
- Test: `src/test/java/com/stup/wristbandprinter/editor/service/TemplateZplRendererTest.java`

- [ ] **Step 1: Calibration (already performed — constants below are validated)**

The font-0 rotated cell metrics were measured via Labelary (12 dpmm) by rendering `^A0B/R,<size>,<size>^FDHgjy^FS` at sizes 24/40/60/100/150 and scanning the PNG for the ink column span. Results:
- **Cross-thickness** = `round(0.94 × fontSize)` for both 90° and 270° (ratio stable 0.917–0.940 across sizes).
- **Left margin** (ink-left − `^FO.x`): **270° (B)** is a constant **3 dots** at every size; **90° (R)** scales as **`round(0.063 × fontSize + 3.5)`** (measured 5/7/9/13 at sizes 24/60/100/150).
- **Centering formula:** `^FO.x = (bandWidth − thickness) / 2 − leftMargin`. Verified: three different-size 270° texts render mutually centered on a centerline (no fan-out).

No further measurement needed; use the constants in Step 3.

- [ ] **Step 2: Write the failing tests (both orientations, literal expected `^FO`)**

For band width 203:
- 270° size 60: `thickness=round(0.94*60)=56`, `leftMargin=3`, `x=(203-56)/2-3=70`.
- 90° size 28: `thickness=round(0.94*28)=26`, `leftMargin=round(0.063*28+3.5)=5`, `x=(203-26)/2-5=83`.
```java
@Test
void render_centeredText_rotated270_centersByCellRatio() {
    TemplateElement el = new TemplateElement("t", ElementType.TEXT, 999, 70, 60, 600, 270,
        DataBinding.FULL_NAME, null, 60, "0", null, null, null, null, null).withCenterOnBand(true);
    String zpl = renderer.render(def(el), data); // band width 203
    assertThat(zpl).contains("^FO70,70");           // (203-56)/2 - 3
    assertThat(zpl).contains("^A0B,60,60").contains("^FDJan Janssens^FS");
}

@Test
void render_centeredText_rotated90_centersByCellRatio() {
    TemplateElement el = new TemplateElement("t", ElementType.TEXT, 999, 70, 28, 600, 90,
        DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null).withCenterOnBand(true);
    String zpl = renderer.render(def(el), data); // band width 203
    assertThat(zpl).contains("^FO83,70");           // (203-26)/2 - round(0.063*28+3.5)=5
    assertThat(zpl).contains("^A0R,28,28").contains("^FDJan Janssens^FS");
}
```

- [ ] **Step 3: Implement rotated-text centering**

Add the calibrated constants and extend `appendText`'s non-`^FB` branch:
```java
    // Font-0 rotated-text cell metrics, measured via Labelary at 12 dpmm (see plan 2026-06-07 Task 4).
    // Cross-thickness ≈ 0.94 × size for both 90°/270°. Left margin (ink-left − ^FO): 270° constant 3;
    // 90° ≈ 0.063 × size + 3.5. Centering: ^FO.x = (bandWidth − thickness)/2 − leftMargin.
    static final float FONT0_CELL_RATIO = 0.94f;
```
Replace the fall-through `^FO` line in `appendText` with:
```java
        int fox = x;
        if (centered) { // rot is 90 or 270 here (0/180 handled by ^FB above)
            int thickness = Math.round(FONT0_CELL_RATIO * size);
            int leftMargin = (rot == 90) ? Math.round(0.063f * size + 3.5f) : 3;
            fox = (bandWidth - thickness) / 2 - leftMargin;
        }
        zpl.append(String.format("^FO%d,%d", fox, y));
        zpl.append(String.format("^A%s%s,%d,%d", font, orientation(el.rotation()), size, size));
        zpl.append(String.format("^FD%s^FS", text));
```

- [ ] **Step 4: Run — verify pass**

Run: `./mvnw -q test -Dtest=TemplateZplRendererTest`
Expected: PASS.

- [ ] **Step 5: Visual confirmation via Labelary (mutual centering)**

Build ZPL with three centered rotated texts of different sizes (use the renderer or hand-craft with the formula) and a centerline; render through Labelary and confirm all three centers sit on the line:
```bash
# expect all three vertically-read texts centered on the x=101 line (band 203)
# (construct with the same ^FO formula as the renderer)
```
Expected: no fan-out; centers aligned within ~1 dot.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/editor/service/TemplateZplRenderer.java \
        src/test/java/com/stup/wristbandprinter/editor/service/TemplateZplRendererTest.java
git commit -m "feat(editor): center rotated text via calibrated font-0 cell ratio"
```

---

## Task 5: Editor — serialize/restore the `centerOnBand` attr

**Files:**
- Modify: `src/main/resources/static/js/editor/canvas.js`

- [ ] **Step 1: Add `centerOnBand` to the serialized non-geometry attrs**

In `canvas.js`, extend `NON_GEO`:
```js
const NON_GEO = ['binding', 'value', 'font', 'symbology', 'showHumanReadable',
  'assetId', 'shape', 'thicknessDots', 'sampleText', 'centerOnBand'];
```
`makeLeaf` already copies every `NON_GEO` attr from the spec onto the node, and `nodeToElement` already serializes them back, so the flag now round-trips for leaves. For groups, add it explicitly in `nodeToElement`'s GROUP branch:
```js
      crossAlign: node.getAttr('crossAlign') || 'START',
      centerOnBand: node.getAttr('centerOnBand') || undefined,
      children: node.getChildren().map(nodeToElement),
```
and in `buildNode`'s GROUP branch:
```js
    node.setAttr('crossAlign', spec.crossAlign || 'START');
    if (spec.centerOnBand) node.setAttr('centerOnBand', true);
```

- [ ] **Step 2: Add the `centerNodeOnBand` helper + apply on load/layout**

Add near `applyLayout` in `canvas.js`:
```js
// Center a top-level node's bbox on the band width (px). Used for centerOnBand nodes.
export function centerNodeOnBand(node) {
  const widthPx = canvasDots.widthDots * scale;
  const r = node.getClientRect({ relativeTo: layer, skipStroke: true });
  node.x(node.x() + (widthPx / 2 - (r.x + r.width / 2)));
}
```
At the end of `applyLayout`, re-center any flagged top-level node:
```js
export function applyLayout() {
  contentNodes().forEach(n => { if (isGroup(n)) layoutGroup(n); });
  contentNodes().forEach(n => { if (n.getAttr('centerOnBand')) centerNodeOnBand(n); });
}
```

- [ ] **Step 3: Syntax check + commit**

```bash
node --check --input-type=module < src/main/resources/static/js/editor/canvas.js
git add src/main/resources/static/js/editor/canvas.js
git commit -m "feat(editor): serialize centerOnBand and re-center flagged nodes in layout"
```

---

## Task 6: Editor — "Center on band" becomes a persistent toggle with drag lock

**Files:**
- Modify: `src/main/resources/static/js/editor/groupops.js`
- Modify: `src/main/resources/static/js/editor/main.js`

- [ ] **Step 1: Replace `centerSelectedOnBand` with `toggleCenterOnBand`**

In `groupops.js`, replace the existing `centerSelectedOnBand` export with:
```js
import { layer, getSelection, setSelection, applyLayout, getCanvasDots, getScale, centerNodeOnBand } from './canvas.js';
// (add centerNodeOnBand to the existing import line)

// Toggle the centerOnBand flag on the selected element/group. When on, lock horizontal
// drag (dragBoundFunc keeps x centered) and center immediately; when off, free it.
export function toggleCenterOnBand() {
  const sel = getSelection();
  if (sel.length !== 1) { alert('Select a single item or group to center.'); return; }
  let node = sel[0];
  while (node.getParent() && node.getParent() !== layer) node = node.getParent();
  const on = !node.getAttr('centerOnBand');
  node.setAttr('centerOnBand', on || undefined);
  if (on) {
    centerNodeOnBand(node);
    node.dragBoundFunc(function (pos) {
      // keep the locked (centered) x, allow y to move
      return { x: this.absolutePosition().x, y: pos.y };
    });
  } else {
    node.dragBoundFunc(null);
  }
  setSelection([node]); // refresh transformer + button state
  layer.draw();
}

// Whether the current single selection (outermost) is centered — for button state.
export function isSelectionCentered() {
  const sel = getSelection();
  if (sel.length !== 1) return false;
  let node = sel[0];
  while (node.getParent() && node.getParent() !== layer) node = node.getParent();
  return !!node.getAttr('centerOnBand');
}
```
Keep the existing `getCanvasDots`/`getScale` imports if still used elsewhere in the file; otherwise leave the import line as shown.

- [ ] **Step 2: Re-apply the drag lock on load**

In `canvas.js` `buildNode` (leaf branch) and after group build, re-attach the lock for restored flagged nodes. At the end of `loadElements`, after `applyLayout()`:
```js
  contentNodes().forEach(n => {
    if (n.getAttr('centerOnBand') && n.getParent() === layer) {
      n.dragBoundFunc(function (pos) { return { x: this.absolutePosition().x, y: pos.y }; });
    }
  });
```

- [ ] **Step 3: Wire the toggle + active state in `main.js`**

Replace the center button wiring:
```js
import { groupSelected, ungroupSelected, toggleCenterOnBand, isSelectionCentered } from './groupops.js';
// …
  const btnCenter = document.getElementById('btn-center');
  btnCenter.addEventListener('click', () => { toggleCenterOnBand(); refreshCenterBtn(); });
  function refreshCenterBtn() { btnCenter.classList.toggle('active', isSelectionCentered()); }
```
And refresh the button when selection changes — `showProperties` is the select handler passed to `initCanvas`; wrap it:
```js
  initCanvas('stage-container', (node) => { showProperties(node); refreshCenterBtn(); });
```
(Move `refreshCenterBtn` definition above this call, or hoist via `function`.)

Add a minimal active style in `static/css/editor.css`:
```css
.tool-btn.active { outline: 2px solid #ff3399; }
```

- [ ] **Step 4: Syntax check + commit**

```bash
node --check --input-type=module < src/main/resources/static/js/editor/groupops.js
node --check --input-type=module < src/main/resources/static/js/editor/main.js
git add src/main/resources/static/js/editor/groupops.js src/main/resources/static/js/editor/main.js \
        src/main/resources/static/css/editor.css
git commit -m "feat(editor): Center on band is now a persistent toggle with horizontal drag lock"
```

---

## Task 7: Run-the-app verification + docs

**Files:**
- Modify: `docs/template-designer.md`

- [ ] **Step 1: Rebuild and run**

```bash
docker compose -p stup-wristbandprinterservice -f docker-compose.local-cluster.yml up --build -d
```
Open `http://localhost:8080/template-editor.html` (hard refresh), log in `admin` / `local-admin`.

- [ ] **Step 2: Verify the toggle + centering**
  - Add Event/Name/Association texts at **different font sizes**, rotate each **270°**.
  - Select each and click **Center on band** — button shows the active outline, element snaps to the band center, and horizontal drag is locked (vertical still moves).
  - Click **Preview** → the Labelary PNG shows all three **mutually centered** (no fan-out).
  - Toggle one **off** → its horizontal drag is free again.

- [ ] **Step 3: Verify round-trip + back-compat**
  - **Save**, reload the template → centered elements stay centered and locked; the active state reflects on selection.
  - Open an **existing** (pre-feature) template → unchanged; nothing is centered.

- [ ] **Step 4: Document + commit**

Add a short "Center on band" subsection to `docs/template-designer.md` describing the toggle, that the renderer is authoritative (non-rotated text uses `^FB`, rotated uses the calibrated cell ratio), and the **barcode limitation** (centered on its stored box, not the exact printed symbol width).
```bash
git add docs/template-designer.md
git commit -m "docs: document Center on band toggle and barcode centering limitation"
```

- [ ] **Step 5: Tear down**

```bash
docker compose -p stup-wristbandprinterservice -f docker-compose.local-cluster.yml down
```

---

## Self-Review Notes

- **Spec coverage:** R1 → Task 1. R2/R3 (toggle + drag lock + live center) → Task 6 (+ canvas helpers Task 5). R4 (persist/round-trip) → Tasks 1 & 5, verified Task 7 Step 3. R5 → Task 2 threading + `centeredOriginX`. R6 (non-rotated `^FB`) → Task 3. R7/R7b (rotated cell ratio + offset) → Task 4 (calibration + constants). R8 (image/shape exact) → Task 2. R8b (barcode stored box + documented) → Task 2 + Task 7 docs. R9 (group cross-extent) → Task 2 group branch. R10 (byte-identical when off) → Task 2 back-compat test.
- **Type/name consistency:** `centerOnBand` (model attr + Konva attr), `centeredOriginX`, `crossExtentDots`, `centerNodeOnBand`, `toggleCenterOnBand`, `isSelectionCentered`, `FONT0_CELL_RATIO`, `FONT0_CELL_OFFSET` used identically across tasks.
- **No placeholders:** the only "to be measured" value (`FONT0_CELL_RATIO`/`OFFSET`) has an exact measurement procedure (Task 4 Step 1) that produces the literal constants written in Step 3 — not a vague TODO.
- **Back-compat:** 16-arg constructor + `group()` factory keep all existing test call sites compiling unchanged (verified: all construction sites use the 16-arg form or `group()`).

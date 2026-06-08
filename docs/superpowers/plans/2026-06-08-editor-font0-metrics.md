# Editor Font-0 Text Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the template editor size on-canvas text with the printer's font-0 model (`chars × fontSize × 0.46` length, `fontSize` thickness, Helvetica/Arial glyphs scaled to fill the box) so the canvas matches the print on both axes, and align the center-on-band renderer to the same `fontSize` model.

**Architecture:** Editor-only geometry change in `canvas.js`: a single `applyTextMetrics(node)` helper scales each `Konva.Text` so its bounding box equals the font-0 footprint; all snapping/serialization/transform flows from that box. The Java `TemplateZplRenderer` rotated-centering is simplified to `(bandWidth − fontSize)/2`, matching the proven `ZplGeneratorService`. No change to `ZplGeneratorService`, the basic wristband, or printed ZPL for existing templates.

**Tech Stack:** Vanilla JS + Konva (no JS test runner → `node --check` + run-the-app); Java 21 + JUnit (renderer unit test, no Postgres needed).

---

## File Structure

| File | Responsibility | Change |
|------|----------------|--------|
| `static/js/editor/canvas.js` | Konva text geometry | `CHAR_ADVANCE_RATIO`, `applyTextMetrics`, Helvetica font, call sites in `makeLeaf` / `applyProp` / resize handler |
| `editor/service/TemplateZplRenderer.java` | ZPL centering | Rotated centered text → `(bandWidth − fontSize)/2`; drop `FONT0_CELL_RATIO` |
| `editor/service/TemplateZplRendererTest.java` | Renderer tests | Update the two rotated centered-text expectations |
| `docs/template-designer.md` | Docs | Note the editor now mirrors the font-0 model |

---

## Task 1: Editor — size text with the font-0 model (scale glyphs to fill)

**Files:**
- Modify: `src/main/resources/static/js/editor/canvas.js`

- [ ] **Step 1: Add the shared advance ratio constant**

Near the other module constants (just below `const QUARTER_STROKE = ...`), add:
```js
// Mirrors ZplGeneratorService.CHAR_ADVANCE_RATIO — Zebra font 0 (^A0) proportional advance ÷ size.
// Used to size editor text to the printed font-0 footprint (length = chars × fontSize × ratio).
const CHAR_ADVANCE_RATIO = 0.46;
```

- [ ] **Step 2: Add the `applyTextMetrics` helper**

Add this function just above `makeLeaf` (it uses `textOf`, `d2p`, `p2d`, already defined in the file):
```js
// Size a text node to the printer's font-0 footprint: length = chars × fontSize × CHAR_ADVANCE_RATIO,
// thickness = fontSize. Glyphs are scaled to fill that box so the canvas matches the print.
function applyTextMetrics(node) {
  node.scaleX(1); node.scaleY(1);
  node.width('auto'); node.height('auto');                 // measure the natural glyph run
  const fsDots = p2d(node.fontSize());
  const chars  = Math.max(1, (textOf(node) || '').length);
  const targetWpx = d2p(Math.round(chars * fsDots * CHAR_ADVANCE_RATIO)); // length along text
  const targetHpx = node.fontSize();                                      // thickness = fontSize
  const sx = targetWpx / Math.max(1, node.width());
  const sy = targetHpx / Math.max(1, node.height());       // ≈ 1 (lineHeight 1 ⇒ height = fontSize)
  node.scaleX(sx); node.scaleY(sy);
  node.setAttr('fitScaleY', sy);                           // lets resize separate gesture from fit
}
```

- [ ] **Step 3: Use the printer-like font and apply metrics in `makeLeaf`**

Find:
```js
  if (s.type === 'TEXT' || s.type === 'STATIC_TEXT') {
    // Text auto-sizes to its content (no fixed width → no wrapping into an invisible sliver).
    node = new Konva.Text({ ...base, fontSize: d2p(s.fontSize || 24), fontFamily: 'Poppins', fill: '#111' });
  } else if (s.type === 'BARCODE') {
```
Replace with:
```js
  if (s.type === 'TEXT' || s.type === 'STATIC_TEXT') {
    // Helvetica/Arial = on-screen stand-in for the printer's resident font 0 (^A0 / CG Triumvirate).
    node = new Konva.Text({ ...base, fontSize: d2p(s.fontSize || 24),
      fontFamily: 'Helvetica, Arial, sans-serif', fill: '#111' });
  } else if (s.type === 'BARCODE') {
```
Then find (end of `makeLeaf`):
```js
  if (node.className === 'Text') node.text(textOf(node));
  wireLeaf(node);
```
Replace with:
```js
  if (node.className === 'Text') { node.text(textOf(node)); applyTextMetrics(node); }
  wireLeaf(node);
```

- [ ] **Step 4: Re-fit on resize (use `scaleY` for the gesture)**

Find the Text branch of the `transformend` handler in `wireLeaf`:
```js
    if (node.className === 'Text') {
      // Resizing text scales the font; bake the scale into fontSize, then clear it.
      const nf = Math.max(2, node.fontSize() * node.scaleX());
      node.scaleX(1); node.scaleY(1);
      node.fontSize(nf);
    } else {
```
Replace with:
```js
    if (node.className === 'Text') {
      // scaleX also carries the font-0 fit (condense), so read the resize gesture from scaleY
      // (whose fit factor is ≈1), bake it into fontSize, then re-derive the box + fit.
      const gesture = node.scaleY() / (node.getAttr('fitScaleY') || 1);
      node.fontSize(Math.max(2, node.fontSize() * gesture));
      applyTextMetrics(node);
    } else {
```

- [ ] **Step 5: Re-fit on property edits (`applyProp`)**

Find (end of `applyProp`, before the final `layer.draw()`):
```js
  if (['stackDirection', 'marginDots', 'crossAlign', 'widthDots', 'heightDots',
       'fontSize', 'value', 'sampleText', 'binding', 'rotation'].includes(key)) applyLayout();
  layer.draw();
```
Replace with:
```js
  if (node.className === 'Text' && ['fontSize', 'value', 'sampleText', 'binding'].includes(key)) {
    applyTextMetrics(node);
  }
  if (['stackDirection', 'marginDots', 'crossAlign', 'widthDots', 'heightDots',
       'fontSize', 'value', 'sampleText', 'binding', 'rotation'].includes(key)) applyLayout();
  layer.draw();
```

- [ ] **Step 6: Syntax check**

Run: `node --check --input-type=module < src/main/resources/static/js/editor/canvas.js`
Expected: no output, exit 0.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/js/editor/canvas.js
git commit -m "feat(editor): size canvas text with printer font-0 metrics (chars*size*0.46)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Renderer — align rotated centered text to the `fontSize` model

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/editor/service/TemplateZplRenderer.java`
- Test: `src/test/java/com/stup/wristbandprinter/editor/service/TemplateZplRendererTest.java`

- [ ] **Step 1: Update the two rotated-centered tests to the fontSize model**

In `TemplateZplRendererTest`, replace the bodies of `render_centeredText_rotated270_centersByCellRatio` and `render_centeredText_rotated90_centersByCellRatio` so the expected `^FO` uses `(bandWidth − fontSize)/2` (band width 203):
- 270° size 60: `(203 − 60)/2 = 71` → `^FO71,70`.
- 90° size 28: `(203 − 28)/2 = 87` → `^FO87,70`.
```java
@Test
void render_centeredText_rotated270_centersOnFontSize() {
    TemplateElement el = new TemplateElement("t", ElementType.TEXT, 999, 70, 60, 600, 270,
        DataBinding.FULL_NAME, null, 60, "0", null, null, null, null, null).withCenterOnBand(true);
    String zpl = renderer.render(def(el), data); // band width 203 → (203-60)/2 = 71
    assertThat(zpl).contains("^FO71,70");
    assertThat(zpl).contains("^A0B,60,60").contains("^FDJan Janssens^FS");
}

@Test
void render_centeredText_rotated90_centersOnFontSize() {
    TemplateElement el = new TemplateElement("t", ElementType.TEXT, 999, 70, 28, 600, 90,
        DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null).withCenterOnBand(true);
    String zpl = renderer.render(def(el), data); // band width 203 → (203-28)/2 = 87
    assertThat(zpl).contains("^FO87,70");
    assertThat(zpl).contains("^A0R,28,28").contains("^FDJan Janssens^FS");
}
```
(Delete the old two `*_centersByCellRatio` methods — replaced by these.)

- [ ] **Step 2: Run — verify failure**

Run: `./mvnw -q test -Dtest=TemplateZplRendererTest#render_centeredText_rotated270_centersOnFontSize`
Expected: FAIL — current code emits `^FO70,70` (the 0.94 calibration), not `^FO71,70`.

- [ ] **Step 3: Simplify the rotated-centering code**

In `TemplateZplRenderer.java`, remove the `FONT0_CELL_RATIO` constant and its comment block:
```java
    // Font-0 rotated-text cell metrics, measured via Labelary at 12 dpmm (see plan 2026-06-07 Task 4).
    // ...
    static final float FONT0_CELL_RATIO = 0.94f;
```
In `appendText`, replace the rotated centering block:
```java
        int fox = x;
        if (centered) { // rot is 90 or 270 here (0/180 handled by ^FB above)
            int thickness = Math.round(FONT0_CELL_RATIO * size);
            int leftMargin = (rot == 90) ? Math.round(0.063f * size + 3.5f) : 3;
            fox = (bandWidth - thickness) / 2 - leftMargin;
        }
```
with the `ZplGeneratorService` model (cross extent = fontSize):
```java
        int fox = x;
        if (centered) { // rot is 90 or 270 here (0/180 handled by ^FB above)
            // Cross extent of rotated ^A0 text = font height = size (matches ZplGeneratorService.centerX).
            fox = (bandWidth - size) / 2;
        }
```

- [ ] **Step 4: Run — verify pass**

Run: `./mvnw -q test -Dtest=TemplateZplRendererTest`
Expected: PASS — the two updated tests plus all existing (non-rotated `^FB`, image/shape/group centering, and the no-flag back-compat case are unaffected).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/editor/service/TemplateZplRenderer.java \
        src/test/java/com/stup/wristbandprinter/editor/service/TemplateZplRendererTest.java
git commit -m "refactor(editor): center rotated text on fontSize model (match ZplGeneratorService)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Rebuild, verify in the app, and document

**Files:**
- Modify: `docs/template-designer.md`

- [ ] **Step 1: Rebuild the running cluster from the worktree**

```bash
docker compose -p stup-wristbandprinterservice -f docker-compose.local-cluster.yml up --build -d
```
Wait for health: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health` → `200`.

- [ ] **Step 2: Confirm the served editor JS carries the new metrics**

```bash
curl -s http://localhost:8080/js/editor/canvas.js | grep -c 'CHAR_ADVANCE_RATIO'   # expect >= 1
curl -s http://localhost:8080/js/editor/canvas.js | grep -c 'applyTextMetrics'      # expect >= 3
curl -s http://localhost:8080/js/editor/canvas.js | grep -c "Helvetica, Arial"      # expect 1
curl -s http://localhost:8080/js/editor/canvas.js | node --check --input-type=module && echo "served canvas.js OK"
```

- [ ] **Step 3: Manual check (browser, http://localhost:8080/template-editor.html, hard refresh)**
  - Add two text blocks, **different font sizes**, rotate both **270°**.
  - Snap one to the **25% quarter (X)** + **Y-center**; snap the other to **X-center** + **Y-center**.
  - Click **Preview**: the Labelary PNG now matches the canvas — the centered one is centered, the quarter one is at the quarter, and **both Y-centers line up** (no fan-out, Y correct).
  - Add a static text block and compare its on-canvas length to the preview — they should match.

- [ ] **Step 4: Document + commit**

Add a short note to `docs/template-designer.md` (near the existing "Snap guides / Center on band" text): the editor sizes text using the printer's font-0 model (`chars × fontSize × 0.46` length, `fontSize` thickness, Helvetica/Arial glyphs), mirroring `ZplGeneratorService`, so canvas placement matches the print on both axes; data-bound fields use the sample length at design time (centered fields are re-centered from real data by the renderer).
```bash
git add docs/template-designer.md
git commit -m "docs: editor text now mirrors the printer font-0 metrics

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 5: Tear down (optional)**

```bash
docker compose -p stup-wristbandprinterservice -f docker-compose.local-cluster.yml down
```

---

## Self-Review Notes

- **Spec coverage:** R1 (length = chars×fontSize×0.46) → Task 1 `applyTextMetrics`. R2 (thickness = fontSize) → `targetHpx`. R3 (recompute on create/edit/resize) → Task 1 Steps 3/4/5. R4 (displayed string) → `textOf(node)`. R5 (bbox flows from box) → scale makes `getClientRect` = font-0 box. R6 (Helvetica/Arial font 0 stand-in) → Step 3. R7 (renderer fontSize model) → Task 2. R8 (no change to basic path/ZPL/existing) → only editor measurement + the centered-rotated formula change; `ZplGeneratorService` untouched.
- **Names consistent:** `CHAR_ADVANCE_RATIO`, `applyTextMetrics`, `fitScaleY` used identically across steps.
- **No placeholders:** all code is literal; expected `^FO` values computed (71, 87).
- **Resize correctness:** `scaleY` gesture ÷ `fitScaleY` (≈1) isolates the resize from the condense fit; verified the math (thickness natural-height = fontSize ⇒ `sy ≈ 1`).

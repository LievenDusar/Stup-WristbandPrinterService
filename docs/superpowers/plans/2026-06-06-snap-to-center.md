# Snap-to-Center Guides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Snap to center" toggle to the wristband template editor so dragged elements (and groups) snap their center to the canvas centerlines, with a dashed guideline that flashes in while snapped.

**Architecture:** Pure front-end change in the existing vanilla-JS Konva editor. Two hidden `Konva.Line` guideline nodes live on the layer. A single layer-level `dragmove` listener (Konva drag events bubble) computes the dragged node's bounding-box center, and if it is within a 10px threshold of a canvas centerline, nudges the node's position onto that line and reveals the matching dashed guideline. `dragend` and disabling the toggle hide the guidelines. No backend, no new dependencies, no persistence.

**Tech Stack:** Vanilla ES modules + vendored Konva 9.3.20 (as in the existing editor). No JS test runner — verified via the run-the-app checklist (Task 5), matching the convention of the prior editor plans.

---

## File Structure

| File | Responsibility | Change |
|------|----------------|--------|
| `src/main/resources/static/template-editor.html` | Editor shell / toolbox markup | Add the "Snap to center" checkbox in the Arrange section |
| `src/main/resources/static/js/editor/canvas.js` | Konva stage, nodes, selection, serialization | Add guideline nodes, snap state, `applySnap`, layer-level drag delegation, `contentNodes()` exclusion, resize repositioning, `setSnapToCenter` export |
| `src/main/resources/static/js/editor/main.js` | Editor bootstrap / control wiring | Import `setSnapToCenter` and wire the checkbox |

No new files. No changes to `groupops.js`, `buildNode`, or `wireLeaf` — the layer-level listener covers every dragged node.

---

## Task 1: Add the "Snap to center" checkbox

**Files:**
- Modify: `src/main/resources/static/template-editor.html` (Arrange section, ~lines 55-58)

- [ ] **Step 1: Add the checkbox below "Center on band"**

Find this block:

```html
        <h3 style="margin-top:16px">Arrange</h3>
        <button class="btn tool-btn" id="btn-group">Group</button>
        <button class="btn tool-btn" id="btn-ungroup">Ungroup</button>
        <button class="btn tool-btn" id="btn-center">Center on band</button>
```

Replace it with (adds the checkbox label as the last item in the section):

```html
        <h3 style="margin-top:16px">Arrange</h3>
        <button class="btn tool-btn" id="btn-group">Group</button>
        <button class="btn tool-btn" id="btn-ungroup">Ungroup</button>
        <button class="btn tool-btn" id="btn-center">Center on band</button>
        <label class="muted" style="display:flex;align-items:center;gap:6px;margin-top:8px">
          <input type="checkbox" id="snap-center"> Snap to center
        </label>
```

- [ ] **Step 2: Verify it renders**

Run: open the editor in a browser (see Task 5 setup) and confirm an unchecked "Snap to center" checkbox appears under "Center on band". It does nothing yet — that is expected.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/template-editor.html
git commit -m "feat(editor): add 'Snap to center' checkbox to toolbox"
```

---

## Task 2: Add guideline nodes, snap state, and resize repositioning

**Files:**
- Modify: `src/main/resources/static/js/editor/canvas.js`

This task adds the dashed guideline infrastructure. After it, the lines exist but stay hidden (nothing toggles them yet) — the app behaves exactly as before.

- [ ] **Step 1: Add module-level state**

Find (top of file, ~lines 6-10):

```js
const MAX_DISPLAY_HEIGHT = 720;
let stage, layer, tr, bg;
let scale = 1;
let canvasDots = { widthDots: 330, lengthDots: 3300, dpi: 300 };
let onSelect = () => {};
let selection = [];
```

Replace with:

```js
const MAX_DISPLAY_HEIGHT = 720;
const SNAP_THRESHOLD = 10; // screen pixels — distance at which the center locks to a centerline
let stage, layer, tr, bg;
let vGuide, hGuide;        // dashed centerline guides (Konva.Line), hidden unless actively snapped
let snapEnabled = false;
let scale = 1;
let canvasDots = { widthDots: 330, lengthDots: 3300, dpi: 300 };
let onSelect = () => {};
let selection = [];
```

- [ ] **Step 2: Create the guide nodes in `initCanvas`**

Find (in `initCanvas`, ~lines 30-32):

```js
  tr = new Konva.Transformer({ rotationSnaps: [0, 90, 180, 270],
    enabledAnchors: ['top-left', 'top-right', 'bottom-left', 'bottom-right'] });
  layer.add(tr);
```

Replace with:

```js
  tr = new Konva.Transformer({ rotationSnaps: [0, 90, 180, 270],
    enabledAnchors: ['top-left', 'top-right', 'bottom-left', 'bottom-right'] });
  layer.add(tr);

  // Dashed center guides. Non-interactive and hidden; revealed only while a drag is snapped.
  // moveToTop() in applySnap() keeps them above content (content nodes are added later).
  const guideStyle = { stroke: '#ff3399', strokeWidth: 1, dash: [6, 4], listening: false, visible: false };
  vGuide = new Konva.Line({ ...guideStyle }); // vertical line  → X-axis (horizontal-center) snap
  hGuide = new Konva.Line({ ...guideStyle }); // horizontal line → Y-axis (vertical-center) snap
  layer.add(vGuide, hGuide);
```

- [ ] **Step 3: Size/position the guides in `resize`**

Find (the whole `resize` function, ~lines 56-65):

```js
export function resize(dots) {
  canvasDots = { ...dots };
  scale = Math.min(MAX_DISPLAY_HEIGHT / dots.lengthDots, 2);
  stage.width(dots.widthDots * scale);
  stage.height(dots.lengthDots * scale);
  bg.width(dots.widthDots * scale);
  bg.height(dots.lengthDots * scale);
  applyLayout();
  layer.draw();
}
```

Replace with:

```js
export function resize(dots) {
  canvasDots = { ...dots };
  scale = Math.min(MAX_DISPLAY_HEIGHT / dots.lengthDots, 2);
  const w = dots.widthDots * scale, h = dots.lengthDots * scale;
  stage.width(w);
  stage.height(h);
  bg.width(w);
  bg.height(h);
  // Guides span the full canvas through the exact center of each axis.
  // Guard: resize() runs once at the end of initCanvas, after the guides are created above.
  if (vGuide) {
    vGuide.points([w / 2, 0, w / 2, h]);
    hGuide.points([0, h / 2, w, h / 2]);
  }
  applyLayout();
  layer.draw();
}
```

- [ ] **Step 4: Exclude the guides from `contentNodes()`**

Find (~lines 75-77):

```js
function contentNodes() {
  return layer.getChildren(n => n !== bg && n.className !== 'Transformer');
}
```

Replace with:

```js
function contentNodes() {
  return layer.getChildren(n =>
    n !== bg && n !== vGuide && n !== hGuide && n.className !== 'Transformer');
}
```

> Why: `contentNodes()` feeds serialization and `loadElements()` (which destroys every content node). Without this exclusion the guide lines would be serialized as template elements and wiped on the next load.

- [ ] **Step 5: Verify nothing regressed**

Run: reload the editor, add a few blocks, drag them, save, and reload the template. Expected: identical behaviour to before (no guidelines appear yet; saved templates round-trip correctly with no stray elements).

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/js/editor/canvas.js
git commit -m "feat(editor): add hidden center guideline nodes + resize/serialize handling"
```

---

## Task 3: Implement snapping and the layer-level drag listener

**Files:**
- Modify: `src/main/resources/static/js/editor/canvas.js`

- [ ] **Step 1: Add `hideGuides`, `applySnap`, and the `setSnapToCenter` export**

Find the exports block (~lines 67-71):

```js
export function setBackgroundColor(c) { bg.fill(c || '#ffffff'); layer.draw(); }
export function getCanvasDots() { return { ...canvasDots }; }
export function getScale() { return scale; }
export function getSelection() { return selection.slice(); }
export { layer, tr };
```

Replace with:

```js
export function setBackgroundColor(c) { bg.fill(c || '#ffffff'); layer.draw(); }
export function getCanvasDots() { return { ...canvasDots }; }
export function getScale() { return scale; }
export function getSelection() { return selection.slice(); }
export { layer, tr };

// Enable/disable center snapping. Disabling clears any guideline left on screen.
export function setSnapToCenter(enabled) {
  snapEnabled = enabled;
  if (!enabled) hideGuides();
}

function hideGuides() {
  if (vGuide) vGuide.visible(false);
  if (hGuide) hGuide.visible(false);
}

// Snap the dragged node's bbox center onto a canvas centerline (each axis independent).
// Reveals the matching dashed guide while snapped; called on every dragmove.
function applySnap(node) {
  if (!snapEnabled) return;
  const r = node.getClientRect({ relativeTo: layer, skipStroke: true });
  const nodeCenterX = r.x + r.width / 2;
  const nodeCenterY = r.y + r.height / 2;
  const canvasCenterX = stage.width() / 2;
  const canvasCenterY = stage.height() / 2;

  if (Math.abs(nodeCenterX - canvasCenterX) < SNAP_THRESHOLD) {
    node.x(node.x() + (canvasCenterX - nodeCenterX));
    vGuide.visible(true); vGuide.moveToTop();
  } else {
    vGuide.visible(false);
  }

  if (Math.abs(nodeCenterY - canvasCenterY) < SNAP_THRESHOLD) {
    node.y(node.y() + (canvasCenterY - nodeCenterY));
    hGuide.visible(true); hGuide.moveToTop();
  } else {
    hGuide.visible(false);
  }

  layer.batchDraw();
}
```

> Why `node.x()/y()` deltas work: top-level nodes share the layer's coordinate space, so `getClientRect({relativeTo: layer})` and `node.x()/y()` are in the same units. The delta `(canvasCenter − nodeCenter)` is the exact nudge to land the center on the line. `getClientRect` returns the axis-aligned bbox, so this is correct for rotated nodes and for groups (composite bbox).

- [ ] **Step 2: Register the layer-level drag delegation in `initCanvas`**

Konva drag events bubble from the dragged node to the layer, so one listener covers leaves and groups (loaded or created via the Group button). Find (in `initCanvas`, ~lines 46-51):

```js
  stage.on('dblclick dbltap', (e) => {
    if (e.target === bg || e.target === stage) return;
    if (e.target.getParent() && e.target.getParent().getAttr('elType') === 'GROUP') {
      setSelection([e.target]);
    }
  });
```

Replace with:

```js
  stage.on('dblclick dbltap', (e) => {
    if (e.target === bg || e.target === stage) return;
    if (e.target.getParent() && e.target.getParent().getAttr('elType') === 'GROUP') {
      setSelection([e.target]);
    }
  });

  // Center-snapping: drag events bubble to the layer, so one pair of listeners
  // covers every draggable node (leaves + groups), however it was created.
  layer.on('dragmove', (e) => applySnap(outermost(e.target)));
  layer.on('dragend', hideGuides);
```

- [ ] **Step 3: Verify nothing regressed**

Run: reload the editor and drag a few blocks around. Expected: dragging still works normally and no errors appear in the devtools console. The snap logic exists but is dormant (`snapEnabled` is `false` and the checkbox isn't wired until Task 4); it is exercised end-to-end in Task 5.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/js/editor/canvas.js
git commit -m "feat(editor): center-snap dragged nodes with dashed guideline"
```

---

## Task 4: Wire the checkbox to the canvas

**Files:**
- Modify: `src/main/resources/static/js/editor/main.js`

- [ ] **Step 1: Import `setSnapToCenter`**

Find (line 3):

```js
import { initCanvas, deleteSelected } from './canvas.js';
```

Replace with:

```js
import { initCanvas, deleteSelected, setSnapToCenter } from './canvas.js';
```

- [ ] **Step 2: Add the change listener**

Find (~lines 18-21):

```js
  document.getElementById('btn-delete').addEventListener('click', deleteSelected);
  document.getElementById('btn-group').addEventListener('click', groupSelected);
  document.getElementById('btn-ungroup').addEventListener('click', ungroupSelected);
  document.getElementById('btn-center').addEventListener('click', centerSelectedOnBand);
```

Replace with:

```js
  document.getElementById('btn-delete').addEventListener('click', deleteSelected);
  document.getElementById('btn-group').addEventListener('click', groupSelected);
  document.getElementById('btn-ungroup').addEventListener('click', ungroupSelected);
  document.getElementById('btn-center').addEventListener('click', centerSelectedOnBand);
  document.getElementById('snap-center').addEventListener('change',
    (e) => setSnapToCenter(e.target.checked));
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/js/editor/main.js
git commit -m "feat(editor): wire 'Snap to center' checkbox to canvas"
```

---

## Task 5: Run-the-app verification checklist

**Files:** none (manual verification — no JS test runner in this project).

- [ ] **Step 1: Start the local cluster**

```bash
./build.sh
docker compose -f docker-compose.local-cluster.yml up --build -d
```

Open `http://localhost:8080/template-editor.html` and log in (`admin` / `local-admin`).

- [ ] **Step 2: Toggle OFF (baseline)**

Leave "Snap to center" unchecked. Add a "Name" block and drag it slowly across the center.
Expected: it moves freely, **no** dashed line ever appears, no snapping.

- [ ] **Step 3: X-axis snap + vertical guide**

Check "Snap to center". Drag the block so its center approaches the horizontal middle of the band.
Expected: within ~10px the block's center **locks** onto the vertical centerline and a **dashed pink vertical line** appears. Dragging away (>10px) releases it and the line disappears. Releasing the mouse (dragend) also clears the line.

- [ ] **Step 4: Y-axis snap + horizontal guide (independent)**

Drag the block toward the vertical middle of the band (top-to-bottom center).
Expected: a **dashed horizontal line** appears and the center locks on the Y axis **independently** of X. Dragging to the exact middle shows **both** lines at once.

- [ ] **Step 5: Group snapping (loaded + runtime)**

Add two blocks, shift-click both, click **Group**. Drag the group toward center.
Expected: the **group** snaps and shows the guide(s) the same way — confirming the layer-level listener covers runtime-created groups. Save, reload the template, and drag the loaded group: same behaviour.

- [ ] **Step 6: Toggle OFF mid-session clears the line**

While a guide line is showing (drag to center and hold is not needed — just snap then release), uncheck "Snap to center".
Expected: snapping stops immediately and no dashed line remains on the canvas.

- [ ] **Step 7: Serialization is clean**

Save the template, then reload it (use the "Open template…" dropdown).
Expected: all elements round-trip correctly and **no** stray line/element is added — confirming the `contentNodes()` exclusion.

- [ ] **Step 8: Canvas resize keeps guides centered**

Change the **W** or **L** field in the topbar, then drag a block to center.
Expected: the guide lines appear through the **new** center of the resized canvas.

- [ ] **Step 9: Tear down**

```bash
docker compose -f docker-compose.local-cluster.yml down
```

---

## Self-Review Notes

- **Spec coverage:** R1/R2/R3 (independent per-axis center snap) → Task 3 `applySnap`. R4 (10px magnetic) → `SNAP_THRESHOLD`. R5/R6 (dashed guide only while snapped) → guide nodes (Task 2) + visibility toggle + `dragend`/`setSnapToCenter(false)` hiding (Task 3). R7 (checkbox, default off) → Task 1 + `snapEnabled = false`. R8 (session-only) → no persistence anywhere. R9 (leaves + groups) → layer-level delegation (Task 3 Step 2), verified Task 5 Step 5. R10 (fires on every dragmove) → `layer.on('dragmove')`.
- **Guides excluded from serialization** → Task 2 Step 4, verified Task 5 Step 7.
- **No placeholders, consistent names:** `vGuide`/`hGuide`/`applySnap`/`hideGuides`/`setSnapToCenter`/`SNAP_THRESHOLD` used identically across all tasks.

---

# Quarter-line snap extension (added 2026-06-07)

**Goal:** Add a second independent "Snap to quarters" checkbox that snaps the element's center to the 25%/75% lines on each axis, with subtle slate-blue dashed guides. Refactors the center-snap code (Tasks 1–4, already implemented) into a data-driven multi-line model.

**Covers spec requirements R11–R14.** Builds on the implemented center-snap code in the worktree.

> Each step replaces the exact center-snap code from Tasks 2–4 with the generalized version. After Task E1 the center snap still behaves identically (it becomes the 50% candidate); Tasks E2–E3 add the quarter lines and checkbox.

## Task E1: Generalize canvas.js to data-driven multi-line snapping

**Files:**
- Modify: `src/main/resources/static/js/editor/canvas.js`

- [ ] **Step 1: Replace the module-level snap state**

Find:
```js
const SNAP_THRESHOLD = 10; // screen pixels — distance at which the center locks to a centerline
let stage, layer, tr, bg;
let vGuide, hGuide;        // dashed centerline guides (Konva.Line), hidden unless actively snapped
let snapEnabled = false;
```

Replace with:
```js
const SNAP_THRESHOLD = 10; // screen pixels — distance at which the center locks to a guide line
const QUARTER_STROKE = '#9bb0c9'; // subtle slate-blue for quarter guides (center stays pink)
let stage, layer, tr, bg;
let vGuide, hGuide, vQ1, vQ2, hQ1, hQ2; // dashed guides (Konva.Line); hidden unless actively snapped
let vGuides = [], hGuides = [];          // candidate lists: { frac, line } per axis
let snapEnabled = false;        // "Snap to center" (50%)
let quarterSnapEnabled = false; // "Snap to quarters" (25% / 75%)
```

- [ ] **Step 2: Create all six guides and the candidate arrays in `initCanvas`**

Find:
```js
  // Dashed center guides. Non-interactive and hidden; revealed only while a drag is snapped.
  // moveToTop() in applySnap() keeps them above content (content nodes are added later).
  const guideStyle = { stroke: '#ff3399', strokeWidth: 1, dash: [6, 4], listening: false, visible: false };
  vGuide = new Konva.Line({ ...guideStyle }); // vertical line  → X-axis (horizontal-center) snap
  hGuide = new Konva.Line({ ...guideStyle }); // horizontal line → Y-axis (vertical-center) snap
  layer.add(vGuide, hGuide);
```

Replace with:
```js
  // Dashed snap guides. Non-interactive and hidden; revealed only while a drag is snapped.
  // moveToTop() in snapAxis() keeps them above content (content nodes are added later).
  // isGuide marks them so contentNodes() never serializes or destroys them.
  const base = { strokeWidth: 1, dash: [6, 4], listening: false, visible: false, isGuide: true };
  const pink = { ...base, stroke: '#ff3399' };       // center (primary)
  const slate = { ...base, stroke: QUARTER_STROKE }; // quarters (subtle)
  vGuide = new Konva.Line({ ...pink });  hGuide = new Konva.Line({ ...pink });
  vQ1 = new Konva.Line({ ...slate });    vQ2 = new Konva.Line({ ...slate });
  hQ1 = new Konva.Line({ ...slate });    hQ2 = new Konva.Line({ ...slate });
  layer.add(vGuide, hGuide, vQ1, vQ2, hQ1, hQ2);
  // Vertical lines snap the X axis; horizontal lines snap the Y axis. 0.5 = center, 0.25/0.75 = quarters.
  vGuides = [{ frac: 0.5, line: vGuide }, { frac: 0.25, line: vQ1 }, { frac: 0.75, line: vQ2 }];
  hGuides = [{ frac: 0.5, line: hGuide }, { frac: 0.25, line: hQ1 }, { frac: 0.75, line: hQ2 }];
```

- [ ] **Step 3: Make `resize` data-driven**

Find:
```js
  // Guides span the full canvas through the exact center of each axis.
  // Guard: resize() runs once at the end of initCanvas, after the guides are created above.
  if (vGuide) {
    vGuide.points([w / 2, 0, w / 2, h]);
    hGuide.points([0, h / 2, w, h / 2]);
  }
```

Replace with:
```js
  // Position every guide from its fractional location. Guard: resize() runs once at the
  // end of initCanvas, after the candidate arrays are populated above.
  if (vGuides.length) {
    vGuides.forEach(g => g.line.points([g.frac * w, 0, g.frac * w, h]));
    hGuides.forEach(g => g.line.points([0, g.frac * h, w, g.frac * h]));
  }
```

- [ ] **Step 4: Exclude guides by marker in `contentNodes`**

Find:
```js
function contentNodes() {
  return layer.getChildren(n =>
    n !== bg && n !== vGuide && n !== hGuide && n.className !== 'Transformer');
}
```

Replace with:
```js
function contentNodes() {
  return layer.getChildren(n =>
    n !== bg && !n.getAttr('isGuide') && n.className !== 'Transformer');
}
```

- [ ] **Step 5: Replace `setSnapToCenter` / `hideGuides` / `applySnap` with the generalized versions**

Find the whole block:
```js
// Enable/disable center snapping. Disabling clears any guideline left on screen.
export function setSnapToCenter(enabled) {
  snapEnabled = enabled;
  if (!enabled) hideGuides();
}

function hideGuides() {
  if (vGuide) vGuide.visible(false);
  if (hGuide) hGuide.visible(false);
}

// Snap the dragged node's bbox center onto a canvas centerline (each axis independent).
// Reveals the matching dashed guide while snapped; called on every dragmove.
function applySnap(node) {
  if (!snapEnabled) return;
  const r = node.getClientRect({ relativeTo: layer, skipStroke: true });
  const nodeCenterX = r.x + r.width / 2;
  const nodeCenterY = r.y + r.height / 2;
  const canvasCenterX = stage.width() / 2;
  const canvasCenterY = stage.height() / 2;

  if (Math.abs(nodeCenterX - canvasCenterX) < SNAP_THRESHOLD) {
    node.x(node.x() + (canvasCenterX - nodeCenterX));
    vGuide.visible(true); vGuide.moveToTop();
  } else {
    vGuide.visible(false);
  }

  if (Math.abs(nodeCenterY - canvasCenterY) < SNAP_THRESHOLD) {
    node.y(node.y() + (canvasCenterY - nodeCenterY));
    hGuide.visible(true); hGuide.moveToTop();
  } else {
    hGuide.visible(false);
  }

  layer.batchDraw();
}
```

Replace with:
```js
// Enable/disable center (50%) snapping. Disabling clears any guide left on screen.
export function setSnapToCenter(enabled) {
  snapEnabled = enabled;
  if (!enabled) hideGuides();
}

// Enable/disable quarter (25% / 75%) snapping. Disabling clears any guide left on screen.
export function setSnapToQuarters(enabled) {
  quarterSnapEnabled = enabled;
  if (!enabled) hideGuides();
}

function hideGuides() {
  [...vGuides, ...hGuides].forEach(g => g.line && g.line.visible(false));
}

// Is the line at this fraction currently active? 0.5 = center toggle, else quarter toggle.
function enabledFrac(frac) {
  return frac === 0.5 ? snapEnabled : quarterSnapEnabled;
}

// Snap one axis: pick the nearest ENABLED candidate line within threshold, move the node's
// center onto it, show only that line (hide the rest on this axis). Each axis is independent.
function snapAxis(node, guides, size, center, axis) {
  let best = null, bestDist = SNAP_THRESHOLD;
  for (const g of guides) {
    if (!enabledFrac(g.frac)) continue;
    const dist = Math.abs(center - g.frac * size);
    if (dist < bestDist) { bestDist = dist; best = g; }
  }
  for (const g of guides) {
    const on = g === best;
    if (on) {
      const target = g.frac * size;
      if (axis === 'x') node.x(node.x() + (target - center));
      else              node.y(node.y() + (target - center));
      g.line.moveToTop();
    }
    g.line.visible(on);
  }
}

// Snap the dragged node's bbox center to the nearest active guide on each axis.
// Reveals the matching dashed guide while snapped; called on every dragmove.
function applySnap(node) {
  if (!snapEnabled && !quarterSnapEnabled) return;
  const r = node.getClientRect({ relativeTo: layer, skipStroke: true });
  snapAxis(node, vGuides, stage.width(),  r.x + r.width  / 2, 'x');
  snapAxis(node, hGuides, stage.height(), r.y + r.height / 2, 'y');
  layer.batchDraw();
}
```

- [ ] **Step 6: Syntax check**

Run: `node --check --input-type=module < src/main/resources/static/js/editor/canvas.js`
Expected: `SYNTAX OK` (exit 0, no output).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/js/editor/canvas.js
git commit -m "feat(editor): generalize snap to multi-line + add quarter (25/75%) guides"
```

## Task E2: Add the "Snap to quarters" checkbox

**Files:**
- Modify: `src/main/resources/static/template-editor.html`

- [ ] **Step 1: Add the checkbox below "Snap to center"**

Find:
```html
        <label class="muted" style="display:flex;align-items:center;gap:6px;margin-top:8px">
          <input type="checkbox" id="snap-center"> Snap to center
        </label>
        <h3 style="margin-top:16px">Logo</h3>
```

Replace with:
```html
        <label class="muted" style="display:flex;align-items:center;gap:6px;margin-top:8px">
          <input type="checkbox" id="snap-center"> Snap to center
        </label>
        <label class="muted" style="display:flex;align-items:center;gap:6px;margin-top:6px">
          <input type="checkbox" id="snap-quarters"> Snap to quarters
        </label>
        <h3 style="margin-top:16px">Logo</h3>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/template-editor.html
git commit -m "feat(editor): add 'Snap to quarters' checkbox to toolbox"
```

## Task E3: Wire the quarter checkbox in main.js

**Files:**
- Modify: `src/main/resources/static/js/editor/main.js`

- [ ] **Step 1: Import `setSnapToQuarters`**

Find:
```js
import { initCanvas, deleteSelected, setSnapToCenter } from './canvas.js';
```

Replace with:
```js
import { initCanvas, deleteSelected, setSnapToCenter, setSnapToQuarters } from './canvas.js';
```

- [ ] **Step 2: Add the change listener**

Find:
```js
  document.getElementById('snap-center').addEventListener('change',
    (e) => setSnapToCenter(e.target.checked));
```

Replace with:
```js
  document.getElementById('snap-center').addEventListener('change',
    (e) => setSnapToCenter(e.target.checked));
  document.getElementById('snap-quarters').addEventListener('change',
    (e) => setSnapToQuarters(e.target.checked));
```

- [ ] **Step 3: Syntax check + commit**

```bash
node --check --input-type=module < src/main/resources/static/js/editor/main.js
git add src/main/resources/static/js/editor/main.js
git commit -m "feat(editor): wire 'Snap to quarters' checkbox to canvas"
```

## Task E4: Rebuild and verify

- [ ] **Step 1: Rebuild the running cluster from the worktree**

```bash
docker compose -p stup-wristbandprinterservice -f docker-compose.local-cluster.yml up --build -d
```

- [ ] **Step 2: Confirm served code includes the quarter feature**

```bash
curl -s http://localhost:8080/template-editor.html | grep -c 'snap-quarters'   # expect 1
curl -s http://localhost:8080/js/editor/canvas.js   | grep -c 'setSnapToQuarters' # expect >= 1
curl -s http://localhost:8080/js/editor/main.js     | grep -c 'setSnapToQuarters' # expect 2
```

- [ ] **Step 3: Manual checks (browser, http://localhost:8080/template-editor.html)**
  - Check **Snap to quarters** only → drag an element; its center snaps to the ¼ and ¾ lines on each axis with **slate-blue** dashed guides; nothing snaps at 50%.
  - Check **both** toggles → near center the **pink** 50% line wins; near a quarter the **slate** line wins; only one line per axis shows.
  - Uncheck **Snap to quarters** mid-session → quarter snapping stops, no slate line lingers; center snap (if still checked) still works.
  - Save + reload a template → no stray guide elements (the `isGuide` exclusion holds for all six lines).

## Extension Self-Review Notes

- **R11** (quarter checkbox, both axes, 4 lines, session-only) → E2 + `quarterSnapEnabled=false` + `vGuides/hGuides` 0.25/0.75 entries.
- **R12** (dashed, only-while-snapped, hide on release/off) → `snapAxis` visibility + `dragend`→`hideGuides` (unchanged) + `setSnapToQuarters(false)`→`hideGuides`.
- **R13** (subtle colour) → `QUARTER_STROKE = '#9bb0c9'`.
- **R14** (independent toggles, nearest-active-line-per-axis, one line per axis) → `enabledFrac` + nearest-within-threshold loop in `snapAxis`.
- **Names consistent:** `vGuides`/`hGuides`/`snapAxis`/`enabledFrac`/`setSnapToQuarters`/`QUARTER_STROKE`/`isGuide` used identically across tasks and spec.

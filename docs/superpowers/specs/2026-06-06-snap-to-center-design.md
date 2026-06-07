# Snap-to-Center & Quarter Guides — Design Spec

**Date:** 2026-06-06
**Status:** Approved
**Feature area:** Template editor canvas (`template-editor.html`)
**Updated:** 2026-06-07 — added the quarter-line (25%/75%) snap extension (see that section).

---

## Overview

Add snap alignment guides to the wristband template editor. When enabled, dragging any element on the canvas causes its bounding-box center to snap magnetically to a canvas guide line — identical to the smart-guide behaviour in Canva or Illustrator. While an axis is snapped, a **dashed guideline flashes in** on that axis and disappears the moment the object pulls away or the drag ends. Both leaf elements and groups snap.

Two independent toggles in the toolbox sidebar select which guide lines are active:

- **Snap to center** — the horizontal/vertical centerlines (50%), drawn in vivid pink.
- **Snap to quarters** — the 25% and 75% lines on each axis, drawn in a subtler slate-blue.

Either, both, or neither may be enabled. When both are on, each axis snaps to whichever active line (50%, 25%, or 75%) is nearest within the threshold.

---

## Requirements

| # | Requirement |
|---|-------------|
| R1 | When snap is enabled and the user drags an element, its bounding-box **center** snaps to the canvas horizontal centerline (X axis) independently. |
| R2 | When snap is enabled and the user drags an element, its bounding-box **center** snaps to the canvas vertical centerline (Y axis) independently. |
| R3 | Each axis snaps independently — snapping on X does not force snapping on Y and vice versa. |
| R4 | The snap is **magnetic**: the element locks to the centerline when its center comes within **10 screen pixels** of the centerline, and releases naturally once the user drags it beyond that threshold. |
| R5 | While an axis is snapped during a drag, a **dashed guideline** is drawn along that axis (full canvas span). The X-axis snap shows the vertical centerline; the Y-axis snap shows the horizontal centerline. |
| R6 | Guidelines are shown **only while actively snapped** — they appear when the object's center locks to a centerline and disappear the instant it pulls beyond the threshold **or** the drag ends. When snap is disabled, no guidelines ever appear. |
| R7 | A **"Snap to center" checkbox** in the toolbox sidebar enables/disables the feature. Default state: unchecked (off). |
| R8 | The toggle is a **session-only preference** — it is not persisted and resets to unchecked on page reload. |
| R9 | The feature applies equally to **leaf elements** (text, barcode, image, shape) and to **groups** — both snap during drag and show guidelines. |
| R10 | The snap fires on every `dragmove` event while the checkbox is checked. |
| R11 | A second, independent **"Snap to quarters" checkbox** snaps the element's center to the **25%** and **75%** lines of each axis (4 lines total: two vertical at ¼/¾ width, two horizontal at ¼/¾ length). Default: unchecked. Session-only (not persisted). |
| R12 | Quarter guidelines behave exactly like center guidelines: dashed, shown **only while actively snapped**, hidden on release / when dragged away / when the checkbox is off. |
| R13 | Quarter guidelines use a **more subtle colour** (`#9bb0c9`, slate-blue) than the pink centerlines (`#ff3399`) so the center reads as the primary guide. |
| R14 | The two toggles are **independent**. With both on, each axis snaps to the **nearest** active line among {25%, 50%, 75%} within `SNAP_THRESHOLD`; only that one line is shown on the axis. Axes remain independent of each other. |

---

## UI

### Checkbox placement

Both checkboxes are placed in the **Arrange** section of the left toolbox sidebar (`template-editor.html`), directly below the existing "Center on band" button, stacked. This groups them naturally with the other alignment helpers.

```html
<!-- Inside the Arrange section in template-editor.html -->
<label class="muted" style="display:flex;align-items:center;gap:6px;margin-top:8px">
  <input type="checkbox" id="snap-center"> Snap to center
</label>
<label class="muted" style="display:flex;align-items:center;gap:6px;margin-top:6px">
  <input type="checkbox" id="snap-quarters"> Snap to quarters
</label>
```

Minimal styling (matches the existing `muted` label style used for W/L/DPI inputs in the topbar). No additional CSS class required.

---

## Architecture

### Files changed

| File | Change |
|------|--------|
| `src/main/resources/static/template-editor.html` | Add the two checkbox labels in the Arrange section |
| `src/main/resources/static/js/editor/canvas.js` | Add snap state, dashed guideline nodes (2 center + 4 quarter), generalized `applySnap`, layer-level drag delegation, `contentNodes()` exclusion, resize repositioning |
| `src/main/resources/static/js/editor/main.js` | Wire both checkboxes to canvas |

No backend changes. No new files.

### canvas.js changes

**New module-level state:**
```js
let snapEnabled = false;
let vGuide, hGuide;   // dashed guideline nodes (Konva.Line)
const SNAP_THRESHOLD = 10; // screen pixels
```

**New exported function:**
```js
export function setSnapToCenter(enabled) {
  snapEnabled = enabled;
  if (!enabled) hideGuides(); // clear any lingering lines when toggled off
}
```

**Guideline nodes (created once in `initCanvas`):**

Two `Konva.Line` nodes are added to the layer in `initCanvas`, initially hidden. They are repositioned/sized in `resize()` to span the current canvas, and toggled visible during snap. Because content elements are added after the guides, the guides are brought to the front via `moveToTop()` when shown (see `applySnap`) so the dashed line renders above the dragged element.

```js
// in initCanvas, after the transformer is added:
const guideStyle = { stroke: '#ff3399', strokeWidth: 1, dash: [6, 4],
  listening: false, visible: false, isGuide: true };
vGuide = new Konva.Line({ ...guideStyle }); // vertical centerline (X snap)
hGuide = new Konva.Line({ ...guideStyle }); // horizontal centerline (Y snap)
layer.add(vGuide, hGuide);
```

> The **Quarter-line snap extension** section below adds four more guide lines (subtle slate-blue) and folds `vGuide`/`hGuide` into the `vGuides`/`hGuides` candidate arrays. The single-guide `resize()`, `hideGuides()`, and `applySnap()` snippets shown in this section are **superseded** by the array-driven versions there.

```js
// in resize(), after stage/bg sizing (guard: resize() runs once at the end of
// initCanvas — create the guides BEFORE that call, or guard with `if (vGuide)`):
if (vGuide) {
  const w = dots.widthDots * scale, h = dots.lengthDots * scale;
  vGuide.points([w / 2, 0, w / 2, h]);
  hGuide.points([0, h / 2, w, h / 2]);
}
```

```js
function hideGuides() {
  if (vGuide) vGuide.visible(false);
  if (hGuide) hGuide.visible(false);
}
```

**Critical: exclude guides from `contentNodes()`.**
`contentNodes()` currently returns every layer child except `bg` and the Transformer. The guideline lines must be excluded too, or they would be serialized as template elements and destroyed by `loadElements`. With six guide lines, tag each one with a marker attr at creation (`line.setAttr('isGuide', true)`) and exclude by that marker — simpler and future-proof than listing every node:
```js
function contentNodes() {
  return layer.getChildren(n =>
    n !== bg && !n.getAttr('isGuide') && n.className !== 'Transformer');
}
```
Every guide `Konva.Line` (center and quarter) is created with `isGuide: true` in its config.

**Shared snap handler `applySnap(node)`:**

A single function used by both leaf nodes and groups. On every `dragmove` while `snapEnabled`:

1. Compute the node's axis-aligned bbox in layer coordinates:
   ```js
   const r = node.getClientRect({ relativeTo: layer, skipStroke: true });
   const nodeCenterX = r.x + r.width  / 2;
   const nodeCenterY = r.y + r.height / 2;
   const canvasCenterX = stage.width()  / 2;
   const canvasCenterY = stage.height() / 2;
   ```
2. Snap X axis independently (threshold = `SNAP_THRESHOLD`) and toggle the **vertical** guideline:
   ```js
   if (Math.abs(nodeCenterX - canvasCenterX) < SNAP_THRESHOLD) {
     node.x(node.x() + (canvasCenterX - nodeCenterX));
     vGuide.visible(true); vGuide.moveToTop();
   } else {
     vGuide.visible(false);
   }
   ```
3. Snap Y axis independently and toggle the **horizontal** guideline:
   ```js
   if (Math.abs(nodeCenterY - canvasCenterY) < SNAP_THRESHOLD) {
     node.y(node.y() + (canvasCenterY - nodeCenterY));
     hGuide.visible(true); hGuide.moveToTop();
   } else {
     hGuide.visible(false);
   }
   ```
4. `layer.draw()` unconditionally (Konva does not auto-redraw position corrections made inside `dragmove`).

When `snapEnabled` is false, `applySnap` returns immediately (and guides stay hidden).

**Wiring via event delegation (`initCanvas`):**

Konva drag events **bubble** from the dragged node up to the layer. A single pair of layer-level listeners therefore catches **every** dragged node — leaf elements, groups loaded from a template, *and* groups created at runtime via the Group button — with no per-node wiring. Register them once in `initCanvas`:
```js
layer.on('dragmove', (e) => {
  const node = outermost(e.target);
  if (node.getAttr('elType')) applySnap(node); // content nodes only (see guard below)
});
layer.on('dragend', hideGuides);
```
`outermost(e.target)` resolves to the top-level node being dragged (group members are non-draggable, so `e.target` is already top-level; `outermost` is belt-and-braces). This satisfies R9 — groups snap and show guidelines exactly like leaf elements, regardless of how they were created. The existing "Center on band" button already centers groups via `centerSelectedOnBand`; this adds the drag-snap path. No changes to `wireLeaf`, `buildNode`, or `groupops.js` are needed.

**Guard — skip Transformer anchor drags.** The Konva `Transformer`'s resize/rotate **anchors are draggable nodes**, and their `dragmove` events also bubble to the layer. For such an event `outermost(e.target)` walks up to the **Transformer**, whose `getClientRect()` includes the **rotater handle** that sticks out beyond the object (measured ~25px horizontal offset at 90°/270°). Without a guard, `applySnap` would snap that inflated box during a rotate/resize, yanking the object to a wrong center. Every real content node (leaf or group) carries an `elType` attr; the Transformer and its anchors do not. So the listener only calls `applySnap` when `node.getAttr('elType')` is set. This fixes erratic snapping while rotating **and** the same latent issue during resize.

**Why `node.x/y` delta correction works:**
`getClientRect` returns the bbox in layer coordinates (pixels). The difference between current center and canvas center gives the exact pixel offset to add to `node.x()/node.y()` to bring the center onto the centerline. This works for rotated nodes and for groups because `getClientRect` returns the axis-aligned bbox of the whole node/subtree.

### Quarter-line snap extension

The center-snap `applySnap` above is **generalized** to support multiple candidate lines per axis. This supersedes the two-`if` body shown above.

**Additional state and guide nodes:**
```js
let quarterSnapEnabled = false;
let vGuides = [], hGuides = [];   // each entry: { frac, line }  (vertical / horizontal)
const QUARTER_STROKE = '#9bb0c9'; // subtle slate-blue (vs center pink #ff3399)
```

In `initCanvas`, the center guides keep their pink stroke and the four quarter guides are created with `QUARTER_STROKE`, then registered with their fractional position. The arrays make `applySnap` and `resize` data-driven:
```js
// vertical lines snap the X axis; horizontal lines snap the Y axis.
vGuides = [ { frac: 0.5, line: vGuide },                 // center (pink)
            { frac: 0.25, line: vQ1 }, { frac: 0.75, line: vQ2 } ]; // quarters (slate)
hGuides = [ { frac: 0.5, line: hGuide },
            { frac: 0.25, line: hQ1 }, { frac: 0.75, line: hQ2 } ];
```
`vQ1/vQ2/hQ1/hQ2` are four `Konva.Line` nodes created next to `vGuide/hGuide` with the subtle style. All six are added to the layer and excluded from `contentNodes()`.

**`enabledFrac(frac)` — is a given line active?**
```js
const enabledFrac = (frac) =>
  (frac === 0.5 ? snapEnabled : quarterSnapEnabled);
```

**Generalized `applySnap(node)`** — per axis, pick the nearest enabled candidate within threshold, snap to it, show only that line:
```js
function applySnap(node) {
  if (!snapEnabled && !quarterSnapEnabled) return;
  const r = node.getClientRect({ relativeTo: layer, skipStroke: true });
  snapAxis(node, vGuides, stage.width(),  r.x + r.width  / 2, 'x');
  snapAxis(node, hGuides, stage.height(), r.y + r.height / 2, 'y');
  layer.batchDraw();
}

// guides: candidate list for this axis; size: stage.width()/height();
// center: node bbox center on this axis; axis: 'x' or 'y'.
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
```
This keeps each axis independent (R3/R14) and shows at most one line per axis. The nearest-within-threshold rule means with both toggles on, an object near the center prefers 50% and near a quarter prefers 25%/75%.

**`hideGuides` and `resize`** iterate the arrays:
```js
function hideGuides() { [...vGuides, ...hGuides].forEach(g => g.line && g.line.visible(false)); }

// in resize(), after computing w/h:
if (vGuides.length) {
  vGuides.forEach(g => g.line.points([g.frac * w, 0, g.frac * w, h]));
  hGuides.forEach(g => g.line.points([0, g.frac * h, w, g.frac * h]));
}
```

**New export:**
```js
export function setSnapToQuarters(enabled) {
  quarterSnapEnabled = enabled;
  if (!enabled) hideGuides();
}
```

> Note: building the `vGuides`/`hGuides` arrays at module scope means `resize()` (which runs once at the end of `initCanvas`) must guard on `vGuides.length`, and the arrays must be populated in `initCanvas` before that `resize()` call — same ordering rule as the original single guides.

### main.js changes

Wire both checkboxes on `DOMContentLoaded` (alongside the existing control wiring), importing `setSnapToCenter` and `setSnapToQuarters` from `canvas.js`:

```js
document.getElementById('snap-center').addEventListener('change',
  (e) => setSnapToCenter(e.target.checked));
document.getElementById('snap-quarters').addEventListener('change',
  (e) => setSnapToQuarters(e.target.checked));
```

---

## Snap threshold

**10 screen pixels.** This is intentionally in screen-pixel space (not dots) so the magnetic feel is consistent regardless of the canvas zoom level or wristband dimensions. At the default scale (~0.22 for a 3300-dot wristband in 720px height), 10 screen pixels corresponds to roughly 45 dots — close enough to be useful, fine enough not to feel grabby.

---

## Edge cases

| Case | Behaviour |
|------|-----------|
| Snap disabled | `applySnap` returns immediately; guides stay hidden |
| Toggle turned off mid-session | `setSnapToCenter(false)` calls `hideGuides()` so no line is left on screen |
| Drag ends while snapped | `dragend` → `hideGuides()` removes the guideline(s) |
| Element larger than canvas | Snap still fires when the element's *center* aligns, even if edges overflow |
| Group dragged (loaded or created via Group button) | Works identically — the layer-level `dragmove` listener catches every dragged node; `getClientRect` returns the group's composite bbox |
| Element already centered | No movement; the correction delta is ~0; guideline still shows (it is centered) |
| Canvas resized (W/L/DPI change) | All six guideline `points()` are recomputed in `resize()` from each line's `frac` so they stay correctly placed |
| Snap during resize/rotate (Transformer anchors) | Anchor drags bubble `dragmove`, but `outermost` is the Transformer (no `elType`), so the `elType` guard skips them — the object is not snapped to the transformer's inflated bbox. Verified empirically: at 90° the transformer center is +25px off the object center. |
| Both toggles on, object near center | Each axis snaps to the **nearest** active line; near 50% the center (pink) wins, near a quarter the slate line wins; only one line shows per axis |
| Quarter toggle off mid-snap | `setSnapToQuarters(false)` → `hideGuides()` clears any visible line; next `dragmove` re-evaluates with only the still-enabled lines |
| Both toggles off | `applySnap` early-returns; no snapping, no lines |

---

## Out of scope

- Snapping to other elements (object-to-object smart guides) — only canvas centerlines
- Snapping edges to canvas centerlines (only center-to-center)
- Persistence of the toggle state across page loads
- Snapping during resize or rotation (drag only)
- Persistent always-on center cross (guidelines show only while actively snapped)

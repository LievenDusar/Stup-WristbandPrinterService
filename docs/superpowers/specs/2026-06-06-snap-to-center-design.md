# Snap-to-Center Guides — Design Spec

**Date:** 2026-06-06
**Status:** Approved
**Feature area:** Template editor canvas (`template-editor.html`)

---

## Overview

Add snap-to-center alignment guides to the wristband template editor. When enabled, dragging any element on the canvas causes its bounding-box center to snap magnetically to the canvas's horizontal and/or vertical centerline — identical to the smart-guide behaviour in Canva or Illustrator. While an axis is snapped, a **dashed guideline flashes in** on that axis and disappears the moment the object pulls away or the drag ends. The feature is toggled by a checkbox in the toolbox sidebar and has no effect when disabled. Both leaf elements and groups snap.

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

---

## UI

### Checkbox placement

The checkbox is placed in the **Arrange** section of the left toolbox sidebar (`template-editor.html`), directly below the existing "Center on band" button. This groups it naturally with the other alignment helpers.

```html
<!-- Inside the Arrange section in template-editor.html -->
<label class="muted snap-label">
  <input type="checkbox" id="snap-center">
  Snap to center
</label>
```

Minimal styling (matches the existing `muted` label style used for W/L/DPI inputs in the topbar). No additional CSS class required unless visual polish is needed.

---

## Architecture

### Files changed

| File | Change |
|------|--------|
| `src/main/resources/static/template-editor.html` | Add checkbox label in Arrange section |
| `src/main/resources/static/js/editor/canvas.js` | Add snap state, dashed guideline nodes, shared `applySnap`, leaf + group wiring |
| `src/main/resources/static/js/editor/main.js` | Wire checkbox to canvas |

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
  listening: false, visible: false };
vGuide = new Konva.Line({ ...guideStyle }); // vertical centerline (X snap)
hGuide = new Konva.Line({ ...guideStyle }); // horizontal centerline (Y snap)
layer.add(vGuide, hGuide);
```

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
`contentNodes()` currently returns every layer child except `bg` and the Transformer. The guideline lines must be excluded too, or they would be serialized as template elements and destroyed by `loadElements`. Update the filter:
```js
function contentNodes() {
  return layer.getChildren(n =>
    n !== bg && n !== vGuide && n !== hGuide && n.className !== 'Transformer');
}
```

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

**Wiring on leaf nodes (`wireLeaf`):**

Add alongside the existing `transformend dragend` listener:
```js
node.on('dragmove', () => applySnap(node));
node.on('dragend', hideGuides);
```

**Wiring on groups (`buildNode`):**

Groups are draggable when `parent === layer` but are **not** routed through `wireLeaf`, so they currently get no snap behaviour. After creating a top-level group, attach the same handlers:
```js
if (parent === layer) {
  node.on('dragmove', () => applySnap(node));
  node.on('dragend', hideGuides);
}
```
This satisfies R9 — groups snap and show guidelines exactly like leaf elements. (The existing "Center on band" button already centers groups via `centerSelectedOnBand`; this adds the drag-snap path.)

**Why `node.x/y` delta correction works:**
`getClientRect` returns the bbox in layer coordinates (pixels). The difference between current center and canvas center gives the exact pixel offset to add to `node.x()/node.y()` to bring the center onto the centerline. This works for rotated nodes and for groups because `getClientRect` returns the axis-aligned bbox of the whole node/subtree.

### main.js changes

Wire the checkbox on DOMContentLoaded (alongside the existing topbar control wiring):

```js
document.getElementById('snap-center').addEventListener('change', e => {
  setSnapToCenter(e.target.checked);
});
```

Import `setSnapToCenter` from `canvas.js`.

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
| Group dragged | Works identically — `applySnap` is wired to top-level groups; `getClientRect` returns the group's composite bbox |
| Element already centered | No movement; the correction delta is ~0; guideline still shows (it is centered) |
| Canvas resized (W/L/DPI change) | Guideline `points()` are recomputed in `resize()` so lines stay centered |
| Snap + transform (resize/rotate) | Snap only fires on `dragmove`, not on `transform` — resize/rotate are unaffected |

---

## Out of scope

- Snapping to other elements (object-to-object smart guides) — only canvas centerlines
- Snapping edges to canvas centerlines (only center-to-center)
- Persistence of the toggle state across page loads
- Snapping during resize or rotation (drag only)
- Persistent always-on center cross (guidelines show only while actively snapped)

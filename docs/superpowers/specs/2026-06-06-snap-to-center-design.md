# Snap-to-Center Guides — Design Spec

**Date:** 2026-06-06
**Status:** Approved
**Feature area:** Template editor canvas (`template-editor.html`)

---

## Overview

Add invisible snap-to-center alignment guides to the wristband template editor. When enabled, dragging any element on the canvas causes its bounding-box center to snap magnetically to the canvas's horizontal and/or vertical centerline — identical to the smart-guide behaviour in Canva or Illustrator. The feature is toggled by a checkbox in the toolbox sidebar and has no effect when disabled.

---

## Requirements

| # | Requirement |
|---|-------------|
| R1 | When snap is enabled and the user drags an element, its bounding-box **center** snaps to the canvas horizontal centerline (X axis) independently. |
| R2 | When snap is enabled and the user drags an element, its bounding-box **center** snaps to the canvas vertical centerline (Y axis) independently. |
| R3 | Each axis snaps independently — snapping on X does not force snapping on Y and vice versa. |
| R4 | The snap is **magnetic**: the element locks to the centerline when its center comes within **10 screen pixels** of the centerline, and releases naturally once the user drags it beyond that threshold. |
| R5 | **No guide lines are drawn** — the snap is entirely invisible to the user. |
| R6 | A **"Snap to center" checkbox** in the toolbox sidebar enables/disables the feature. Default state: unchecked (off). |
| R7 | The toggle is a **session-only preference** — it is not persisted and resets to unchecked on page reload. |
| R8 | The feature applies equally to **leaf elements** (text, barcode, image, shape) and to **groups**. |
| R9 | The snap fires on every `dragmove` event while the checkbox is checked. |

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
| `src/main/resources/static/js/editor/canvas.js` | Add snap state + logic |
| `src/main/resources/static/js/editor/main.js` | Wire checkbox to canvas |

No backend changes. No new files.

### canvas.js changes

**New module-level state:**
```js
let snapEnabled = false;
```

**New exported function:**
```js
export function setSnapToCenter(enabled) {
  snapEnabled = enabled;
}
```

**New `dragmove` listener in `wireLeaf`:**

Added alongside the existing `transformend dragend` listener. On every `dragmove` event while `snapEnabled` is `true`:

1. Compute the node's axis-aligned bounding box in layer coordinates:
   ```js
   const r = node.getClientRect({ relativeTo: layer, skipStroke: true });
   ```
2. Compute the node's center in screen pixels:
   ```js
   const nodeCenterX = r.x + r.width  / 2;
   const nodeCenterY = r.y + r.height / 2;
   ```
3. Compute the canvas center:
   ```js
   const canvasCenterX = stage.width()  / 2;
   const canvasCenterY = stage.height() / 2;
   ```
4. Snap X axis independently (threshold = 10px):
   ```js
   if (Math.abs(nodeCenterX - canvasCenterX) < 10) {
     node.x(node.x() + (canvasCenterX - nodeCenterX));
   }
   ```
5. Snap Y axis independently (threshold = 10px):
   ```js
   if (Math.abs(nodeCenterY - canvasCenterY) < 10) {
     node.y(node.y() + (canvasCenterY - nodeCenterY));
   }
   ```
6. Call `layer.draw()` unconditionally at the end of the handler (Konva does not auto-redraw position corrections made inside `dragmove`).

**Why `node.x/y` delta correction works:**
`getClientRect` returns the bbox in layer coordinates (pixels). The difference between current nodeCenterX and canvasCenterX gives the exact pixel offset to add to `node.x()/node.y()` to bring the center to the canvas centerline. This works correctly for rotated nodes because `getClientRect` returns the axis-aligned bbox of the rotated node.

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
| Snap disabled | `dragmove` listener is a no-op (early return on `!snapEnabled`) |
| Element larger than canvas | Snap still fires when the element's *center* aligns, even if edges overflow |
| Group dragged | Works identically — `getClientRect` on a group returns its composite bbox |
| Element already centered | No movement; the correction delta is ~0 |
| Snap + transform (resize/rotate) | Snap only fires on `dragmove`, not on `transformmove` — resize/rotate are unaffected |

---

## Out of scope

- Visible guide lines (explicitly not wanted)
- Snapping to other elements (object-to-object smart guides)
- Snapping edges to canvas centerlines (only center-to-center)
- Persistence of the toggle state across page loads
- Snapping during resize or rotation (drag only)

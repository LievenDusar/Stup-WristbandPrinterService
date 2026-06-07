# Center-on-Band (renderer-authoritative) — Design Spec

**Date:** 2026-06-07
**Status:** Approved
**Feature area:** Template designer — model, editor canvas/UI, ZPL renderer

---

## Overview

Add an optional per-element **"Center on band"** flag. When set, the element is centered across the **band width** (the cross axis) by the **ZPL renderer itself**, using ZPL-accurate positioning — not by the editor's stored `x`. This fixes the case where rotated (90°/270°) text looks centered on the editor canvas (measured with the **Poppins** web font) but prints off-center, because Zebra **font 0** has different metrics. Because each element is centered independently by exact metrics, differently-sized elements no longer "fan out" relative to each other.

The element's **length** position (down the band) is unchanged — only the width-axis position is taken over.

### Why this is needed (root cause, established empirically)

- The editor canvas stores `x` = the Konva/Poppins axis-aligned bbox top-left. To "center", it stores `x = bandCenter − thickness/2` using Konva's measured thickness.
- The ZPL renderer emits `^FO x,y`; `^FO` is the bbox top-left for every orientation (verified via Labelary).
- For rotated text the **cross-thickness** the editor used (Poppins) ≠ the thickness Zebra font 0 actually renders. The mismatch scales with font size, so centered elements of different sizes drift apart. Measured drift: ~15 dots at fontSize 60.

---

## Requirements

| # | Requirement |
|---|-------------|
| R1 | `TemplateElement` gains an optional boolean `centerOnBand`. Absent/false ⇒ exact current behavior (back-compatible; existing templates unchanged). |
| R2 | The toolbox **"Center on band" button becomes a toggle** acting on the single selected element or group. Pressed state reflects `centerOnBand`. |
| R3 | While `centerOnBand` is on, the **editor canvas** keeps the element centered on the band width live (re-centering when size/rotation/content changes) and **disables horizontal dragging** (vertical drag stays free). Turning it off leaves the element where it is and restores free horizontal movement. |
| R4 | `centerOnBand` is **persisted** in the template JSON and round-trips through save/load and serialize. |
| R5 | For a `centerOnBand` element the **renderer ignores stored `x`** and centers it across the band width using exact ZPL positioning (per element type, below). The element's `y` (length position) is unchanged. |
| R6 | **Non-rotated text** (0°/180°) centers via ZPL field block: `^FO0,y` + `^FB<bandWidth>,1,0,C`. Exact and metric-free (verified). |
| R7 | **Rotated text** (90°/270°) centers using the font's deterministic cross-thickness: `^FO<(bandWidth − thickness)/2>,y`, where `thickness = FONT0_CELL_RATIO × fontSize`. `FONT0_CELL_RATIO` is **calibrated by precise pixel measurement** (see Calibration). |
| R7b | If the rotated-text cross-thickness cannot be made exact within ±1 dot by a single ratio (e.g. cap/descender asymmetry), the calibration captures both a **ratio** and an **anchor offset** so the rendered cell is centered to ±1 dot across sizes. |
| R8 | **Image, shape** (any rotation) center on their known cross dimension: `^FO<(bandWidth − crossDim)/2>,y`. Exact. |
| R8b | **Barcode** centers on its **stored** cross box (`widthDots` for 0/180, `heightDots` for 90/270). The actual ZPL symbol width depends on module width/data, so this is exact to the editor's box, not the printed bars — acceptable because the editor controls the box and barcodes are rarely the centering-critical element. Documented as a known limitation. |
| R9 | A **group** with `centerOnBand` centers its whole cross-extent (the renderer already computes the rotation-aware group footprint via `sizeOf`). |
| R10 | Non-`centerOnBand` elements emit **byte-identical ZPL** to today (no `^FB`, original `^FO`). |

---

## Architecture

### Model (`editor/domain`)

`TemplateElement` is a Java record. Add `Boolean centerOnBand` (nullable; `null`/`false` ⇒ off). It is part of the `jsonb` `TemplateDefinition`, so no DB migration is needed (jsonb is schemaless). Update the record, its `group(...)` factory if present, and any all-args constructors/usages in tests.

### Renderer (`editor/service/TemplateZplRenderer`)

`renderNode` already receives absolute `absX, absY`. Add a centering decision before emitting a leaf:

- For a `centerOnBand` leaf, compute the **cross-extent** `cw` for the band-width axis (see below) and the centered origin `cx = (bandWidth − cw) / 2`. Use `cx` instead of `absX`. `bandWidth = def.canvas().widthDots()`, threaded into `renderNode`/append methods (or read from a field set at the start of `renderWith`).
- The element's `absY` is used unchanged.

Per type:

- **TEXT/STATIC_TEXT, rotation 0/180:** emit `^FO0,absY` then `^FB<bandWidth>,1,0,C` then the `^A..` and `^FD..`. The `^FB` centers the line across the full width regardless of glyph metrics. (`^FB` parameters: width, max-lines=1, line-gap=0, justification=C.)
- **TEXT/STATIC_TEXT, rotation 90/270:** `thickness = round(FONT0_CELL_RATIO × fontSize) (+ anchorOffset)`. Emit `^FO<(bandWidth − thickness)/2 (+ anchorOffset)>,absY` then `^A..B/R..` then `^FD..`.
- **BARCODE:** the printed symbol width depends on module width and data, which the renderer does not compute, so barcodes are centered on the **stored cross box** (`widthDots` for 0/180, `heightDots` for 90/270) like image/shape — exact to the stored geometry (which the editor controls), not the exact printed bars. Known limitation (R8b).
- **IMAGE/SHAPE:** crossDim = the rotation-aware width (`rot 90/270 ⇒ heightDots`, else `widthDots`). Emit centered `^FO`.

A small helper `crossExtentDots(el, bandWidth)` returns the width-axis extent used for centering, keeping `appendText/appendBarcode/appendImage/appendShape` readable.

### Calibration (`FONT0_CELL_RATIO`)

Add a calibration **test/utility** (not run at print time) that renders font 0 text via the existing Labelary client at several sizes (e.g. 24/40/60/100), measures the rendered cross-thickness by scanning the returned PNG for the ink bounding box, and asserts the ratio is stable. The pinned constant (and any anchor offset) lives as `static final` fields in the renderer with a comment citing the measurement. This keeps "pixel-perfect" honest: the number is measured, not guessed, and a test guards it. The print path itself makes **no** network call — it uses the baked-in constant.

### Editor canvas (`static/js/editor/canvas.js`, `groupops.js`, `toolbar.js`/`main.js`)

- **State:** store `centerOnBand` as a Konva attr on the node (it's already in `NON_GEO`-style attr handling; add `'centerOnBand'` to the serialized non-geometry set).
- **Toggle:** `centerSelectedOnBand()` becomes `toggleCenterOnBand()` — flips the attr on the outermost selected node, applies/clears centering, updates the button's active state, and redraws.
- **Live centering:** a `centerNodeOnBand(node)` helper sets the node so its bbox center x = `bandWidthPx/2`. Call it: on toggle-on, after any `transformend`/`dragend` of a centered node, after property edits that change size/rotation/content, and in `applyLayout`. 
- **Drag lock:** while a node is centered, constrain horizontal drag. Implement with Konva `dragBoundFunc` on the node: keep `pos.x` fixed at the value that centers the bbox, allow `pos.y` to change. (Set/clear the `dragBoundFunc` when the flag toggles.)
- **Serialize:** `nodeToElement` includes `centerOnBand: true` when set; `buildNode`/`makeLeaf` restore it and apply centering + drag lock on load.
- **Button state:** `#btn-center` shows an active class when the current selection is centered; clears when selection changes.

> Editor centering uses the bbox like today; the **renderer** is authoritative for print. The canvas is a faithful preview, and the Labelary preview path (shared resolver) shows the exact ZPL result, so "what you preview is what prints" still holds.

---

## Edge cases

| Case | Behaviour |
|------|-----------|
| No flag (default) | Byte-identical ZPL and identical editor behavior to today (R10). |
| Flag on a group | Group centered by its cross-extent; children keep their internal layout. |
| Toggle off | Element stays at its current centered x as a normal value; horizontal drag re-enabled. |
| Element wider than band | Centering still applies (`cx` may be negative); matches editor. |
| Rotation changed while centered | Editor re-centers live; renderer picks the right per-rotation path. |
| Mixed sizes centered | Each centered independently by exact metrics ⇒ no fan-out (the bug). |
| Existing saved templates | Load unchanged; `centerOnBand` absent ⇒ off. |

---

## Testing

- **Renderer unit tests (`TemplateZplRendererTest`):**
  - non-rotated centered text ⇒ contains `^FB<PW>,1,0,C` and `^FO0,`.
  - rotated centered text ⇒ `^FO<computed>,` with the calibrated thickness.
  - centered image/shape ⇒ `^FO<(PW−crossDim)/2>,`.
  - centered group ⇒ centered origin by cross-extent.
  - **back-compat:** element without the flag ⇒ exact current ZPL (regression-guard existing assertions).
- **Calibration test:** measures `FONT0_CELL_RATIO` via Labelary and asserts stability across sizes (tolerance ±1 dot), pinning the constant.
- **Editor:** run-the-app checklist (no JS test runner): toggle on/off, live re-center on size/rotation change, horizontal drag locked, save+reload round-trip, and Labelary preview shows mixed-size rotated elements mutually centered.

---

## Out of scope

- Centering on the **length** axis (only band width).
- Distributing/aligning multiple elements relative to each other (only band-relative centering).
- Per-glyph exact ink centering for rotated text (cell-level centering is the standard definition; ink-within-cell is a font characteristic, uniform across elements, no size-dependent drift).
- Changing the non-template (legacy) print path.

# Editor Font-0 Text Metrics — Design Spec

**Date:** 2026-06-08
**Status:** Approved
**Feature area:** Template designer editor canvas (`static/js/editor/canvas.js`) + a small renderer alignment

---

## Overview

The template-editor canvas measures text with the **Poppins** web font, but wristbands print with **Zebra font 0** (a proportional CG-Triumvirate-like face). The two have different letter widths, so any position the user sets by dragging or snapping is stored in Poppins-space and **prints shifted** — most visibly for 90°/270°-rotated text, where the mismatch shows on **both** axes (the text *length* runs along the band, the *thickness* across it).

The fix is to make the editor size text using the **same font-0 model the working basic-wristband path already uses**. `ZplGeneratorService` (the legacy `/print` layout, which prints correctly) models font 0 with two calibrated rules:

```java
static final double CHAR_ADVANCE_RATIO = 0.46; // ^A0 proportional advance ÷ font size, calibrated vs Labelary
lineExtent(chars, fontSize) = chars * fontSize * CHAR_ADVANCE_RATIO;  // length along the text
// thickness across the band = fontSize
```

The editor will mirror this: a text element's box becomes **length = `chars × fontSize × 0.46`**, **thickness = `fontSize`**. Then the on-canvas footprint equals the printed footprint, so snap-to-center, the 25%/75% quarters, free drag, **and the Y axis** all line up with the print. The ZPL renderer already places `^FO` at the stored bbox top-left, so *what you see prints*.

### Non-goals / guardrails

- **No change** to `ZplGeneratorService`, the basic wristband, the ZPL bytes sent to printers, or existing saved templates' rendered output. This is **editor-canvas geometry only** (plus one small renderer-constant alignment, below).

---

## Requirements

> **Update (after testing):** a flat average ratio (`0.46`, as `ZplGeneratorService` uses for its
> group-centred layout) proved too coarse for centring independent lines — on real names it was off
> by up to **52 dots** because it ignores per-letter widths. R1 below now uses a **measured
> per-character font-0 advance table** (`FONT0_ADV`), validated to **±3 dots** on real strings.

| # | Requirement |
|---|-------------|
| R1 | A text element's on-canvas **length** (reading direction) is `round(Σ font0Advance(char) × fontSize)` dots, using a **measured per-character font-0 advance table** (`FONT0_ADV`, normalised to font size; unmapped chars fall back to `0.5`). The table is measured from the printer's own rendering (Labelary, 12 dpmm) and validated to ±3 dots on real names. |
| R2 | A text element's on-canvas **thickness** (cross direction) is `fontSize` dots (line height = 1). |
| R3 | The metrics are recomputed whenever the element is created, its **text/binding/sampleText** changes, or its **fontSize** changes (incl. via resize). |
| R4 | `chars` is the length of the **displayed** string: the static `value` for STATIC_TEXT, or the resolved sample/placeholder for data-bound TEXT (the same string the canvas shows today). |
| R5 | The element's **bounding box** (used for snapping, the transformer, serialization, rotation) equals this font-0 box — i.e. all existing geometry flows from the new box automatically. |
| R6 | Canvas text renders in the on-screen equivalent of the **printer's resident font 0** (Zebra `^A0` = CG Triumvirate, a Helvetica/Arial-class face): `font-family: 'Helvetica, Arial, sans-serif'`. There is **no bundled font file** to reuse — `ZplGeneratorService` prints with `^A0` (the device font), so Helvetica/Arial is the faithful on-screen stand-in. Not Poppins. |
| R7 | The center-on-band renderer's **rotated-text** centering is aligned to the same model — cross extent = `fontSize` — matching `ZplGeneratorService.centerX(fontSize)`, replacing the separate `0.94`/`leftMargin` calibration. |
| R8 | No change to the basic-wristband path, the printed ZPL for existing templates, or `ZplGeneratorService`. |

---

## Architecture

### Editor canvas (`static/js/editor/canvas.js`)

**The metric table:**
```js
// Per-character font-0 advance ÷ fontSize, measured from the printer's rendering (Labelary).
const FONT0_ADV = { ' ': 0.296, 'A': 0.553, 'I': 0.273, 'M': 0.753, 'W': 0.811, /* … ~95 entries … */ };
const FONT0_DEFAULT_ADV = 0.5;
function font0AdvanceUnits(str) { let u = 0; for (const ch of str) u += (FONT0_ADV[ch] ?? FONT0_DEFAULT_ADV); return u; }
```

**Text node creation (`makeLeaf`, TEXT/STATIC_TEXT branch):** create the `Konva.Text` with the printer-like face; the box + glyph fit are applied by `applyTextMetrics`:
```js
// Helvetica/Arial = on-screen stand-in for the printer's resident font 0 (^A0 / CG Triumvirate).
node = new Konva.Text({ ...base, fontFamily: 'Helvetica, Arial, sans-serif', fill: '#111' });
// fontSize + box + fit set by applyTextMetrics below
```

**New helper `applyTextMetrics(node)`** — the single source of truth for a text node's box. It measures the natural glyph run, then **scales the glyphs to exactly fill the font-0 box** so the canvas text occupies the printed footprint (same result), not just an accurate empty box:
```js
function applyTextMetrics(node) {
  node.scaleX(1); node.scaleY(1);
  node.width('auto'); node.height('auto');          // measure natural glyph run
  const fsDots = p2d(node.fontSize());
  const units = font0AdvanceUnits(textOf(node) || '');
  const targetWpx = d2p(Math.max(1, Math.round(units * fsDots)));          // length (per-char table)
  const targetHpx = node.fontSize();                                      // thickness = fontSize
  const sx = targetWpx / node.width();   // condense Helvetica (~0.5/ch) to the font-0 box (0.46/ch)
  const sy = targetHpx / node.height();  // ≈ 1 (lineHeight 1 ⇒ natural height = fontSize)
  node.scaleX(sx); node.scaleY(sy);
  node.setAttr('fitScaleY', sy);          // remembered so resize can separate gesture from fit
}
```
`getClientRect` now returns the scaled box = the font-0 footprint, and the glyphs fill it (lightly condensed, ~8%). All geometry (snap, transformer, serialize, rotation) flows from this.

Call `applyTextMetrics` at the end of `makeLeaf` for text nodes (after `node.text(textOf(node))`), and in `applyProp` after any change to `fontSize` / `value` / `sampleText` / `binding`.

**Resize handling (`wireLeaf` transformend, Text branch):** today it bakes `scaleX` into `fontSize` assuming scale is purely the resize gesture. With scale-to-fit, `scaleX` also carries the condense factor — so the gesture is read from **`scaleY`** instead (which is only the resize, since the fit `sy ≈ 1`). Then re-fit:
```js
// Text branch of transformend:
const gesture = node.scaleY() / (node.getAttr('fitScaleY') || 1);
node.fontSize(Math.max(2, node.fontSize() * gesture));
applyTextMetrics(node);   // resets scale, re-derives box + fit from the new fontSize
```
This keeps thickness = `fontSize` and length = the model after every resize.

**Everything else is unchanged** — `bboxTLDots`, `placeAtBboxTL`, snapping, serialization, rotation all read `getClientRect`, which now returns the font-0 box.

### Renderer alignment (`editor/service/TemplateZplRenderer.java`)

Replace the rotated-text centering calibration (the `FONT0_CELL_RATIO = 0.94f` + per-orientation `leftMargin`) with the `ZplGeneratorService` model: cross extent = `fontSize`, centered origin `(bandWidth − fontSize) / 2` — identical to `ZplGeneratorService.centerX`. This keeps the editor, the basic path, and centered template text all on one model. Non-rotated centered text keeps using `^FB,…,C` (unchanged). Update the two rotated-centering unit tests to the new expected `^FO`.

> **Why drop 0.94:** it was an independent ink measurement that disagrees ~6% with the proven `ZplGeneratorService` model. Consistency with the working path (and the now-accurate editor) matters more than the sub-dot ink nuance.

---

## Edge cases & known limits

| Case | Behaviour |
|------|-----------|
| Empty text | `chars` clamps to ≥1 so the box never collapses to zero. |
| Data-bound text, design vs print | The editor box uses the **sample/placeholder** length (best estimate at design time). At print the real data length may differ; for **centered** data fields the renderer re-centers from the real value (center-on-band), so they stay centered. Non-centered data fields whose real length differs from the sample will shift — inherent (true length is unknown at design time). |
| Resize | Resizing changes `fontSize`; length/thickness re-derive from the model (the box is never a free rectangle for text). |
| Non-text elements | Unchanged — barcode/image/shape already use explicit dot boxes. |
| Existing templates | Load and render unchanged; only the editor's *measurement* of text changes, which affects new edits, not stored output. |

---

## Testing

- **Renderer unit tests (`TemplateZplRendererTest`):** update the two rotated centered-text cases to the `(bandWidth − fontSize)/2` model; keep the non-rotated `^FB` and back-compat (no-flag byte-identical) cases. Run `./mvnw test -Dtest=TemplateZplRendererTest`.
- **Editor (no JS test runner):** `node --check` each changed file. Run-the-app checklist: place two 270° texts of different font sizes, snap one to the 25% quarter + Y-center and the other to X-center + Y-center, **Preview**, and confirm the Labelary preview matches the canvas on both axes (centered text centered; quarter at the quarter). Compare a static text block's canvas footprint to its preview.

---

## Out of scope

- Per-character font-0 width tables (we use the same single average ratio the backend uses).
- Changing `ZplGeneratorService` or the basic wristband.
- Making data-bound text exact when real data length differs from the design-time sample (inherent; centered fields are handled by the renderer).
- Barcode symbol-width accuracy (unchanged; pre-existing limitation).

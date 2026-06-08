# Wristband Template Designer

A visual drag-and-drop designer for creating custom wristband layouts, plus the backend that
stores them and renders them to ZPL. This document is the single reference for the whole
feature — its goals, architecture, data model, API, and roadmap.

> **Status:** All three plans implemented — persistence & catalog API (Plan 1), ZPL rendering /
> assets / previews / `/print` integration (Plan 2), and the Konva.js drag-and-drop editor UI
> (Plan 3). See [Roadmap](#roadmap) and [Using the editor](#using-the-editor).

---

## Why

Until now the service printed a single **hardcoded** layout (logo → barcode → text → logo),
built by `ZplGeneratorService` from `WristbandProperties`. The designer lets staff create their
own layouts: a fixed wristband canvas, a toolbox of draggable blocks (name, event, association,
barcode, static text, logo, shapes), positioned/resized/rotated freely, saved, previewed, and
selectable from the Symfony event app — including a different template per project type.

## Key decisions

| Decision | Choice |
|---|---|
| Data blocks | Fixed set (event, first/last/full name, association, barcode) + static extras (free text, logo, shapes). No user-defined field names. |
| Colour support | **Preview background only** — colour tints the preview to judge contrast on coloured stock. Print stays monochrome (thermal). Default white. |
| Symfony integration | Catalog + template ID — Symfony lists templates (filterable by project type), can fetch a PNG preview, and prints by passing a `templateId`/`slug`. |
| Project types | Optional, **non-unique** `projectType` tag — multiple templates can share a type; the catalog API filters by it. |
| Storage / rendering | Declarative JSON element model is the source of truth; the generated ZPL (with placeholders) is also saved as a snapshot for export/audit. |
| Editor stack | Konva.js + vanilla JS, no build step, served as a new admin page inside the existing app, behind the existing admin login. |
| Rotation | Quantized to 0 / 90 / 180 / 270 (the only orientations ZPL supports). |
| Legacy layout | Stays as the default — `/print` without a `templateId` uses the current `ZplGeneratorService`. Zero breaking change. |

## Architecture

All designer/editor code lives in a dedicated feature package
`com.stup.wristbandprinter.editor`. Because the app's `@SpringBootApplication` sits at
`com.stup.wristbandprinter`, this sub-package is auto-scanned — no extra Spring/JPA config.

```
com.stup.wristbandprinter.editor
├── domain        Canvas, ElementType, DataBinding, ShapeType, TemplateElement,
│                 TemplateDefinition, UpsertTemplateRequest, Template{Summary,Detail}Response
├── persistence   WristbandTemplateEntity (jsonb), WristbandTemplateRepository
├── service       TemplateService            (Plan 2: TemplateZplRenderer, TemplateAssetService, GfImageEncoder)
└── controller    TemplateController
```

Front-end editor assets (Plan 3) will live under `src/main/resources/static/`.

## Data model

Templates are persisted in the `wristband_template` table (Flyway `V3`), with the layout stored
as a `jsonb` `definition` column and a `generated_zpl` snapshot column (null until Plan 2).

`definition` is a `TemplateDefinition`:

```json
{
  "canvas": { "widthDots": 203, "lengthDots": 2233, "dpi": 300 },
  "elements": [
    {
      "id": "el-1",
      "type": "TEXT",
      "x": 40, "y": 120, "widthDots": 28, "heightDots": 600, "rotation": 90,
      "binding": "FULL_NAME",
      "fontSize": 28,
      "font": "0"
    }
  ]
}
```

Element common fields: `id`, `type`, `x`, `y`, `widthDots`, `heightDots`,
`rotation` ∈ {0, 90, 180, 270}. All coordinates are in **printer dots** (DPI-independent).

| `type` | Extra fields |
|---|---|
| `TEXT` (data-bound) | `binding` ∈ {EVENT_NAME, FIRST_NAME, LAST_NAME, FULL_NAME, ASSOCIATION_NAME}, `fontSize`, `font` |
| `STATIC_TEXT` | `value`, `fontSize`, `font` |
| `BARCODE` | `binding` = BARCODE_VALUE, `symbology` (e.g. CODE128), `showHumanReadable` |
| `IMAGE` | `assetId` (uploaded logo) |
| `SHAPE` | `shape` ∈ {BOX, LINE}, `thicknessDots` |
| `GROUP` | `children` (items or groups), `stackDirection` (LENGTH/WIDTH), `marginDots`, `crossAlign` (START/CENTER/END) |

> Groups stack their children along `stackDirection` with `marginDots` between them, aligned on the
> cross-axis by `crossAlign`; the renderer flattens groups to absolute positions. Data-bound and
> static blocks may carry an optional `sampleText` used by the editor canvas and the live preview.

## API

Editor endpoints use the **admin cookie**; catalog/preview/print use the existing **X-API-Key**
(so Symfony is unaffected). `/api/**` is already `authenticated()` in `SecurityConfig`.

| Method | Path | Purpose | Status |
|---|---|---|---|
| `POST` | `/api/templates` | Create a template → `201 + detail` | ✅ Plan 1 |
| `PUT` | `/api/templates/{id}` | Update a template → `200` / `404` | ✅ Plan 1 |
| `GET` | `/api/templates` | Catalog list (`id, slug, name, projectType, updatedAt`); `?projectType=` filters | ✅ Plan 1 |
| `GET` | `/api/templates/{id}` | Full definition → `200` / `404` | ✅ Plan 1 |
| `DELETE` | `/api/templates/{id}` | Soft-delete → `204` / `404` | ✅ Plan 1 |
| `GET` | `/api/templates/{id}/preview?color=white` | PNG with sample data (Symfony thumbnails) | ✅ Plan 2 |
| `POST` | `/api/templates/{id}/preview` | PNG with supplied data (editor live preview) | ✅ Plan 2 |
| `POST` | `/api/templates/assets`, `GET .../assets/{id}` | Upload / fetch logo | ✅ Plan 2 |
| `POST` | `/api/wristbands/print` (+ optional `templateId`) | Print via a template; absent → legacy layout | ✅ Plan 2 |

## Symfony flow

1. `GET /api/templates?projectType=festival` → list of `{ id, slug, name, projectType }`.
2. Optionally `GET /api/templates/{id}/preview` → PNG thumbnail (same Labelary mechanism as the
   jobs slide-in).
3. User picks a template.
4. Symfony calls `POST /api/wristbands/print` with `templateId` + the five data fields.

## Roadmap

| Plan | Scope | Status |
|---|---|---|
| **1 — Persistence & Catalog/CRUD API** | Domain model, `jsonb` storage, Flyway `V3`, `TemplateService`, `/api/templates`, tests | ✅ Done |
| **2 — Rendering, assets, preview & print** | `GfImageEncoder`, `TemplateAssetService` (logo→`^GF`), `TemplateZplRenderer`, save-time ZPL snapshot, preview PNG endpoints, `/print` `templateId` routing | ✅ Done |
| **3 — Editor UI** | Konva.js drag-and-drop page (`template-editor.html`), toolbox, properties panel, colour preview, save/export | ✅ Done |

## Using the editor

Open `/template-editor.html` (linked from the jobs page header; admin login required). Drag blocks
from the left toolbox onto the wristband canvas, position/resize/rotate them (rotation snaps to
0/90/180/270), and edit each element in the right-hand properties panel. Set the template name,
optional project type, stock colour, and canvas size/DPI in the top bar, then **Save**. **Preview**
opens a colour-tinted PNG (rendered by Labelary); **Export ZPL** downloads the saved snapshot;
**Open template…** reloads any saved template.

**Grouping & alignment:** shift-click to multi-select, then **Group** to stack items (set
direction, margin, and cross-alignment in the properties panel); groups can be nested. Data blocks
accept **sample text** that shows on the canvas and drives the preview; static blocks take their
text in the properties panel.

**Snap guides:** tick **Snap to center** and/or **Snap to quarters** in the toolbox to make a
dragged element's centre snap to the band's 50% (pink) and/or 25%/75% (slate) lines. The guides are
invisible until an axis snaps, then a dashed line flashes in; each axis snaps independently. The
toggles are a session preference (not saved).

**Print-accurate text (font-0 metrics):** the editor sizes every text block with the **same font-0
model the printer uses** (mirroring `ZplGeneratorService`): length along the text = `chars ×
fontSize × 0.46`, thickness = `fontSize`, drawn in a Helvetica/Arial face (the on-screen stand-in
for the printer's resident font `^A0`). So a text block on the canvas occupies the footprint it
will print, and **snap-to-center, the 25%/75% quarters, free placement, and the Y axis all match
the print** — for upright and rotated text alike. Data-bound fields use the **sample** length at
design time; their real printed length depends on the data (centred data fields are re-centred from
the real value by the renderer).

**Center on band:** the **Center on band** button is a persistent toggle (it shows an active
outline when on). While on, the selected element/group is kept centred across the band **width**
and its horizontal drag is locked (vertical still moves); the flag is saved with the template. With
the font-0 editor metrics above, snapping already centres accurately; this toggle additionally lets
the renderer re-centre at print time (useful for data-bound text whose real length isn't known at
design time). Non-rotated text centres via a ZPL field block (`^FB`); rotated text and
images/shapes centre on the `fontSize`/known dimensions (the same model as the basic wristband).

> **Center-on-band limitation:** a **barcode** is centred on its stored box, not on the exact
> printed symbol width (the renderer doesn't compute the rendered bar width), so a centred barcode
> may sit a few dots off true centre. Centre text/images/shapes for pixel-accurate results.

> **Known limitation:** barcodes show as a placeholder rectangle on the editor canvas (the real
> symbol appears only in the PNG preview and on the printer), and the renderer currently emits
> Code 128 regardless of the selected symbology — CODE39/QR support is a planned renderer
> follow-up.

## References

- Design spec: [`docs/superpowers/specs/2026-06-02-wristband-template-designer-design.md`](superpowers/specs/2026-06-02-wristband-template-designer-design.md)
- Plan 1: [`docs/superpowers/plans/2026-06-02-template-designer-1-persistence-api.md`](superpowers/plans/2026-06-02-template-designer-1-persistence-api.md)

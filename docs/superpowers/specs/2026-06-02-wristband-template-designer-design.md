# Wristband Template Designer — Design

**Date:** 2026-06-02
**Status:** Approved (design phase)

## 1. Problem & Goal

Today the service prints one **hardcoded** wristband layout (logo → barcode → text → logo,
vertically centered), built by `ZplGeneratorService` from `WristbandProperties`. The five data
fields are fixed: event name, first name, last name, association, barcode value. The Symfony
event app calls `POST /api/wristbands/print` with those fields.

We want a **visual drag-and-drop template designer** so staff can design their own wristband
layouts: a fixed wristband canvas with a toolbox of draggable component blocks (name, event,
association, barcode, static text, logo, shapes), positioned, resized, and rotated freely.
Templates are saved (including their generated ZPL), previewed, and selectable from Symfony per
project type.

## 2. Key Decisions (resolved during brainstorming)

| Decision | Choice |
|---|---|
| Data blocks | **Fixed set + static extras** — the 5 existing data fields, plus free text, logo, shapes, barcode. No user-defined field names. |
| Color support | **Preview background only** — color tints the preview to judge contrast on colored stock. Print output stays monochrome (thermal). Default white. |
| Symfony integration | **Catalog + template ID** — Symfony lists templates (filterable by project type), can fetch a PNG preview, and prints by passing a `templateId`/`slug`. |
| Project types | **Optional, non-unique `projectType` tag** — multiple template layouts can share a project type; catalog API can filter by it. |
| Storage / rendering | **Option C (hybrid)** — declarative JSON model is the source of truth; the generated ZPL (with placeholders) is also saved as a snapshot for export/audit. |
| Editor stack | **Konva.js + vanilla JS**, no build step, served as a new admin page inside the existing Spring Boot app, behind the existing admin login. |
| Rotation | **Quantized to 0 / 90 / 180 / 270** — a hard ZPL constraint (orientations N/R/I/B). The editor surfaces only these four. |
| Legacy layout | **Stays as the default** — `/print` without a `templateId` uses the current `ZplGeneratorService`. Zero breaking change to existing Symfony calls. |

## 3. Architecture Overview

```
┌──────────────────────────┐         ┌─────────────────────────────────────────┐
│ Symfony event app        │         │ Spring Boot Wristband Printer Service     │
│                          │  HTTP   │                                           │
│  • GET /api/templates    │────────▶│  TemplateController (CRUD + catalog)      │
│    (filter projectType)  │         │  TemplateService ── TemplateRepository    │
│  • GET .../preview (PNG) │◀────────│  TemplateZplRenderer ── LabelaryPreview   │
│  • POST /print           │         │  TemplateAssetService (logo → ^GF)        │
│    (templateId + data)   │         │                                           │
└──────────────────────────┘         │  Admin browser:                           │
                                      │   template-editor.html + Konva.js         │
                                      │   (behind admin cookie auth)              │
                                      └─────────────────────────────────────────┘
                                                 │
                                                 ▼  PostgreSQL (Flyway)
                                   wristband_template, template_asset
```

The designer lives inside the current app: one deployment, reusing the existing admin auth
(`AuthCookieService`), API-key auth (`ApiKeyAuthFilter`), and Labelary preview plumbing.

## 4. Template Data Model

### 4.1 Database (Flyway `V3__create_wristband_templates.sql`)

Consistent with the existing soft-delete pattern (`V2__add_deleted_flag.sql`).

`wristband_template`:
- `id` UUID primary key
- `slug` TEXT unique, stable — the identifier Symfony references
- `name` TEXT
- `project_type` TEXT nullable — optional, non-unique filter tag
- `canvas_width_dots` INT, `canvas_length_dots` INT, `dpi` INT
- `default_preview_color` TEXT default `white`
- `definition` JSONB — the element model (see 4.2)
- `generated_zpl` TEXT — the saved ZPL snapshot (with `${binding}` placeholders)
- `created_at`, `updated_at` TIMESTAMP
- `deleted` BOOLEAN default false

`template_asset`:
- `id` UUID primary key
- `name` TEXT
- `png` BYTEA — uploaded image bytes
- `width` INT, `height` INT (native pixel size)
- `created_at` TIMESTAMP

Assets are stored in the DB (not the filesystem) so they survive container restarts under the
existing Docker/Postgres deployment.

### 4.2 Element model (`definition` JSON)

```json
{
  "canvas": { "widthDots": 203, "lengthDots": 2233, "dpi": 300 },
  "elements": [
    {
      "id": "el-1",
      "type": "text",
      "x": 40, "y": 120, "widthDots": 28, "heightDots": 600, "rotation": 90,
      "binding": "fullName",
      "fontSize": 28,
      "font": "0"
    }
  ]
}
```

Common element fields: `id`, `type`, `x`, `y`, `widthDots`, `heightDots`,
`rotation` ∈ {0, 90, 180, 270}.

Element types:
- **`text`** (data-bound): `binding` ∈ {`eventName`, `firstName`, `lastName`, `fullName`,
  `associationName`}, `fontSize`, `font`.
- **`staticText`**: `value`, `fontSize`, `font`.
- **`barcode`**: `binding` = `barcodeValue`, `symbology` (default `CODE128`),
  `showHumanReadable`.
- **`image`**: `assetId` (references `template_asset`); defaults to the bundled STUP logo.
- **`shape`**: `shape` ∈ {`box`, `line`}, `thicknessDots`.

**Coordinates are stored in printer dots** (DPI-independent). The editor renders at a screen
scale factor (dots → CSS px); positions never depend on screen resolution.

## 5. ZPL Rendering Engine

New `TemplateZplRenderer`:
- Input: a template `definition` + a data map (the 5 fields) + DPI.
- Walks `elements` and emits `^FO x,y` followed by the right command per type:
  - text / staticText → `^A0<orientation>,h,h` + `^FD…^FS` (orientation N/R/I/B from `rotation`)
  - barcode → `^BC<orientation>,height,hri,…` / `^BQ` for QR + `^FD…^FS`
  - image → `^GF…` via `TemplateAssetService` (generalized `LogoConversionService`)
  - shape → `^GB…` (box/line)
- Substitutes `binding` values from the data map at render time. `fullName` = first + last.
- Reuses the existing `sanitize()` logic (strips `^` and `~`) on all user data.

On **save**, the renderer runs with `${binding}` placeholders to produce `generated_zpl`
(the snapshot). On **print/preview**, it runs with real (or sample) data.

The existing `ZplGeneratorService` is unchanged and remains the legacy default path.

## 6. Logos / Assets

- `POST /api/templates/assets` (multipart PNG) → stores bytes in `template_asset`, returns
  `assetId`.
- `TemplateAssetService` generalizes the current `LogoConversionService` to convert any stored
  asset to a `^GF` graphic field at a target size for a given element.
- The bundled `stup-logo.png` remains available as a default image element.

## 7. API Surface

Editor endpoints use the **admin cookie**; catalog/preview/print use the existing **X-API-Key**
(so Symfony is unaffected by auth).

| Method | Path | Purpose | Auth |
|---|---|---|---|
| `POST` | `/api/templates` | Create template | admin |
| `PUT` | `/api/templates/{id}` | Update template (re-generates ZPL snapshot) | admin |
| `DELETE` | `/api/templates/{id}` | Soft-delete | admin |
| `POST` | `/api/templates/{id}/duplicate` | Clone a template | admin |
| `GET` | `/api/templates?projectType=…` | Catalog list (`id, slug, name, projectType, updatedAt`) | API key |
| `GET` | `/api/templates/{id}` | Full definition | API key |
| `GET` | `/api/templates/{id}/preview?color=white` | PNG with **sample** data (Symfony thumbnails) | API key |
| `POST` | `/api/templates/{id}/preview` | PNG with **supplied** data (editor live preview) | admin |
| `POST` | `/api/templates/assets` | Upload logo | admin |
| `GET` | `/api/templates/assets/{id}` | Fetch asset | admin |

**Print integration:** `POST /api/wristbands/print` gains an optional `templateId` (or `slug`).
- Present → render via `TemplateZplRenderer` using the named template.
- Absent → legacy `ZplGeneratorService` (current behaviour preserved).

Preview PNGs are produced by the existing `LabelaryPreviewService`, exactly like the jobs
slide-in (`/api/wristbands/jobs/{id}/preview`), so Symfony renders them the same way:
fetch blob → object URL → `<img>`.

## 8. Editor UI

New `template-editor.html` + `js/template-editor.js`, Konva.js vendored as a static file,
behind the admin login (reusing `login.html` / `AuthCookieService`).

Layout:
- **Left — toolbox:** draggable blocks (Name, Event, Association, Barcode, Static text, Logo,
  Box, Line). Copy-drag: dropping creates a new Konva node with a unique element id; the same
  block may be added multiple times.
- **Center — canvas:** the wristband at its real aspect ratio, background tinted by the selected
  preview color. Konva `Transformer` provides resize/rotate handles; drag to position; snapping
  to edges/center.
- **Right — properties panel:** x / y, size, rotation (0/90/180/270 only), font size, data
  binding, barcode symbology, asset selection.
- **Top bar:** name, projectType, canvas size + DPI, preview-color selector, **Save**,
  **Render preview** (Labelary PNG, same mechanism as the jobs slide-in), **Export ZPL**.

The canvas serializes to/from the `definition` JSON. Reopening a template rebuilds the nodes
losslessly from the model.

## 9. Symfony Flow

1. `GET /api/templates?projectType=festival` → list of `{ id, slug, name, projectType }`.
2. For each, optionally `GET /api/templates/{id}/preview` → PNG thumbnail (rendered like the
   slide-in).
3. User picks a template.
4. Symfony calls `POST /api/wristbands/print` with `templateId` + the five data fields.

## 10. Testing (TDD)

- **`TemplateZplRenderer`** — golden-ZPL unit tests per element type (text, static, barcode,
  image, shape) and per rotation; placeholder vs. real-data substitution.
- **`TemplateController`** — CRUD, catalog filtering by `projectType`, soft-delete, validation.
- **`TemplateAssetService`** — PNG → `^GF` conversion (mirrors `LogoConversionServiceTest`).
- **Integration** — save a template, then render it via Labelary (Labelary mocked, matching
  `LabelaryPreviewServiceTest` / `WristbandIntegrationTest` style).
- **Print integration** — `/print` with and without `templateId` (legacy path still works).
- Editor JS verified manually / lightweight; no heavy front-end test harness in this phase.

## 11. Out of Scope (YAGNI)

- Arbitrary rotation angles (ZPL supports only the four orientations).
- User-defined field names / dynamic data schema.
- Multi-color *printing* (thermal hardware is monochrome).
- Template version history / audit log beyond `updated_at`.
- Real-time collaborative editing.
- Migrating the legacy fixed layout into a seeded "default" template (can be a later follow-up;
  the legacy path stays as-is for now).

## 12. Risks & Assumptions

- **Assumption:** the Zebra setup is monochrome thermal — confirmed by current ZPL usage.
- **Risk:** ZPL layout fidelity vs. Labelary preview — mitigated by previewing the *generated*
  ZPL (the same artifact that prints), and by golden-ZPL tests.
- **Risk:** long real data overflowing a fixed-size element at print time — the declarative model
  lets the renderer center/fit per actual data; flag overflow handling during implementation.
- **Dependency:** Labelary API availability for previews (already a dependency today).

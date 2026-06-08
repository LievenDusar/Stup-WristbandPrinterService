# Electricity wristband — design spec

## Goal

Add a second, structurally-different wristband type printable via `/api/wristbands`:
the **electricity wristband**, handed to guests who are allowed to use the powerboxes
on the campsite. It is attached to a power plug to identify who has permission. Unlike
the existing (default/staff) band, it carries no barcode or personal name — it carries
a "permission" line, a fill-in-the-blank line for an association name, an event logo,
and the event name.

The **default band is not modified** — its request DTO, validation, layout service,
and ZPL generator stay exactly as they are. Symfony will call the new band from a
different page/flow than the existing one.

This spec also covers a new admin "wristband gallery" page so staff can see, at a
glance, what each band type looks like (thumbnail → click for a larger preview).

## Non-goals / explicitly out of scope

- Changing the default band in any way (layout, fields, validation, ZPL).
- The template designer / editor (per the maintainer: "the editor logic can be
  ignored for the moment").
- Letting the gallery preview with user-entered data — it renders fixed sample data
  per band type (see "Gallery screen").
- Multi-symbology barcodes / other known limitations — unrelated to this change.

## Architecture

### Isolated generators, thin shared catalog

The two bands are structurally different (one has a barcode + person identity; the
other has an icon, a fill-in line, and a second logo). Rather than forcing them
through one shared "rendering strategy" interface, each band type owns its **request
DTO, its data record, and its ZPL generator** end to end — mirroring how
`ZplGeneratorService` already stands alone today:

- `ElectricityWristbandPrintRequest` — new, narrow request DTO.
- `ElectricityWristbandData` — new small record holding the resolved fields.
- `ElectricityZplGeneratorService` — new generator, parallel to `ZplGeneratorService`,
  owns the electricity layout entirely (own config block, own positioning math).

The **only** place that needs to know about *all* band types is the new gallery
screen, so a small `WristbandGalleryCatalog` component holds a list of
`{ key, displayName, sampleRequest }` entries — one per band type — that the gallery
controller iterates to build thumbnails via the existing Labelary preview pipeline.

Adding a third band type later means: one new DTO, one new generator, one new
controller mapping, one new catalog entry. Nothing about the existing two changes.

### Making the job system type-aware (additive only)

The job queue/persistence/SSE/history system (`PrintQueueService`, `PrintJob`,
`PrintJobEntity`, the `print_jobs` table, `WristbandZplResolver`) is currently
hardcoded to `WristbandPrintRequest` with denormalized columns and no discriminator.
To route both band types through the **same** unified job system (so operators get one
job list, SSE stream, reprint/cancel/history for both), we generalize this layer
*additively*:

- A new minimal **sealed interface** `PrintableRequest` (permits `WristbandPrintRequest`,
  `ElectricityWristbandPrintRequest`) exposing `eventName()`, `printerId()`,
  `wristbandType()`. `WristbandPrintRequest` gains an `implements` clause only — no
  change to its fields, validation, or behavior.
- `PrintJob.request` is retyped from `WristbandPrintRequest` to `PrintableRequest`;
  `PrintQueueService.enqueue(PrintableRequest)` is generalized — its internals
  (persist-before-enqueue, per-printer queue offer, SSE broadcast, crash recovery)
  are unchanged, just no longer hardcoded to one concrete type.
- `WristbandZplResolver.resolve(PrintableRequest)` becomes the single dispatch point:
  a sealed pattern-matching `switch` routes `WristbandPrintRequest` through its
  **exact existing** code path (legacy generator or template), and
  `ElectricityWristbandPrintRequest` through the new electricity data-builder +
  generator. This mirrors how the resolver already centralizes "template vs. legacy."
- **Schema** — one additive Flyway migration (`V6__add_wristband_type.sql`):
  - adds `wristband_type VARCHAR NOT NULL DEFAULT 'DEFAULT'` (backfills existing rows
    automatically via the default),
  - relaxes `first_name`, `last_name`, `barcode_value` to nullable (electricity jobs
    leave them `NULL`).
  `event_name`, `association_name`, `printer_id` columns are already shared between
  both request shapes.
- `PrintJobEntity`/`JpaJobStore` reconstruct the correct concrete request type on
  restore, branching on the discriminator.
- `PrintJobResponse`/`PrintJobDetailResponse` gain a `wristbandType` field; the
  type-specific fields (`firstName`, `lastName`, `barcodeValue`) become nullable in
  the response shape.

Net effect: **the default band's request DTO, validation, layout service, and ZPL
generator are not touched.** Only the surrounding job-tracking plumbing becomes
type-aware, via additive, backward-compatible changes (existing rows default to
`DEFAULT` and keep working unmodified).

## Request contract

### `ElectricityWristbandPrintRequest`

New, narrow DTO — mirrors the validation style of `WristbandPrintRequest`:

| Field             | Required | Notes                                                              |
|-------------------|----------|--------------------------------------------------------------------|
| `eventName`       | yes      | e.g. `"Pukkelpop 2026"` — same semantics as the default band       |
| `associationName` | no       | when blank/absent, the band prints a dotted fill-in line instead   |
| `printerId`       | no       | same routing semantics as the default band (defaults to first)     |

No `firstName`/`lastName`/`barcodeValue`/`templateId` — this band carries no personal
identity and no template routing.

### `ElectricityWristbandData`

```java
record ElectricityWristbandData(String eventName, String associationName) {}
```
`associationName` is `null`/blank when the dotted line should print; the generator
decides dots-vs-name at render time (see Layout).

## Layout

Same physical band as the default: 300 × 3300 dots @ 300 DPI, same orientation.
Three blocks, vertically centered as one group on the long (Y) axis — directly
mirroring how the default band centers its logo→barcode→text→logo stack — with a
configurable margin between each block.

```
┌──────────────────────────────┐
│                              │
│        [STUP logo]           │  Block 1 — image, pre-rotated 180°
│                              │           (same asset + LogoConversionService
│                              │            pipeline as the default band)
│      ── block margin ──      │
│                              │
│  ⚡  Toelating ELEKTRICITEIT  ⚡│  Block 2 — "permission" group, rotated 270°:
│                              │    • icon image ×2 (before & after the text),
│   ── writing-space gap ──    │      both rendered upright/180° like a logo
│                              │    • "Toelating ELEKTRICITEIT" as ^A0B text
│   ..........................  │    • extra configurable gap (writing room)
│                              │    • dotted line OR associationName — same
│                              │      field/font/position either way (^A0B)
│      ── block margin ──      │
│                              │
│        PUKKELPOP 2026        │  Block 3 — "event" group, both 180°/inverted:
│        [Pukkelpop logo]      │    • eventName as ^A0I (inverted) text
│                              │    • event logo image, pre-rotated 180°
└──────────────────────────────┘
```

Mechanical notes:

- **Rotations** — `^A0B` (270°, "bottom up") for the permission/fill-in text, exactly
  like the default band's event/name/association block; `^A0I` (180°, "inverted") for
  `eventName`, matching the visual orientation of the pre-rotated-180° logo images
  beside/below it. Both icon instances are rendered as upright/180° images (like a
  logo), framing the 270°-rotated text — a deliberate, slightly "sideways-looking"
  juxtaposition the maintainer confirmed is intentional.
- **Dotted/association field** — a single `^A0B` text field whose *content* is either
  a run of literal `.` characters (count configurable) or the sanitized
  `associationName`. Same font size and field origin either way, so a hand-filled name
  lines up exactly where the dots were. Reuses the existing `sanitize()` approach
  (strip `^`/`~`).
- **Writing-space gap** — a dedicated, separately configurable margin between the
  "Toelating ELEKTRICITEIT" line and the dotted line (distinct from the standard
  inter-line gap), giving staff room to physically write an association name on the
  printed band.
- **Vertical centering / block lengths** — follow the existing pattern in
  `ZplGeneratorService`: compute each block's Y-extent (text via `lineExtent` /
  `CHAR_ADVANCE_RATIO`, images via their converted height), sum block extents + margins
  into a `totalHeight`, then center the whole stack with
  `topY = (lengthDots - totalHeight) / 2` and derive each block's origin from there.
- **Horizontal centering** — each block (and each multi-element line within block 2)
  is group-centered across the band width the same way the default band group-centers
  its three text lines (`groupX = (widthDots - totalXWidth) / 2`).

## Configuration

New `wristband.electricity.*` block in `WristbandProperties` (or a sibling
`@ConfigurationProperties` class — to be decided during planning, following existing
conventions), bound in `application.yml`:

- `eventLogoPath` — classpath/filesystem path to the Pukkelpop-style event logo PNG
  (config-driven per the maintainer's choice — swapped per event by ops, same
  conversion pipeline as the STUP logo: scale to band width minus margins, pre-rotate
  180°, cache the `^GF` command + height).
- `iconPath` — classpath/filesystem path to the electricity icon PNG (see "Assets").
- `margins.*` — between-block margins (logo↔permission, permission↔event) and the
  dedicated writing-space gap, mirroring `WristbandProperties.Margins`.
- `text.*` — font sizes for "Toelating ELEKTRICITEIT", the dotted/association line,
  and the event name; dot count for the fill-in line.
- Side margins / icon sizing reuse existing top-level properties (`logoSideMarginDots`,
  `widthDots`, `lengthDots`, `dpi`) where they apply identically.

## Assets needed (provided by the maintainer, not generated by this change)

- **Electricity icon** — the maintainer plans to pick a Font Awesome SVG. The existing
  `LogoConversionService` pipeline uses `ImageIO`/raster decoding (PNG/JPEG), **not**
  SVG, so the chosen icon must be exported to a high-contrast monochrome PNG before
  being dropped into `classpath:images/`. The plan should note this as a prerequisite
  step, not attempt SVG rendering.
- **Pukkelpop event logo** — a PNG, same constraints (monochrome-friendly for thermal
  print), placed at the configured `eventLogoPath`.

## Endpoints

The URL pattern is **`/api/wristbands/{type}/…`** — type slug first, action second.
All wristband-generation endpoints live under a per-type namespace; the job/stream
endpoints operate on job IDs and therefore stay type-agnostic and unchanged.

### Wristband type endpoints

| Method | URL | Notes |
|--------|-----|-------|
| `POST` | `/api/wristbands/crew/print` | **Renamed** from `/print` — Symfony must update its call URL. Same request contract (`WristbandPrintRequest`), same `202 Accepted` + `PrintJobResponse` response. |
| `POST` | `/api/wristbands/crew/preview/zpl` | Renamed from `/preview/zpl`. |
| `POST` | `/api/wristbands/crew/preview/image` | Renamed from `/preview/image`. |
| `POST` | `/api/wristbands/electricity/print` | New — accepts `ElectricityWristbandPrintRequest`, returns `202 Accepted` + `PrintJobResponse` (same shape, adds `wristbandType`). |
| `POST` | `/api/wristbands/electricity/preview/zpl` | New — returns ZPL as plain text. |
| `POST` | `/api/wristbands/electricity/preview/image` | New — returns a Labelary-rendered PNG. |

Future band types follow the same pattern: `POST /api/wristbands/{slug}/print` etc.

A short-lived `/print` alias (HTTP 301/308 redirect to `/crew/print`) may be offered
during the Symfony transition window, then removed once both sides are updated.

### Unchanged job / SSE endpoints

| Method | URL |
|--------|-----|
| `GET` | `/api/wristbands/jobs` |
| `GET` | `/api/wristbands/jobs/{jobId}` |
| `GET` | `/api/wristbands/jobs/{jobId}/preview` |
| `GET` | `/api/wristbands/jobs/stream` |
| `GET` | `/api/wristbands/jobs/{jobId}/stream` |
| `POST` | `/api/wristbands/jobs/{jobId}/reprint` |
| `POST` | `/api/wristbands/jobs/{jobId}/cancel` |
| `DELETE` | `/api/wristbands/jobs/completed` |
| `GET` | `/api/wristbands/gallery` |
| `GET` | `/api/wristbands/printers` |

Job/stream endpoints are type-agnostic: the `PrintJobResponse` gains a `wristbandType`
field so subscribers can tell which kind of job they're tracking, but the endpoint
URLs and payload shapes are otherwise unchanged.

## Gallery screen ("default bands" overview)

A new static admin page (e.g. `wristband-gallery.html`), alongside `jobs.html` /
`template-editor.html`, behind the same admin-cookie auth, linked from the nav:

- Iterates `WristbandGalleryCatalog` entries (one per registered band type — initially
  the default band and the electricity band).
- Renders a **small thumbnail** per entry by calling the existing Labelary preview
  pipeline with each entry's fixed **sample request** (e.g. the default band's existing
  Swagger example data; for electricity, a sample like `eventName: "Pukkelpop 2026"`,
  `associationName: null` → renders the dotted line).
- Clicking a thumbnail shows a **larger render of the same fixed sample** — pure
  "browse what each band looks like," no form/editable fields (keeps scope tight and
  avoids overlapping with the existing `/preview/image` tools).
- New read-only controller endpoint(s) to back this — e.g.
  `GET /api/wristbands/gallery` (list of catalog entries + metadata) and reuse of
  `LabelaryPreviewService` for the actual PNG rendering, OR simple static-sample-driven
  calls to the existing per-type preview endpoints from the front-end JS. (Exact
  wiring — server-rendered list vs. client-side calls to preview endpoints — to be
  decided in the implementation plan, following the conventions already used by
  `jobs.js`.)

## Testing

Mirroring the existing test conventions (mirrored package paths, Testcontainers
Postgres, mocked printer/Labelary):

- `ElectricityZplGeneratorServiceTest` — unit tests for layout math (block centering,
  dots-vs-association swap, rotation directives in generated ZPL), parallel to
  `ZplGeneratorServiceTest`.
- `WristbandZplResolverTest` — extend to cover the new sealed dispatch branch.
- `WristbandControllerTest` — new tests for the `/print/electricity` and
  `/preview/electricity/*` endpoints (validation, response shape).
- `PrintQueueServiceTest` / `JpaJobStoreTest` — extend to cover enqueue/persist/restore
  of `ElectricityWristbandPrintRequest` jobs, the discriminator round-trip, and that
  existing `WristbandPrintRequest` jobs continue to round-trip unchanged (regression
  coverage for the additive schema change).
- `WorkerProfileContextTest` — verify new beans carry the correct `@Profile("!worker")`
  guard (per the project's load-bearing profile convention).
- A migration test / Flyway validation that `V6__...` applies cleanly against existing
  data and defaults pre-existing rows to `wristband_type = 'DEFAULT'`.

## Documentation

Per project convention ("Docs are part of the change"): update `docs/` (likely a new
`docs/electricity-wristband.md` or a section in the existing wristband docs) describing
the new band, its endpoint contract, and its configuration — and keep this spec's
implementation plan under `docs/superpowers/plans/`.

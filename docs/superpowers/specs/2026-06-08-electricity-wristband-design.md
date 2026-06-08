# Permit wristband — design spec

## Goal

Add a second, structurally-different wristband type printable via `/api/wristbands`:
the **permit wristband**, used whenever a guest needs a physical token that identifies
what they are allowed to do or access (e.g. use a campsite power box, park in a
reserved area, access catering, etc.).

The specific permission is **not baked into the band type** — the caller passes a
`permitLabel` (e.g. `"ELEKTRICITEIT"`, `"PARKING"`, `"CATERING"`) and the band prints
**"Toelating [permitLabel]"** dynamically. This means the same endpoint and layout
serve all future permission types without code changes.

Unlike the existing crew band, the permit band carries no barcode, no personal name,
and no shift-scanning identity. It does carry:
- "Toelating [permitLabel]" — the permission being granted
- A fill-in-the-blank association line (dots, or an association name if provided)
- An optional scan code (QR or barcode), if the caller wants to link it to a record
- The event name and event logo (e.g. Pukkelpop)

The **crew band (default) is not modified** — its request DTO, validation, layout
service, and ZPL generator stay exactly as they are. Symfony will call the permit band
from a different page/flow than the existing crew band.

This spec also covers a new admin "wristband gallery" page so staff can see, at a
glance, what each registered band type looks like (thumbnail → click for a larger preview).

## Non-goals / explicitly out of scope

- Changing the crew band in any way (layout, fields, validation, ZPL).
- The template designer / editor (per the maintainer: "the editor logic can be ignored
  for the moment").
- Letting the gallery preview with user-entered data — it renders fixed sample data
  per band type (see "Gallery screen").
- **Font Awesome → PNG icon rendering** — `iconName` is accepted and stored in this
  implementation but **not rendered**. The icon printing feature is a follow-up (see
  "Future: icon rendering" below).

## Architecture

### Isolated generators, thin shared catalog

The two bands are structurally different (one has a barcode + person identity; the
other has a dynamic permit label, fill-in line, and a second logo). Rather than forcing
them through a shared rendering strategy interface, each band type owns its **request
DTO, its data record, and its ZPL generator** end to end — mirroring how
`ZplGeneratorService` already stands alone today:

- `PermitWristbandPrintRequest` — new, narrow request DTO.
- `PermitWristbandData` — new small record holding the resolved fields.
- `PermitZplGeneratorService` — new generator, parallel to `ZplGeneratorService`,
  owns the permit layout entirely (own config block, own positioning math).

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
  `PermitWristbandPrintRequest`) exposing `eventName()`, `printerId()`,
  `wristbandType()`. `WristbandPrintRequest` gains an `implements` clause only — no
  change to its fields, validation, or behavior.
- `PrintJob.request` is retyped from `WristbandPrintRequest` to `PrintableRequest`;
  `PrintQueueService.enqueue(PrintableRequest)` is generalized — its internals
  (persist-before-enqueue, per-printer queue offer, SSE broadcast, crash recovery)
  are unchanged, just no longer hardcoded to one concrete type.
- `WristbandZplResolver.resolve(PrintableRequest)` becomes the single dispatch point:
  a sealed pattern-matching `switch` routes `WristbandPrintRequest` through its
  **exact existing** code path (legacy generator or template), and
  `PermitWristbandPrintRequest` through the new permit data-builder + generator. This
  mirrors how the resolver already centralizes "template vs. legacy."
- **Schema** — one additive Flyway migration (`V6__add_wristband_type.sql`):
  - adds `wristband_type VARCHAR NOT NULL DEFAULT 'CREW'` (backfills existing rows
    automatically via the default),
  - adds `permit_label VARCHAR` (nullable — populated only for `PERMIT` jobs),
  - relaxes `first_name`, `last_name`, `barcode_value` to nullable (permit jobs leave
    them `NULL`).
  `event_name`, `association_name`, `printer_id`, `code_value`, `code_symbology`
  columns are shared between both request shapes (add the code-related columns here
  too if not already present).
- `PrintJobEntity`/`JpaJobStore` reconstruct the correct concrete request type on
  restore, branching on the discriminator.
- `PrintJobResponse`/`PrintJobDetailResponse` gain a `wristbandType` field; the
  type-specific fields (`firstName`, `lastName`, `barcodeValue`, `permitLabel`) become
  nullable in the response shape.

Net effect: **the crew band's request DTO, validation, layout service, and ZPL
generator are not touched.** Only the surrounding job-tracking plumbing becomes
type-aware, via additive, backward-compatible changes (existing rows default to
`CREW` and keep working unmodified).

## Request contract

### `PermitWristbandPrintRequest`

New, narrow DTO — mirrors the validation style of `WristbandPrintRequest`:

| Field             | Required | Notes                                                                   |
|-------------------|----------|-------------------------------------------------------------------------|
| `eventName`       | yes      | e.g. `"Pukkelpop 2026"` — same semantics as the crew band              |
| `permitLabel`     | yes      | the permission being granted, e.g. `"ELEKTRICITEIT"`, `"PARKING"`. Printed as "Toelating [permitLabel]" |
| `associationName` | no       | when blank/absent, the band prints a dotted fill-in line instead        |
| `codeValue`       | no       | string to encode as a scan code; when absent, no code block is rendered |
| `codeSymbology`   | no       | `CODE128` (default), `QR`, or `CODE39`; ignored when `codeValue` absent |
| `iconName`        | no       | Font Awesome icon name (e.g. `"bolt"`); **stored but not rendered** in this implementation — reserved for the FA→PNG follow-up |
| `printerId`       | no       | same routing semantics as the crew band (defaults to first)             |

No `firstName`/`lastName`/`barcodeValue`/`templateId` — this band carries no personal
identity and no template routing.

### `PermitWristbandData`

```java
record PermitWristbandData(
    String eventName,
    String permitLabel,       // rendered as "Toelating [permitLabel]"
    String associationName,   // null/blank → dots
    String codeValue,         // null → no scan code block
    CodeSymbology symbology   // CODE128 | QR | CODE39
    // iconName deliberately excluded: not rendered in this implementation
) {}
```

## Layout

Same physical band as the crew band: 300 × 3300 dots @ 300 DPI, same orientation.
Up to four blocks (block 3 is optional — present only when `codeValue` is supplied),
all vertically centered as one group on the long (Y) axis — directly mirroring how
the crew band centers its logo→barcode→text→logo stack.

**All inter-block gaps use a single uniform margin value** (`between-blocks`, default
≈ 60 dots / ~5 mm at 300 DPI — small and consistent, not too large). The only
*separate* spacing value is the writing-space gap *inside* block 2, which is a
different concern (physical writing room, not visual separation between blocks).

```
┌──────────────────────────────┐
│        [STUP logo]           │  Block 1 — image, pre-rotated 180°
│                              │           (same asset + LogoConversionService
│                              │            pipeline as the crew band)
│      ── between-blocks ──    │  ← same uniform margin everywhere
│                              │
│    Toelating ELEKTRICITEIT   │  Block 2 — "permission" group, rotated 270°:
│   ── writing-space gap ──    │    • "Toelating [permitLabel]" as ^A0B text
│  ..........................   │    • writing-space gap (physical handwriting room)
│                              │    • dotted line OR associationName (^A0B)
│      ── between-blocks ──    │
│                              │
│      [QR / barcode]          │  Block 3 — optional; only when codeValue present
│                              │
│      ── between-blocks ──    │  ← same uniform margin
│                              │
│      PUKKELPOP 2026          │  Block 4 — "event" group, both 180°/inverted:
│      [Pukkelpop logo]        │    • eventName as ^A0I (inverted) text
│                              │    • event logo image, pre-rotated 180°
└──────────────────────────────┘
```

**Note on icons:** the diagram omits the icon slots intentionally. When the
`iconName` → PNG rendering feature is implemented later, the first line of block 2
will become `⚡ Toelating [permitLabel] ⚡` with icon images flanking the text
(rendered upright/180° like a logo, confirmed design). The layout math must leave
room for this extension point — an `iconName` field present on the request signals
intent, and the generator should be structured so adding icon rendering in a follow-up
pass is a local change to block 2's first line only.

When `codeValue` is absent, block 3 and its surrounding margins are omitted entirely
and the three remaining blocks are re-centered as a group.

Mechanical notes:

- **Rotations** — `^A0B` (270°, "bottom up") for the permission/fill-in text, exactly
  like the crew band's event/name/association block; `^A0I` (180°, "inverted") for
  `eventName`, matching the visual orientation of the pre-rotated-180° logo images
  beside/below it.
- **`permitLabel` rendering** — sanitized (strip `^`/`~`) and rendered in a
  configurable font size. The full first line reads `"Toelating " + sanitize(permitLabel)`.
  Both parts share a single `^FD` field (not separate fields), so they print as one
  continuous string without a gap.
- **Dotted/association field** — a single `^A0B` text field whose *content* is either
  a run of literal `.` characters (count configurable) or the sanitized
  `associationName`. Same font size and field origin either way.
- **Writing-space gap** — a dedicated margin between the "Toelating [permitLabel]"
  line and the dotted line *inside* block 2, larger than `betweenBlocks`, giving staff
  room to physically write on the printed band.
- **Vertical centering / block lengths** — follow the existing pattern in
  `ZplGeneratorService`: compute each present block's Y-extent (text via `lineExtent` /
  `CHAR_ADVANCE_RATIO`, images via their converted height, scan code via its estimated
  module budget), sum block extents + margins into a `totalHeight`, then center with
  `topY = (lengthDots - totalHeight) / 2`.
- **Horizontal centering** — each block is group-centered across the band width the
  same way the crew band group-centers its three text lines
  (`groupX = (widthDots - totalXWidth) / 2`).

## Scan-code capability (general, applies to all band types)

A lightweight shared building block usable by any band type's generator:

- **`CodeSymbology` enum** — `CODE128`, `QR`, `CODE39`. Lives in `domain/` alongside
  other shared types.
- **`ScanCodeRenderer`** (or equivalent helper) — takes a value + symbology and
  returns a ZPL snippet: `^BCB` for Code 128/Code 39 (bottom-up rotation, same as the
  crew band today), `^BQN` for QR (square, centered). Extracted as a standalone
  utility so `ZplGeneratorService`, `PermitZplGeneratorService`, and any future
  generator can call it without duplicating ZPL symbology logic.
- **Size estimation** — each symbology variant provides a Y-extent estimate (dots)
  used by the vertical-centering math, matching the existing `estimateBarcodeYLength`
  pattern in `ZplGeneratorService`.

**Crew band backward compatibility** — `WristbandPrintRequest` gains an optional
`codeSymbology` field (default `CODE128`). Existing Symfony calls that omit it
continue printing Code 128 identically. The `barcodeValue` field stays required for
the crew band (scanning is always needed); `codeSymbology` is just a format override.

This also closes the known CLAUDE.md gap: *"Template renderer emits Code 128
regardless of selected symbology"* — `ScanCodeRenderer` is available for the template
renderer follow-up without further design work.

## Future: icon rendering (Font Awesome → PNG)

`iconName` is accepted and persisted in this implementation but **not rendered**. A
dedicated follow-up task should:

1. Build a `FontAwesomeIconConverter` (or equivalent) that, given an FA icon name,
   produces a high-contrast monochrome PNG in memory (via an SVG rasterizer or a
   pre-built icon sprite), then passes it through the existing `GfImageEncoder`
   pipeline to produce a `^GF` ZPL command.
2. Cache the result by icon name at startup (or lazily on first use) — same pattern
   as `LogoConversionService` caches the STUP logo.
3. Update `PermitZplGeneratorService` to include the icon `^GF` before and after
   the "Toelating [permitLabel]" text when `iconName` is present.

Until this follow-up lands, jobs with `iconName` set print without the icon — the
band is still valid and readable; it just lacks the decorative framing.

## Configuration

New `wristband.permit.*` block (sibling `@ConfigurationProperties` class or nested
class — to be decided during planning, following existing conventions), bound in
`application.yml`:

- `eventLogoPath` — classpath/filesystem path to the event logo PNG (e.g. Pukkelpop);
  swapped per event by ops without code changes (same conversion pipeline as the STUP
  logo: scale to band width minus side margins, pre-rotate 180°, cache `^GF` + height).
- `margins.betweenBlocks` — **single uniform value** applied between every block pair;
  default ≈ 60 dots (~5 mm at 300 DPI). Intentionally modest and consistent.
- `margins.writingSpaceGap` — the separate internal gap *inside* block 2 between the
  "Toelating [permitLabel]" line and the dotted line; larger than `betweenBlocks` to
  give physical writing room.
- `text.fontSizePermitLabel` — font size for the "Toelating [permitLabel]" line.
- `text.fontSizeAssociation` — font size for the dotted/association line.
- `text.fontSizeEventName` — font size for the event name in block 4.
- `text.dotCount` — number of `.` characters used when no `associationName` is supplied.
- `code.defaultSymbology` — default symbology when the request omits `codeSymbology`;
  `CODE128` unless overridden.
- Side margins reuse existing top-level properties (`logoSideMarginDots`, `widthDots`,
  `lengthDots`, `dpi`) where they apply identically.

Note: there is no `iconPath` config — the icon is now driven by the per-request
`iconName` field and will be loaded dynamically by the future FA converter. There is
no static icon asset required for this implementation.

## Assets needed

- **Event logo PNG** (provided by the maintainer) — e.g. `classpath:images/pukkelpop-logo.png`,
  configured via `wristband.permit.eventLogoPath`. Must be a high-contrast monochrome-
  friendly PNG (thermal printers need strong B/W contrast). Swapped per event by
  updating the config path and redeploying.

No electricity icon PNG is needed for this implementation. Icon rendering is deferred
to the Font Awesome follow-up.

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
| `POST` | `/api/wristbands/permit/print` | New — accepts `PermitWristbandPrintRequest`, returns `202 Accepted` + `PrintJobResponse` (same shape, adds `wristbandType`). |
| `POST` | `/api/wristbands/permit/preview/zpl` | New — returns ZPL as plain text. |
| `POST` | `/api/wristbands/permit/preview/image` | New — returns a Labelary-rendered PNG. |

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

## Gallery screen ("wristband types" overview)

A new static admin page (e.g. `wristband-gallery.html`), alongside `jobs.html` /
`template-editor.html`, behind the same admin-cookie auth, linked from the nav:

- Iterates `WristbandGalleryCatalog` entries (one per registered band type — initially
  the crew band and the permit band).
- Renders a **small thumbnail** per entry by calling the existing Labelary preview
  pipeline with each entry's fixed **sample request** (e.g. crew band uses its existing
  Swagger example data; permit band uses `eventName: "Pukkelpop 2026"`,
  `permitLabel: "ELEKTRICITEIT"`, `associationName: null` → renders the dotted line).
- Clicking a thumbnail shows a **larger render of the same fixed sample** — pure
  "browse what each band looks like," no form/editable fields.
- New read-only controller endpoint(s) to back this — e.g.
  `GET /api/wristbands/gallery` (list of catalog entries + metadata); exact wiring
  (server-rendered list vs. client-side calls to preview endpoints) to be decided in
  the implementation plan, following conventions already used by `jobs.js`.

## Testing

Mirroring the existing test conventions (mirrored package paths, Testcontainers
Postgres, mocked printer/Labelary):

- `PermitZplGeneratorServiceTest` — unit tests for layout math (block centering,
  dots-vs-association swap, `permitLabel` rendering, optional code block present/absent,
  rotation directives in generated ZPL), parallel to `ZplGeneratorServiceTest`.
- `WristbandZplResolverTest` — extend to cover the new sealed dispatch branch.
- `WristbandControllerTest` — new tests for the `/permit/print` and
  `/permit/preview/*` endpoints (validation — `permitLabel` required, response shape).
- `PrintQueueServiceTest` / `JpaJobStoreTest` — extend to cover enqueue/persist/restore
  of `PermitWristbandPrintRequest` jobs, the discriminator round-trip, and that existing
  `WristbandPrintRequest` jobs continue to round-trip unchanged (regression coverage
  for the additive schema change).
- `WorkerProfileContextTest` — verify new beans carry the correct `@Profile("!worker")`
  guard (per the project's load-bearing profile convention).
- A migration test / Flyway validation that `V6__...` applies cleanly against existing
  data and defaults pre-existing rows to `wristband_type = 'CREW'`.

## Documentation

Documentation updates are a required deliverable — not optional follow-up. Every file
below must be updated (or created) as part of the implementation, before the work is
considered done.

### Files to update alongside the code

**`docs/api.md`** — the endpoint reference is the most heavily affected:
- Replace all old `/api/wristbands/print`, `/preview/zpl`, `/preview/image` URLs with
  the new `/api/wristbands/crew/print`, `/crew/preview/zpl`, `/crew/preview/image` names.
- Add the three new permit endpoints (`/permit/print`, `/permit/preview/zpl`,
  `/permit/preview/image`) with their full request/response shape, including
  `permitLabel` (required), `iconName` (optional, stored but not rendered yet), the
  optional `codeValue`/`codeSymbology` fields, and `wristbandType` in `PrintJobResponse`.
- Add the new `GET /api/wristbands/gallery` endpoint.
- Update curl examples to use the new `/crew/print` URL.
- Note the temporary `/print` redirect alias (if implemented) and its planned removal.

**`docs/configuration.md`** — add a new `wristband.permit.*` section documenting all
config keys listed in the Configuration section above, plus note the `codeSymbology`
override available on all band-type requests.

**`docs/permit-wristband.md`** ← **new file** — create this document covering:
- Purpose: what this band type is for (general permission/allowance; electricity as the
  initial use case, designed to extend to any future permit type)
- Layout diagram and description of all four blocks, rotations, `permitLabel` rendering,
  dots-vs-association fill-in behaviour, and the optional scan code
- The `iconName` field: what it does today (stored, not rendered) and what it will do
  after the FA follow-up
- Endpoint contract (link to `api.md` for full reference; summarise the key fields here)
- Assets required (event logo PNG), naming convention, and the event-logo swap runbook
  (edit config path, redeploy — no code change needed)
- Configuration reference (link to `configuration.md`)

**`README.md`** — update any top-level mention of the print endpoint URL or wristband
type count. If README links to `docs/api.md` for endpoint detail, verify the summary
text is still accurate after the URL rename.

### Files to update at the very end of implementation

These two files capture the broader architectural picture and must be updated **after**
all code and the above docs are complete, as a final step:

**`CLAUDE.md`** — update to reflect:
- Architecture overview: mention `PrintableRequest` sealed interface, the two-band-type
  model, and the `/{type}/action` URL pattern; update the request flow description to
  reference `/crew/print` and `/permit/print`.
- Folder structure: add `domain/CodeSymbology.java`, `service/ScanCodeRenderer.java`,
  `service/PermitZplGeneratorService.java`, the new controller mapping(s), and
  `WristbandGalleryCatalog` to the relevant packages.
- Known issues: **close** the "Template renderer emits Code 128 regardless of selected
  symbology" entry — `ScanCodeRenderer` now provides multi-symbology support for
  fixed-layout generators; note the template designer renderer follow-up is still open.
  **Add** the new known limitation: "`iconName` is accepted but not rendered — deferred
  to the Font Awesome follow-up."
- Current work in progress: move this feature from "in progress" to "landed" once done,
  and update the "Recommended next steps" list (add FA icon rendering as next step).

**`HANDOVER.md`** — add a new entry under "Decisions that were made" recording:
- The `/{type}/action` URL structure and the reasoning (extensibility, clear per-role
  naming, Symfony one-line update).
- The `permitLabel` generalization — why "electricity" was not baked into the URL or
  layout, and how it opens the door to PARKING, CATERING, etc. without code changes.
- The `PrintableRequest` sealed interface approach and why isolated generators were
  preferred over a shared strategy interface.
- The additive V6 migration strategy (nullable columns + discriminator, backfill default).
- The `ScanCodeRenderer` shared helper and why it was extracted.
- The uniform `betweenBlocks` margin decision.
- `iconName` stored-but-not-rendered decision and the FA follow-up plan.
- Notable rejected alternatives (single-DTO with conditional validation, hardcoded
  `/electricity/print` URL, per-gap margins, shared strategy interface).

# Template Editor — Grouping, Alignment, Margins & Placeholders — Design

**Date:** 2026-06-03
**Status:** Approved (design phase)
**Builds on:** the Wristband Template Designer (Plans 1–3). Requires the Plan 3 editor
(`/template-editor.html`) to be in `main` before implementation.

## Problem & Goal

The editor currently places elements as a flat list of absolutely-positioned items. Users need to:

1. **Center** an item or group across the wristband.
2. **Group** items together, and **nest** groups (group-of-groups).
3. Edit the **margin** (spacing) between items in a group.
4. Give data-bound blocks (firstName, lastName, …) editable **placeholder/sample text** visible
   on the canvas and in the preview.
5. Enter **free-text** content for static blocks via the properties panel (single-line).

## Decisions (resolved during brainstorming)

| Decision | Choice |
|---|---|
| Grouping persistence | Saved & re-editable — groups are part of the saved `definition`. |
| Group layout | Auto-stack with **choosable direction** (along band length or width) + one editable margin between members. |
| Nesting | **Nested groups** — a group may contain items and other groups (recursive). |
| Alignment | **Both**: a "center on band" action for an item/group, and a per-group cross-axis `crossAlign` (start/center/end). |
| Placeholder text | **Editable, saved, drives canvas + preview** (per-element `sampleText`); real prints still use Symfony data. |
| Free text | **Single-line** (existing `value` field); no multi-line / `^FB`. |
| Model representation | **Recursive group nodes** (Approach A): an element can be a `GROUP` with `children`. |

## Model changes — `TemplateDefinition`

`elements` becomes a **tree**. `TemplateElement` (one record, matching the existing pragmatic
style) gains optional, nullable fields — stored in the existing `jsonb` column, **no DB
migration**:

- `children: List<TemplateElement>` — present when `type == GROUP`
- `stackDirection: StackDirection` — `LENGTH` (stack down the long axis) or `WIDTH` (across the short axis)
- `marginDots: Integer` — gap between consecutive children
- `crossAlign: CrossAlign` — `START | CENTER | END` on the group's cross-axis
- `sampleText: String` — design/preview-only sample value for data-bound and static blocks

New enums: `ElementType.GROUP`, `StackDirection {LENGTH, WIDTH}`, `CrossAlign {START, CENTER, END}`.

**Coordinate rules:**
- Top-level (ungrouped) elements keep absolute `x/y` in dots — unchanged from today, so existing
  templates deserialize and render identically (back-compatible).
- A group has its own `x/y` (origin). Its children's `x/y` are **computed** by the stack layout
  and ignored by the renderer.

## Renderer — `TemplateZplRenderer` (recursive flatten)

`renderWith` becomes recursive. For a `GROUP`:
1. Lay children out in order along `stackDirection`: child *i* starts at
   `Σ(previous children axis-size) + i·marginDots` from the group origin along the stack axis.
   Axis-size = `heightDots` when `LENGTH`, `widthDots` when `WIDTH` (the editor stores every
   element's bounding box, so no font metrics are needed).
2. On the cross-axis, offset each child by `crossAlign` within the group's cross-size
   (= max child cross-size).
3. Translate all child positions by the group's absolute origin, then recurse for nested groups.
4. Leaf rendering (`TEXT`, `STATIC_TEXT`, `BARCODE`, `IMAGE`, `SHAPE`) is unchanged; bound/static
   values resolve exactly as today (real data, or `${BINDING}` placeholders for the snapshot).

"Center on band" needs no renderer special-casing — the editor stores the resulting `x`.

## Editor UX

- **Multi-select** (shift-click and rubber-band) → **Group** / **Ungroup** buttons. Grouping a
  selection that already contains a group produces a **nested** group (Konva.Group within a group).
- **Group properties** (group selected): `stackDirection`, `marginDots`, `crossAlign`. Editing any
  re-runs the stack layout live on the canvas.
- **Align → Center on band**: centers the selected item/group across the band width
  (`x = (canvas.widthDots − crossExtent) / 2` in dot-space).
- **Sample text**: data-bound blocks and static blocks show a `sampleText` field in the properties
  panel; the canvas renders it for realistic sizing; it is saved per element.
- **Free text**: the existing single-line `value` field is the editable static-text content;
  ensure it is present and obvious.
- **Serialization** is recursive: Konva groups ⇄ `GROUP` elements with `children`.

## Preview integration

The **Preview** button switches to **POST `/api/templates/{id}/preview`** with a `WristbandData`
body assembled from each block's `sampleText` (falling back to the existing sample defaults), so
the rendered PNG reflects the typed placeholder text.

## Testing

- **Renderer (golden ZPL):** a `LENGTH` stack and a `WIDTH` stack (correct offsets + margin); a
  nested group; each `crossAlign` value; and a **back-compat** test proving a flat (group-less)
  definition renders byte-for-byte as before.
- **Domain:** JSON round-trip of a nested `GROUP` definition.
- **Editor:** verified via the run-the-app checklist (no JS test runner), per Plan 3.

## Scope / implementation plans

Two plans:
1. **Backend** — new enums + `TemplateElement` fields + `GROUP` type, recursive renderer, domain
   + renderer tests. Self-contained and independently testable.
2. **Editor** — grouping/nesting, group properties, center-on-band, sample-text fields, recursive
   serialization, preview wiring.

## Out of scope (YAGNI)

- Multi-line free text (`^FB`).
- Per-child individual margins (one margin per group).
- Distribute/align actions beyond center-on-band + group cross-align (e.g. align-left, space-evenly).
- Group-level rotation (members rotate individually; a group's rotation stays 0).

## Risks & assumptions

- **Assumption:** a child's stored `widthDots`/`heightDots` is an acceptable layout footprint. This
  holds because the editor sets the bounding box for every element; barcodes/text use the box, not
  glyph metrics. Flagged for the renderer tests.
- **Risk:** recursion bugs (infinite nesting, orphaned ids). Mitigated by id-uniqueness on group
  and a depth guard in the renderer.
- **Dependency:** Plan 3 (editor) must be merged to `main` before the editor plan executes.

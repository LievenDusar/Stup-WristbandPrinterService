# Permit Wristband

## Purpose

Permit wristbands grant campsite guests access to specific resources (e.g. electricity/power
boxes, parking areas, catering backstage). Unlike the crew wristband, they carry no personal
details and are not tied to a specific person in the STUP system — they are a physical
access token.

## Layout

The band is 300 × 3300 dots at 300 DPI (same physical stock as the crew band). The blocks
are stacked vertically with `betweenBlocks` gaps between them.

```
Block 1 – STUP logo (180° rotated)
[betweenBlocks gap]
Block 2 – "Toelating [permitLabel]"  (permitTextFontSize)
           [interLineGap]
           "aan " + clubName  OR  "aan " + dotted fill-in line  (clubFontSize / dashes)
[betweenBlocks gap]
Block 3 (optional) – scan code (CODE128 / CODE39 / QR)
[betweenBlocks gap, only if block 3 present]
Block 4 – eventName  (eventNameFontSize)
```

Block 2 is two lines that bind into one centered text block — the same way the crew band stacks
and centres its text lines. Both are rotated 270° (`^A0B`), separated by a small `interLineGap`
across the band width, and centred on the same axis: line 1 is `Toelating [permitLabel]`, line 2
is `aan ` followed by the `clubName` when supplied, otherwise a dotted fill-in line for writing the
name by hand.

## API contract

### Enqueue
`POST /api/wristbands/permit/print`

### Preview (ZPL text)
`POST /api/wristbands/permit/preview/zpl`

### Preview (PNG image)
`POST /api/wristbands/permit/preview/image`

All three endpoints accept `PermitWristbandPrintRequest`. See [api.md](api.md) for the
full field list.

## Supported permit types

Any non-blank `permitLabel` is accepted. Current conventions:

| `permitLabel` | Resource |
|---------------|----------|
| `ELEKTRICITEIT` | Campsite power box |
| `PARKING` | Parking zone (add vendor / VIP suffix as needed) |

To add a new type, pass a new `permitLabel` — no code changes required.

## Ops runbook

**Calibrate layout:** Use the preview endpoint, inspect the PNG, and adjust `wristband.permit.*`
values. No code changes needed.

**Reprint a permit:** Use `POST /api/wristbands/jobs/{jobId}/reprint`.

**`iconName` field:** Accepted and stored in the database but not rendered. Reserved for a
future Font Awesome icon overlay. Pass any Font Awesome icon name (e.g. `bolt`, `car`) —
it will be persisted for future use.

# Permit Wristband

## Purpose

Permit wristbands grant campsite guests access to specific resources (e.g. electricity/power
boxes, parking areas, catering backstage). Unlike the crew wristband, they carry no personal
details and are not tied to a specific person in the STUP system — they are a physical
access token.

## Layout

The band is 300 × 3300 dots at 300 DPI (same physical stock as the crew band). Four blocks
are stacked vertically with `betweenBlocks` gaps between them.

```
Block 1 – STUP logo (180° rotated)
[betweenBlocks gap]
Block 2 – "Toelating [permitLabel]"  (permitTextFontSize)
           [writingSpaceGap blank space]
           associationName  OR  dotted fill-in line  (associationFontSize / dashes)
[betweenBlocks gap]
Block 3 (optional) – scan code (CODE128 / CODE39 / QR)
[betweenBlocks gap, only if block 3 present]
Block 4 – eventName  (eventNameFontSize)
           event logo (180° rotated)
```

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

## Assets

`wristband.permit.event-logo-path` points to the event-specific logo printed in block 4.
Override per environment in `application-prod.yml` or via environment variable:

```yaml
wristband:
  permit:
    event-logo-path: /opt/stup/logos/pukkelpop-2026.png
```

The logo is loaded at startup. If it cannot be found the service logs a warning and omits
the logo — it does **not** fail to start.

## Ops runbook

**Change the event logo:** Update `event-logo-path` and restart the management container.

**Calibrate layout:** Use the preview endpoint, inspect the PNG, and adjust `wristband.permit.*`
values. No code changes needed.

**Reprint a permit:** Use `POST /api/wristbands/jobs/{jobId}/reprint`.

**`iconName` field:** Accepted and stored in the database but not rendered. Reserved for a
future Font Awesome icon overlay. Pass any Font Awesome icon name (e.g. `bolt`, `car`) —
it will be persisted for future use.

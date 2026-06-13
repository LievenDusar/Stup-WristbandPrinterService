# API Reference

Base path: `/api/wristbands`  
Authentication: `X-API-Key` header on all endpoints except `/jobs/stream`.

---

## Crew wristband

| Method | URL | Description |
|--------|-----|-------------|
| POST | `/crew/print` | Enqueue a crew print job → 202 |
| POST | `/crew/preview/zpl` | Return ZPL as plain text |
| POST | `/crew/preview/image` | Return PNG preview via Labelary |
| POST | `/print` ⚠ | **Deprecated 308 alias** → `/crew/print` |

### Request body (`WristbandPrintRequest`)

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `eventName` | string | ✅ | |
| `firstName` | string | ✅ | |
| `lastName` | string | ✅ | |
| `clubName` | string | ✅ | Printed on the band |
| `barcodeValue` | string | ✅ | Scanned at the event |
| `templateId` | UUID | ❌ | When set, renders via the named designer template |
| `codeSymbology` | `CODE128` \| `CODE39` \| `QR` | ❌ | Defaults to `CODE128` |
| `stockColorCode` | integer | ❌ | Preview-only PNG tint (see stock colors below) |
| `printerId` | string | ❌ | Defaults to first registered printer |

---

## Permit wristband

| Method | URL | Description |
|--------|-----|-------------|
| POST | `/permit/print` | Enqueue a permit print job → 202 |
| POST | `/permit/preview/zpl` | Return ZPL as plain text |
| POST | `/permit/preview/image` | Return PNG preview via Labelary |

### Request body (`PermitWristbandPrintRequest`)

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `eventName` | string | ✅ | Printed in block 4 |
| `permitLabel` | string | ✅ | e.g. `ELEKTRICITEIT`, `PARKING`. Printed as "Toelating [label]" |
| `clubName` | string | ❌ | If absent, a dotted fill-in line is printed instead |
| `iconName` | string | ❌ | Font Awesome icon name — stored only, not rendered yet |
| `codeValue` | string | ❌ | When present, a scan code is printed in block 3 |
| `codeSymbology` | `CODE128` \| `CODE39` \| `QR` | ❌ | Defaults to `CODE128` |
| `stockColorCode` | integer | ❌ | Preview-only PNG tint |
| `printerId` | string | ❌ | Defaults to first registered printer |

---

## Jobs (type-agnostic)

| Method | URL | Description |
|--------|-----|-------------|
| GET | `/jobs` | List jobs; optional `?status=PENDING\|PRINTING\|DONE\|FAILED\|CANCELLED` |
| GET | `/jobs/{jobId}` | Get full job detail |
| GET | `/jobs/{jobId}/preview` | PNG preview of the job's wristband |
| GET | `/jobs/stream` | SSE stream of all job status updates |
| GET | `/jobs/{jobId}/stream` | SSE stream for one job |
| POST | `/jobs/{jobId}/reprint` | Re-enqueue a job; optional `?printerId=` |
| POST | `/jobs/{jobId}/cancel` | Cancel a PENDING job → 409 if already started |
| DELETE | `/jobs/completed` | Soft-delete all DONE/FAILED/CANCELLED jobs |

---

## Printers & Gallery

| Method | URL | Description |
|--------|-----|-------------|
| GET | `/printers` | List registered printers |
| GET | `/gallery` | List all wristband types with sample preview data |

---

## Stock color codes

`stockColorCode` in any print or preview request applies a color tint to the
**PNG preview only**. ZPL output is always monochrome.

Default palette (configurable in `wristband.stock-colors`):

| Code | Color |
|------|-------|
| 1 | White (no-op) |
| 2 | Purple `#800080` |
| 3 | Yellow `#FFFF00` |
| 4 | Blue `#0000FF` |
| 5 | Green `#008000` |
| 6 | Red `#FF0000` |

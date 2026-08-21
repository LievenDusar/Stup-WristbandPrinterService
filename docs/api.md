# API Reference

Base path: `/api/wristbands`  
Authentication: `X-API-Key` header on all endpoints except `/jobs/stream`.

> **Calling from the STUP Symfony app?** The browser calls `/print` + `/preview/*` directly with a
> **print-only key** (`SECURITY_PRINT_API_KEY`), with CORS limited to the STUP origin
> (`CORS_ALLOWED_ORIGINS`). That key reaches print/preview and its own job's status
> (`GET /jobs/{jobId}`, `/jobs/{jobId}/stream`) — never the global list/stream or admin endpoints. See
> [symfony-proxy-integration.md](symfony-proxy-integration.md).

---

## Print & preview (polymorphic)

A single set of endpoints handles all wristband types. The `wristbandType` discriminator
field in the JSON body selects the type: `"crew"`, `"permit"`, or `"freetext"` (lowercase on
the wire in both requests and responses).

> **Breaking change (hard cut):** All type-specific sub-paths and the legacy 308-redirect alias
> are removed. Symfony must deploy these new paths in lockstep with this service.

| Method | URL | Description |
|--------|-----|-------------|
| POST | `/print` | Enqueue a print job → 202 |
| POST | `/preview/zpl` | Return ZPL as plain text |
| POST | `/preview/image` | Return PNG preview via Labelary |

### Crew request body

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `wristbandType` | `"crew"` | ✅ | Discriminator — must be lowercase `"crew"` |
| `eventName` | string | ✅ | |
| `firstName` | string | ✅ | |
| `lastName` | string | ✅ | |
| `clubName` | string | ✅ | Printed on the band |
| `barcodeValue` | string | ✅ | Scanned at the event |
| `templateId` | UUID | ❌ | When set, renders via the named designer template |
| `codeSymbology` | `CODE128` \| `CODE39` \| `QR` | ❌ | Defaults to `CODE128` |
| `stockColorCode` | integer | ❌ | Preview-only PNG tint (see stock colors below) |
| `printerId` | string | ❌ | Defaults to first registered printer |
| `copies` | integer | ❌ | Number of bands to print; defaults to 1 |

**Crew example:**

```json
{
  "wristbandType": "crew",
  "eventName": "Pukkelpop 2026",
  "firstName": "Annechien",
  "lastName": "Van De Wall",
  "clubName": "Chiro Sint-Christina Brustem",
  "barcodeValue": "12345654245524789"
}
```

### Permit request body

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `wristbandType` | `"permit"` | ✅ | Discriminator — must be lowercase `"permit"` |
| `eventName` | string | ✅ | Printed in block 4 |
| `permitLabel` | string | ✅ | e.g. `ELEKTRICITEIT`, `PARKING`. Printed as "Toelating [label]" |
| `clubName` | string | ❌ | If absent, a dotted fill-in line is printed instead |
| `iconName` | string | ❌ | Font Awesome icon name — stored only, not rendered yet |
| `codeValue` | string | ❌ | When present, a scan code is printed in block 3 |
| `codeSymbology` | `CODE128` \| `CODE39` \| `QR` | ❌ | Defaults to `CODE128` |
| `stockColorCode` | integer | ❌ | Preview-only PNG tint |
| `printerId` | string | ❌ | Defaults to first registered printer |
| `copies` | integer | ❌ | Number of bands to print; defaults to 1 |

**Permit example:**

```json
{
  "wristbandType": "permit",
  "eventName": "Pukkelpop 2026",
  "permitLabel": "ELEKTRICITEIT",
  "clubName": "Backstage crew"
}
```

### Free-text request body

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `wristbandType` | `"freetext"` | ✅ | Discriminator — must be lowercase `"freetext"` |
| `text` | string | ✅ | Freely entered text, printed between two STUP logos |
| `stockColorCode` | integer | ❌ | Preview-only PNG tint |
| `printerId` | string | ❌ | Defaults to first registered printer |
| `copies` | integer | ❌ | Number of bands to print; defaults to 1 |

**Free-text example:**

```json
{
  "wristbandType": "freetext",
  "text": "Backstage"
}
```

### Response note

The jobs list response field `wristbandType` is also lowercase: `"crew"`, `"permit"`, or `"freetext"`.

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

## Wristband templates (`/api/wristband-templates`)

> **Breaking change (hard cut):** The old template path prefix is removed; all template endpoints
> now live under `/api/wristband-templates/**`.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/wristband-templates` | Create a template → 201 + detail |
| PUT | `/api/wristband-templates/{id}` | Update a template → 200 / 404 |
| GET | `/api/wristband-templates` | Catalog list; `?projectType=` filters |
| GET | `/api/wristband-templates/{id}` | Full definition → 200 / 404 |
| DELETE | `/api/wristband-templates/{id}` | Soft-delete → 204 / 404 |
| POST | `/api/wristband-templates/{id}/preview` | PNG preview; body is optional — omit for sample data, supply `WristbandData` for live preview |

> The old `GET /api/wristband-templates/{id}/preview` is removed. Preview is now a single
> `POST` with an optional body.

---

## Wristband assets (`/api/wristband-assets`)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/wristband-assets` | Upload a logo asset → 201 + `{ id }` |
| GET | `/api/wristband-assets/{id}` | Fetch a logo asset by id |

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

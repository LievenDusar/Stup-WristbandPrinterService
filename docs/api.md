# API endpoints

[← Back to README](../README.md)

All endpoints (except `/api/wristbands/jobs/stream` and `/jobs.html`) require:

```
X-API-Key: <your-api-key>
```

## Wristbands

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/wristbands/print` | Enqueue a print job → `202 + jobId`. Optional `printerId` selects the printer (default = first); unknown id → `400`. Response carries `printerId` + `printerName` |
| `POST` | `/api/wristbands/preview/zpl` | Return generated ZPL as plain text |
| `POST` | `/api/wristbands/preview/image` | Return rendered PNG via Labelary |
| `GET` | `/api/wristbands/printers` | List routable printers (`[{id, displayName}]`) |
| `GET` | `/api/wristbands/jobs` | List all jobs (`?status=PENDING\|PRINTING\|DONE\|FAILED`) |
| `GET` | `/api/wristbands/jobs/{jobId}` | Get one job (incl. `printerId`/`printerName`) |
| `GET` | `/api/wristbands/jobs/stream` | SSE stream — real-time updates for **all** jobs |
| `GET` | `/api/wristbands/jobs/{jobId}/stream` | SSE stream for **one** job; emits its current status, then updates, and closes on a terminal status (for Symfony to follow a single job) |
| `POST` | `/api/wristbands/jobs/{jobId}/reprint` | Reprint a previous job; optional `?printerId=` re-routes it to another printer |
| `DELETE` | `/api/wristbands/jobs/completed` | Soft-delete DONE/FAILED/CANCELLED jobs |

## Templates

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/templates` | Create a wristband template → `201 + detail` |
| `PUT` | `/api/templates/{id}` | Update a template → `200` / `404` |
| `GET` | `/api/templates` | List templates (catalog); `?projectType=` filters |
| `GET` | `/api/templates/{id}` | Get a template's full definition → `200` / `404` |
| `DELETE` | `/api/templates/{id}` | Soft-delete a template → `204` / `404` |
| `GET` | `/api/templates/{id}/preview` | PNG preview with sample data (`?color=` tints stock) |
| `POST` | `/api/templates/{id}/preview` | PNG preview with supplied `WristbandData` body |
| `POST` | `/api/templates/assets` | Upload a logo (multipart `file`) → `201 + assetId` |
| `GET` | `/api/templates/assets/{id}` | Fetch a stored logo PNG |

> **Wristband Template Designer:** the `/api/templates` endpoints back a visual template designer.
> `POST /api/wristbands/print` accepts an optional `templateId` — when set, the wristband is rendered
> from that template instead of the default fixed layout. Architecture, data model, full API and
> roadmap are in [template-designer.md](template-designer.md).

**Example print request:**

```bash
curl -X POST http://localhost:8080/api/wristbands/print \
  -H "X-API-Key: local-dev-key" -H "Content-Type: application/json" \
  -d '{
    "eventName": "Pukkelpop 2026",
    "firstName": "Annechien",
    "lastName": "Van De Wall",
    "associationName": "Chiro Sint-Christina Brustem",
    "barcodeValue": "12345455244226789"
  }'
```

**Example ZPL preview** (paste the output at [labelary.com/viewer.html](https://labelary.com/viewer.html)):

```bash
curl -X POST http://localhost:8080/api/wristbands/preview/zpl \
  -H "X-API-Key: local-dev-key" -H "Content-Type: application/json" \
  -d '{"eventName":"Pukkelpop 2026","firstName":"Annechien","lastName":"Van De Wall","associationName":"Chiro Sint-Christina Brustem","barcodeValue":"12345455244226789"}' \
  | pbcopy
```

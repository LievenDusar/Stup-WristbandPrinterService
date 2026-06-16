# API Endpoint Restructure — Design

- **Date:** 2026-06-16
- **Status:** Approved (design); implementation plan to follow
- **Author:** Lieven Dusar (with Claude)
- **Driver:** API naming-convention request from the Symfony developer (Dirk Vanstraelen)

## 1. Context & motivation

The external STUP Symfony application is the primary consumer of this service's HTTP API. Dirk
asked for a more consistent, predictable URL structure and — most importantly — a **single print
URL** so Symfony stores one endpoint and selects the wristband type in the JSON body instead of in
the path.

After review, most of the agreed target list already matches the current API. The real work is
concentrated in two areas: **merging the split crew/permit print & preview endpoints into one
polymorphic set**, and **renaming the template/asset endpoints**. Jobs and printer endpoints are
already at their target paths.

This is a **breaking change to the public contract**, taken deliberately as a **hard cut** (no
redirect aliases). Symfony deploys its endpoint changes in lockstep.

## 2. Goals

- One print endpoint and one set of preview endpoints serving both CREW and PERMIT, with the type
  carried in the body as a self-documenting `wristbandType` discriminator.
- Consistent resource-oriented URLs for templates and assets.
- Keep the print/route/worker pipeline and the shared `WristbandZplResolver` behaviour unchanged
  below the controller layer.
- Keep docs and Swagger faithful to the new contract (what you preview is what prints stays true).

## 3. Non-goals

- No change to the print queue, routing, registry, worker protocol, or SSE mechanics.
- No change to `/api/wristbands/jobs/**` or `/api/wristbands/printers/**` paths (already correct).
- No change to internal endpoints: `/api/internal/print`, `/api/internal/printers/**`,
  `/api/wristbands/login|logout`, `/api/wristbands/gallery`.
- No backward-compatibility aliases or redirects (explicitly out of scope per decision below).

## 4. Decisions (resolved during brainstorming)

1. **Backward compatibility: hard cut.** Old paths are removed outright. No 308 redirects, no
   shims. Symfony cuts over in the same deploy.
2. **Discriminator field name: `wristbandType`.** Self-documenting — the JSON is readable without
   the API docs.
3. **Discriminator value casing: lowercase on the wire** (`"crew"` / `"permit"`), in **both**
   request bodies **and** the jobs response. The Java `WristbandType` enum keeps uppercase
   constants (Java convention) and gains a Jackson mapper (`@JsonValue` + `@JsonCreator`) to
   translate. Input parsing is case-insensitive for robustness; canonical output is lowercase.
4. **Template preview consolidation:** drop `GET /{id}/preview`; fold it into
   `POST /{id}/preview` with an **optional** body — no body means sample data (the old GET
   behaviour), a body means the supplied data.
5. **Templates/assets rename is also a hard rename** (no aliases). Safe because these endpoints
   are **not yet consumed by Symfony** — the template designer is an in-development tool whose only
   current caller is the in-repo editor front-end.

## 5. Endpoint map (before → after)

### Print & preview — MERGE (external; breaking)

| Before | After |
|--------|-------|
| `POST /api/wristbands/crew/print` | `POST /api/wristbands/print` |
| `POST /api/wristbands/permit/print` | *(merged into the above)* |
| `POST /api/wristbands/crew/preview/zpl` | `POST /api/wristbands/preview/zpl` |
| `POST /api/wristbands/permit/preview/zpl` | *(merged)* |
| `POST /api/wristbands/crew/preview/image` | `POST /api/wristbands/preview/image` |
| `POST /api/wristbands/permit/preview/image` | *(merged)* |
| `POST /api/wristbands/print` (308 → `/crew/print`) | **removed** (path reused by the real print) |

### Templates — RENAME (internal; in-development)

| Before | After |
|--------|-------|
| `GET    /api/templates` | `GET    /api/wristband-templates` |
| `POST   /api/templates` | `POST   /api/wristband-templates` |
| `GET    /api/templates/{id}` | `GET    /api/wristband-templates/{id}` |
| `PUT    /api/templates/{id}` | `PUT    /api/wristband-templates/{id}` |
| `DELETE /api/templates/{id}` | `DELETE /api/wristband-templates/{id}` |
| `GET    /api/templates/{id}/preview` | **removed** (folded into POST) |
| `POST   /api/templates/{id}/preview` | `POST   /api/wristband-templates/{id}/preview` (body optional) |

### Assets — RENAME + EXTRACT (internal; in-development)

| Before | After |
|--------|-------|
| `POST /api/templates/assets` | `POST /api/wristband-assets` |
| `GET  /api/templates/assets/{id}` | `GET  /api/wristband-assets/{id}` |

### Jobs & printers — UNCHANGED paths

`GET /api/wristbands/jobs`, `/jobs/{jobId}`, `POST /jobs/{jobId}/reprint`, `/jobs/{jobId}/cancel`,
`GET /jobs/{jobId}/preview`, `/jobs/{jobId}/stream`, `/jobs/stream`, `DELETE /jobs/completed`,
`GET /api/wristbands/printers`, `PATCH /printers/{id}`, `POST /printers/{id}/test`,
`POST /printers/{id}/hide`, `POST /printers/{id}/default`. **No path change.** The jobs response
body changes only in the casing of `wristbandType` (see §6.3).

## 6. Detailed design

### 6.1 Polymorphic print/preview body

The three merged endpoints accept the existing sealed `PrintableRequest` directly. Jackson
polymorphic deserialization selects the concrete subtype from the body's `wristbandType`:

```java
@JsonTypeInfo(use = Id.NAME, include = As.EXISTING_PROPERTY, property = "wristbandType", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = WristbandPrintRequest.class,       name = "crew"),
    @JsonSubTypes.Type(value = PermitWristbandPrintRequest.class, name = "permit")
})
public sealed interface PrintableRequest permits WristbandPrintRequest, PermitWristbandPrintRequest { ... }
```

Wire examples:

```jsonc
// crew
{ "wristbandType": "crew", "eventName": "Pukkelpop 2026", "firstName": "Annechien",
  "lastName": "Van De Wall", "associationName": "Chiro ...", "barcodeValue": "12345",
  "printerId": "zebra-01", "copies": 1 }

// permit
{ "wristbandType": "permit", "eventName": "Pukkelpop 2026", "permitLabel": "Camping A",
  "stockColorCode": 2, "printerId": "zebra-01" }
```

Per-type bean validation is preserved: Jackson resolves the concrete type, then `@Valid` runs the
type-specific `@NotBlank`/`@Min` constraints. The service layer (`PrintQueueService.enqueue`,
`WristbandZplResolver.resolve`) already accepts `PrintableRequest` — **no change below the
controller.**

> The exact Jackson wiring (`EXISTING_PROPERTY` vs a dedicated type property, and its interplay
> with the enum's `@JsonValue`) is pinned down **test-first** in the implementation plan; the
> binding contract is fixed regardless: a lowercase `wristbandType` discriminator that round-trips
> identically on request and response.

### 6.2 `WristbandType` enum mapper

```java
public enum WristbandType {
    CREW, PERMIT;

    @JsonValue
    public String wire() { return name().toLowerCase(); }

    @JsonCreator
    public static WristbandType fromWire(String v) { return valueOf(v.trim().toUpperCase()); }
}
```

This makes every serialization of the enum (print/preview discriminator output, jobs response,
gallery) emit lowercase, and accepts lowercase/upper input. `@JsonSubTypes` names must match the
lowercase wire form (`"crew"`/`"permit"`) so the discriminator and the enum agree.

### 6.3 Controller layer

- **`WristbandController`** gains the three merged endpoints (`/print`, `/preview/zpl`,
  `/preview/image`) accepting `@Valid @RequestBody PrintableRequest`. The `/crew/*` methods and the
  legacy `/print` 308 redirect are removed. The `applyStockColor` helper (currently duplicated)
  serves the single `/preview/image` for both variants — both already carry `stockColorCode`.
- **`PermitWristbandController` is deleted**; its three methods are subsumed by the merged
  endpoints. Its `applyStockColor` logic moves to (or is shared with) `WristbandController`.
- **`TemplateController`** is remapped to `@RequestMapping("/api/wristband-templates")`, loses the
  asset methods and the `GET /{id}/preview`, and makes the `POST /{id}/preview` body optional
  (`@RequestBody(required = false) WristbandData data`, passing `null` → sample data).
- **New `WristbandAssetController`** at `@RequestMapping("/api/wristband-assets")` holds
  `POST` (upload) and `GET /{id}` (fetch), moved out of `TemplateController`.

### 6.4 Security

`SecurityConfig` path matchers are updated for the renamed/merged paths (e.g. any explicit
references to `/api/wristbands/crew/**`, `/api/wristbands/permit/**`, `/api/templates/**`). The
API-key requirement and the admin-cookie rules are preserved exactly; only path strings change.
`SecurityConfigTest` is updated accordingly.

### 6.5 Front-end

- **`editor/api.js`:** template URLs `/api/templates` → `/api/wristband-templates`; asset URLs
  `/api/templates/assets` → `/api/wristband-assets`. `previewPng(id, color)` switches from `GET`
  to `POST` with no body (sample-data preview); `previewPngWithData` stays a POST under the new
  path.
- **`editor/canvas.js`:** asset image URL `/api/templates/assets/{id}` →
  `/api/wristband-assets/{id}`.
- **`jobs.js`:** the `wristbandType` value from the jobs response is now lowercase; update the
  type-filter grouping/labels and any comparison to use the lowercase value (display labels can
  still be title-cased for presentation).
- `gallery.js`, `login.js` hit only unchanged paths — untouched (but the gallery's `wristbandType`
  rendering, if any, follows the lowercase change).

### 6.6 Swagger / OpenAPI

- `@Operation` summaries updated for the merged/renamed endpoints; `OpenApiConfig` tags reviewed
  (the per-type "Wristbands" operations collapse to one set).
- The merged body is documented as a polymorphic schema: `@Schema` with `oneOf` of
  `WristbandPrintRequest` / `PermitWristbandPrintRequest` and `discriminatorProperty =
  "wristbandType"`, so Swagger UI renders both variants and a working "Try it out".

## 7. Backward compatibility

**None retained.** All old paths return 404 after the change. Coordinated with Symfony to deploy
the new paths simultaneously. The template/asset rename is risk-free externally (no Symfony
consumer yet).

## 8. Testing strategy

Following the project rule that every feature ships with tests against real Postgres
(Testcontainers), printer socket and Labelary mocked:

- **Merge `WristbandControllerTest` + `PermitWristbandControllerTest`** into one suite covering:
  `POST /print` with `wristbandType: "crew"` and `"permit"`; both preview endpoints; lowercase and
  uppercase input both accepted; missing/unknown `wristbandType` → 400; per-type validation errors
  still fire; old `/crew/*`, `/permit/*`, legacy `/print` paths → 404.
- **`TemplateControllerTest`** repathed to `/api/wristband-templates`; `POST /{id}/preview` with
  and without body; old `/api/templates/**` → 404.
- **New asset test** for `/api/wristband-assets` (upload + fetch); old asset paths → 404.
- **`WristbandIntegrationTest`** end-to-end on the new print path with the lowercase discriminator.
- **`SecurityConfigTest`** updated for new path matchers.
- A focused test that the jobs response serializes `wristbandType` lowercase.
- Confirm `WorkerProfileContextTest` still green (profile guards unchanged).

## 9. Affected files (non-exhaustive)

- `controller/WristbandController.java` (merge), `controller/PermitWristbandController.java`
  (delete), `editor/controller/TemplateController.java` (rename + trim), **new**
  `editor/controller/WristbandAssetController.java`.
- `domain/PrintableRequest.java` (Jackson polymorphism), `domain/WristbandType.java` (mapper).
- `config/SecurityConfig.java`, `config/OpenApiConfig.java`.
- `static/js/editor/api.js`, `static/js/editor/canvas.js`, `static/js/jobs.js`.
- Tests as listed in §8.
- Docs: `docs/api.md` (primary), `README.md`, `CLAUDE.md` (request-flow + jobs-UI sections),
  `docs/permit-wristband.md`, `docs/template-designer.md`; HANDOVER note.

## 10. Rollout

1. Merge this branch.
2. Symfony switches to `POST /api/wristbands/print` with the `wristbandType` body field and the new
   preview/template paths, deployed together with this service.
3. Verify a real crew and permit print end-to-end on staging before production.

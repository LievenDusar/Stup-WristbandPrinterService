# Configuration

[← Back to README](../README.md)

Every setting, its default, and what it does. Settings are grouped by area; wristband geometry has
its own [Wristband layout](#wristband-layout) section with an annotated diagram.

---

## Printer & routing

| Property | Default | Description |
|---|---|---|
| `worker.*` (worker role) | — | A worker's self-registration identity: `worker.id` (`WORKER_ID`), `worker.display-name`, `worker.base-url`, `worker.management-base-url`, `worker.heartbeat-millis`. The management registry is built from these registrations (no static `cluster.printers`). |
| `printer.host` | `localhost` | Zebra printer IP/host — **set per worker** via `PRINTER_HOST`; unused by management |
| `printer.port` | `9100` | Zebra printer TCP port (per worker) |
| `printer.timeout-ms` | `5000` | Socket connection timeout (ms) |
| `printer.max-retries` | `2` | Extra attempts after the first on a transient socket failure |
| `printer.retry-backoff-ms` | `500` | Pause between retry attempts (ms) |
| `printer.clear-cache-enabled` | `true` | Prepend a clear command to every job. Images are sent inline (`^GFA`) and never stored, so this is normally a no-op — it guards against object build-up in printer memory that historically stalled the printer after a number of prints |
| `printer.clear-command` | `^XA^IDR:*.*^FS^XZ` | ZPL prepended before each label when clearing is enabled. Wipes objects from the printer's **RAM drive (R:)** only — no flash wear. Override if your printer model needs a different command |
| `queue.max-depth` | `100` | Max pending jobs **per printer** before new submissions are rejected with HTTP 429 |
| `print.max-copies` | `200` | Maximum number of copies a single print job may request (Zebra `^PQ`). A request with `copies` outside `1..max-copies` is rejected with HTTP 400. Raise it if an event legitimately prints larger batches |

## Integrations & security

| Property | Default | Description |
|---|---|---|
| `labelary.base-url` | `http://api.labelary.com` | Labelary API base URL (preview rendering) |
| `security.api-key` | `changeme` | Static **admin** API key — override in production; shared by management + workers. Keep off the browser. |
| `security.print-api-key` | _(empty)_ | Optional **print-only** key (`SECURITY_PRINT_API_KEY`): valid on `POST /print`, `/preview/zpl`, `/preview/image` and on reading its own job's status (`GET /jobs/{jobId}`, `GET /jobs/{jobId}/stream`). Cannot reach the global job list/stream or any admin endpoint. Safe to expose in a browser. Blank = off. |
| `cors.allowed-origins` | _(empty)_ | Browser origin(s) allowed to call cross-origin (`CORS_ALLOWED_ORIGINS`, comma-separated), e.g. `https://www.stupvzw.be`. Empty = no cross-origin allowed. |

> **Browser callers (Symfony):** the print-only key + CORS let the Symfony front-end call
> `/print` and `/preview/*` directly without exposing the admin key. Full guide:
> [symfony-proxy-integration.md](symfony-proxy-integration.md).

## Profiles & startup

Activate one Spring profile per process:

| Process | Profile | Activation |
|---|---|---|
| Management — local | `local` | `--spring.profiles.active=local` |
| Management — production | `prod` | `SPRING_PROFILES_ACTIVE=prod` |
| Worker (printer node) | `worker` | `SPRING_PROFILES_ACTIVE=worker` — no DB/UI; needs `PRINTER_HOST` + `SECURITY_API_KEY` |

> ⚠️ Under the `prod` profile the application **refuses to start** if `security.api-key` is unset,
> blank, or left at the default `changeme` — set `SECURITY_API_KEY` to a real value.

## Wristband layout

The band is generated programmatically as ZPL — there are no absolute coordinates to maintain. You
set the band dimensions, the gaps between elements, and the font/barcode sizes; the service centres
everything and stacks the elements in this fixed order, from the non-adhesive end to the adhesive end:

> **logo → barcode → text (event / name / club) → logo**

The diagram maps each setting to the element it controls. Orange labels (left) are the gaps between
elements; the labels on the right size the elements themselves.

![Wristband layout settings](images/wristband-layout.svg)

### Band dimensions & spacing

| Property | Default | Controls |
|---|---|---|
| `wristband.width-dots` | `300` | Band width (across), in dots |
| `wristband.length-dots` | `3300` | Band length (along), in dots |
| `wristband.dpi` | `300` | Printer resolution (203 or 300) |
| `wristband.logo-path` | `classpath:images/stup-logo.png` | STUP logo PNG — bundled in the JAR, no external file needed |
| `wristband.logo-side-margin-dots` | `75` | Left/right margin around each logo, in dots |
| `wristband.margins.between-logo-and-barcode` | `30` | Gap: top logo → barcode |
| `wristband.margins.between-barcode-and-text` | `120` | Gap: barcode → text block |
| `wristband.margins.between-text-and-logo` | `120` | Gap: text block → bottom logo |

### Text & barcode

| Property | Default | Controls |
|---|---|---|
| `wristband.text.font-size-event` | `45` | Font height of the event-name line |
| `wristband.text.font-size-name` | `74` | Font height of the first + last name line (largest) |
| `wristband.text.font-size-club` | `45` | Font height of the club line |
| `wristband.barcode.type` | `CODE128` | Barcode symbology |
| `wristband.barcode.height-dots` | `270` | Barcode height (across the band), in dots |
| `wristband.barcode.module-width-dots` | `3` | Narrow-bar width — wider = longer along the band & easier to scan |
| `wristband.barcode.show-human-readable` | `false` | Print the value as text next to the barcode |

> 💡 **Calibration:** every position derives from the values above — no code changes needed. After a
> first test print, tune `wristband.margins.*` and `wristband.text.*` in `application-prod.yml`.

---

## Stock colors

`wristband.stock-colors` maps integer codes to hex values. Used by all preview endpoints
when `stockColorCode` is included in the request. ZPL is always monochrome — the tint is
applied to the PNG only by `PreviewColorService`.

```yaml
wristband:
  stock-colors:
    1: "#FFFFFF"   # white (default stock — no visual tint)
    2: "#800080"   # purple
    3: "#FFFF00"   # yellow
    4: "#0000FF"   # blue
    5: "#008000"   # green
    6: "#FF0000"   # red
```

To add more colors, append entries and redeploy. There is no reserved range — keep 1 = white.

---

## Permit wristband layout

All values under `wristband.permit.*`:

| Key | Default | Description |
|-----|---------|-------------|
| `margins.between-blocks` | 60 | Gap between the top-level layout blocks (dots) |
| `margins.inter-line-gap` | 12 | Gap (dots) between block-2's two lines ("Toelating …" / "aan …"), like the crew band |
| `text.font-size-permit-label` | 66 | Font size for "Toelating [label]" |
| `text.font-size-club` | 42 | Font size for the "aan …" club / fill-in line |
| `text.font-size-event-name` | 52 | Font size for the event name in block 4 |
| `text.dot-count` | 45 | Number of dots for the "aan …" writing-line when no club name is given |
| `code.default-symbology` | `CODE128` | Scan-code symbology when `codeSymbology` is absent in the request |
| `code.height-dots` | 200 | Bar height of the optional scan code |
| `code.module-width-dots` | 2 | Narrow-bar module width for the optional scan code |
| `code.show-human-readable` | false | Whether to print the human-readable value below the scan code |

Calibrate by using `POST /api/wristbands/preview/image` (with `"wristbandType": "permit"`) and adjusting YAML values.

---

## Free-text wristband layout

All values under `wristband.free-text.*`:

| Key | Default | Description |
|-----|---------|-------------|
| `font-size` | 66 | Font size for the free text line |
| `between-logo-and-text` | 150 | Gap (dots) between each logo and the text, applied symmetrically on both sides |

Layout is logo → text → logo, the whole group centered along the band length, with the text
centered across the band width — same convention as the other bands. Calibrate by using
`POST /api/wristbands/preview/image` (with `"wristbandType": "freetext"`) and adjusting YAML values.

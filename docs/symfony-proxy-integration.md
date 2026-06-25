# Symfony → wristband service integration

How the STUP **Symfony** event app calls the wristband printer service from the browser without
running into CORS. Two approaches are documented: the **implemented** one (CORS + a print-only key),
and the **higher-security alternative** (server-side proxy) for if requirements change.

> 👉 Just want the copy-paste production example (Symfony config → controller → Twig + JS)?
> See [symfony-usage-example.md](symfony-usage-example.md).

## Background: why the browser sees a CORS error

When a page on the Symfony origin calls `https://stupllp001.stupvzw.be:8443/...` directly, that is
a **cross-origin** request. The browser first sends a preflight `OPTIONS` (because of the
`X-API-Key` + `application/json` headers). If the service doesn't answer that preflight with the
right CORS headers, the browser blocks the real request — that is the
`CORS request did not succeed` error.

> Note: that exact Firefox wording can also mean the request failed at the **TLS/network** layer
> (the service uses a self-signed cert on `:8443`). If the call fails *server-side* too, suspect the
> cert/network first — see [production-deployment.md](production-deployment.md).

---

## Implemented approach — CORS + a print-only key

The browser calls the print/preview endpoints **directly**, authenticated with a **separate,
limited API key** that is only valid for those endpoints. The service answers cross-origin requests
from the configured STUP origin(s).

### Security trade-off (read this)

The browser must send the key, so the key **is visible in client-side JavaScript / DevTools** — it
is not a secret. We accept that here because:

- It is a **print-only** key (`ROLE_PRINT`). A leaked print key can only print wristbands; it
  **cannot** reach the jobs UI, job history, reprint/cancel, printer management, or templates —
  those stay admin-only.
- The worst case is unwanted wristbands, not data exposure.

CORS itself is **not** a security control — it only restrains browsers. Anyone who reads the key
from the page can call the service with `curl` from anywhere. Keep the admin key (`security.api-key`)
off the browser entirely; only ever ship the print-only key.

If that trade-off is unacceptable, use the [server-side proxy](#alternative--server-side-proxy)
instead, where the key never reaches the browser.

### Service configuration (this repo)

Set two things on the **management** service. The Spring properties are `security.print-api-key`
and `cors.allowed-origins`; the matching container environment variables are:

```bash
# Limited key for /print + /preview only — safe(ish) to expose in the browser
SECURITY_PRINT_API_KEY=<a dedicated print key, different from the admin key>

# Exact browser origin(s) allowed to call cross-origin (comma-separated)
CORS_ALLOWED_ORIGINS=https://www.stupvzw.be
```

> **Production (`docker-compose.prod.yml` + `.env.prod`):** you set **`PRINT_API_KEY`** in
> `.env.prod` (the compose file maps it to `SECURITY_PRINT_API_KEY`), and `CORS_ALLOWED_ORIGINS`
> directly. Step-by-step: [production-deployment.md → Direct browser calls](production-deployment.md#direct-browser-calls-from-symfony-optional).

Both default to empty/off (`application.yml`). With them set, the service:

- answers the CORS preflight for `OPTIONS` without auth,
- emits `Access-Control-Allow-Origin` for the configured origin(s),
- accepts the print-only key (or the admin key) on
  `POST /api/wristbands/print`, `/preview/zpl`, `/preview/image`,
- still rejects that key (`401`) on every admin endpoint.

Wiring lives in `SecurityConfig` (`corsConfigurationSource` + the `hasAnyRole("PRINT","ADMIN")`
matcher) and `ApiKeyAuthFilter` (`ROLE_PRINT` vs `ROLE_ADMIN`).

### Frontend (Symfony / browser)

```js
const res = await fetch('https://stupllp001.stupvzw.be:8443/api/wristbands/print', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-API-Key': PRINT_ONLY_KEY,   // the print-only key — NOT the admin key
  },
  body: JSON.stringify({
    wristbandType: 'crew',
    eventName: 'Pukkelpop 2026',
    firstName: 'Annechien',
    lastName: 'Van De Wall',
    clubName: 'Chiro Sint-Christina Brustem',
    barcodeValue: '12345654245524789',
    stockColorCode: 1,   // optional
    copies: 1            // optional
  }),
});
```

The browser handles the preflight automatically; no extra client code is needed.

> Still self-signed cert in prod: the browser must trust the `:8443` certificate (install the CA, or
> the user accepts it once) or the request fails before CORS even matters.

### Request body reference

`POST /api/wristbands/print` takes a polymorphic body with a lowercase `wristbandType`
discriminator (`"crew"` or `"permit"`). Full field tables are in [api.md](api.md). Crew fields:

| Field            | Required | Notes                                                        |
| ---------------- | -------- | ------------------------------------------------------------ |
| `eventName`      | yes      |                                                              |
| `firstName`      | yes      |                                                              |
| `lastName`       | yes      |                                                              |
| `clubName`       | yes      |                                                              |
| `barcodeValue`   | yes      |                                                              |
| `templateId`     | no       | UUID; opts into template rendering instead of legacy layout  |
| `codeSymbology`  | no       | defaults to `CODE128`                                        |
| `stockColorCode` | no       | 1=white 2=purple 3=yellow 4=blue 5=green 6=red (preview only) |
| `printerId`      | no       | omitted → default printer                                    |
| `copies`         | no       | defaults to 1; 1..`print.max-copies` (default 200)           |

---

## Alternative — server-side proxy

The most secure option: the browser calls a **same-origin** route on the Symfony server, which
forwards the request to the printer service with the key attached **server-side**. The key never
reaches the browser, and there is no cross-origin request (so no CORS at all).

```
Browser ──(same-origin POST /print/wristband)──► Symfony controller
                                                   │  attaches X-API-Key (server-side)
                                                   ▼
                         https://stupllp001.stupvzw.be:8443/api/wristbands/print
```

Sketch of the Symfony side (a scoped `HttpClient` + a controller):

```yaml
# config/packages/framework.yaml
framework:
    http_client:
        scoped_clients:
            wristbandPrinter.client:
                base_uri: '%env(WRISTBAND_PRINTER_BASE_URL)%'
                headers:
                    X-API-Key: '%env(WRISTBAND_PRINTER_API_KEY)%'
                verify_peer: '%env(bool:WRISTBAND_PRINTER_VERIFY_TLS)%'   # self-signed cert: see below
                verify_host: '%env(bool:WRISTBAND_PRINTER_VERIFY_TLS)%'
                # cafile: '%kernel.project_dir%/config/certs/print-service-ca.pem'
```

```php
// src/Controller/WristbandPrintController.php
#[Route('/print/wristband', methods: ['POST'])]
#[IsGranted('ROLE_USER')]
public function print(Request $request): Response
{
    $upstream = $this->wristbandPrinterClient->request(
        'POST', '/api/wristbands/print',
        ['json' => json_decode($request->getContent(), true) ?: []],
    );
    return new Response(
        $upstream->getContent(false),
        $upstream->getStatusCode(),
        ['Content-Type' => $upstream->getHeaders(false)['content-type'][0] ?? 'application/json'],
    );
}
```

**Known prod pitfall:** the proxy runs server-side, so the Symfony host must be able to (a) **reach**
`stupllp001.stupvzw.be:8443` over the network and (b) **trust** its self-signed cert (`cafile`, or
`WRISTBAND_PRINTER_VERIFY_TLS=false` on a trusted private network). Both failures surface as
"works from a browser but not from the server" — diagnose with `curl -v` (and `curl -vk`) from the
Symfony host.

---

## Which to use

| | Key in browser? | Blast radius if key leaks | Server must reach `:8443`? |
| --- | --- | --- | --- |
| **CORS + print-only key** (implemented) | Yes, visible | Print wristbands only | No (browser calls directly) |
| **Server-side proxy** | No | n/a (key stays server-side) | Yes (network + TLS trust) |

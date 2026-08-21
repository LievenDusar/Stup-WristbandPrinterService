# Symfony usage example (production)

A complete, copy-paste example of how the STUP **Symfony** app prints a wristband in production,
using the implemented **browser-direct** approach: the browser calls the print service straight,
authenticated with the **print-only key**, with **CORS** allowing the Symfony origin.

For the why/security background see [symfony-proxy-integration.md](symfony-proxy-integration.md);
for the server-side setup see
[production-deployment.md → Direct browser calls](production-deployment.md#direct-browser-calls-from-symfony-optional).

## Before you start

On the **management** service (this repo), set in `.env.prod`:

```dotenv
PRINT_API_KEY=<the print-only key>          # different from API_KEY
CORS_ALLOWED_ORIGINS=https://www.stupvzw.be     # your Symfony origin
```

And make sure **the browser trusts the management certificate** (real CA cert, or the CA imported in
the browser) — a `fetch()` to a self-signed endpoint fails silently.

---

## 1. Symfony config (server-side)

Keep the URL and key out of your code — in env/secrets.

```dotenv
# .env.local  (or your secrets vault)
WRISTBAND_PRINT_URL=https://stupllp001.stupvzw.be:8443
WRISTBAND_PRINT_KEY=the-print-only-key       # the SAME value as PRINT_API_KEY above
```

```yaml
# config/services.yaml
parameters:
    app.wristband_print_url: '%env(WRISTBAND_PRINT_URL)%'
    app.wristband_print_key: '%env(WRISTBAND_PRINT_KEY)%'
```

> The print-only key is meant to be visible in the browser, so injecting it into the page is fine.
> **Never** inject the admin `API_KEY` this way.

## 2. Controller — hand the config to the template

```php
// src/Controller/WristbandController.php
namespace App\Controller;

use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

class WristbandController extends AbstractController
{
    #[Route('/wristbands/print', name: 'wristband_print_page')]
    public function printPage(): Response
    {
        return $this->render('wristband/print.html.twig', [
            'printUrl' => $this->getParameter('app.wristband_print_url'),
            'printKey' => $this->getParameter('app.wristband_print_key'),
        ]);
    }
}
```

## 3. Twig + JavaScript — the actual print call

```twig
{# templates/wristband/print.html.twig #}
<button id="print-btn"
        data-print-url="{{ printUrl }}"
        data-print-key="{{ printKey }}">
  Print wristband
</button>
<p id="print-status"></p>

<script>
document.getElementById('print-btn').addEventListener('click', async (e) => {
  const { printUrl, printKey } = e.currentTarget.dataset;
  const status = document.getElementById('print-status');
  status.textContent = 'Sending…';

  try {
    const res = await fetch(printUrl + '/api/wristbands/print', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-API-Key': printKey,
      },
      body: JSON.stringify({
        wristbandType: 'crew',                       // "crew", "permit", or "freetext"
        eventName: 'Pukkelpop 2026',
        firstName: 'Annechien',
        lastName: 'Van De Wall',
        clubName: 'Chiro Sint-Christina Brustem',
        barcodeValue: '12345654245524789',
        // optional:
        // printerId: 'printer-1',                    // omit → default printer
        // copies: 1,
        // stockColorCode: 1,                         // preview tint only
      }),
    });

    if (res.status === 202) {
      const job = await res.json();
      status.textContent = 'Queued ✔ job ' + job.jobId;   // see response shape below
    } else {
      const err = await res.json().catch(() => ({}));
      status.textContent = 'Failed (' + res.status + '): ' + (err.message || '');
    }
  } catch (e) {
    // network/TLS error (e.g. the browser does not trust the certificate)
    status.textContent = 'Could not reach the print service.';
  }
});
</script>
```

## 4. What you get back

**Success — `202 Accepted`** with the job:

```json
{
  "jobId": "f1e2d3c4-5678-90ab-cdef-1234567890ab",
  "status": "PENDING",
  "wristbandType": "crew",
  "printerId": "printer-1",
  "printerName": "Inkom",
  "eventName": "Pukkelpop 2026",
  "firstName": "Annechien",
  "lastName": "Van De Wall",
  "permitLabel": null,
  "copies": 1,
  "submittedAt": "2026-06-25T12:00:00Z",
  "completedAt": null,
  "error": null
}
```

**Errors** — the body is `{ "status", "error", "message", "fields" }`:

| Status | Meaning | Typical cause |
| --- | --- | --- |
| `400` | Bad request | invalid/missing field, unknown `printerId`, `copies` out of range |
| `401` | Unauthorized | wrong or missing `X-API-Key` |
| `429` | Too many requests | that printer's queue is full |
| `503` | Service unavailable | no printer registered yet |

## 5. Permit wristband

Same call, different body (no personal data):

```js
body: JSON.stringify({
  wristbandType: 'permit',
  eventName: 'Pukkelpop 2026',
  permitLabel: 'ELEKTRICITEIT',
})
```

See [permit-wristband.md](permit-wristband.md) for the full field list.

## 6. Following the print job's status

The print call returns `202` immediately. The print-only key may also **read its own job's status** —
both the single-job endpoint (`GET /jobs/{jobId}`) and that job's SSE stream
(`GET /jobs/{jobId}/stream`) are open to it. (The *global* job list and *global* stream stay
admin-only.)

**Polling — simplest, works from the browser:**

```js
async function jobStatus(printUrl, printKey, jobId) {
  const res = await fetch(printUrl + '/api/wristbands/jobs/' + jobId, {
    headers: { 'X-API-Key': printKey },
  });
  if (!res.ok) return null;          // 404 = unknown id, 401 = wrong key
  const job = await res.json();
  return job.status;                 // PENDING → PRINTING → DONE / FAILED / CANCELLED
}
```

Poll every 1–2 s until `status` is `DONE`, `FAILED`, or `CANCELLED`.

**SSE stream — mind the header limitation.** `GET /jobs/{jobId}/stream` is allowed for the print
key, but a browser `EventSource` **cannot set the `X-API-Key` header**, so plain
`new EventSource(url)` won't authenticate. To stream from the browser you need a fetch-based SSE
reader that sets the header (e.g. `@microsoft/fetch-event-source`); otherwise use polling above.

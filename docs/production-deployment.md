# Production deployment

[← Back to README](../README.md)

Deploy with `docker-compose.prod.yml`: **one management service** plus **one worker per Zebra
printer**. Follow the [deployment steps](#deployment) in order.

---

## Overview

| Service | Role |
|---|---|
| **management** | The only public service (HTTPS on 8443). Holds the TLS certificate, the DB connection, and the dynamic printer registry (built from worker self-registration). Flyway runs the migrations here, once, on startup |
| **workers** | One per printer; internal HTTP only, no certificate and no database. Each worker self-registers with management on startup and sends a heartbeat |
| **database** | Not bundled — management connects to a dedicated, remote `stup_wristband_db` database on the Symfony site's Postgres instance |
| **API key (admin)** | Management and every worker share the same `API_KEY`. It unlocks everything — keep it server-side, never in a browser |
| **Print-only key (optional)** | A *separate*, limited key (`PRINT_API_KEY`) + a CORS allow-list (`CORS_ALLOWED_ORIGINS`) let the Symfony browser app call `/print` + `/preview` directly without exposing the admin key. Off unless you set both. See [Direct browser calls from Symfony](#direct-browser-calls-from-symfony-optional) |

> 📝 Throughout the steps below, replace every **`[placeholder]`** with your real value. The
> per-printer placeholders — **`[printer-N-ip]`** and **`[printer-N-label]`** — are the ones you fill
> in per Zebra.

## Prerequisites

- An empty `stup_wristband_db` database + role exists on the prod Postgres (a DBA creates the database;
  Flyway creates the tables — see the note below).
- Every Zebra is reachable from the server — verify with `ping [printer-1-ip]`.
- The base image is built: `./build.sh`.

> 📝 **Database tables / migrations.** The schema is managed by Flyway; the migration scripts live in
> [`src/main/resources/db/migration`](../src/main/resources/db/migration) (`V1__…​.sql`, `V2__…​.sql`, …).
> Management runs them **automatically** against the remote database the first time it starts, so no
> manual step is needed when the DB role has DDL rights. If your DB user is restricted to DML, have a
> DBA apply those `.sql` files **in version order** once, before launching — then management starts
> against the already-migrated schema.

## Deployment

### 1. Configure secrets, the database, and the printer IPs

Copy the example env file:

```bash
cp .env.example .env.prod
```

Edit `.env.prod` — one `PRINTERn_HOST` line per physical printer:

```dotenv
API_KEY=[strong-api-key]
ADMIN_PASSWORD=[strong-admin-password]
SSL_KEYSTORE_PASSWORD=[strong-keystore-password]
MANAGEMENT_HOSTNAME=[hostname-symfony-connects-to]

SPRING_DATASOURCE_URL=jdbc:postgresql://[db-host]:5432/stup_wristband_db
DB_USERNAME=[db-user]
DB_PASSWORD=[db-password]

PRINTER1_HOST=[printer-1-ip]
PRINTER2_HOST=[printer-2-ip]

# Optional — only if the Symfony app prints straight from the browser. Leave out otherwise.
# Use a DIFFERENT value than API_KEY (this one is visible in the browser).
# PRINT_API_KEY=[separate-print-only-key]
# CORS_ALLOWED_ORIGINS=https://[symfony-site]
```

### 2. Declare one worker per printer

In `docker-compose.prod.yml`, `printer-worker-1` already exists. For each additional printer,
uncomment/copy the `printer-worker-2` template and point it at that printer's `PRINTERn_HOST`. Each
worker also carries its **self-registration identity** — `WORKER_ID` (the value Symfony sends as
`printerId`), `WORKER_DISPLAY_NAME` (shown in the UI), `WORKER_BASE_URL` (the worker's own
in-network URL, used by management to forward print jobs) and `WORKER_MANAGEMENT_BASE_URL` (where
the worker calls management to register and send heartbeats):

```yaml
  printer-worker-2:
    <<: *worker-base
    environment:
      SPRING_PROFILES_ACTIVE: worker
      SECURITY_API_KEY: ${API_KEY}
      PRINTER_HOST: ${PRINTER2_HOST}
      WORKER_ID: printer-2
      WORKER_DISPLAY_NAME: [printer-2-label]
      WORKER_BASE_URL: http://printer-worker-2:8080
      WORKER_MANAGEMENT_BASE_URL: http://management:8081
```

Add each new worker to the management service's `depends_on` list.

> 📝 **Worker → management uses an internal plain-HTTP port.** Management's public connector is
> HTTPS-only on 8443 with a self-signed certificate, which a worker's `RestClient` cannot verify
> (PKIX failure). So management *also* listens on a plain-HTTP port (`8081`, set via
> `server.internal-http.*` in `application-prod.yml`) that is **not** published to the host — only
> reachable on the private Docker network. Workers therefore set
> `WORKER_MANAGEMENT_BASE_URL=http://management:8081` (not `https://...:8443`). Public traffic stays
> HTTPS-only; see [HTTPS & Symfony certificate trust](#https--symfony-certificate-trust).

### 3. Printers self-register — no static registry to edit

Management holds **no static printer list**. On startup (and via heartbeat) each worker calls
management's internal registration endpoint using its `WORKER_ID`, `WORKER_DISPLAY_NAME`,
`WORKER_BASE_URL` and `WORKER_MANAGEMENT_BASE_URL`, and management adds/refreshes that printer in
its registry automatically. There is nothing to edit on the `management` service for a new
printer beyond the worker definition in step 2.

### 4. Launch

```bash
./build.sh
docker compose -f docker-compose.prod.yml --env-file .env.prod up --build -d
```

### 5. Verify

```bash
# health (self-signed cert → -k)
curl -fsk https://[management-hostname]:8443/actuator/health

# the registry lists every printer you configured
curl -fsk https://[management-hostname]:8443/api/wristbands/printers \
  -H "X-API-Key: [api-key]"

# a test print to a specific printer (note the required lowercase "wristbandType")
curl -fsk -X POST https://[management-hostname]:8443/api/wristbands/print \
  -H "X-API-Key: [api-key]" -H "Content-Type: application/json" \
  -d '{"wristbandType":"crew","eventName":"Test","firstName":"Jan","lastName":"Janssen","clubName":"STUP","barcodeValue":"123","printerId":"printer-1"}'
```

Then open `https://[management-hostname]:8443/jobs.html` (admin / your `ADMIN_PASSWORD`).

## Adding a printer later

A printer is **one worker service**, added in `docker-compose.prod.yml`, then a redeploy — there is
no registry to edit on management. The example below adds a second printer (`printer-2`); bump the
index for each further printer.

**1. `.env.prod`** — declare the new printer's IP:

```dotenv
PRINTER2_HOST=10.0.0.52
```

**2. `docker-compose.prod.yml`** — add a worker service with its self-registration identity. The
file already ships a commented `printer-worker-2` template right after `printer-worker-1`; uncomment
it (or copy the block and bump the index for a third printer):

```yaml
  printer-worker-2:
    <<: *worker-base
    environment:
      SPRING_PROFILES_ACTIVE: worker
      SECURITY_API_KEY: ${API_KEY}
      PRINTER_HOST: ${PRINTER2_HOST}
      WORKER_ID: printer-2
      WORKER_DISPLAY_NAME: Inkom
      WORKER_BASE_URL: http://printer-worker-2:8080
      WORKER_MANAGEMENT_BASE_URL: http://management:8081
```

(Optionally add `printer-worker-2` to the management `depends_on:` list so it starts first.) See the
note in step 2 above on why `WORKER_MANAGEMENT_BASE_URL` points at the internal HTTP port
(`http://management:8081`) rather than HTTPS.

**3. Redeploy** — pick the command for your situation:

**Image already built — just add the new worker** (leaves the running services untouched):

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d printer-worker-2
```

**App code or image changed — rebuild and recreate** management + workers:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up --build -d
```

The new printer then self-registers on startup and appears in `GET /api/wristbands/printers`, the
jobs-page printer filter, and the reprint picker. Workers do **not** publish a host port and need no
certificate. The **first** printer to register is the default when a request omits `printerId`; an
unknown `printerId` is rejected with **400**.

> The local virtual cluster works the same way — `docker-compose.local-cluster.yml` defines its
> workers with `WORKER_ID` / `WORKER_DISPLAY_NAME` / `WORKER_BASE_URL` /
> `WORKER_MANAGEMENT_BASE_URL`, and they self-register with `management` over the plain-HTTP
> in-network connection (no TLS prerequisite locally).

## Direct browser calls from Symfony (optional)

By default every endpoint needs the **admin** `API_KEY`, which must stay server-side. If the Symfony
app prints **straight from the user's browser**, turn on the limited path instead — a **print-only
key** plus a **CORS allow-list** — so you never ship the admin key to the browser.

**What it does**

- The print-only key works on `POST /api/wristbands/print`, `/preview/zpl`, `/preview/image`, and on
  reading **its own job's status**: `GET /jobs/{jobId}` and `GET /jobs/{jobId}/stream`.
- It is rejected (401) on everything else — the global job list, the global stream, reprint, cancel,
  printers, templates.
- CORS lets the browser call cross-origin; without it the browser blocks the request.

**1. Set two values in `.env.prod`:**

```dotenv
PRINT_API_KEY=[separate-print-only-key]     # different from API_KEY — it is visible in the browser
CORS_ALLOWED_ORIGINS=https://[symfony-site]  # exact origin(s), comma-separated, e.g. https://www.stupvzw.be
```

Leave both unset to keep the feature off. Restart management to apply.

> ⚠️ CORS matches the origin **exactly** (scheme + host). `https://www.stupvzw.be` and
> `https://stupvzw.be` (without `www`) are *different* origins. List every origin the site is
> actually served from, comma-separated — e.g.
> `CORS_ALLOWED_ORIGINS=https://www.stupvzw.be,https://stupvzw.be`.

**2. In the Symfony front-end**, send the **print-only** key as the `X-API-Key` header on the call
to `https://<MANAGEMENT_HOSTNAME>:8443/api/wristbands/print`. Never put `API_KEY` there. A full
copy-paste example (Symfony config → controller → Twig + JS) is in
[symfony-usage-example.md](symfony-usage-example.md).

**3. Certificate — the catch for browser calls.** Management uses a **self-signed** certificate
(below). A browser `fetch()` to a self-signed endpoint **fails silently** (it shows up as a CORS
error) because there is no "accept the risk" prompt for background requests. So for the browser path
you must either:

- give management a **real, CA-signed certificate** (replace the keystore — see the cert section
  below), **or**
- install the management CA certificate in **every operator's browser/OS trust store**.

> 💡 If you can't do either, use the **server-side proxy** instead: the browser calls Symfony
> same-origin, and Symfony forwards server-side with the admin key. Then only the Symfony *server*
> must trust the cert, not each browser. Full comparison and code:
> [symfony-proxy-integration.md](symfony-proxy-integration.md).

**Verify** (from a machine that trusts the cert; `-k` skips the check for a quick test):

```bash
# print-only key works on preview …
curl -k -X POST https://[management-hostname]:8443/api/wristbands/preview/zpl \
  -H "X-API-Key: [print-only-key]" -H "Content-Type: application/json" \
  -d '{"wristbandType":"crew","eventName":"Test","firstName":"Jan","lastName":"Janssen","clubName":"STUP","barcodeValue":"123"}'

# … but is refused (401) on an admin endpoint
curl -k -o /dev/null -w '%{http_code}\n' https://[management-hostname]:8443/api/wristbands/jobs \
  -H "X-API-Key: [print-only-key]"      # expect 401
```

## HTTPS & Symfony certificate trust

Only **management** terminates TLS: in the `prod` profile it listens **HTTPS-only on 8443** with a
self-signed certificate. Workers are HTTP on the private Docker network and are never exposed.
Symfony reaches management at `https://<MANAGEMENT_HOSTNAME>:8443/...` — either from a **browser**
(needs a browser-trusted cert, see [Direct browser calls](#direct-browser-calls-from-symfony-optional))
or **server-side** (the proxy alternative; only the Symfony server trusts the cert, via `cafile`
below).

The keystore is generated on first start and stored in the `certs-management` volume (reused across
redeploys, so the cert is stable). `MANAGEMENT_HOSTNAME` (in `.env.prod`) becomes the certificate's
CN/SAN — set it before the first start; the compose file maps it to `SSL_CERT_HOSTNAME`. To
regenerate, remove the volume: `docker volume rm <project>_certs-management`.

**Export the public certificate** from the running container:

```bash
docker compose -f docker-compose.prod.yml cp management:/certs/server.crt ./server.crt
```

**Trust it in Symfony** — either (recommended) point the HTTP client at it as a CA:

```yaml
# config/packages/framework.yaml
framework:
    http_client:
        scoped_clients:
            wristband.client:
                base_uri: 'https://<MANAGEMENT_HOSTNAME>:8443'
                cafile: '%kernel.project_dir%/config/certs/server.crt'
```

...or, on a trusted private network, disable peer verification instead:

```yaml
                verify_peer: false
                verify_host: false
```

### Browser calls need a *real* certificate

`verify_peer: false` only works **server-side** (the proxy). A **browser** cannot skip an untrusted
certificate for a background `fetch()` — the request just fails (it shows up as a CORS / "request did
not succeed" error), with **no** "accept the risk" prompt like the one you click on the jobs UI. So
if Symfony prints **straight from the browser** (the [Direct browser calls](#direct-browser-calls-from-symfony-optional)
path), the self-signed cert **will not work** — you need one the browser already trusts.

**Give management a real (CA-signed) certificate.** The entrypoint **reuses** any keystore already at
`/certs/keystore.p12`, so you just drop a real one in:

```bash
# 1. From your CA-signed cert + key, build a PKCS12 keystore (password = SSL_KEYSTORE_PASSWORD)
openssl pkcs12 -export -in fullchain.pem -inkey privkey.pem \
  -out keystore.p12 -passout pass:"$SSL_KEYSTORE_PASSWORD"

# 2. Put it in the management certs volume as keystore.p12 (replacing the self-signed one), restart
docker compose -f docker-compose.prod.yml cp ./keystore.p12 management:/certs/keystore.p12
docker compose -f docker-compose.prod.yml restart management   # logs "Reusing existing keystore"
```

Renew before expiry by replacing the file and restarting. (Alternatively, put a reverse proxy /
load balancer with a real cert in front of `:8443` — then it terminates TLS for the browser.)

### Certificate hostname must match the URL

The cert's CN/SAN is fixed the **first** time the keystore is generated, from `SSL_CERT_HOSTNAME`
(= `MANAGEMENT_HOSTNAME`). Because the keystore is **reused**, changing `MANAGEMENT_HOSTNAME` later
does **not** update it — the browser then shows a name-mismatch error even after you accept the
warning. If the URL changed (e.g. to `stupllp001.stupvzw.be`), fix the env value and regenerate:

```bash
# .env.prod
MANAGEMENT_HOSTNAME=stupllp001.stupvzw.be

# delete the old keystore so it regenerates with the right hostname, then restart
docker volume rm <project>_certs-management     # or: delete /certs/keystore.p12 in the volume
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d management
```

> Check what the running cert actually says:
> `echo | openssl s_client -connect stupllp001.stupvzw.be:8443 -servername stupllp001.stupvzw.be 2>/dev/null | openssl x509 -noout -subject -issuer -dates`

> ⚠️ `MANAGEMENT_HOSTNAME` must match the hostname Symfony connects to, or hostname verification fails.

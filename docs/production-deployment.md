# Production deployment

[← Back to README](../README.md)

Deploy with `docker-compose.prod.yml`: **one management service** plus **one worker per Zebra
printer**. Follow the [deployment steps](#deployment) in order.

---

## Overview

| Service | Role |
|---|---|
| **management** | The only public service (HTTPS on 8443). Holds the TLS certificate, the DB connection, and the printer registry. Flyway runs the migrations here, once, on startup |
| **workers** | One per printer; internal HTTP only, no certificate and no database |
| **database** | Not bundled — management connects to a dedicated, remote `wristbands` database on the Symfony site's Postgres instance |
| **API key** | Management and every worker share the same `API_KEY` |

> 📝 Throughout the steps below, replace every **`[placeholder]`** with your real value. The
> per-printer placeholders — **`[printer-N-ip]`** and **`[printer-N-label]`** — are the ones you fill
> in per Zebra.

## Prerequisites

- An empty `wristbands` database + role exists on the prod Postgres (a DBA creates the database;
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

SPRING_DATASOURCE_URL=jdbc:postgresql://[db-host]:5432/wristbands
DB_USERNAME=[db-user]
DB_PASSWORD=[db-password]

PRINTER1_HOST=[printer-1-ip]
PRINTER2_HOST=[printer-2-ip]
```

### 2. Declare one worker per printer

In `docker-compose.prod.yml`, `printer-worker-1` already exists. For each additional printer,
uncomment/copy the `printer-worker-2` template and point it at that printer's `PRINTERn_HOST`:

```yaml
  printer-worker-2:
    <<: *worker-base
    environment:
      SPRING_PROFILES_ACTIVE: worker
      SECURITY_API_KEY: ${API_KEY}
      PRINTER_HOST: ${PRINTER2_HOST}
```

Add each new worker to the management service's `depends_on` list.

### 3. Register the printers in management

In the `management` service, edit `SPRING_APPLICATION_JSON` so the registry lists every worker.
`id` is what Symfony sends as `printerId`, `display-name` is shown in the UI, and the `base-url`
host **must** equal the worker's service name. Only `[printer-N-label]` is free text:

```yaml
      SPRING_APPLICATION_JSON: '{"cluster":{"printers":[{"id":"printer-1","display-name":"[printer-1-label]","base-url":"http://printer-worker-1:8080"},{"id":"printer-2","display-name":"[printer-2-label]","base-url":"http://printer-worker-2:8080"}]}}'
```

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

# a test print to a specific printer
curl -fsk -X POST https://[management-hostname]:8443/api/wristbands/print \
  -H "X-API-Key: [api-key]" -H "Content-Type: application/json" \
  -d '{"eventName":"Test","firstName":"Jan","lastName":"Janssen","associationName":"STUP","barcodeValue":"123","printerId":"printer-1"}'
```

Then open `https://[management-hostname]:8443/jobs.html` (admin / your `ADMIN_PASSWORD`).

## Adding a printer later

A printer is **one worker service + one registry entry**, edited together in the same two files,
then a redeploy. The example below adds a second printer (`printer-2`); bump the index for each
further printer.

**1. `.env.prod`** — declare the new printer's IP:

```dotenv
PRINTER2_HOST=10.0.0.52
```

**2a. `docker-compose.prod.yml`** — add a worker service. The file already ships a commented
`printer-worker-2` template right after `printer-worker-1`; uncomment it (or copy the block and bump
the index for a third printer):

```yaml
  printer-worker-2:
    <<: *worker-base
    environment:
      SPRING_PROFILES_ACTIVE: worker
      SECURITY_API_KEY: ${API_KEY}
      PRINTER_HOST: ${PRINTER2_HOST}
```

**2b. `docker-compose.prod.yml`** — add one line to the **management** printer registry in
`SPRING_APPLICATION_JSON`. The `base-url` host **must** equal the worker's service name, and `id` is
the value Symfony sends as `printerId`:

```yaml
      SPRING_APPLICATION_JSON: '{
        "cluster":
          {"printers":[
            {"id":"printer-1", "display-name":"Secretariaat", "base-url":"http://printer-worker-1:8080"},
            {"id":"printer-2", "display-name":"Inkom",        "base-url":"http://printer-worker-2:8080"}
          ]}
        }'
```

(Optionally add `printer-worker-2` to the management `depends_on:` list so it starts first.)

**3. Redeploy:**

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up --build -d
```

The new printer then appears in `GET /api/wristbands/printers`, the jobs-page printer filter, and the
reprint picker. Workers do **not** publish a host port and need no certificate. The **first**
registered printer is the default when a request omits `printerId`; an unknown `printerId` is
rejected with **400**.

> The local virtual cluster works the same way — `docker-compose.local-cluster.yml` defines its
> workers and the `cluster.printers` registry (as YAML, not JSON) in exactly this shape.

## HTTPS & Symfony certificate trust

Only **management** terminates TLS: in the `prod` profile it listens **HTTPS-only on 8443** with a
self-signed certificate. Workers are HTTP on the private Docker network and are never exposed.
Symfony calls management at `https://<MANAGEMENT_HOSTNAME>:8443/...`.

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

> ⚠️ `MANAGEMENT_HOSTNAME` must match the hostname Symfony connects to, or hostname verification fails.

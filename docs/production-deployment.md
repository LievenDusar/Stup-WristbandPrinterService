# Production deployment

[← Back to README](../README.md)

`docker-compose.prod.yml` runs **one management service** plus **one worker per Zebra printer**:

- **management** — the only public service (HTTPS on 8443). Holds the TLS certificate, the database
  connection, and the printer registry. Flyway runs the migrations here, once, on startup.
- **workers** — one per printer; internal HTTP only, no certificate and no database.
- **database** — not bundled: management connects to a dedicated, remote `wristbands` database on the
  Symfony site's Postgres instance.
- **API key** — management and every worker share the same `API_KEY`.

> Throughout the steps below, replace every **`[placeholder]`** with your real value. The per-printer
> placeholders — **`[printer-N-ip]`** and **`[printer-N-label]`** — are the ones you fill in per Zebra.

**Prerequisites**

- An empty `wristbands` database + role exists on the prod Postgres (a DBA creates the database;
  Flyway creates the tables — see the note below).
- Every Zebra is reachable from the server — verify with `ping [printer-1-ip]`.
- The base image is built: `./build.sh`.

> **Database tables / migrations.** The schema is managed by Flyway; the migration scripts live in
> [`src/main/resources/db/migration`](../src/main/resources/db/migration) (`V1__…​.sql`, `V2__…​.sql`, …).
> Management runs them **automatically** against the remote database the first time it starts, so no
> manual step is needed when the DB role has DDL rights. If your DB user is restricted to DML, have a
> DBA apply those `.sql` files **in version order** once, before launching — then management starts
> against the already-migrated schema.

**Step 1 — Configure secrets, the database, and the printer IPs (`.env.prod`)**

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

**Step 2 — Declare one worker per printer (`docker-compose.prod.yml`)**

`printer-worker-1` already exists. For each additional printer, uncomment/copy the
`printer-worker-2` template and point it at that printer's `PRINTERn_HOST`:

```yaml
  printer-worker-2:
    <<: *worker-base
    environment:
      SPRING_PROFILES_ACTIVE: worker
      SECURITY_API_KEY: ${API_KEY}
      PRINTER_HOST: ${PRINTER2_HOST}
```

Add each new worker to the management service's `depends_on` list.

**Step 3 — Register the printers in management (`docker-compose.prod.yml`)**

In the `management` service, edit `SPRING_APPLICATION_JSON` so the registry lists every worker.
`id` is what Symfony sends as `printerId`, `display-name` is shown in the UI, and the `base-url`
host **must** equal the worker's service name. Only `[printer-N-label]` is free text:

```yaml
      SPRING_APPLICATION_JSON: '{"cluster":{"printers":[{"id":"printer-1","display-name":"[printer-1-label]","base-url":"http://printer-worker-1:8080"},{"id":"printer-2","display-name":"[printer-2-label]","base-url":"http://printer-worker-2:8080"}]}}'
```

**Step 4 — Launch**

```bash
./build.sh
docker compose -f docker-compose.prod.yml --env-file .env.prod up --build -d
```

**Step 5 — Verify**

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

**Adding another printer later** — repeat the same edits for the next index, then redeploy:

1. `.env.prod`: add `PRINTER3_HOST=[printer-3-ip]`.
2. `docker-compose.prod.yml`: add a `printer-worker-3` service (Step 2) and a registry entry
   `{"id":"printer-3","display-name":"[printer-3-label]","base-url":"http://printer-worker-3:8080"}` (Step 3).
3. `docker compose -f docker-compose.prod.yml --env-file .env.prod up --build -d`.

The new printer then appears in `GET /api/wristbands/printers`, the jobs-page filter chips, and the
reprint picker. Workers do **not** publish a host port and need no certificate.

## HTTPS and Symfony cert trust

Only **management** terminates TLS: in the `prod` profile it listens **HTTPS-only on 8443** with a
self-signed certificate. Workers are HTTP on the private Docker network and are never exposed.
Symfony calls management at `https://<MANAGEMENT_HOSTNAME>:8443/...`.

The keystore is generated on first start and stored in the `certs-management` volume (reused across
redeploys, so the cert is stable). `MANAGEMENT_HOSTNAME` (in `.env.prod`) becomes the certificate's
CN/SAN — set it before the first start; the compose file maps it to `SSL_CERT_HOSTNAME`. To
regenerate, remove the volume: `docker volume rm <project>_certs-management`.

Export the public certificate from the running container:

```bash
docker compose -f docker-compose.prod.yml cp management:/certs/server.crt ./server.crt
```

Then either (recommended) point the Symfony HTTP client at it as a CA:

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

`MANAGEMENT_HOSTNAME` must match the hostname Symfony connects to, or hostname verification fails.

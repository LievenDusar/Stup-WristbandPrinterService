# Running locally

[← Back to README](../README.md)

Get started locally in one of two ways — a **basic** native run in IntelliJ for the fastest inner
loop, or the **full** Docker virtual cluster that mirrors production. Pick by what you need:

| Path | Setup | Best for | Prints? |
|---|---|---|---|
| **[In IntelliJ](#in-intellij)** | Basic | Fastest inner loop (JDK + Maven) | Only with an added worker |
| **[Via Docker](#via-docker)** | Full | Closest to production; no host Java | Yes — virtual cluster with fake printers |

## Contents

- **[In IntelliJ](#in-intellij)** — basic native run
  - [Prerequisites](#prerequisites)
  - [Steps](#steps)
  - [Optional: print end-to-end](#optional-print-end-to-end)
  - [Troubleshooting](#troubleshooting)
- **[Via Docker](#via-docker)** — full virtual cluster
  - [Prerequisites](#prerequisites-1)
  - [Run the cluster](#run-the-cluster)
  - [Rebuilding after code changes](#rebuilding-after-code-changes)
  - [Adding a third (virtual) printer](#adding-a-third-virtual-printer)
  - [Other run modes](#other-run-modes)
  - [Troubleshooting](#troubleshooting-1)

---

## In IntelliJ

Runs the **management** service from the IDE. On its own it serves the UI and API but **does not
print** — add a worker ([Optional: print end-to-end](#optional-print-end-to-end)) for the full flow.

### Prerequisites

- JDK 21
- IntelliJ IDEA
- Docker (for a local PostgreSQL)

### Steps

1. **Add the logo** — place `stup-logo.png` in `src/main/resources/images/`.
2. **Start PostgreSQL** — the `local` profile expects database `stup_wristband_db` and user/password
   `wristbands` / `wristbands` on `localhost:5432`:

   ```bash
   docker run --name stup-pg \
     -e POSTGRES_DB=stup_wristband_db -e POSTGRES_USER=wristbands -e POSTGRES_PASSWORD=wristbands \
     -p 5432:5432 -d postgres:16-alpine
   ```

   Flyway creates the schema on first start.
3. **Open the project** — `File ▸ Open`, select `pom.xml`, import as a Maven project, and let
   IntelliJ download the dependencies.
4. **Run with the `local` profile** — run `WristbandPrinterApplication` once to generate a Spring
   Boot run configuration, then edit it and set **Active profiles** to `local`. Run again.
   Management starts on **http://localhost:8080** → `/jobs.html` (admin / `local-admin`).

### Optional: print end-to-end

Management alone doesn't print. To make jobs land somewhere, run a worker against a fake printer:

1. **Start a fake printer** in a terminal:

   ```bash
   while true; do nc -l 9100; done
   ```

2. **Duplicate the run configuration** and set these **environment variables**:

   ```
   SPRING_PROFILES_ACTIVE=worker;SECURITY_API_KEY=local-dev-key;PRINTER_HOST=localhost;PRINTER_PORT=9100;SERVER_PORT=8089
   ```

`application-local.yml` already registers `printer-1` at `http://localhost:8089`, so jobs flow
management → worker → fake printer.

### Troubleshooting

> 💡 **Port 5432 already in use?** Start the container with `-p 5433:5432` and set
> `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/stup_wristband_db` in the run
> configuration's environment. (PostgreSQL only sets the password when the data volume is first
> created — if you reused an old `stup-pg`, run `docker rm -f stup-pg` and recreate.)

---

## Via Docker

No host Java needed. The **virtual cluster** (`docker-compose.local-cluster.yml`) mirrors the
production topology **without real printers**: Postgres + management + two workers + two fake
printers (`socat` TCP listeners that log the ZPL they receive).

### Prerequisites

Build the shared base image once (and after changing `docker/base/Dockerfile`):

```bash
./build.sh
```

### Run the cluster

1. **Start the stack:**

   ```bash
   docker compose -f docker-compose.local-cluster.yml up --build -d
   ```

2. **Open the UI** — **http://localhost:8080/jobs.html** (admin / `local-admin`). Two printers are
   registered (`printer-1`, `printer-2`), each wired to its own fake printer. The jobs page shows the
   **Printer** column, a per-printer **filter**, parallel printing, and the **reprint printer picker**.

3. **Send test prints** — omit `printerId` for the default printer, or set it to target one:

   ```bash
   curl -s -X POST http://localhost:8080/api/wristbands/print \
     -H "Content-Type: application/json" -H "X-API-Key: local-dev-key" \
     -d '{"eventName":"Test","firstName":"Jan","lastName":"Janssen","clubName":"STUP","barcodeValue":"111"}'

   curl -s -X POST http://localhost:8080/api/wristbands/print \
     -H "Content-Type: application/json" -H "X-API-Key: local-dev-key" \
     -d '{"eventName":"Test","firstName":"An","lastName":"Peeters","clubName":"STUP","barcodeValue":"222","printerId":"printer-2"}'
   ```

4. **Watch the ZPL arrive** at each fake printer:

   ```bash
   docker compose -f docker-compose.local-cluster.yml logs -f fakeprinter-1 fakeprinter-2
   ```

5. **Stop:**

   ```bash
   docker compose -f docker-compose.local-cluster.yml down
   ```

### Rebuilding after code changes

There is **no live reload** in the Docker path — the app is baked into the `wristband-printer` image
at build time. After editing any application code (Java, `application*.yml`, Flyway migrations, or
the static `*.html` / `js/` / `css/` files), rebuild and recreate the containers:

```bash
docker compose -f docker-compose.local-cluster.yml up --build -d
```

`--build` rebuilds the image once (via `worker-1`'s `build: .`) and recreates **management** and both
**workers** from it. Postgres and the fake printers keep running, and **your job history in Postgres
survives** the rebuild.

**Common variations:**

```bash
# Watch management come back up after the rebuild
docker compose -f docker-compose.local-cluster.yml logs -f management

# Rebuild a single service (e.g. after a worker-only change)
docker compose -f docker-compose.local-cluster.yml up --build -d worker-1 worker-2

# Force a clean rebuild that ignores Docker's layer cache
docker compose -f docker-compose.local-cluster.yml build --no-cache
docker compose -f docker-compose.local-cluster.yml up -d

# Rebuild AND wipe the database (fresh Flyway run from V1 — drops all job history)
docker compose -f docker-compose.local-cluster.yml down -v
docker compose -f docker-compose.local-cluster.yml up --build -d
```

> ⚠️ **Changed `docker/base/Dockerfile`?** That edit is *not* picked up by `--build` (it only
> rebuilds the app image, which is `FROM wristband-base:21`). Rebuild the base image first:
>
> ```bash
> ./build.sh
> docker compose -f docker-compose.local-cluster.yml up --build -d
> ```

### Adding a third (virtual) printer

The cluster ships two printers (`printer-1`, `printer-2`). A third is just **two services** added to
`docker-compose.local-cluster.yml` — a fake printer and its worker. Workers **self-register**, so
there is nothing to edit on `management`.

1. **Add a fake printer** (a `socat` TCP listener that logs the ZPL it receives) next to
   `fakeprinter-1` / `fakeprinter-2`:

   ```yaml
     fakeprinter-3:
       image: alpine/socat
       command: -u TCP-LISTEN:9100,fork,reuseaddr OPEN:/dev/stdout
       restart: unless-stopped
   ```

2. **Add a worker** next to `worker-1` / `worker-2`, pointed at that fake printer:

   ```yaml
     worker-3:
       <<: *worker-base
       build: .
       environment:
         - SPRING_PROFILES_ACTIVE=worker
         - SECURITY_API_KEY=local-dev-key
         - PRINTER_HOST=fakeprinter-3
         - PRINTER_PORT=9100
         - WORKER_ID=printer-3
         - WORKER_DISPLAY_NAME=Inkom
         - WORKER_BASE_URL=http://worker-3:8080
         - WORKER_MANAGEMENT_BASE_URL=http://management:8080
       depends_on:
         - fakeprinter-3
   ```

3. **Create only the two new containers.** Name the services so Compose leaves Postgres,
   management, and the existing workers running. **Omit `--build`**: no application code changed, so
   this reuses the already-built `wristband-printer` image instead of rebuilding it.

   ```bash
   docker compose -f docker-compose.local-cluster.yml up -d worker-3 fakeprinter-3
   ```

   `worker-3` already `depends_on` `fakeprinter-3`, so naming just `worker-3` starts both; add
   `--no-deps` if you want to guarantee nothing else is touched.

4. **Verify it registered** and watch ZPL arrive:

   ```bash
   curl -s http://localhost:8080/api/wristbands/printers \
     -H "X-API-Key: local-dev-key"                          # printer-3 now listed
   docker compose -f docker-compose.local-cluster.yml logs -f fakeprinter-3
   ```

   `printer-3` ("Inkom") then appears in the jobs-UI printer filter and **Menu ▸ Manage printers**.
   Target it from a print request with `"printerId":"printer-3"`.

> 💡 `WORKER_ID` is the public `printerId`; `WORKER_DISPLAY_NAME` is only the UI label.
> `WORKER_BASE_URL` must match the service name (`worker-3`) — management reaches the worker over the
> Docker network, not via `PRINTER_HOST`.

### Other run modes

**Management only** — for pure UI/template work without printers, `docker compose up --build` runs
just Postgres + management on HTTP 8080. Printing fails until a worker exists.

### Troubleshooting

> ⚠️ **Upgrading from an older compose?** If `docker-compose.yml` previously ran with a custom
> `DB_PASSWORD`, the persisted `pgdata` volume was initialized with it and the new hardcoded
> `wristbands` credentials fail. Run `docker compose down -v` once to recreate the volume.

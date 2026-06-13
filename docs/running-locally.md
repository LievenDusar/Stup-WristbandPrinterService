# Running locally

[← Back to README](../README.md)

Two ways to run the stack on your machine:

| Path | Best for | Prints? |
|---|---|---|
| **[In IntelliJ](#in-intellij)** | Fastest inner loop (JDK + Maven) | Only with an added worker |
| **[Via Docker](#via-docker)** | Closest to production; no host Java | Yes — virtual cluster with fake printers |

---

## In IntelliJ

Runs the **management** service from the IDE. On its own it serves the UI/API but does not print —
add a worker (step 5) for end-to-end printing.

### Prerequisites

JDK 21, IntelliJ IDEA, Docker (for a local PostgreSQL).

### Steps

1. **Add the logo** — place `stup-logo.png` in `src/main/resources/images/`.
2. **Start PostgreSQL** — the `local` profile expects database `stup_wristband_db`, user/password
   `wristbands`/`wristbands` on `localhost:5432`:

   ```bash
   docker run --name stup-pg \
     -e POSTGRES_DB=stup_wristband_db -e POSTGRES_USER=wristbands -e POSTGRES_PASSWORD=wristbands \
     -p 5432:5432 -d postgres:16-alpine
   ```

   Flyway creates the schema on first start.
3. **Open the project** — `File ▸ Open` and select the `pom.xml`; import it as a Maven project and
   let IntelliJ download the dependencies.
4. **Run management with the `local` profile** — open `WristbandPrinterApplication` and run it once
   to generate a Spring Boot run configuration, then edit that configuration and set **Active
   profiles** to `local` (equivalent to `--spring.profiles.active=local`). Run again. Management
   starts on **http://localhost:8080** → `/jobs.html` (admin / `local-admin`).
5. **(Optional) Run a worker so prints land somewhere** — start a fake printer in a terminal:

   ```bash
   while true; do nc -l 9100; done
   ```

   Then duplicate the run configuration and set these **Environment variables**:

   ```
   SPRING_PROFILES_ACTIVE=worker;SECURITY_API_KEY=local-dev-key;PRINTER_HOST=localhost;PRINTER_PORT=9100;SERVER_PORT=8089
   ```

   `application-local.yml` already registers `printer-1` at `http://localhost:8089`, so jobs flow
   management → worker → fake printer.

> 💡 **Port 5432 already in use?** Start the container with `-p 5433:5432` and set
> `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/stup_wristband_db` in the run configuration's
> environment. (PostgreSQL only sets the password when the data volume is first created — if you
> reused an old `stup-pg`, `docker rm -f stup-pg` and recreate.)

## Via Docker

No host Java needed. Build the shared base image once (and after changing `docker/base/Dockerfile`):

```bash
./build.sh
```

The **full virtual cluster** (`docker-compose.local-cluster.yml`) mirrors the production topology
**without real printers**: Postgres + management + two workers + two fake printers (`socat` TCP
listeners that log the ZPL they receive).

### Steps

1. **Start the stack:**

   ```bash
   docker compose -f docker-compose.local-cluster.yml up --build -d
   ```

2. **Open the UI** — **http://localhost:8080/jobs.html** (admin / `local-admin`). Two printers are
   registered (`printer-1`, `printer-2`), each wired to its own fake printer.
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

5. **Stop:** `docker compose -f docker-compose.local-cluster.yml down`.

The jobs page shows the **Printer** column, a per-printer **filter dropdown**, parallel printing and the
**reprint printer picker**.

### Rebuilding after code changes

The application is baked into the `wristband-printer` image at build time — there is **no live
reload** in the Docker path. After editing **any** application code (Java, `application*.yml`,
Flyway migrations, or the static `*.html` / `js/` / `css/` files), rebuild the image and recreate
the containers:

```bash
docker compose -f docker-compose.local-cluster.yml up --build -d
```

`--build` rebuilds the `wristband-printer` image once (via the `worker-1` service's `build: .`) and
recreates the **management** and both **worker** containers from the new image. Postgres and the
fake printers are left running, and **your job history in Postgres survives** the rebuild.

Common variations:

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

### Variations

**Management only** — for pure UI/template work without printers, `docker compose up --build` runs
just Postgres + management on HTTP 8080; printing fails until a worker exists.

> 💡 **Add a virtual printer:** add a `fakeprinter-3` (copy a socat service) and a `worker-3`
> (`PRINTER_HOST=fakeprinter-3`) to `docker-compose.local-cluster.yml`, then add a third entry to the
> management `SPRING_APPLICATION_JSON` registry pointing at `http://worker-3:8080`.

> ⚠️ **Upgrading from an older compose?** If `docker-compose.yml` previously ran with a custom
> `DB_PASSWORD`, the persisted `pgdata` volume was initialized with it and the new hardcoded
> `wristbands` credentials fail. Run `docker compose down -v` once to recreate the volume.

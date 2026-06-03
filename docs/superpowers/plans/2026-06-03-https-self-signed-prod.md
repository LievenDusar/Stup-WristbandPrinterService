# Self-Signed HTTPS (prod) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the wristband printer service reachable over HTTPS (self-signed) in the prod/Docker deployment so the STUP Symfony app can call it securely.

**Architecture:** Spring Boot terminates TLS itself in the `prod` profile, listening HTTPS-only on port 8443. A self-signed PKCS12 keystore is generated once by a container entrypoint script and persisted on a named Docker volume (never committed, never baked into the image). The public cert is exported for the Symfony side to trust. `local` profile is untouched (plain HTTP:8080).

**Tech Stack:** Spring Boot (Java 21), `keytool` (JRE), Docker / docker-compose, alpine `curl`.

**Spec:** `docs/superpowers/specs/2026-06-03-https-self-signed-prod-design.md`

> **Note on testing:** This is infrastructure/config work — there are no unit tests to write. Verification is done by building and running the container and observing behavior. Each task ends with a concrete verification command and the expected output.

---

### Task 1: Add HTTPS config to the `prod` profile

**Files:**
- Modify: `src/main/resources/application-prod.yml`

- [ ] **Step 1: Add the server SSL block**

Prepend a `server:` block to `src/main/resources/application-prod.yml` (keep the existing `printer:`, `security:`, `labelary:` content below it). The file becomes:

```yaml
server:
  port: 8443
  ssl:
    enabled: true
    key-store: file:${SSL_KEYSTORE_PATH:/certs/keystore.p12}
    key-store-type: PKCS12
    key-store-password: ${SSL_KEYSTORE_PASSWORD}
    key-alias: wristband

printer:
  host: ${PRINTER_HOST}   # set via environment variable
  port: 9100
  timeout-ms: 5000

security:
  api-key: ${SECURITY_API_KEY}   # set via environment variable
  admin:
    password: ${ADMIN_PASSWORD}

labelary:
  base-url: http://api.labelary.com
```

- [ ] **Step 2: Verify YAML is well-formed**

Run: `cd /Users/lievendusar/Dropbox/Projecten/Stup-WristbandPrinterService && python3 -c "import yaml,sys; yaml.safe_load(open('src/main/resources/application-prod.yml')); print('ok')"`
Expected: `ok`

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application-prod.yml
git commit -m "feat(prod): serve HTTPS on 8443 via self-signed keystore"
```

---

### Task 2: Create the keystore-generation entrypoint script

**Files:**
- Create: `docker-entrypoint.sh`

- [ ] **Step 1: Write the entrypoint script**

Create `docker-entrypoint.sh` with exactly this content:

```sh
#!/bin/sh
set -e

SSL_KEYSTORE_PATH="${SSL_KEYSTORE_PATH:-/certs/keystore.p12}"
SSL_CERT_HOSTNAME="${SSL_CERT_HOSTNAME:-localhost}"
CERT_DIR="$(dirname "$SSL_KEYSTORE_PATH")"

if [ -z "$SSL_KEYSTORE_PASSWORD" ]; then
  echo "ERROR: SSL_KEYSTORE_PASSWORD is not set" >&2
  exit 1
fi

mkdir -p "$CERT_DIR"

if [ ! -f "$SSL_KEYSTORE_PATH" ]; then
  echo "No keystore at $SSL_KEYSTORE_PATH - generating self-signed certificate for '$SSL_CERT_HOSTNAME'..."
  if echo "$SSL_CERT_HOSTNAME" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'; then
    SAN="ip:$SSL_CERT_HOSTNAME"
  else
    SAN="dns:$SSL_CERT_HOSTNAME"
  fi
  keytool -genkeypair \
    -alias wristband \
    -keyalg RSA -keysize 2048 \
    -validity 3650 \
    -storetype PKCS12 \
    -keystore "$SSL_KEYSTORE_PATH" \
    -storepass "$SSL_KEYSTORE_PASSWORD" \
    -dname "CN=$SSL_CERT_HOSTNAME, O=STUP, C=BE" \
    -ext "san=$SAN"
  keytool -exportcert -rfc \
    -alias wristband \
    -keystore "$SSL_KEYSTORE_PATH" \
    -storepass "$SSL_KEYSTORE_PASSWORD" \
    -file "$CERT_DIR/server.crt"
  echo "Keystore generated; public certificate exported to $CERT_DIR/server.crt"
else
  echo "Reusing existing keystore at $SSL_KEYSTORE_PATH"
fi

exec java -jar /app/app.jar
```

- [ ] **Step 2: Make it executable and lint with sh**

Run: `cd /Users/lievendusar/Dropbox/Projecten/Stup-WristbandPrinterService && chmod +x docker-entrypoint.sh && sh -n docker-entrypoint.sh && echo "syntax ok"`
Expected: `syntax ok`

- [ ] **Step 3: Commit**

```bash
git add docker-entrypoint.sh
git commit -m "feat(docker): entrypoint generates/reuses self-signed keystore"
```

---

### Task 3: Wire the entrypoint and curl into the Dockerfile

**Files:**
- Modify: `Dockerfile`

- [ ] **Step 1: Update the runtime stage**

Replace the runtime stage (stage 2) of `Dockerfile` so the whole file reads:

```dockerfile
# Stage 1: build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl
COPY --from=build /app/target/wristband-printer-service-*.jar app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh
EXPOSE 8443
ENTRYPOINT ["/app/docker-entrypoint.sh"]
```

- [ ] **Step 2: Verify the image builds**

Run: `cd /Users/lievendusar/Dropbox/Projecten/Stup-WristbandPrinterService && docker build -t wristband-printer:https-test .`
Expected: build completes with `naming to docker.io/library/wristband-printer:https-test` (or `Successfully tagged`), no errors.

- [ ] **Step 3: Commit**

```bash
git add Dockerfile
git commit -m "feat(docker): add curl + keystore entrypoint, expose 8443"
```

---

### Task 4: Update docker-compose for HTTPS

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Update the `wristband-printer` service**

In `docker-compose.yml`, change the `wristband-printer` service so its `ports`, `environment`, `healthcheck` and the top-level `volumes` match the following (leave the `postgres` service unchanged):

```yaml
  wristband-printer:
    build: .
    ports:
      - "8443:8443"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - SECURITY_API_KEY=${API_KEY}
      - ADMIN_PASSWORD=${ADMIN_PASSWORD}
      - PRINTER_HOST=${PRINTER_HOST}
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/wristbands
      - SPRING_DATASOURCE_USERNAME=wristbands
      - SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}
      - SSL_KEYSTORE_PATH=/certs/keystore.p12
      - SSL_KEYSTORE_PASSWORD=${SSL_KEYSTORE_PASSWORD}
      - SSL_CERT_HOSTNAME=${SSL_CERT_HOSTNAME}
    volumes:
      - certs:/certs
    depends_on:
      postgres:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-fsk", "https://localhost:8443/actuator/health"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 20s
    deploy:
      resources:
        limits:
          memory: 512m
          cpus: '1.0'
    restart: unless-stopped
```

And update the top-level `volumes:` block at the end of the file to:

```yaml
volumes:
  pgdata:
  certs:
```

- [ ] **Step 2: Verify compose config parses**

Run: `cd /Users/lievendusar/Dropbox/Projecten/Stup-WristbandPrinterService && docker compose config >/dev/null && echo "compose ok"`
Expected: `compose ok` (warnings about unset env vars are fine until `.env` is updated in Task 5).

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(compose): publish 8443, persist certs volume, HTTPS healthcheck"
```

---

### Task 5: Add SSL env vars to `.env.example` and `.env`

**Files:**
- Modify: `.env.example`
- Modify: `.env` (gitignored — not committed)

- [ ] **Step 1: Update `.env.example`**

Set `.env.example` to:

```
# Copy this file to .env and fill in the values
API_KEY=your-strong-api-key-here
PRINTER_HOST=192.168.1.100
DB_PASSWORD=your-strong-db-password
ADMIN_PASSWORD=your-strong-admin-password
SSL_KEYSTORE_PASSWORD=your-strong-keystore-password
SSL_CERT_HOSTNAME=wristband.example.local
```

- [ ] **Step 2: Update the local `.env`**

Append to `.env` (do NOT commit this file — it is gitignored):

```
SSL_KEYSTORE_PASSWORD=<generate a strong password, e.g. `openssl rand -hex 24`>
SSL_CERT_HOSTNAME=<the hostname or IP the Symfony app uses to reach this service>
```

Generate the password with: `openssl rand -hex 24`

- [ ] **Step 3: Verify compose now resolves all vars**

Run: `cd /Users/lievendusar/Dropbox/Projecten/Stup-WristbandPrinterService && docker compose config 2>&1 | grep -i ssl`
Expected: lines showing `SSL_KEYSTORE_PASSWORD`, `SSL_CERT_HOSTNAME`, `SSL_KEYSTORE_PATH` resolved (no empty values).

- [ ] **Step 4: Commit (`.env.example` only)**

```bash
git add .env.example
git commit -m "docs(env): document SSL_KEYSTORE_PASSWORD and SSL_CERT_HOSTNAME"
```

---

### Task 6: End-to-end verification

**Files:** none (runtime verification)

- [ ] **Step 1: Build and start the stack**

Run: `cd /Users/lievendusar/Dropbox/Projecten/Stup-WristbandPrinterService && docker compose up -d --build`
Expected: `postgres` and `wristband-printer` start without errors.

- [ ] **Step 2: Confirm the keystore was generated once**

Run: `docker compose logs wristband-printer | grep -i keystore`
Expected: a line `No keystore ... - generating self-signed certificate ...` and `Keystore generated; public certificate exported to /certs/server.crt`.

- [ ] **Step 3: Confirm HTTPS health is UP**

Run: `curl -fsk https://localhost:8443/actuator/health`
Expected: `{"status":"UP"}`

- [ ] **Step 4: Confirm there is no plain-HTTP listener**

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8443/ ; echo "exit=$?"`
Expected: the request fails (empty/garbled response or non-2xx) — the port speaks TLS only, not HTTP.

- [ ] **Step 5: Confirm the keystore is reused on restart**

Run: `docker compose restart wristband-printer && sleep 8 && docker compose logs --tail=30 wristband-printer | grep -i keystore`
Expected: `Reusing existing keystore at /certs/keystore.p12` (NOT regenerated).

- [ ] **Step 6: Confirm the compose healthcheck reports healthy**

Run: `docker inspect --format '{{.State.Health.Status}}' $(docker compose ps -q wristband-printer)`
Expected: `healthy` (may take up to ~30s after start).

- [ ] **Step 7: Export the public cert for the Symfony side (sanity check)**

Run: `docker compose cp wristband-printer:/certs/server.crt ./server.crt && openssl x509 -in server.crt -noout -subject -ext subjectAltName`
Expected: subject `CN=<SSL_CERT_HOSTNAME>...` and a `Subject Alternative Name` matching the hostname/IP. Then remove the temp file: `rm server.crt`.

- [ ] **Step 8: Tear down test stack**

Run: `docker compose down`
Expected: containers removed (the `certs` volume persists).

---

### Task 7: Document HTTPS + Symfony trust in the README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add an HTTPS section**

Add a new section to `README.md` (near the Docker/deployment section). Use this content:

```markdown
## HTTPS (prod)

In the `prod` profile the service listens **HTTPS-only on port 8443** using a
self-signed certificate. The Symfony app calls it at `https://<host>:8443/...`.

The keystore is generated automatically on first container start and stored on
the `certs` Docker volume (`SSL_KEYSTORE_PASSWORD` and `SSL_CERT_HOSTNAME` come
from `.env`). It is reused on subsequent starts, so the certificate is stable
across redeploys.

### Letting Symfony trust the certificate

Export the public certificate from the running container:

```bash
docker compose cp wristband-printer:/certs/server.crt ./server.crt
```

Then either (recommended) point the Symfony HTTP client at it as a CA:

```yaml
# config/packages/framework.yaml
framework:
    http_client:
        scoped_clients:
            wristband.client:
                base_uri: 'https://<host>:8443'
                cafile: '%kernel.project_dir%/config/certs/server.crt'
```

…or, on a trusted private network, disable peer verification instead:

```yaml
                verify_peer: false
                verify_host: false
```

`SSL_CERT_HOSTNAME` must match the host the Symfony app connects to, otherwise
hostname verification fails (use `verify_host: false` or fix the hostname).
```

- [ ] **Step 2: Verify the section renders / links are intact**

Run: `cd /Users/lievendusar/Dropbox/Projecten/Stup-WristbandPrinterService && grep -n "HTTPS (prod)" README.md`
Expected: a line number is printed (section present).

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: document prod HTTPS and Symfony cert trust"
```

---

## Done when

- `prod` serves HTTPS-only on 8443 with a self-signed cert (Task 1–4).
- Keystore is generated once and persisted on the `certs` volume; reused on restart (Task 6 steps 2 & 5).
- `curl -fsk https://localhost:8443/actuator/health` → `{"status":"UP"}` and the compose healthcheck is `healthy` (Task 6 steps 3 & 6).
- Env vars documented (Task 5) and README explains Symfony trust (Task 7).
- `local` profile is unchanged (still HTTP:8080).

# HTTPS for the wristband service (prod) — self-signed TLS

**Date:** 2026-06-03
**Status:** Approved (design)

## Goal

The STUP Symfony event application must reach the wristband printer service over
HTTPS. A self-signed certificate is acceptable. Scope is the **prod / Docker**
deployment only; the `local` profile keeps plain HTTP.

## Decisions

| Decision | Choice |
|----------|--------|
| TLS termination | Spring Boot built-in SSL (no reverse proxy) |
| Environments | Prod / Docker only (`local` unchanged) |
| Protocols | HTTPS only (no plain HTTP listener in prod) |
| Port | `8443` (published `8443:8443`); target URL `https://<host>:8443/...` |
| Keystore | Generated once at container start, persisted on a named volume |
| Healthcheck | `curl` added to the runtime image; HTTPS check with `-k` |

## Architecture

- The Spring Boot app terminates TLS itself using a self-signed PKCS12 keystore.
- In `prod`, the listener is HTTPS on port `8443`. There is no HTTP listener.
- The keystore is **not** committed to git and **not** baked into the image. A
  startup script generates it on first run and persists it on a Docker volume so
  the certificate stays stable across restarts and rebuilds — the Symfony side
  trusts it once.

### Keystore lifecycle

A `docker-entrypoint.sh` script (copied into the image, set as `ENTRYPOINT`):

1. If `${SSL_KEYSTORE_PATH}` (default `/certs/keystore.p12`) does **not** exist:
   - Generate a self-signed cert with `keytool` (ships in the JRE):
     - RSA 2048, validity 3650 days, alias `wristband`.
     - `-dname` CN set from `SSL_CERT_HOSTNAME`.
     - SAN (`-ext san=dns:<host>` / `ip:<host>` as appropriate) from
       `SSL_CERT_HOSTNAME` so hostname verification can succeed if Symfony opts
       to verify.
     - Store password from `SSL_KEYSTORE_PASSWORD`.
   - Export the public certificate to `${certs}/server.crt` so it can be handed
     to the Symfony side for trust.
   - Log that a new keystore was generated.
2. If the keystore already exists, log that it is being reused (no regeneration).
3. `exec java -jar app.jar` so the JVM is PID 1 and receives signals.

The `/certs` directory is backed by a named Docker volume (`certs:`).

## Configuration changes

**`application-prod.yml`**
```yaml
server:
  port: 8443
  ssl:
    enabled: true
    key-store: file:${SSL_KEYSTORE_PATH:/certs/keystore.p12}
    key-store-type: PKCS12
    key-store-password: ${SSL_KEYSTORE_PASSWORD}
    key-alias: wristband
```
(`local` and base `application.yml` keep `server.port: 8080`, no SSL.)

**`Dockerfile`** (runtime stage)
- `RUN apk add --no-cache curl`
- `COPY docker-entrypoint.sh /app/` and make it executable.
- `EXPOSE 8443` (replacing `8080`).
- `ENTRYPOINT ["/app/docker-entrypoint.sh"]`.

**`docker-compose.yml`** (`wristband-printer` service)
- `ports: ["8443:8443"]` (was `8080:8080`).
- Add env: `SSL_KEYSTORE_PASSWORD=${SSL_KEYSTORE_PASSWORD}`,
  `SSL_CERT_HOSTNAME=${SSL_CERT_HOSTNAME}`,
  `SSL_KEYSTORE_PATH=/certs/keystore.p12`.
- Add volume mount `certs:/certs` and declare `certs:` under top-level `volumes:`.
- Healthcheck: `["CMD", "curl", "-fsk", "https://localhost:8443/actuator/health"]`.

**`.env.example` / `.env`**
- Add `SSL_KEYSTORE_PASSWORD=your-strong-keystore-password`.
- Add `SSL_CERT_HOSTNAME=wristband.example.local` (the hostname/IP the Symfony
  site uses to reach the service).

## Symfony consumer (documentation only)

Self-signed certs are not trusted by default. README gains a short section:
- **Recommended:** add the exported `server.crt` to the Symfony host's CA bundle
  (or pin it via the HTTP client's `cafile`/`capath` option) so verification
  passes.
- **Alternative:** disable peer verification in the Symfony HTTP client
  (`verify_peer: false`) — acceptable on a trusted private network, less secure.
- Document where to retrieve `server.crt` (the `certs` volume, e.g.
  `docker compose cp wristband-printer:/certs/server.crt ./`).

No code changes are made to the Symfony app in this work.

## Out of scope

- `local` profile HTTPS.
- A reverse proxy / load balancer.
- A CA-issued (non-self-signed) certificate and automated renewal.
- Changes to the Symfony application itself.

## Testing / verification

1. `docker compose build && docker compose up -d`.
2. First-start logs show the keystore being **generated** once and `server.crt`
   exported.
3. `curl -k https://localhost:8443/actuator/health` returns `{"status":"UP"}`.
4. Plain `curl http://localhost:8443/` is refused/fails (no HTTP listener).
5. `docker compose restart wristband-printer` → logs show the keystore is
   **reused**, not regenerated; service comes back `UP`.
6. Compose healthcheck reports `healthy`.

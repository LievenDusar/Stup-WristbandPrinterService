#!/bin/sh
set -e

# Only the prod profile serves HTTPS and therefore needs a keystore.
# Local (and any non-prod) profile runs plain HTTP, so skip cert generation.
if [ "$SPRING_PROFILES_ACTIVE" = "prod" ]; then
  SSL_KEYSTORE_PATH="${SSL_KEYSTORE_PATH:-/certs/keystore.p12}"
  SSL_CERT_HOSTNAME="${SSL_CERT_HOSTNAME:-localhost}"
  CERT_DIR="$(dirname "$SSL_KEYSTORE_PATH")"

  if [ -z "$SSL_KEYSTORE_PASSWORD" ]; then
    echo "ERROR: SSL_KEYSTORE_PASSWORD is not set (required for prod profile)" >&2
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
else
  echo "Profile '${SPRING_PROFILES_ACTIVE:-default}' - HTTP mode, skipping TLS keystore generation"
fi

exec java -jar /app/app.jar

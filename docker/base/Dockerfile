# Shared "Java 21 tech stack" base image.
# The application image (../../Dockerfile) extends this via `FROM wristband-base:21`.
# Keeping the JRE + tooling here means any host with only Docker can run the service —
# no Java on the host. `keytool` ships with the JRE, so only curl is added.
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl

# Stage 1: build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: runtime (extends the shared Java 21 base image)
FROM wristband-base:21
WORKDIR /app
COPY --from=build /app/target/wristband-printer-service-*.jar app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh
# 8080 = local HTTP, 8443 = production HTTPS
EXPOSE 8080 8443
ENTRYPOINT ["/app/docker-entrypoint.sh"]

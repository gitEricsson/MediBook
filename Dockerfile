# ─── Build Stage ─────────────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app

ARG BUILD_VERSION=dev
ARG BUILD_SHA=unknown

COPY pom.xml ./
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn clean package -DskipTests -q

# ─── Runtime Stage ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

ARG BUILD_VERSION=dev
ARG BUILD_SHA=unknown
ARG BUILD_DATE

LABEL org.opencontainers.image.title="MediBook API" \
      org.opencontainers.image.description="Healthcare Appointment and Consultation Platform" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.revision="${BUILD_SHA}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="https://github.com/gitEricsson/MediBook"

WORKDIR /app

RUN addgroup -S medibook && adduser -S medibook -G medibook \
 && mkdir -p /app/uploads/avatars \
 && chown -R medibook:medibook /app/uploads

COPY --from=builder --chown=medibook:medibook /app/target/medibook-*.jar app.jar

USER medibook

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=300s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness 2>/dev/null | grep -q '"status"' || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-XX:+UseStringDeduplication", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Dspring.backgroundpreinitializer.ignore=true", \
  "-jar", "app.jar"]

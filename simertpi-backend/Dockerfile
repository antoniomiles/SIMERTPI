# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY src/main ./src/main
# QA runs on the host/CI agent with Testcontainers; only production sources enter this build.
ARG SOURCE_DATE_EPOCH=315532800
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --no-transfer-progress -Dproject.build.outputTimestamp=${SOURCE_DATE_EPOCH} package

FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555 AS runtime
RUN apk add --no-cache curl \
    && addgroup -g 10001 app \
    && adduser -D -u 10001 -G app app \
    && mkdir -p /app /var/lib/simertpi/evidence \
    && chown -R app:app /app /var/lib/simertpi
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/simertpi-backend-*.jar /app/app.jar
ARG VCS_REF=unversioned
LABEL org.opencontainers.image.title="simertpi-backend" \
      org.opencontainers.image.revision=${VCS_REF}
ENV SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE=prod \
    SIMERTPI_EVIDENCE_STORAGE_BASE_PATH=/var/lib/simertpi/evidence
USER 10001:10001
EXPOSE 8080
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=5s --start-period=75s --retries=3 \
    CMD curl --fail --silent --max-time 3 --output /dev/null "http://127.0.0.1:${SERVER_PORT}/actuator/health/readiness" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

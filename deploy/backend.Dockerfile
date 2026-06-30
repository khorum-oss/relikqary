# syntax=docker/dockerfile:1
# Relikquary backend (API) image. Multi-stage: build the Spring Boot bootJar on a JDK, run it on a
# slim JRE as a non-root user. Build context is the repository root:
#   docker build -f deploy/backend.Dockerfile -t relikquary-backend:local .

# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
# .dockerignore keeps build/, .gradle/, node_modules/, .git/ out of the context.
COPY . .
# bootJar (no -PbundleFrontend): API only. Strict dependency verification runs here.
RUN ./gradlew :backend:bootJar --no-daemon --console=plain

# ---- runtime stage ----
FROM eclipse-temurin:21-jre AS runtime
# curl is used by the container HEALTHCHECK.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system relikquary \
    && useradd --system --gid relikquary --home-dir /app --shell /usr/sbin/nologin relikquary \
    && mkdir -p /app /data \
    && chown -R relikquary:relikquary /app /data
WORKDIR /app
COPY --from=build --chown=relikquary:relikquary /workspace/backend/build/libs/backend.jar /app/relikquary.jar
# Filesystem storage lives on the volume by default; override the backend via env for S3.
ENV RELIKQUARY_STORAGE_ROOT=/data
USER relikquary
EXPOSE 8080
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/relikquary.jar"]

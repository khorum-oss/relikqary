# syntax=docker/dockerfile:1
# Relikquary combined image: a single container serving both the API and the UI (under /ui). Built with
# the existing `-PbundleFrontend` toggle, which builds the SvelteKit UI (BASE_PATH=/ui) and bundles it
# into the backend jar. Build context is the repository root:
#   docker build -f deploy/combined.Dockerfile -t relikquary:local .

# ---- build stage (JDK + Node: -PbundleFrontend builds and bundles the UI) ----
FROM eclipse-temurin:21-jdk AS build
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates gnupg \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:bootJar -PbundleFrontend --no-daemon --console=plain

# ---- runtime stage (slim JRE, non-root) ----
FROM eclipse-temurin:21-jre AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system relikquary \
    && useradd --system --gid relikquary --home-dir /app --shell /usr/sbin/nologin relikquary \
    && mkdir -p /app /data \
    && chown -R relikquary:relikquary /app /data
WORKDIR /app
COPY --from=build --chown=relikquary:relikquary /workspace/backend/build/libs/backend.jar /app/relikquary.jar
ENV RELIKQUARY_STORAGE_ROOT=/data
USER relikquary
EXPOSE 8080
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/relikquary.jar"]

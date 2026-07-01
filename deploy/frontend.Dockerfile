# syntax=docker/dockerfile:1
# Relikquary frontend (UI) image. Builds the SvelteKit (adapter-static) SPA and serves it with a
# non-root nginx that reverse-proxies the API and Maven repository paths to the backend. Build context
# is the repository root:
#   docker build -f deploy/frontend.Dockerfile -t relikquary-frontend:local .

# ---- build stage ----
FROM node:22 AS build
WORKDIR /app
# Set BEFORE `npm ci`: @playwright/test's postinstall would otherwise download ~150MB of browsers during
# install (unused at build time) and can hang/fail the image build. Mirrors the CI frontend job.
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# Standalone UI served at the root (BASE_PATH unset). The combined image, by contrast, serves under /ui.
RUN npm run build

# ---- runtime stage (non-root nginx, listens on 8080) ----
FROM nginxinc/nginx-unprivileged:stable-alpine AS runtime
# Static SPA output.
COPY --from=build /app/build /usr/share/nginx/html
# Reverse-proxy config; ${RELIKQUARY_BACKEND} is substituted at container start by the nginx entrypoint.
COPY deploy/nginx/default.conf.template /etc/nginx/templates/default.conf.template
ENV RELIKQUARY_BACKEND=http://backend:8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O - http://localhost:8080/ >/dev/null 2>&1 || exit 1

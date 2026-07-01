#!/usr/bin/env bash
# Runs the frontend Playwright e2e against a real backend with AUTH ENABLED (feature 008):
#   1. starts the backend bootJar with scripts/e2e-config.yml (alice publisher; open 'releases',
#      alice-only 'private'),
#   2. seeds artifacts (with credentials) into both repos,
#   3. runs Playwright (which starts `vite dev`, proxying /api + repo paths to the backend),
#   4. tears the backend down.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAR="$ROOT/backend/build/libs/backend.jar"
CONFIG="$ROOT/frontend/scripts/e2e-config.yml"
STORE="$(mktemp -d)"
CURL=(curl -sf --noproxy '*')
ALICE=(-u alice:pw)

[ -f "$JAR" ] || { echo "Build the backend jar first: ./gradlew :backend:bootJar"; exit 1; }

echo "Starting backend (auth on, store=$STORE)..."
java -jar "$JAR" \
  --spring.config.location="file:$CONFIG" \
  --relikquary.storage.filesystem.root="$STORE" \
  --relikquary.persistence.sqlite.path="$STORE/relikquary.db" \
  >/tmp/relikquary-e2e-backend.log 2>&1 &
BPID=$!
trap 'kill "$BPID" 2>/dev/null || true' EXIT

echo "Waiting for backend..."
for _ in $(seq 1 60); do
  "${CURL[@]}" http://127.0.0.1:8080/api/repositories >/dev/null 2>&1 && break
  sleep 1
done

seed() { # repo path-base
  local base="http://127.0.0.1:8080/$1/$2"
  printf 'jar-bytes-here' | "${CURL[@]}" "${ALICE[@]}" -X PUT --data-binary @- "$base.jar"
  printf '<project/>'     | "${CURL[@]}" "${ALICE[@]}" -X PUT --data-binary @- "$base.pom"
  printf 'deadbeef'       | "${CURL[@]}" "${ALICE[@]}" -X PUT --data-binary @- "$base.jar.sha1"
}

echo "Seeding artifacts (open + private)..."
seed releases com/example/widget/1.0.0/widget-1.0.0
seed private com/acme/lib/1.0.0/lib-1.0.0

# A Gradle module coordinate (feature 011): a jar + a real .module with a variant, so the browse UI can
# badge it, render consume snippets, and show the parsed module detail.
seed_module() {
  local base="http://127.0.0.1:8080/releases/com/example/gmodule/2.0.0/gmodule-2.0.0"
  # An explicit content type is required: with curl's default form content type, Tomcat consumes the PUT
  # body as form parameters and the stored file would be empty.
  local octet=(-H 'Content-Type: application/octet-stream')
  printf 'gmodule-jar' | "${CURL[@]}" "${ALICE[@]}" "${octet[@]}" -X PUT --data-binary @- "$base.jar"
  printf '%s' '{"formatVersion":"1.1","component":{"group":"com.example","module":"gmodule","version":"2.0.0"},"variants":[{"name":"apiElements","attributes":{"org.gradle.usage":"java-api","org.gradle.category":"library"},"capabilities":[{"group":"com.example","name":"gmodule","version":"2.0.0"}],"dependencies":[{"group":"com.google.guava","module":"guava","version":{"requires":"33.0.0-jre"}}],"files":[{"name":"gmodule-2.0.0.jar","url":"gmodule-2.0.0.jar","size":11,"sha256":"deadbeef"}]}]}' \
    | "${CURL[@]}" "${ALICE[@]}" "${octet[@]}" -X PUT --data-binary @- "$base.module"
}
seed_module

echo "Running Playwright..."
cd "$ROOT/frontend"
# Prefer a pre-installed Chromium when present (this environment); otherwise let Playwright use its
# own installed browser (e.g. CI after `npx playwright install`).
if [ -z "${RELIKQUARY_CHROMIUM_PATH:-}" ] && [ -x /opt/pw-browsers/chromium ]; then
  export RELIKQUARY_CHROMIUM_PATH=/opt/pw-browsers/chromium
fi
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm run test:e2e

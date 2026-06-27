#!/usr/bin/env bash
# Runs the frontend Playwright e2e against a real backend:
#   1. starts the backend bootJar (auth disabled, temp filesystem store),
#   2. seeds an artifact via the Maven publish endpoint,
#   3. runs Playwright (which starts `vite dev`, proxying /api + downloads to the backend),
#   4. tears the backend down.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAR="$ROOT/backend/build/libs/backend.jar"
STORE="$(mktemp -d)"
CURL=(curl -sf --noproxy '*')

[ -f "$JAR" ] || { echo "Build the backend jar first: ./gradlew :backend:bootJar"; exit 1; }

echo "Starting backend (auth off, store=$STORE)..."
java -jar "$JAR" \
  --relikquary.security.enabled=false \
  --relikquary.storage.filesystem.root="$STORE" \
  >/tmp/relikquary-e2e-backend.log 2>&1 &
BPID=$!
trap 'kill "$BPID" 2>/dev/null || true' EXIT

echo "Waiting for backend..."
for _ in $(seq 1 60); do
  "${CURL[@]}" http://127.0.0.1:8080/api/repositories >/dev/null 2>&1 && break
  sleep 1
done

echo "Seeding an artifact..."
base="http://127.0.0.1:8080/releases/com/example/widget/1.0.0/widget-1.0.0"
printf 'jar-bytes-here' | "${CURL[@]}" -X PUT --data-binary @- "$base.jar"
printf '<project/>'     | "${CURL[@]}" -X PUT --data-binary @- "$base.pom"
printf 'deadbeef'       | "${CURL[@]}" -X PUT --data-binary @- "$base.jar.sha1"

echo "Running Playwright..."
cd "$ROOT/frontend"
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm run test:e2e

#!/usr/bin/env bash
# Guarded local smoke test for the backend image (feature 013): build it, run it, then publish and
# resolve an artifact through the running container and assert byte-equality. Skips cleanly (exit 0)
# when no Docker runtime is available, so it never blocks a build that lacks a daemon.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="relikquary-backend:smoke"
NAME="relikquary-smoke-$$"
PORT="${RELIKQUARY_SMOKE_PORT:-18080}"
PASS='{noop}smoke-secret'

if ! command -v docker >/dev/null 2>&1; then
  echo "smoke: docker not found — skipping (this is expected where no container runtime is available)."
  exit 0
fi

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "smoke: building $IMAGE ..."
docker build -f "$ROOT/deploy/backend.Dockerfile" -t "$IMAGE" "$ROOT"

echo "smoke: starting container ..."
docker run -d --name "$NAME" -p "$PORT:8080" \
  -e RELIKQUARY_SECURITY_USERS_0_USERNAME=publisher \
  -e RELIKQUARY_SECURITY_USERS_0_PASSWORD="$PASS" \
  -e RELIKQUARY_SECURITY_USERS_0_ROLES_0=PUBLISH \
  "$IMAGE" >/dev/null

echo "smoke: waiting for readiness ..."
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:$PORT/actuator/health/readiness" >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -fsS "http://127.0.0.1:$PORT/actuator/health/readiness" >/dev/null

base="http://127.0.0.1:$PORT/releases/com/example/smoke/1.0.0/smoke-1.0.0.jar"
payload="relikquary-smoke-$$-$RANDOM"

echo "smoke: publishing ..."
printf '%s' "$payload" | curl -fsS -u "publisher:smoke-secret" \
  -H 'Content-Type: application/octet-stream' -X PUT --data-binary @- "$base" >/dev/null

echo "smoke: resolving ..."
got="$(curl -fsS "$base")"

if [ "$got" = "$payload" ]; then
  echo "smoke: OK — published and resolved byte-identical through the container."
else
  echo "smoke: FAIL — resolved bytes did not match published bytes." >&2
  exit 1
fi

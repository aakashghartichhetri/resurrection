#!/usr/bin/env bash
# Restore a real pg_dump into a disposable PostgreSQL container; never overwrite the source DB.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
for tool in docker mktemp; do command -v "$tool" >/dev/null || { echo "Missing $tool" >&2; exit 1; }; done
TMP="$(mktemp -d)"
CONTAINER=""
cleanup() {
  if [[ -n "$CONTAINER" ]]; then docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; fi
  rm -rf "$TMP"
}
trap cleanup EXIT
PROJECT="${COMPOSE_PROJECT_NAME:-resurrection}"
DOCKER_COMPOSE=(docker compose -p "$PROJECT")
"${DOCKER_COMPOSE[@]}" exec -T db pg_dump -U resurrection -d resurrection -Fc > "$TMP/resurrection.dump"
[[ -s "$TMP/resurrection.dump" ]] || { echo "pg_dump produced an empty file" >&2; exit 1; }
original_workflows=$("${DOCKER_COMPOSE[@]}" exec -T db psql -U resurrection -d resurrection -Atc 'SELECT COUNT(*) FROM workflows')
original_events=$("${DOCKER_COMPOSE[@]}" exec -T db psql -U resurrection -d resurrection -Atc 'SELECT COUNT(*) FROM workflow_events')
CONTAINER=$(docker run -d --rm --network none \
  -e POSTGRES_USER=resurrection -e POSTGRES_DB=resurrection \
  -e POSTGRES_PASSWORD=disposable_restore_test postgres:17-alpine)
for _ in $(seq 1 30); do
  if docker exec "$CONTAINER" pg_isready -U resurrection -d resurrection >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$CONTAINER" pg_isready -U resurrection -d resurrection >/dev/null
docker exec -i "$CONTAINER" pg_restore --no-owner --no-privileges \
  -U resurrection -d resurrection < "$TMP/resurrection.dump"
restored_workflows=$(docker exec "$CONTAINER" psql -U resurrection -d resurrection -Atc 'SELECT COUNT(*) FROM workflows')
restored_events=$(docker exec "$CONTAINER" psql -U resurrection -d resurrection -Atc 'SELECT COUNT(*) FROM workflow_events')
[[ "$original_workflows" == "$restored_workflows" && "$original_events" == "$restored_events" ]] || {
  echo "Backup/restore verification failed: workflow and event counts differ" >&2; exit 1;
}
echo "PASS: disposable restore ($restored_workflows workflows, $restored_events events)"
if command -v sha256sum >/dev/null; then
  sha256sum "$TMP/resurrection.dump" | awk '{ print "Archive SHA-256: " $1 }'
elif command -v shasum >/dev/null; then
  shasum -a 256 "$TMP/resurrection.dump" | awk '{ print "Archive SHA-256: " $1 }'
fi

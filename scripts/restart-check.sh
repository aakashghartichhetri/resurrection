#!/usr/bin/env bash
# Run only against an isolated demo stack: this intentionally restarts the API container.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
BASE_URL="${BASE_URL:-http://localhost:8080}"
APP_USERNAME="${APP_USERNAME:-resurrection}"
APP_PASSWORD="${APP_PASSWORD:-dev-only-change-me}"
PROJECT="${COMPOSE_PROJECT_NAME:-resurrection}"
for tool in docker curl python3; do command -v "$tool" >/dev/null || { echo "Missing $tool" >&2; exit 1; }; done
api() { curl --fail --silent --show-error -u "$APP_USERNAME:$APP_PASSWORD" "$@"; }
created=$(api -H 'Content-Type: application/json' -d '{"name":"restart durability check"}' "$BASE_URL/api/workflows")
id=$(printf '%s' "$created" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
api -X POST -H "Idempotency-Key: $(python3 -c 'import uuid;print(uuid.uuid4())')" "$BASE_URL/api/workflows/$id/advance" >/dev/null
before=$(api "$BASE_URL/api/workflows/$id")
echo "Restarting API to test persistence of $id (test stack only)..."
docker compose -p "$PROJECT" restart api >/dev/null
ready=0
for _ in $(seq 1 60); do
  if api "$BASE_URL/api/workflows/$id" >/dev/null 2>&1; then ready=1; break; fi
  sleep 2
done
[[ "$ready" == "1" ]] || { echo 'API did not recover after restart' >&2; exit 1; }
after=$(api "$BASE_URL/api/workflows/$id")
python3 - "$before" "$after" <<'PY'
import json,sys
old,new = json.loads(sys.argv[1]),json.loads(sys.argv[2])
assert old['workflow'] == new['workflow'], 'projection changed across restart'
assert old['events'] == new['events'], 'event history changed across restart'
assert new['workflow']['step'] == 'VALIDATED'
print('PASS: workflow projection and event history survived API restart')
PY

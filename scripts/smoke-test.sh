#!/usr/bin/env bash
# Local integration smoke test. Creates a fresh workflow; never mutates others.
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"
APP_USERNAME="${APP_USERNAME:-resurrection}"
APP_PASSWORD="${APP_PASSWORD:-dev-only-change-me}"
for program in curl python3; do
  command -v "$program" >/dev/null || { echo "Missing: $program" >&2; exit 1; }
done
json_value() { python3 -c "import json,sys; data=json.load(sys.stdin); print(data[sys.argv[1]])" "$1"; }
new_uuid() { python3 -c 'import uuid; print(uuid.uuid4())'; }
api() { curl -fsS -u "$APP_USERNAME:$APP_PASSWORD" "$@"; }

printf 'Checking readiness...\n'
curl -fsS "$BASE_URL/healthz" >/dev/null
# A proxy provides /api; health is anonymous but all mutation calls use operator credentials.
name="smoke-$(date +%s)"
created=$(api -H 'Content-Type: application/json' -d "{\"name\":\"$name\"}" "$BASE_URL/api/workflows")
id=$(printf '%s' "$created" | json_value id)
[[ -n "$id" ]] || { echo 'No workflow id returned' >&2; exit 1; }
echo "Created workflow $id"

key=$(new_uuid)
first=$(api -X POST -H "Idempotency-Key: $key" "$BASE_URL/api/workflows/$id/advance")
second=$(api -X POST -H "Idempotency-Key: $key" "$BASE_URL/api/workflows/$id/advance")
[[ "$first" == "$second" ]] || { echo 'Idempotent replay changed the response' >&2; exit 1; }
[[ "$(printf '%s' "$second" | json_value lastSequence)" == "2" ]] || exit 1
echo 'Duplicate advance was safely deduplicated'

api -X POST -H 'Content-Type: application/json' -d '{"type":"FAIL_NEXT_STEP"}' "$BASE_URL/api/workflows/$id/faults" >/dev/null
failed=$(api -X POST -H "Idempotency-Key: $(new_uuid)" "$BASE_URL/api/workflows/$id/advance")
[[ "$(printf '%s' "$failed" | json_value status)" == "FAILED" ]] || exit 1
preview=$(api "$BASE_URL/api/workflows/$id/recovery-preview")
[[ "$(printf '%s' "$preview" | json_value action)" == "RESUME_AFTER_FAILURE" ]] || exit 1
payload=$(printf '%s' "$preview" | python3 -c 'import json,sys; p=json.load(sys.stdin); print(json.dumps(dict(action=p["action"],expectedSequence=p["expectedSequence"],expectedHash=p["expectedHash"],projectionFingerprint=p["projectionFingerprint"])))')
resumed=$(api -X POST -H 'Content-Type: application/json' -d "$payload" "$BASE_URL/api/workflows/$id/recover")
[[ "$(printf '%s' "$resumed" | json_value status)" == "ACTIVE" ]] || exit 1
echo 'Failure diagnosed and operator-approved recovery committed'

api -X POST -H 'Content-Type: application/json' -d '{"type":"CORRUPT_PROJECTION"}' "$BASE_URL/api/workflows/$id/faults" >/dev/null
blocked=$(curl -sS -o /dev/null -w '%{http_code}' -u "$APP_USERNAME:$APP_PASSWORD" -X POST -H "Idempotency-Key: $(new_uuid)" "$BASE_URL/api/workflows/$id/advance")
[[ "$blocked" == "409" ]] || { echo "Expected 409 on drift, got $blocked" >&2; exit 1; }
preview=$(api "$BASE_URL/api/workflows/$id/recovery-preview")
[[ "$(printf '%s' "$preview" | json_value action)" == "REBUILD_PROJECTION" ]] || exit 1
payload=$(printf '%s' "$preview" | python3 -c 'import json,sys; p=json.load(sys.stdin); print(json.dumps(dict(action=p["action"],expectedSequence=p["expectedSequence"],expectedHash=p["expectedHash"],projectionFingerprint=p["projectionFingerprint"])))')
api -X POST -H 'Content-Type: application/json' -d "$payload" "$BASE_URL/api/workflows/$id/recover" >/dev/null
final=$(api "$BASE_URL/api/workflows/$id/recovery-preview")
[[ "$(printf '%s' "$final" | json_value action)" == "NONE" ]] || exit 1
count=$(api "$BASE_URL/api/workflows/$id" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["approvals"]))')
[[ "$count" == "2" ]] || exit 1
echo 'Projection restored, verified, and both approvals audited'
echo 'PASS: workflow retry, failure, and recovery checks'

printf 'Checking fleet-level diagnosis and separate fault audit...\n'
operations=$(api "$BASE_URL/api/operations")
state=$(printf '%s' "$operations" | python3 -c 'import json,sys; data=json.load(sys.stdin); wid=sys.argv[1]; print(next(row["state"] for row in data["workflows"] if row["id"]==wid))' "$id")
[[ "$state" == "HEALTHY" ]] || { echo "Expected healthy after recovery, got $state" >&2; exit 1; }
audit=$(api "$BASE_URL/api/workflows/$id" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d["faultAudits"]))')
[[ "$audit" == "2" ]] || { echo "Expected 2 fault audit entries, got $audit" >&2; exit 1; }
echo 'PASS: fleet inspection reports recovery verified; both injected faults audited'

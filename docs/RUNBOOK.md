# Operator runbook

## A workflow shows inconsistent state

Open **Investigations** and inspect the replay preview. If the event chain and checkpoints verify, compare `expectedStep`, `expectedStatus`, and `projectionFingerprint` with the stored projection. Record why a rebuild or resume is warranted before clicking **Approve**. A stale proposal returns HTTP 409: refresh and review again.

If replay reports **HISTORY_INVALID**, do not rebuild. Preserve database evidence, correlate the HTTP `X-Request-Id` with logs, and investigate missing or altered records. The application deliberately has no override for an invalid event history.

## Application or database outage

Check `docker compose ps` and `docker compose logs --tail=200 api db web`. Use `/actuator/health/readiness` for database-backed readiness; app-start alone is not sufficient. A restart should not reset events when the same named Postgres volume is retained. Do not use `docker compose down -v` against valuable data.

## Backups

Run `bash scripts/verify-backup.sh` against a local/test instance. It makes a real `pg_dump -Fc`, restores into a disposable Postgres 17 container, and compares workflow/event counts. The script is a restore **smoke check**, not a full disaster-recovery drill or a retention policy. Schedule off-host encrypted backups and test a controlled restoration before accepting production data. Never restore over a live database without a documented maintenance plan and a verified backup.

## Performance or elevated errors

Inspect operator-authenticated `/actuator/metrics/http.server.requests`, `/actuator/metrics/resurrection.replay.duration`, and `/actuator/metrics/resurrection.replay.count`. High replay latency can be caused by long event histories; there is currently no checkpoint jump-ahead optimization. Limit demo workflow volume accordingly and use benchmark evidence before changing the architecture.

# RESURRECTION

**A small, runnable failure-recovery lab** built with Java 25, Spring Boot, PostgreSQL, Flyway, React, TypeScript, Docker Compose, and GitHub Actions.

> This is a simulated workflow, not an autonomous production repair system. Nothing is shipped, billed, or changed outside this demo database. Recovery requires a human click or API call. See **Limits and tradeoffs** before describing it in an interview.

## What you'll see

A package moves through four states: **CREATED → VALIDATED → PACKED → SHIPPED**. Each successful transition is committed with an event in PostgreSQL. You can arm a failure for the next transition or intentionally corrupt the *stored projection* (the current-state row). The recovery inspector checks the event hash chain and checkpoints, reconstructs the expected state, and simulates one proposed action without writing. An operator must explicitly approve it.

### Four essential features and acceptance criteria

| Feature | Acceptance criteria |
|---|---|
| 1. Durable state transitions | Create a workflow; advance three times; state becomes `COMPLETED`. Event sequence numbers are consecutive, and a restart does not erase data. |
| 2. Replay and checkpoints | `GET /recovery-preview` replays hash-linked events and validates checkpoints stored every two events; it also dry-runs the proposed action. A healthy workflow says `NONE` is needed. |
| 3. Controlled failure injection | Arm `FAIL_NEXT_STEP` then advance; the transition does **not** commit, a `STEP_FAILED` event is recorded, and status becomes `FAILED`. |
| 4. Detection and human-approved recovery | Corrupt the stored projection; advance is rejected with HTTP 409; preview dry-runs `REBUILD_PROJECTION`; approval restores the state and records `PROJECTION_REPAIRED`. A failed workflow instead proposes `RESUME_AFTER_FAILURE`. |

## Prerequisites

**Easiest path:** Docker Desktop (or Docker Engine with Compose v2), running, with at least ~3 GB free RAM and internet access for the first image/dependency downloads. You do **not** need Java, Maven, Node, or PostgreSQL installed locally for this path.

Optional local development: **JDK 25**, **Maven 3.9+**, **Node.js 22.12+ with npm**, and **Docker** for PostgreSQL and Testcontainers.

## Quick start (one command)

From the folder containing `compose.yaml`:

```bash
docker compose up --build
```

When the containers are ready, open **http://localhost:8080**. The Compose stack creates PostgreSQL, builds the Spring Boot backend, and builds/serves the React UI. The first build may download several images and dependencies.

Login in the UI with the local demo credentials:

- Username: `resurrection`
- Password: `dev-only-change-me`

Two sample workflows appear automatically on the first startup: **Demo: Parcel A-104** (healthy) and **Demo: Parcel B-205** (failed after a simulated transition). Refresh the page or click **Connect** after entering credentials.

To customize credentials, copy `.env.example` to `.env`, edit `APP_PASSWORD` and `DB_PASSWORD`, and restart with `docker compose up --build`. `.env` is excluded from Git. The database password in `.env` is used when PostgreSQL initializes its empty volume; changing it later requires changing the DB user's password or resetting the **local demo** database volume.

Stop without losing records:

```bash
docker compose down
```

Start again with `docker compose up --build` and your workflow history will still be there. To intentionally wipe demo data:

```bash
docker compose down -v
```

**Caution:** `-v` deletes the project's Docker PostgreSQL volume and its data.

## Manual test walkthrough (UI)

### A. Normal progress

1. Connect, type a name, and click **Create workflow**.
2. Click **Advance one step** three times.
3. Expect `CREATED → VALIDATED → PACKED → SHIPPED`, status `COMPLETED`, and four events (`CREATED` plus three `ADVANCED`).
4. Click **Inspect & replay**; expect `PROJECTION VERIFIED`, recovery action `NONE`, and two verified checkpoints.

### B. A failed transition, reconstructed

1. Create a fresh workflow; advance once to `VALIDATED`.
2. Click **Arm next-step failure**, then **Advance one step**.
3. Expect the step stays `VALIDATED` while status changes to `FAILED`. The timeline shows `FAULT_ARMED` and `STEP_FAILED`.
4. Click **Inspect & replay**; expect proposed action `RESUME_AFTER_FAILURE` with expected state `VALIDATED / FAILED` and dry-run state `VALIDATED / ACTIVE`.
5. Click **Approve recovery**; expect status `ACTIVE` and an appended `RECOVERED` event. Advance again; expect `PACKED`.

### C. Projection corruption, reconstructed

1. Create another workflow; advance once to `VALIDATED`.
2. Click **Corrupt projection**. The stored step is now deliberately wrong, but **no event** legitimizes this mutation.
3. Click **Advance one step**; expect HTTP 409 (shown as an error), preventing a transition based on inconsistent state.
4. Click **Inspect & replay**; expect `DRIFT DETECTED`, expected `VALIDATED / ACTIVE`, action `REBUILD_PROJECTION` and a matching dry-run state.
5. Click **Approve recovery**; expect stored step `VALIDATED` again and a `PROJECTION_REPAIRED` audit event. Inspect again; expect `NONE`.
6. Restart the stack and verify the repaired workflow persists.

### D. API authentication and errors

- Try `curl -i http://localhost:8080/api/workflows` without credentials: expect **401**.
- Try creating with an empty name or a name longer than 100 characters: expect **400**.
- Try advancing a `COMPLETED` workflow: expect **409**.
- Try approving a stale recovery preview: expect **409**.

## API reference

All endpoints except `/api/health` require HTTP Basic credentials. Requests return JSON. Example commands:

```bash
# Health (public)
curl http://localhost:8080/api/health

# List the seeded workflows
curl -u resurrection:dev-only-change-me http://localhost:8080/api/workflows

# Create: copy the "id" UUID from the JSON response for later commands
curl -u resurrection:dev-only-change-me \
  -H 'Content-Type: application/json' \
  -d '{"name":"Demo via API"}' \
  http://localhost:8080/api/workflows

# Substitute your UUID below
ID=YOUR_WORKFLOW_UUID

# Advance one transition
curl -u resurrection:dev-only-change-me -X POST \
  "http://localhost:8080/api/workflows/$ID/advance"

# Arm failure for the next advance
curl -u resurrection:dev-only-change-me -H 'Content-Type: application/json' \
  -d '{"type":"FAIL_NEXT_STEP"}' \
  "http://localhost:8080/api/workflows/$ID/faults"

# Inspect (read-only): note "action" and "expectedSequence"
curl -u resurrection:dev-only-change-me \
  "http://localhost:8080/api/workflows/$ID/recovery-preview"

# Approve the precise action and event sequence from the preview
curl -u resurrection:dev-only-change-me -H 'Content-Type: application/json' \
  -d '{"action":"RESUME_AFTER_FAILURE","expectedSequence":4}' \
  "http://localhost:8080/api/workflows/$ID/recover"
```

That last sample is only valid after `CREATED` (1), `ADVANCED` (2), `FAULT_ARMED` (3), and `STEP_FAILED` (4). Use the actual preview values for other workflows.

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/health` | Public liveness response `{ "status": "UP" }` (not a database readiness check). |
| `POST` | `/api/workflows` | Create; body `{ "name": "..." }`. |
| `GET` | `/api/workflows` | List all current projections. |
| `GET` | `/api/workflows/{id}` | Projection, events, number of checkpoints. |
| `POST` | `/api/workflows/{id}/advance` | One atomic step or a recorded injected failure. |
| `POST` | `/api/workflows/{id}/faults` | Body `{"type":"FAIL_NEXT_STEP"}` or `{"type":"CORRUPT_PROJECTION"}`. |
| `GET` | `/api/workflows/{id}/recovery-preview` | Validate and replay without writing. |
| `POST` | `/api/workflows/{id}/recover` | Body `{ "action": "...", "expectedSequence": 4 }`; requires matching preview. |

### Expected HTTP statuses

`200` for successful reads/commands, `201` for creation, `400` for invalid input, `401` for missing/wrong credentials, `404` for unknown workflow IDs, `409` for invalid transitions, inconsistent projections, stale approvals, or damaged history.

## Exact commands to run automated tests

You need **JDK 25**, Maven 3.9+, and a running Docker engine. `Testcontainers` starts a disposable PostgreSQL container during integration tests; it does not use your Compose database.

```bash
cd backend
mvn -B verify
```

This runs the pure-Java replay unit tests and Spring MVC/PostgreSQL integration tests. It also tests authentication and rejected unsafe writes. Docker must be reachable by the test process (in GitHub Actions, it is).

Frontend build and TypeScript verification:

```bash
cd frontend
npm install
npm run build
```

For hot-reload development while Compose is running:

```bash
cd frontend
npm run dev
```

Open http://localhost:5173; Vite proxies `/api` to the backend at `localhost:8081`.

The project pins every **direct** npm dependency to an exact version. The included `package-lock.json` provides the root manifest but was not fully resolved because npm registry access was unavailable in the creation environment. **Run `npm install` once on an internet-connected machine** to populate the transitive lock before committing or switching CI to `npm ci`. The supplied Dockerfile and CI workflow intentionally run `npm install`, not `npm ci`, for this reason. This is a reproducibility limitation to resolve before production use.

## Project map

```text
resurrection/
├── compose.yaml                 # one-command API + UI + database
├── .env.example                 # local demo credentials
├── .github/workflows/ci.yml     # Java and React checks
├── backend/
│   ├── pom.xml                  # Spring Boot / pinned Java release
│   ├── Dockerfile
│   └── src/
│       ├── main/java/dev/resurrection/
│       │   ├── api/            # HTTP endpoints and errors
│       │   ├── config/         # Basic auth and demo seed data
│       │   ├── domain/         # deterministic replay and rules
│       │   ├── persistence/    # JPA entities and repositories
│       │   └── service/        # transactions and recovery commands
│       ├── main/resources/db/migration/V1__init.sql
│       └── test/java/dev/resurrection/  # unit/integration tests
└── frontend/
    ├── package.json / package-lock.json
    ├── src/App.tsx              # dashboard and actions
    ├── src/types.ts             # API response types
    └── nginx.conf              # static UI and /api reverse proxy
```

## Architecture and engineering decisions

**PostgreSQL is the durability boundary.** Each command writes its event, stored projection, and optional checkpoint inside a single Spring `@Transactional` database transaction. On a database crash, PostgreSQL commits all of them or none; the demo does not pretend to recover partial external side effects.

**The event history is the source of truth.** The `workflows` row is a projection for quick reads. The pure-Java `ReplayEngine` validates event order, a per-workflow SHA-256 hash chain, legal state transitions, and every saved checkpoint. Replay recreates the expected state. A checkpoint is also validated against replayed history rather than blindly trusted. The hashes detect accidental or unauthorized edits that do not recompute the entire chain; they are **not cryptographic signatures** against a privileged database attacker who can rewrite every hash. All events are currently scanned on each replay: clear and correct for a small MVP, but O(number of events) work. A later phase can use independently validated checkpoint anchors for faster replay.

**All modifying commands lock the workflow row.** `SELECT ... FOR UPDATE` (JPA `PESSIMISTIC_WRITE`) serializes transitions for the same workflow and keeps its sequence assignment consistent. The database also enforces `(workflow_id, sequence_number)` uniqueness. A recovery approval carries the event sequence seen in the preview to prevent stale approvals. This is a small single-database design, not a distributed coordination protocol.

**Faults are controlled and explicit.** `FAIL_NEXT_STEP` records a fault and later an unsuccessful transition without advancing the step. `CORRUPT_PROJECTION` intentionally modifies only the projection to emulate an inconsistent write; it is an operator-only demo action, not a production feature. No endpoint corrupts the event ledger. If the ledger or a checkpoint fails verification, auto-repair is blocked.

**Basic security is present, not production-complete.** The API uses a configured operator with Spring Security HTTP Basic, input validation, non-cookie stateless authentication, and loopback-only exposed host ports. The demo credentials are intentionally known. Do not expose this as-is on a public network. Production would need HTTPS, proper identity/RBAC, secret management, audit identities, CSRF review, rate limiting, and removal of the corruption endpoint.

**Why not Kafka, Redis, Kubernetes, AI auto-fixes, or microservices?** They are unnecessary for a four-feature MVP. A single PostgreSQL transaction is easier to understand and proves atomic local state changes. The system **does not** discover arbitrary root causes, simulate several candidate fixes, or repair external systems yet; it diagnoses only the two intentionally injected failure modes.

## Limits and troubleshooting

- **The first build needs internet.** Maven Central, npm, and Docker image registries must be reachable.
- **Docker daemon not running:** start Docker Desktop/Engine, then `docker compose up --build`.
- **Port already allocated:** free local ports 8080, 8081, or 5433; or edit the left side of port mappings in `compose.yaml`.
- **Frontend returns 502 on initial startup:** the Spring API may still be starting. Wait for the API log to show startup completion and refresh; `depends_on` does not wait for Spring readiness.
- **Database startup errors after password change:** PostgreSQL's named volume retained an old password. Restore old `.env` credentials or reset the local demo volume with `docker compose down -v` (this deletes demo data).
- **`mvn verify` fails with Docker/Testcontainers unavailable:** start Docker and verify `docker ps` works under the same user running Maven.
- **Recovery shows HTTP 409:** inspect the proposed action; if history integrity is damaged, recovery deliberately refuses to proceed.
- **Preview after corruption:** the dashboard shows the intentionally wrong projection until you approve reconstruction.
- **No production guarantees:** no external side-effect compensation, multi-instance leader election, database restore from total data loss, cross-service saga, cryptographic signatures, event pruning, or high-volume benchmarking.

## Short roadmap

**Phase 1 — MVP (included):** Spring Boot + React, PostgreSQL event ledger, validated checkpoints, fault injection, replay, human-approved recovery, demo data, tests, Compose, CI configuration.

**Phase 2 — reliability and security:** Fully resolved npm lockfile, operator identities and real audit trails, multi-failure test scenarios, stricter invariants, immutable ledger DB permissions, deeper negative tests, realistic recovery previews.

**Phase 3 — scale:** Measure replay latency with long histories; validate checkpoint anchors and resume replay from latest trusted checkpoint; exercise concurrent commands, index/event growth, optimistic versus pessimistic locking, and recovery under process restarts. Introduce a queue only if measurements require one.

**Phase 4 — ship and explain:** Deploy with TLS, secrets, real metrics and traces, dashboards, backup-and-restore drills, load tests, and a concise architecture diagram. Record a demo of fail → detect → replay → approve → restore and publish CI evidence.

## Verification record at packaging time

The package was authored in an environment with **Java 21**, **Node.js 22**, and **no Maven, Docker daemon, or external package registry access**. A standalone compile/run of the dependency-free Java replay engine, source/JSON/YAML checks, and TypeScript syntax checks can be performed here; **this is not equivalent to a successful Spring Boot build, PostgreSQL Testcontainers suite, Docker Compose run, or React bundle build**. Run the exact tests above in a normal development environment and capture the CI results before claiming the complete app is verified.

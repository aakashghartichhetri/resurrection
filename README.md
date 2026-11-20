# RESURRECTION — Workflow Recovery

**Know what failed. Recover with confidence.**

RESURRECTION is a full-stack operator workspace for understanding failed workflows. It saves each step in PostgreSQL, reconstructs the expected state from a verified event history, and asks an operator to approve recovery before changing anything.

## Overview

Imagine a warehouse dispatch stops after validation. Did packing finish? Was the database updated? Is it safe to retry?

The workspace answers those questions using **deterministic event replay**, rather than simply trusting the latest saved status. The sample dispatches are simulated; nothing is shipped or charged in the real world.

## Tech Stack

- **Frontend:** React, TypeScript, Vite, responsive CSS
- **Backend:** Java 25, Spring Boot, Spring MVC, Spring Security, JPA
- **Data:** PostgreSQL, Flyway, hash-linked events, checkpoints
- **Testing:** JUnit, Testcontainers, frontend logic tests, API smoke test
- **Deployment:** Docker Compose, Nginx, Caddy HTTPS, GitHub Actions

## Key Features

- **Operations overview:** Find the most urgent incident, inspect live workflow totals, and jump directly to an investigation.
- **Workflow register:** Search and filter dispatches, follow their progress, and advance valid steps.
- **Replay and recovery:** Check event hashes and checkpoints, compare reconstructed and saved state, preview a repair, and record an approval. Invalid history blocks recovery.
- **Failure drills:** Simulate a failed step or corrupted saved state in the local demonstration.
- **Audit history:** Review events, approvals, and fault tests; export a JSON evidence report.

## Getting Started

**Prerequisite:** Docker Desktop or Docker Engine with Compose v2. No local Java or Node installation is required.

```bash
cp .env.example .env
docker compose -p resurrection up -d --build --wait
docker compose -p resurrection ps
```

Open **http://localhost:8080** and sign in with the local example credentials: `resurrection` / `dev-only-change-me`. The backend listens on `http://localhost:8081`; PostgreSQL is not exposed to the host.

**Important:** Change the values in `.env` before using this beyond your own machine. If upgrading an existing installation, **keep its original `.env`** rather than copying the example. PostgreSQL passwords do not automatically change inside an existing volume.

## Try a Recovery

1. Select **Practice a recovery** and start a drill.
2. Open **Investigations**. Compare the stored state with the replayed result and read the causal timeline.
3. Review the proposed action, approve it, and inspect the decision in **Audit history**.
4. Continue the workflow in **Workflow register** and export the evidence if needed.

## Testing

```bash
# With the local stack running
bash scripts/smoke-test.sh

# Full backend and frontend suites (JDK 25, Maven, Node.js 22 and Docker)
(cd backend && mvn -B verify)
(cd frontend && npm install && npm test && npm run build)

# Stop, preserving your database
docker compose -p resurrection down
```

GitHub Actions runs the tests and builds the complete local Compose stack. For repeatable frontend installs, run `npm install` on a networked machine and commit the generated `frontend/package-lock.json`.

## Architecture

```text
React dashboard → Nginx → Spring Boot API → PostgreSQL
                            ├─ Workflow commands and approval audit
                            └─ Deterministic replay and checkpoints
```

Database transactions keep state changes and events together. Idempotency keys protect retry requests, and row locking protects concurrent updates. The interface refreshes the operations view automatically while keeping recovery approval manual.

## Deployment and Scope

`compose.deploy.yaml` and `deploy/Caddyfile` support **single-operator HTTPS deployment** with a private API and database. Set a real domain and strong credentials with `deploy/.env.production.example`; production mode disables sample data and fault injection.

This is a **functional recovery demonstration and potential single-operator pilot**, not a finished multi-customer SaaS. It does not repair arbitrary external services or provide customer accounts, tenant isolation, billing, password recovery, enterprise monitoring, or a contractual availability guarantee. Those require additional development and testing before accepting paying customers.

To upgrade without losing data, back up PostgreSQL first and reuse the Compose project name `resurrection`. Do not run `docker compose down -v` unless you intend to delete the saved database.

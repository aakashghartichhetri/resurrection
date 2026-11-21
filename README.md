# RESURRECTION 

**A Java and React application that records workflow transitions, reconstructs failed dispatches from verified events, and lets an operator approve a safe recovery.**

## Why it exists

A warehouse dispatch can fail between validation and packing. After a restart, an ordinary status field may not explain what actually committed. RESURRECTION is a small investigation workspace for engineers and operators: it records each step, checks the history, and explains whether the saved state can be trusted. All dispatches are **simulated**; this application does not contact shipping or payment services.

## What it can do

- **Monitor workflows:** Search dispatches, see which need attention, and inspect recent transitions.
- **Replay event history:** Validate ordered, SHA-256-linked events and checkpoints before reconstructing state.
- **Practice failures:** Inject a stopped transition or corrupt a saved projection in the local demo.
- **Review recovery:** Preview a suggested action, detect stale approvals, and keep an operator audit trail.
- **Handle retries safely:** Use database transactions, workflow row locks, and idempotency keys to avoid duplicate transitions.
- **Investigate problems:** Export evidence and match API errors to server logs using request IDs.

## Stack and architecture

**Java 25**, Spring Boot 3.5, Spring MVC, Spring Security, Spring Data JPA, PostgreSQL 17, Flyway, JUnit, Testcontainers, React 19, TypeScript, Vite, Nginx, Docker Compose, and GitHub Actions. Caddy provides HTTPS for a single-operator deployment.

```text
Browser → Nginx → Spring Boot API → PostgreSQL
                     │                 ├─ Workflow projection
                     ├─ Commands        ├─ Events & checkpoints
                     └─ Replay & audit  └─ Approvals
```

## Get started

**Prerequisite:** Docker Desktop or Docker Engine with Compose v2. You do not need Java, Maven, Node, or PostgreSQL installed on your computer to start the stack.

```bash
cp .env.example .env
docker compose -p resurrection up -d --build --wait
docker compose -p resurrection ps
```

Open **http://localhost:8080**. The development login is `resurrection` / `dev-only-change-me`; change it in `.env` before any shared use. PostgreSQL stays inside Docker. Keep the Compose project name and existing `.env` when upgrading so the original database volume is reused. Do **not** run `down -v` if you need your data.

To try recovery, open **Practice a recovery**, start a simulated incident, inspect it in **Investigations**, review the proposed action, then approve. The approval appears in **Audit history**. If event history is invalid, the app blocks repair rather than guessing.

## Tests and checks

```bash
bash scripts/replay-check.sh                  # Pure Java integrity checks
bash scripts/replay-benchmark.sh              # Replay-only microbenchmark
bash scripts/smoke-test.sh                    # With Compose running
bash scripts/restart-check.sh                 # Restart API in a test stack only
bash scripts/verify-backup.sh                 # Disposable PostgreSQL restore
(cd backend && mvn -B verify)                 # JDK 25, Maven, Docker
(cd frontend && npm install && npm test && npm run build)
docker compose -p resurrection down           # Preserves database volume
```

The pure-Java check and frontend logic tests passed in the authoring environment. Full Maven/Testcontainers, frontend bundle, Docker startup, database restore, and remote CI are **UNVERIFIED** for this revision. See [Testing](docs/TESTING.md) and [Replay benchmarks](docs/BENCHMARKS.md) for measured results and precise limits. A fully resolved `frontend/package-lock.json` still needs to be generated and committed from a networked machine for reproducible `npm ci` builds.

## Engineering decisions

- **Events are evidence; projections are convenience.** Replay detects disagreement instead of silently overwriting it.
- **Atomic operations matter.** Event writes, checkpoints, command results, and projection updates commit or roll back together.
- **An approval is conditional.** Recovery rechecks the latest history and saved-state fingerprint while holding a row lock.
- **Keep the architecture small.** The current use case does not require Kafka, Kubernetes, or distributed workers.

## Limitations and deployment

This is a **single-operator lab**, not a production multi-tenant recovery platform. Hash chains detect accidental modification but cannot stop a database administrator from rewriting and rehashing history. It has no real-world side-effect reconciliation, MFA, customer isolation, or uptime guarantees. For hosted HTTPS setup and operational boundaries, read [Architecture](docs/ARCHITECTURE.md), [Security](docs/SECURITY.md), and the [Runbook](docs/RUNBOOK.md). Do not expose the local demo configuration to the public internet.

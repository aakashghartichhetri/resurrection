# Test plan and verification gates

Run the dependency-free replay safety check with `bash scripts/replay-check.sh`. It compiles the pure Java domain classes and checks deterministic replay, corrupted event messages, reordered events, and a deleted checkpoint. `bash scripts/replay-benchmark.sh` runs a narrow replay-only benchmark; see `BENCHMARKS.md`.

With **JDK 25, Maven 3.9+, and Docker**, run `cd backend && mvn -B verify`. JUnit and Testcontainers use a real PostgreSQL 17 container with Flyway migrations. Integration cases cover authorization, input validation, replay and checkpoints, idempotent retries, concurrent advances, cross-workflow key races, simultaneous approvals, corrupt projections, damaged history, and rollback if the recovery approval audit fails. The rollback case installs a temporary database trigger and drops it afterward.

With **Node.js 22.12+** and registry access, run `cd frontend && npm install && npm test && npm run build`. The frontend suite checks evidence handling, fleet sorting/filtering, and human-readable API errors. It does not replace real browser-accessibility and interaction tests.

With the application started using `docker compose -p resurrection up -d --build --wait`, run `bash scripts/smoke-test.sh`. It performs a complete authenticated failure/recovery story. `bash scripts/restart-check.sh` restarts the API service in the **isolated test stack** and confirms the workflow and events survive. Do not run this against a shared or production deployment. `bash scripts/verify-backup.sh` dumps the active local database and restores it into an isolated, disposable PostgreSQL container; it does not replace the source database.

CI runs the replay check, Maven/Testcontainers tests, frontend tests, dependency audit, production UI build, Docker stack smoke test, and a disposable database restore. A passing CI run must be observed before calling the release verified. GitHub Actions and the production HTTPS path have **not** been run in the offline authoring environment.

For HTTP workload measurements on a **throwaway local database only**, run `python3 scripts/api-benchmark.py --requests 100 --concurrency 8`. It creates benchmark workflows; do not run it on real customer records or public deployments. This script has been syntax-checked, not executed against a live API here.

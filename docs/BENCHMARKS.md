# Replay microbenchmark

Run `bash scripts/replay-benchmark.sh` from the repository root. It creates fixed, synthetically generated event chains and checkpoints, warms the JVM, and runs 75 replay samples per history size. It reports median/p95 elapsed time, replay throughput, and per-thread Java allocation when supported by the runtime.

Measured on **2026-11-21**, in the authoring container: Linux, **OpenJDK 21.0.12.1**, **4 reported CPUs**, with no PostgreSQL, network, or concurrent clients. This is a compatibility-only measurement; the application targets **Java 25**. One observed run:

| Events | Median (ms) | p95 (ms) | Replays/s | Avg thread allocation |
|---:|---:|---:|---:|---:|
| 10 | 0.178 | 0.467 | 4,784 | 19,784 B |
| 100 | 0.275 | 0.449 | 2,826 | 199,710 B |
| 1,000 | 0.558 | 3.860 | 971 | 1,317,444 B |
| 5,000 | 2.155 | 5.690 | 333 | 6,594,432 B |

Numbers vary substantially with JIT warmup, garbage collection, CPU scheduling, and machine load. They are **not** request latency, database throughput, full-service memory use, or availability evidence. In this implementation replay is O(history length); use an end-to-end load test on the actual deployment before quoting service throughput.

## End-to-end load check (UNVERIFIED here)

With a **disposable local database**, run `python3 scripts/api-benchmark.py --requests 100 --concurrency 8`. It creates and advances a fresh workflow for every sample using concurrent HTTP clients, then prints median and p95 end-to-end request-pair latency, completed operation throughput, and wall time. It does not delete these test workflows. Record the host CPU/RAM, Docker configuration, Postgres volume type, Java version, concurrency, exact command, and warmup state before comparing runs. This check cannot establish saturation throughput or sustained SLOs; it is a repeatable diagnostic starting point. No HTTP throughput numbers have been measured in this authoring environment.

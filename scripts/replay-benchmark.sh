#!/usr/bin/env bash
# Benchmark only the pure event replay algorithm. No API, PostgreSQL, or network I/O.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
javac --release 21 -d "$TMP" "$ROOT"/backend/src/main/java/dev/resurrection/domain/*.java \
  "$ROOT"/backend/src/test/java/dev/resurrection/domain/ReplayBenchmark.java
java -cp "$TMP" dev.resurrection.domain.ReplayBenchmark

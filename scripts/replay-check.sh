#!/usr/bin/env bash
# Verify the deterministic replay boundary without Maven, Docker, or the database.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
javac --release 21 -d "$TMP" "$ROOT"/backend/src/main/java/dev/resurrection/domain/*.java \
  "$ROOT"/backend/src/test/java/dev/resurrection/domain/ReplaySelfCheck.java
java -cp "$TMP" dev.resurrection.domain.ReplaySelfCheck

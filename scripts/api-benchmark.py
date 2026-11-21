#!/usr/bin/env python3
"""End-to-end create+advance load check for a disposable local RESURRECTION database.

No third-party Python dependencies. Creates new workflows, never modifies existing ones.
DO NOT RUN against a production deployment.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import math
import os
import statistics
import time
from urllib.request import Request, urlopen
import uuid


def run_one(index: int, base: str, auth: str):
    def post(path, payload, key=None):
        headers = {"Authorization": auth, "Content-Type": "application/json"}
        if key is not None:
            headers["Idempotency-Key"] = key
        request = Request(base + path, data=json.dumps(payload).encode(), headers=headers, method="POST")
        with urlopen(request, timeout=20) as response:
            return json.loads(response.read())

    start = time.perf_counter()
    created = post("/api/workflows", {"name": f"benchmark-{index:04}-{uuid.uuid4().hex[:8]}"})
    advanced = post(f"/api/workflows/{created['id']}/advance", {}, str(uuid.uuid4()))
    if advanced["step"] != "VALIDATED":
        raise RuntimeError(f"Unexpected step: {advanced['step']}")
    return (time.perf_counter() - start) * 1000


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--requests", type=int, default=30)
    parser.add_argument("--concurrency", type=int, default=4)
    args = parser.parse_args()
    if not 1 <= args.requests <= 500 or not 1 <= args.concurrency <= 32:
        parser.error("requests must be 1..500; concurrency must be 1..32")
    base = os.getenv("BASE_URL", "http://localhost:8080").rstrip("/")
    username = os.getenv("APP_USERNAME", "resurrection")
    password = os.getenv("APP_PASSWORD", "dev-only-change-me")
    auth = "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode()
    durations = []
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(run_one, i, base, auth) for i in range(args.requests)]
        for future in as_completed(futures):
            durations.append(future.result())
    seconds = time.perf_counter() - started
    durations.sort()
    p95 = durations[math.ceil(0.95 * len(durations)) - 1]
    print(json.dumps({
        "requests": args.requests,
        "concurrency": args.concurrency,
        "operation": "create workflow + advance once",
        "median_ms": round(statistics.median(durations), 3),
        "p95_ms": round(p95, 3),
        "throughput_ops_s": round(args.requests / seconds, 3),
        "wall_seconds": round(seconds, 3),
    }, indent=2))


if __name__ == "__main__":
    main()

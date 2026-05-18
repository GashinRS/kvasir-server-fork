#!/usr/bin/env python3
"""
run_benchmarks.py — Kvasir KG query benchmark runner
=====================================================

Runs the three benchmark suites against the configured Kvasir pod and
prints latency statistics (min / avg / p95 / max).

The benchmark parameters are derived from:
  1. benchmark_state.json  — written by load_data.py (preferred)
  2. Live pod introspection — if no state file is present, the script
     queries the pod to discover sensor IRIs and the timestamp range.

Usage:
    python run_benchmarks.py

All settings (server URL, credentials, iterations, …) are read from .env.
"""

from __future__ import annotations

import json
import os
import random
import sys
import time
from datetime import datetime, timezone, timedelta
from typing import Any

import requests

from kvasir_benchmark_helpers import (
    CFG,
    JSONLD_CONTEXT,
    SAREF_HAS_TIMESTAMP,
    SAREF_MEASUREMENT,
    SAREF_SENSOR,
    auth_headers,
    print_stats,
    run_query,
)


SLICE_BENCHMARK_NAME = "benchmark-timeseries-slice"
LATEST_MEASUREMENTS_LIMIT = 50
SLICE_BENCHMARK_SDL = """
type Query {
    measurements: [saref_Measurement!]!
}

type saref_Measurement {
    id: ID!
    saref_hasTimestamp: String
    saref_hasValue: Int
}
""".strip()


# ---------------------------------------------------------------------------
# State loading / discovery
# ---------------------------------------------------------------------------

def _load_state() -> dict | None:
    if os.path.exists(CFG.state_file):
        with open(CFG.state_file, encoding="utf-8") as f:
            state = json.load(f)
        # Validate that the state matches the current pod
        if state.get("pod_uri") != CFG.pod_uri:
            print(
                f"[WARN] State file pod_uri ({state['pod_uri']}) does not match "
                f"configured pod ({CFG.pod_uri}). Ignoring state file and discovering live."
            )
            return None
        print(f"[INFO] Loaded state from '{CFG.state_file}'.")
        return state
    return None


def _discover_state() -> dict:
    """
    Auto-discover sensor IRIs and timestamp range from the live pod.

    Queries all saref_Sensor entities to get their IRIs, then fetches the
    min/max saref_hasTimestamp from saref_Measurement to build the time window.
    """
    print("[INFO] No state file found — discovering data from the live pod…")

    # Discover sensors
    sensors_result = run_query(
        "{ saref_Sensor { id rdfs_label } }",
        context=JSONLD_CONTEXT,
    )
    sensor_rows = sensors_result.get("data", {}).get("saref_Sensor", [])
    if not sensor_rows:
        print(
            "[ERROR] No saref_Sensor entities found in the pod. "
            "Run load_data.py first.",
            file=sys.stderr,
        )
        sys.exit(1)

    sensor_ids: list[str] = [row["id"] for row in sensor_rows if "id" in row]
    print(f"[INFO] Discovered {len(sensor_ids)} sensors.")

    # Discover min timestamp (first record) and total count via two cheap page-1 queries
    min_result = run_query(
        '{ saref_Measurement(orderBy: ["saref_hasTimestamp"], pageSize: 1) { saref_hasTimestamp } }',
        context=JSONLD_CONTEXT,
    )
    min_rows = min_result.get("data", {}).get("saref_Measurement", [])
    if not min_rows or not min_rows[0].get("saref_hasTimestamp"):
        print(
            "[ERROR] No saref_Measurement timestamps found. Run load_data.py first.",
            file=sys.stderr,
        )
        sys.exit(1)
    min_ts_raw = min_rows[0]["saref_hasTimestamp"]
    min_ts_iso = min_ts_raw[0] if isinstance(min_ts_raw, list) else min_ts_raw

    max_result = run_query(
        '{ saref_Measurement(orderBy: ["-saref_hasTimestamp"], pageSize: 1) { saref_hasTimestamp } }',
        context=JSONLD_CONTEXT,
    )
    max_rows = max_result.get("data", {}).get("saref_Measurement", [])
    if max_rows:
        max_ts_raw = max_rows[0]["saref_hasTimestamp"]
        max_ts_iso = max_ts_raw[0] if isinstance(max_ts_raw, list) else max_ts_raw
    else:
        max_ts_iso = min_ts_iso

    # Estimate records_per_sensor from the timestamp span (each record is 1 second apart)
    try:
        min_dt = datetime.fromisoformat(min_ts_iso.replace("Z", "+00:00"))
        max_dt = datetime.fromisoformat(max_ts_iso.replace("Z", "+00:00"))
        span_seconds = int((max_dt - min_dt).total_seconds()) + 1
        records_per_sensor = span_seconds
    except Exception:
        records_per_sensor = CFG.records_per_sensor  # fall back to configured value

    return {
        "pod_uri": CFG.pod_uri,
        "sensor_ids": sensor_ids,
        "start_timestamp_iso": min_ts_iso,
        "records_per_sensor": records_per_sensor,
        "nr_of_sensors": len(sensor_ids),
        "generated_at": None,  # unknown
    }


def _build_time_window(state: dict) -> tuple[str, str]:
    """
    Derive a query time window that is 25 % into the dataset timeline,
    TIME_RANGE_SECONDS wide — mirroring the JUnit benchmark logic.
    """
    start_ts_iso = state["start_timestamp_iso"]
    records_per_sensor = state.get("records_per_sensor", CFG.records_per_sensor)

    # Parse the stored ISO timestamp
    start_dt = datetime.fromisoformat(start_ts_iso.replace("Z", "+00:00"))

    quarter_offset = records_per_sensor // 4
    window_start = start_dt + timedelta(seconds=quarter_offset)
    window_end = window_start + timedelta(seconds=CFG.time_range_seconds)

    # Truncate to microseconds (as the JUnit test does with ChronoUnit.MICROS)
    def _fmt(dt: datetime) -> str:
        return dt.strftime("%Y-%m-%dT%H:%M:%S.%f") + "Z"

    return _fmt(window_start), _fmt(window_end)


# ---------------------------------------------------------------------------
# Benchmark helpers
# ---------------------------------------------------------------------------

def _timed_query(graphql: str, context: dict | None = None) -> tuple[dict, float]:
    t0 = time.perf_counter()
    result = run_query(graphql, context)
    elapsed_ms = (time.perf_counter() - t0) * 1000
    return result, elapsed_ms


def _slice_uri(slice_name: str) -> str:
    return f"{CFG.pod_uri}/slices/{slice_name}"


def _slice_payload(slice_name: str, sdl: str) -> dict:
    return {
        "@context": JSONLD_CONTEXT,
        "kss:name": slice_name,
        "kss:schema": {
            "@type": "https://kvasir.discover.ilabt.imec.be/vocab#EmbeddedSliceSchema",
            "kss:sdl": sdl,
        },
        "kss:description": "Benchmark slice for external time-range query measurements.",
    }


def _ensure_slice(slice_name: str, sdl: str) -> str:
    """Ensure a benchmark slice exists and has the expected embedded SDL."""
    slice_uri = _slice_uri(slice_name)
    get_resp = requests.get(
        slice_uri,
        headers=auth_headers({"Accept": "application/ld+json"}),
        timeout=60,
    )

    if get_resp.status_code == 404:
        create_resp = requests.post(
            f"{CFG.pod_uri}/slices",
            json=_slice_payload(slice_name, sdl),
            headers=auth_headers({"Content-Type": "application/ld+json", "Accept": "application/ld+json"}),
            timeout=60,
        )
        if create_resp.status_code not in (200, 201, 204):
            create_resp.raise_for_status()
        print(f"[INFO] Created benchmark slice '{slice_name}'.")
        return slice_name

    get_resp.raise_for_status()
    current_slice = get_resp.json()
    current_context = current_slice.get("context") or {}
    current_schema = current_slice.get("schema") or {}
    current_sdl = current_schema.get("sdl")

    if current_sdl != sdl or current_context != JSONLD_CONTEXT:
        update_resp = requests.put(
            slice_uri,
            json=_slice_payload(slice_name, sdl),
            headers=auth_headers({"Content-Type": "application/ld+json", "Accept": "application/ld+json"}),
            timeout=60,
        )
        if update_resp.status_code not in (200, 204):
            update_resp.raise_for_status()
        print(f"[INFO] Updated benchmark slice '{slice_name}' to match expected SDL/context.")
    else:
        print(f"[INFO] Reusing benchmark slice '{slice_name}'.")

    return slice_name


def _run_iterations(
    label: str,
    graphql_fn,          # callable(iteration: int) -> str
    result_key: str,
    context: dict | None = None,
) -> list[float]:
    latencies: list[float] = []
    for i in range(CFG.query_iterations):
        result, ms = _timed_query(graphql_fn(i), context)
        count = len((result.get("data") or {}).get(result_key) or [])
        if i == 0:
            print(f"  First query returned {count} rows")
        print(f"  Iteration {i+1}/{CFG.query_iterations}: {ms:.0f} ms")
        latencies.append(ms)
    return latencies


# ---------------------------------------------------------------------------
# Benchmark 1 — Time-range query
# ---------------------------------------------------------------------------

def benchmark_time_range(state: dict, window_start: str, window_end: str) -> None:
    print("\n─── Benchmark 1: Time-range query ───")
    nr = state["nr_of_sensors"]
    rps = state.get("records_per_sensor", CFG.records_per_sensor)

    def make_query(_i: int) -> str:
        return f"""
{{
  saref_Measurement(
    orderBy: ["saref_hasTimestamp"]
  ) {{
    id
    saref_hasTimestamp @filter(if: "it>='{window_start}';it<='{window_end}'")
    saref_hasValue
  }}
}}
""".strip()

    latencies = _run_iterations(
        "time-range",
        make_query,
        result_key="saref_Measurement",
        context=JSONLD_CONTEXT,
    )
    print_stats(
        title="ClickHouse Schema Benchmark — Time-range query",
        dataset=f"{nr} sensors × {rps} records = {nr * rps} rows",
        window=f"{window_start} → {window_end}",
        runs=CFG.query_iterations,
        latencies_ms=latencies,
    )


# ---------------------------------------------------------------------------
# Benchmark 2 — Time-range with predefined schema (Slice-like)
# ---------------------------------------------------------------------------

def benchmark_time_range_predefined_schema(state: dict, window_start: str, window_end: str) -> None:
    print("\n─── Benchmark 2: Time-range query via slice endpoint ───")
    nr = state["nr_of_sensors"]
    rps = state.get("records_per_sensor", CFG.records_per_sensor)
    slice_name = _ensure_slice(SLICE_BENCHMARK_NAME, SLICE_BENCHMARK_SDL)

    def make_query(_i: int) -> str:
        return f"""
{{
  measurements(
    orderBy: ["saref_hasTimestamp"]
  ) {{
    id
    saref_hasTimestamp @filter(if: "it>='{window_start}';it<='{window_end}'")
    saref_hasValue
  }}
}}
""".strip()

    def _timed_predefined(i: int) -> tuple[dict, float]:
        body = {
            "query": make_query(i),
        }
        t0 = time.perf_counter()
        resp = requests.post(
            f"{CFG.pod_uri}/slices/{slice_name}/query",
            json=body,
            headers=auth_headers({"Content-Type": "application/json", "Accept": "application/json"}),
            timeout=300,
        )
        resp.raise_for_status()
        elapsed_ms = (time.perf_counter() - t0) * 1000
        return resp.json(), elapsed_ms

    latencies: list[float] = []
    for i in range(CFG.query_iterations):
        result, ms = _timed_predefined(i)
        count = len((result.get("data") or {}).get("measurements") or [])
        if i == 0:
            print(f"  First predefined-schema query returned {count} rows")
        print(f"  Iteration {i+1}/{CFG.query_iterations}: {ms:.0f} ms")
        latencies.append(ms)

    print_stats(
        title="ClickHouse Schema Benchmark — Time-range (slice endpoint)",
        dataset=f"{nr} sensors × {rps} records = {nr * rps} rows",
        window=f"{window_start} → {window_end}",
        runs=CFG.query_iterations,
        latencies_ms=latencies,
    )


# ---------------------------------------------------------------------------
# Benchmark 3 — Single-ID sensor lookup
# ---------------------------------------------------------------------------

def benchmark_single_id_lookup(state: dict) -> None:
    all_sensor_ids: list[str] = state["sensor_ids"]
    sensor_ids = all_sensor_ids[:CFG.single_id_lookup_sensors]
    nr = state["nr_of_sensors"]
    rps = state.get("records_per_sensor", CFG.records_per_sensor)

    # Construct measurement IDs for the selected sensors (using observation 0 from each)
    measurement_ids = [f"{sensor_id}_Observation0" for sensor_id in sensor_ids]

    total_sensor_queries = CFG.query_iterations * len(sensor_ids)
    total_measurement_queries = CFG.query_iterations * len(measurement_ids)
    total_queries = total_sensor_queries + total_measurement_queries

    print(
        f"\n─── Benchmark 3: Single ID lookup "
        f"({len(sensor_ids)} sensors + {len(measurement_ids)} measurements, {total_queries} total queries) ───"
    )

    sensor_latencies: list[float] = []
    measurement_latencies: list[float] = []
    first_sensor = True
    first_measurement = True

    # Sensor ID lookups
    print("  Running sensor ID lookups…")
    for i in range(CFG.query_iterations):
        for j, sensor_id in enumerate(sensor_ids):
            graphql = f"""
{{
  saref_Sensor(id: "{sensor_id}") {{
    id
    rdfs_label
  }}
}}
""".strip()
            result, ms = _timed_query(graphql, context=JSONLD_CONTEXT)
            if first_sensor:
                row = (result.get("data") or {}).get("saref_Sensor")
                print(f"    First sensor id-lookup returned: {row}")
                first_sensor = False
            print(
                f"    Sensor iteration {i+1}/{CFG.query_iterations} "
                f"id {j+1}/{len(sensor_ids)}: {ms:.0f} ms"
            )
            sensor_latencies.append(ms)

    # Measurement ID lookups
    print("  Running measurement ID lookups…")
    for i in range(CFG.query_iterations):
        for j, measurement_id in enumerate(measurement_ids):
            graphql = f"""
{{
  saref_Measurement(id: "{measurement_id}") {{
    id
    saref_hasTimestamp
    saref_hasValue
  }}
}}
""".strip()
            result, ms = _timed_query(graphql, context=JSONLD_CONTEXT)
            if first_measurement:
                row = (result.get("data") or {}).get("saref_Measurement")
                print(f"    First measurement id-lookup returned: {row}")
                first_measurement = False
            print(
                f"    Measurement iteration {i+1}/{CFG.query_iterations} "
                f"id {j+1}/{len(measurement_ids)}: {ms:.0f} ms"
            )
            measurement_latencies.append(ms)

    # Report stats for each entity type and combined
    print_stats(
        title="ClickHouse Single ID Lookup — Sensors",
        dataset=f"{nr} sensors + {nr * rps} measurements in table",
        window=None,
        runs=total_sensor_queries,
        latencies_ms=sensor_latencies,
    )
    print_stats(
        title="ClickHouse Single ID Lookup — Measurements",
        dataset=f"{nr} sensors + {nr * rps} measurements in table",
        window=None,
        runs=total_measurement_queries,
        latencies_ms=measurement_latencies,
    )
    print_stats(
        title="ClickHouse Single ID Lookup — Combined (sensors + measurements)",
        dataset=f"{nr} sensors + {nr * rps} measurements in table",
        window=None,
        runs=total_queries,
        latencies_ms=sensor_latencies + measurement_latencies,
    )


def benchmark_latest_measurements_for_random_sensor(state: dict) -> None:
    sensor_ids: list[str] = state["sensor_ids"]
    if not sensor_ids:
        print("[ERROR] No sensor IDs available for latest-measurements benchmark.", file=sys.stderr)
        sys.exit(1)

    selected_sensor_id = random.choice(sensor_ids)
    nr = state["nr_of_sensors"]
    rps = state.get("records_per_sensor", CFG.records_per_sensor)

    print(
        "\n─── Benchmark 4: Latest measurements for one random sensor "
        f"({LATEST_MEASUREMENTS_LIMIT} rows, {CFG.query_iterations} queries) ───"
    )
    print(f"[INFO] Selected sensor: {selected_sensor_id}")

    def make_query(_i: int) -> str:
        return f"""
{{
  saref_Measurement(
    saref_measurementMadeBy: "{selected_sensor_id}",
    orderBy: ["-saref_hasTimestamp"],
    pageSize: {LATEST_MEASUREMENTS_LIMIT}
  ) {{
    id
    saref_hasTimestamp
    saref_hasValue
  }}
}}
""".strip()

    latencies = _run_iterations(
        "latest-measurements-random-sensor",
        make_query,
        result_key="saref_Measurement",
        context=JSONLD_CONTEXT,
    )
    print_stats(
        title="ClickHouse Latest 50 Measurements Benchmark",
        dataset=(
            f"1 randomly selected sensor out of {nr}; "
            f"{nr * rps} measurements in table"
        ),
        window=None,
        runs=CFG.query_iterations,
        latencies_ms=latencies,
    )


BENCHMARK_RUNNERS = {
    "time_range": lambda state, window_start, window_end: benchmark_time_range(state, window_start, window_end),
    "slice": lambda state, window_start, window_end: benchmark_time_range_predefined_schema(
        state,
        window_start,
        window_end,
    ),
    "single_id_lookup": lambda state, window_start, window_end: benchmark_single_id_lookup(state),
    "latest_measurements": lambda state, window_start, window_end: benchmark_latest_measurements_for_random_sensor(
        state,
    ),
}

BENCHMARK_ALIASES = {
    "time": "time_range",
    "time-range": "time_range",
    "time_range": "time_range",
    "slice": "slice",
    "slice-time-range": "slice",
    "slice_time_range": "slice",
    "single-id": "single_id_lookup",
    "single-id-lookup": "single_id_lookup",
    "single_id": "single_id_lookup",
    "single_id_lookup": "single_id_lookup",
    "latest": "latest_measurements",
    "latest-measurements": "latest_measurements",
    "latest_measurements": "latest_measurements",
    "random-sensor": "latest_measurements",
    "random_sensor": "latest_measurements",
}


def _selected_benchmarks() -> list[str]:
    raw_names = [item.strip().lower() for item in CFG.benchmarks.split(",") if item.strip()]
    if not raw_names:
        raw_names = ["slice"]

    if "all" in raw_names:
        return list(BENCHMARK_RUNNERS.keys())

    selected: list[str] = []
    unknown: list[str] = []
    for name in raw_names:
        normalized = BENCHMARK_ALIASES.get(name)
        if normalized is None:
            unknown.append(name)
            continue
        if normalized not in selected:
            selected.append(normalized)

    if unknown:
        available = ", ".join(["all", *BENCHMARK_RUNNERS.keys()])
        print(
            f"[ERROR] Unknown benchmark name(s): {', '.join(unknown)}. "
            f"Available values: {available}",
            file=sys.stderr,
        )
        sys.exit(1)

    return selected


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    print("=== Kvasir KG query benchmark runner ===")
    print(f"  Pod            : {CFG.pod_uri}")
    print(f"  Iterations     : {CFG.query_iterations}")
    print(f"  Time window    : {CFG.time_range_seconds}s")
    print()

    state = _load_state() or _discover_state()
    window_start, window_end = _build_time_window(state)
    print(f"[INFO] Query window: {window_start} → {window_end}")

    selected_benchmarks = _selected_benchmarks()
    print(f"[INFO] Selected benchmarks: {', '.join(selected_benchmarks)}")

    for benchmark_name in selected_benchmarks:
        BENCHMARK_RUNNERS[benchmark_name](state, window_start, window_end)

    print("\n=== All benchmarks complete ===")


if __name__ == "__main__":
    main()


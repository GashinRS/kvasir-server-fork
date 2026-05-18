#!/usr/bin/env python3
"""
load_data.py — Kvasir KG benchmark data loader
================================================

Generates SAREF sensor + measurement data and ingests it into the configured
Kvasir pod.

Sensors are inserted via the Changes API (small payload).
Measurements are streamed as N-Triples files uploaded to
    PUT /{podId}/s3/benchmark/batch_{n}.nt
and auto-ingested into the KG by the server-side RDF ingester (auto-rdf-ingest
must be enabled for the pod).  Committed status is detected by polling
GET /{podId}/changes and checking kss:associatedReferences for our S3 keys.

All parameters are read from .env (copy .env.example → .env).

Usage:
    python load_data.py
"""

from __future__ import annotations

import io
import json
import random
import sys
import time
import uuid
from datetime import datetime, timezone, timedelta

import requests

from kvasir_benchmark_helpers import (
    CFG,
    EXAMPLE_BASE,
    JSONLD_CONTEXT,
    RDFS_LABEL,
    SAREF_HAS_TIMESTAMP,
    SAREF_HAS_VALUE,
    SAREF_MEASUREMENT,
    SAREF_MEASUREMENT_MADE_BY,
    SAREF_SENSOR,
    auth_headers,
    ensure_pod_exists,
    post_change_and_wait,
)

# ── RDF / XSD constants ────────────────────────────────────────────────────
RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
XSD_DATETIME = "http://www.w3.org/2001/XMLSchema#dateTime"
XSD_INTEGER = "http://www.w3.org/2001/XMLSchema#integer"
KSS_BASE = "https://kvasir.discover.ilabt.imec.be/vocab#"

# How many batches to upload before waiting for them to be committed.
# Keeps the in-flight window bounded without serialising every batch.
UPLOAD_WAVE_SIZE = CFG.upload_wave_size

# Polling interval while waiting for auto-ingested batches to commit (seconds).
POLL_INTERVAL = 3.0

# Timeout for a single wave to be committed (seconds).
WAVE_TIMEOUT = 600


# ─────────────────────────────────────────────────────────────────────────────
# Data generation
# ─────────────────────────────────────────────────────────────────────────────

def _iso(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%S.%f") + "Z"


def generate_sensors(nr: int) -> tuple[list[dict], list[str]]:
    sensors, ids = [], []
    for i in range(nr):
        sid = f"{EXAMPLE_BASE}{uuid.uuid4()}"
        ids.append(sid)
        sensors.append(
            {
                "@id": sid,
                "@type": SAREF_SENSOR,
                RDFS_LABEL: f"Benchmark temperature sensor {i + 1}",
            }
        )
    return sensors, ids


def _nt_uri(uri: str) -> str:
    return f"<{uri}>"


def _nt_typed(value: str, xsd: str) -> str:
    escaped = value.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"^^<{xsd}>'


def generate_nt_batch(
    batch_records: list[tuple[str, str, datetime, int]],
) -> bytes:
    """
    Generate an N-Triples document for a list of (obs_id, sensor_id, ts, value)
    tuples.  Returns the encoded bytes ready for upload.
    """
    buf = io.StringIO()
    for obs_id, sensor_id, ts, value in batch_records:
        subj = _nt_uri(obs_id)
        buf.write(f"{subj} {_nt_uri(RDF_TYPE)} {_nt_uri(SAREF_MEASUREMENT)} .\n")
        buf.write(f"{subj} {_nt_uri(SAREF_MEASUREMENT_MADE_BY)} {_nt_uri(sensor_id)} .\n")
        buf.write(f"{subj} {_nt_uri(SAREF_HAS_TIMESTAMP)} {_nt_typed(_iso(ts), XSD_DATETIME)} .\n")
        buf.write(f"{subj} {_nt_uri(SAREF_HAS_VALUE)} {_nt_typed(str(value), XSD_INTEGER)} .\n")
    return buf.getvalue().encode("utf-8")


def all_measurement_batches(
    sensor_ids: list[str],
    records_per_sensor: int,
    start_ts: datetime,
    batch_size: int,
):
    """
    Yield (batch_index, nt_bytes) pairs, one at a time, without keeping the
    entire dataset in memory.

    Iterates over all sensors × records in interleaved order (same layout as
    TestDataGenerator.getAllJsonLD — sensors first, then all measurements
    grouped by sensor):
        sensor_0 measurements, sensor_1 measurements, ..., sensor_N measurements
    """
    buf: list[tuple[str, str, datetime, int]] = []
    batch_index = 0

    for sensor_id in sensor_ids:
        for j in range(records_per_sensor):
            ts = start_ts + timedelta(seconds=j)
            obs_id = f"{sensor_id}_Observation{j}"
            buf.append((obs_id, sensor_id, ts, random.randint(35, 39)))

            if len(buf) >= batch_size:
                yield batch_index, generate_nt_batch(buf)
                buf.clear()
                batch_index += 1

    if buf:
        yield batch_index, generate_nt_batch(buf)


# ─────────────────────────────────────────────────────────────────────────────
# S3 upload
# ─────────────────────────────────────────────────────────────────────────────

def upload_to_s3(key: str, data: bytes) -> None:
    """Upload an N-Triples file to /{podId}/s3/{key}."""
    resp = requests.put(
        f"{CFG.pod_uri}/s3/{key}",
        data=data,
        headers=auth_headers({"Content-Type": "application/n-triples"}),
        timeout=300,
    )
    resp.raise_for_status()


# ─────────────────────────────────────────────────────────────────────────────
# Change-report polling for auto-ingested S3 files
# ─────────────────────────────────────────────────────────────────────────────

TERMINAL_STATES = {
    "COMMITTED",
    "ASSERTION_FAILED",
    "NO_MATCHES",
    "TOO_MANY_MATCHES",
    "VALIDATION_ERROR",
    "INTERNAL_ERROR",
}


def _list_changes(uri: str | None = None) -> tuple[list[dict], str | None]:
    """
    Fetch one page of change reports from GET /{podId}/changes.

    If uri is None, fetches the first page with default page size.
    If uri is provided, follows that URI directly (from Link rel=next header).

    Returns (items, next_uri).
    """
    if uri is None:
        uri = f"{CFG.pod_uri}/changes?pageSize=200"

    resp = requests.get(
        uri,
        headers=auth_headers({"Accept": "application/ld+json"}),
        timeout=60,
    )
    resp.raise_for_status()
    body = resp.json()

    # The response is a JSON-LD @graph list
    items: list[dict] = body if isinstance(body, list) else body.get("@graph", [])

    # Extract next URI from Link rel=next header
    next_uri = None
    link_header = resp.headers.get("Link", "")
    for part in link_header.split(","):
        part = part.strip()
        if 'rel="next"' in part:
            # Extract the URI from <...>; rel="next"
            url_part = part.split(";")[0].strip().strip("<>")
            next_uri = url_part
            break

    return items, next_uri


def _get_s3_key_from_report(report: dict) -> str | None:
    """
    Extract the S3 key referenced by a change report, if any.
    The JSON-LD structure produced by Kvasir is:
        "kss:associatedReferences": [
            { "kss:changeType": "INSERT",
              "kss:reference": { "@type": "kss:S3Reference", "kss:key": "..." } }
        ]
    """
    refs = report.get("kss:associatedReferences", [])
    if isinstance(refs, dict):
        refs = [refs]
    for ref in refs:
        inner = ref.get("kss:reference", {})
        key = inner.get("kss:key")
        if key:
            return key
    return None


def _get_result_code(report: dict) -> str | None:
    return report.get("kss:statusCode")


def wait_for_s3_keys_committed(
    pending_keys: set[str],
    timeout: float = WAVE_TIMEOUT,
    poll_interval: float = POLL_INTERVAL,
) -> None:
    """
    Poll GET /{podId}/changes until every key in pending_keys has a committed
    (or failed) change report.  Raises on failure or timeout.
    """
    remaining = set(pending_keys)
    deadline = time.time() + timeout

    while remaining:
        if time.time() > deadline:
            raise TimeoutError(
                f"Timed out waiting for {len(remaining)} S3 batch(es) to be committed: "
                + ", ".join(sorted(remaining))
            )

        # Paginate through recent change reports
        next_uri = None
        while True:
            items, next_uri = _list_changes(next_uri)
            for report in items:
                key = _get_s3_key_from_report(report)
                if key and key in remaining:
                    code = _get_result_code(report)
                    if code == "COMMITTED":
                        remaining.discard(key)
                    elif code in TERMINAL_STATES:
                        raise RuntimeError(
                            f"Auto-ingestion of '{key}' failed with status '{code}'"
                        )
            if not next_uri or not remaining:
                break

        if remaining:
            time.sleep(poll_interval)


# ─────────────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────────────

def _change_payload(records: list[dict]) -> dict:
    return {"@context": JSONLD_CONTEXT, "kss:insert": records}


def main() -> None:
    total_records = CFG.nr_of_sensors * CFG.records_per_sensor
    total_batches = -(-total_records // CFG.insert_batch_size)  # ceiling division

    print(
        "=== Kvasir KG benchmark data loader ===\n"
        f"  Pod          : {CFG.pod_uri}\n"
        f"  Sensors      : {CFG.nr_of_sensors}\n"
        f"  Records/sens : {CFG.records_per_sensor:,}\n"
        f"  Total rows   : {total_records:,}\n"
        f"  NT batch size: {CFG.insert_batch_size:,}\n"
        f"  NT batches   : {total_batches}\n"
        f"  Upload wave  : {UPLOAD_WAVE_SIZE} files\n"
    )

    # 1. Ensure pod exists ────────────────────────────────────────────────────
    ensure_pod_exists()

    # 2. Generate and ingest sensor metadata ──────────────────────────────────
    print("[1/3] Generating sensor data…")
    sensors, sensor_ids = generate_sensors(CFG.nr_of_sensors)

    # start_ts mirrors TestDataGenerator: Instant.now().minusSeconds(recordsPerSensor)
    start_ts = datetime.now(tz=timezone.utc).replace(microsecond=0) - timedelta(
        seconds=CFG.records_per_sensor
    )
    start_ts_iso = _iso(start_ts)

    print(f"[2/3] Ingesting {len(sensors)} sensors via Changes API…")
    t0 = time.time()
    post_change_and_wait(_change_payload(sensors))
    print(f"      Done in {time.time() - t0:.1f}s")

    # 3. Stream measurement batches to S3 ─────────────────────────────────────
    print(f"[3/3] Uploading {total_batches} N-Triples batch(es) to S3…")
    wave_keys: list[str] = []
    all_uploaded_keys: list[str] = []
    t_total = time.time()

    for batch_idx, nt_bytes in all_measurement_batches(
        sensor_ids, CFG.records_per_sensor, start_ts, CFG.insert_batch_size
    ):
        s3_key = f"benchmark/batch_{batch_idx:05d}.nt"
        t_batch = time.time()
        upload_to_s3(s3_key, nt_bytes)
        elapsed = time.time() - t_batch
        size_kb = len(nt_bytes) / 1024
        print(
            f"  Uploaded batch {batch_idx + 1}/{total_batches}: "
            f"{s3_key} ({size_kb:.0f} KB) in {elapsed:.1f}s"
        )
        wave_keys.append(s3_key)
        all_uploaded_keys.append(s3_key)

        # Flush wave: wait for this group to be committed before uploading more
        if len(wave_keys) >= UPLOAD_WAVE_SIZE:
            print(f"  Waiting for wave of {len(wave_keys)} batch(es) to be committed…")
            t_wave = time.time()
            wait_for_s3_keys_committed(set(wave_keys))
            print(f"  Wave committed in {time.time() - t_wave:.1f}s")
            wave_keys.clear()

    # Wait for any remaining batches in the last (partial) wave
    if wave_keys:
        print(f"  Waiting for final wave of {len(wave_keys)} batch(es) to be committed…")
        t_wave = time.time()
        wait_for_s3_keys_committed(set(wave_keys))
        print(f"  Final wave committed in {time.time() - t_wave:.1f}s")

    print(f"\n  All batches committed in {time.time() - t_total:.1f}s total.")

    # 4. Write state file ─────────────────────────────────────────────────────
    state = {
        "pod_uri": CFG.pod_uri,
        "sensor_ids": sensor_ids,
        "start_timestamp_iso": start_ts_iso,
        "records_per_sensor": CFG.records_per_sensor,
        "nr_of_sensors": CFG.nr_of_sensors,
        "uploaded_s3_keys": all_uploaded_keys,
        "generated_at": _iso(datetime.now(tz=timezone.utc)),
    }
    with open(CFG.state_file, "w", encoding="utf-8") as f:
        json.dump(state, f, indent=2)

    print(f"\n=== Ingestion complete ===")
    print(f"  State written to: {CFG.state_file}")
    print("  Run 'python run_benchmarks.py' to execute the query benchmarks.")


if __name__ == "__main__":
    main()


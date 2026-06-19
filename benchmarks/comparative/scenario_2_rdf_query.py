#!/usr/bin/env python3
"""
Scenario 2: RDF Query — CSS Solid Protocol vs Kvasir GraphQL

Compares read/query performance for RDF data:
- CSS: download full N-Triples file + client-side parse/filter (using rdflib)
- Kvasir: GraphQL queries with server-side @filter / arguments

Data is pre-loaded to both platforms before benchmarking starts.
"""

from __future__ import annotations

import io
import time
from datetime import timedelta

import rdflib
from rdflib import RDF, URIRef

from helpers.config import CFG
from helpers.css_client import css_client
from helpers.data_generator import (
    JSONLD_CONTEXT,
    SAREF_BASE,
    generate_saref_dataset,
    SarefDataset,
)
from helpers.kvasir_client import (
    kvasir_client_s2 as kvasir_client,
    kvasir_client_candidate_s2 as kvasir_client_cand,
)
from helpers.stats import BenchmarkResult, ResultCollector, print_comparison_table


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

_SCENARIO = "rdf_query"
_NT_FILE = "benchmark-data.nt"
_S3_KEY = "benchmark/rdf-data.nt"

_SAREF_MEASUREMENT = URIRef(f"{SAREF_BASE}Measurement")
_SAREF_HAS_TIMESTAMP = URIRef(f"{SAREF_BASE}hasTimestamp")
_SAREF_MADE_BY = URIRef(f"{SAREF_BASE}measurementMadeBy")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_result(
    platform: str,
    operation: str,
    iteration: int,
    latency_ms: float,
    is_warmup: bool,
    dataset_size: int = 0,
    records_returned: int = 0,
) -> BenchmarkResult:
    return BenchmarkResult(
        scenario=_SCENARIO,
        platform=platform,
        operation=operation,
        dataset_size=dataset_size,
        iteration=iteration,
        latency_ms=latency_ms,
        records_returned=records_returned,
        is_warmup=is_warmup,
    )


def _ts_str(dt) -> str:
    """Format a datetime the same way the SAREF data generator does."""
    return dt.strftime("%Y-%m-%dT%H:%M:%S.%f") + "Z"


def _ordered_platform_latencies(
    latencies: dict[str, list[float]],
) -> dict[str, list[float]]:
    return {
        platform: latencies[platform]
        for platform in CFG.platforms
        if platform in latencies
    }


def _run_kvasir_query(
    client,
    platform: str,
    operation: str,
    query: str,
    result_key: str,
    iteration: int,
    is_warmup: bool,
    collector: ResultCollector,
    latencies: dict[str, list[float]],
    dataset_size: int = 0,
) -> None:
    result, ms = client.run_query(query, context=JSONLD_CONTEXT)
    count_raw = result.get("data", {}).get(result_key, [])
    count = len(count_raw) if isinstance(count_raw, list) else (1 if count_raw else 0)
    collector.add(
        _make_result(
            platform,
            operation,
            iteration,
            ms,
            is_warmup,
            dataset_size=dataset_size,
            records_returned=count,
        )
    )
    if not is_warmup:
        latencies.setdefault(platform, []).append(ms)
    print(f"{platform.upper()}={ms:.0f}ms ({count} rec)", end="  ")


# ---------------------------------------------------------------------------
# Data loading (not timed)
# ---------------------------------------------------------------------------


def _load_data(dataset: SarefDataset) -> None:
    size_kb = len(dataset.ntriples_bytes) / 1024
    total_records = dataset.nr_sensors * dataset.records_per_sensor
    print(
        f"  Dataset: {dataset.nr_sensors} sensors × {dataset.records_per_sensor} records "
        f"= {total_records} measurements ({size_kb:.0f} KB N-Triples)"
    )
    print()

    if CFG.run_css:
        print("  [CSS] Uploading N-Triples file …", end=" ", flush=True)
        css_client.upload_file(
            _NT_FILE, dataset.ntriples_bytes, "application/n-triples"
        )
        print("done.")

    for client, label, run_flag in [
        (kvasir_client, "Kvasir", CFG.run_kvasir),
        (kvasir_client_cand, "Kvasir Candidate", CFG.run_kvasir_candidate),
    ]:
        if not run_flag:
            continue
        print(f"  [{label}] Uploading N-Triples to S3 …", end=" ", flush=True)
        client.upload_rdf_to_s3(_S3_KEY, dataset.ntriples_bytes)
        print("done.")
        print(f"  [{label}] Waiting for auto-ingest …", end=" ", flush=True)
        client.wait_for_s3_key_committed(_S3_KEY)
        print("done.")
    print()


# ---------------------------------------------------------------------------
# Sub-benchmark 1: Full dataset retrieval
# ---------------------------------------------------------------------------


def _benchmark_full_retrieval(
    dataset: SarefDataset, collector: ResultCollector
) -> None:
    total_iterations = CFG.warmup_iterations + CFG.iterations
    total_records = dataset.nr_sensors * dataset.records_per_sensor

    print(f"\n  Sub-benchmark: full_retrieval ({total_records} measurements)")

    css_latencies: list[float] = []
    kvasir_latencies: dict[str, list[float]] = {}

    query = f"""
{{
  saref_Measurement(pageSize: {total_records}) {{
    id
    saref_hasTimestamp
    saref_hasValue
  }}
}}
"""

    for i in range(total_iterations):
        is_warmup = i < CFG.warmup_iterations
        label = (
            "warmup"
            if is_warmup
            else f"iter {i - CFG.warmup_iterations + 1}/{CFG.iterations}"
        )
        print(f"    [{label}] ", end="", flush=True)

        if CFG.run_css:
            t0 = time.perf_counter()
            content, _ = css_client.download_file(_NT_FILE)
            graph = rdflib.Graph()
            graph.parse(io.BytesIO(content), format="nt")
            css_count = len(list(graph.subjects(RDF.type, _SAREF_MEASUREMENT)))
            css_ms = (time.perf_counter() - t0) * 1000
            collector.add(
                _make_result(
                    "css",
                    "full_retrieval",
                    i,
                    css_ms,
                    is_warmup,
                    dataset_size=total_records,
                    records_returned=css_count,
                )
            )
            if not is_warmup:
                css_latencies.append(css_ms)
            print(f"CSS={css_ms:.0f}ms ({css_count} rec)  ", end="", flush=True)

        if CFG.run_kvasir:
            _run_kvasir_query(
                kvasir_client,
                "kvasir",
                "full_retrieval",
                query,
                "saref_Measurement",
                i,
                is_warmup,
                collector,
                kvasir_latencies,
                dataset_size=total_records,
            )

        if CFG.run_kvasir_candidate:
            _run_kvasir_query(
                kvasir_client_cand,
                "kvasir-candidate",
                "full_retrieval",
                query,
                "saref_Measurement",
                i,
                is_warmup,
                collector,
                kvasir_latencies,
                dataset_size=total_records,
            )

        print()

    print_comparison_table(
        "Full Retrieval",
        _ordered_platform_latencies({"css": css_latencies, **kvasir_latencies}),
    )


# ---------------------------------------------------------------------------
# Sub-benchmark 2: Targeted query — single entity
# ---------------------------------------------------------------------------


def _benchmark_targeted_single(
    dataset: SarefDataset, collector: ResultCollector
) -> None:
    total_iterations = CFG.warmup_iterations + CFG.iterations
    target_sensor = dataset.sensor_ids[0]

    print(f"\n  Sub-benchmark: targeted_single (sensor={target_sensor})")

    css_latencies: list[float] = []
    kvasir_latencies: dict[str, list[float]] = {}

    query = f'''
{{
  saref_Sensor(id: "{target_sensor}") {{
    id
    rdfs_label
  }}
}}
'''

    for i in range(total_iterations):
        is_warmup = i < CFG.warmup_iterations
        label = (
            "warmup"
            if is_warmup
            else f"iter {i - CFG.warmup_iterations + 1}/{CFG.iterations}"
        )
        print(f"    [{label}] ", end="", flush=True)

        if CFG.run_css:
            t0 = time.perf_counter()
            content, _ = css_client.download_file(_NT_FILE)
            graph = rdflib.Graph()
            graph.parse(io.BytesIO(content), format="nt")
            css_count = len(list(graph.triples((URIRef(target_sensor), None, None))))
            css_ms = (time.perf_counter() - t0) * 1000
            collector.add(
                _make_result(
                    "css",
                    "targeted_single",
                    i,
                    css_ms,
                    is_warmup,
                    records_returned=css_count,
                )
            )
            if not is_warmup:
                css_latencies.append(css_ms)
            print(f"CSS={css_ms:.0f}ms ({css_count} triples)  ", end="", flush=True)

        if CFG.run_kvasir:
            _run_kvasir_query(
                kvasir_client,
                "kvasir",
                "targeted_single",
                query,
                "saref_Sensor",
                i,
                is_warmup,
                collector,
                kvasir_latencies,
            )

        if CFG.run_kvasir_candidate:
            _run_kvasir_query(
                kvasir_client_cand,
                "kvasir-candidate",
                "targeted_single",
                query,
                "saref_Sensor",
                i,
                is_warmup,
                collector,
                kvasir_latencies,
            )

        print()

    print_comparison_table(
        "Targeted Single Entity",
        _ordered_platform_latencies({"css": css_latencies, **kvasir_latencies}),
    )


# ---------------------------------------------------------------------------
# Sub-benchmark 3: Targeted query — time range
# ---------------------------------------------------------------------------


def _benchmark_targeted_time_range(
    dataset: SarefDataset, collector: ResultCollector
) -> None:
    total_iterations = CFG.warmup_iterations + CFG.iterations

    window_start = dataset.start_timestamp + timedelta(
        seconds=dataset.records_per_sensor // 4
    )
    window_end = window_start + timedelta(seconds=60)
    window_start_str = _ts_str(window_start)
    window_end_str = _ts_str(window_end)

    print(f"\n  Sub-benchmark: targeted_time_range")
    print(f"    Window: {window_start_str} → {window_end_str}")

    css_latencies: list[float] = []
    kvasir_latencies: dict[str, list[float]] = {}

    query = f"""
{{
  saref_Measurement(orderBy: ["saref_hasTimestamp"]) {{
    id
    saref_hasTimestamp @filter(if: "it>='{window_start_str}';it<='{window_end_str}'")
    saref_hasValue
  }}
}}
"""

    for i in range(total_iterations):
        is_warmup = i < CFG.warmup_iterations
        label = (
            "warmup"
            if is_warmup
            else f"iter {i - CFG.warmup_iterations + 1}/{CFG.iterations}"
        )
        print(f"    [{label}] ", end="", flush=True)

        if CFG.run_css:
            t0 = time.perf_counter()
            content, _ = css_client.download_file(_NT_FILE)
            graph = rdflib.Graph()
            graph.parse(io.BytesIO(content), format="nt")
            matching = [
                subj
                for subj in graph.subjects(RDF.type, _SAREF_MEASUREMENT)
                for ts_literal in graph.objects(subj, _SAREF_HAS_TIMESTAMP)
                if window_start_str <= str(ts_literal) <= window_end_str
            ]
            css_ms = (time.perf_counter() - t0) * 1000
            collector.add(
                _make_result(
                    "css",
                    "targeted_time_range",
                    i,
                    css_ms,
                    is_warmup,
                    records_returned=len(matching),
                )
            )
            if not is_warmup:
                css_latencies.append(css_ms)
            print(f"CSS={css_ms:.0f}ms ({len(matching)} matched)  ", end="", flush=True)

        if CFG.run_kvasir:
            _run_kvasir_query(
                kvasir_client,
                "kvasir",
                "targeted_time_range",
                query,
                "saref_Measurement",
                i,
                is_warmup,
                collector,
                kvasir_latencies,
            )

        if CFG.run_kvasir_candidate:
            _run_kvasir_query(
                kvasir_client_cand,
                "kvasir-candidate",
                "targeted_time_range",
                query,
                "saref_Measurement",
                i,
                is_warmup,
                collector,
                kvasir_latencies,
            )

        print()

    print_comparison_table(
        "Targeted Time Range",
        _ordered_platform_latencies({"css": css_latencies, **kvasir_latencies}),
    )


# ---------------------------------------------------------------------------
# Sub-benchmark 4: Latest N records
# ---------------------------------------------------------------------------


def _benchmark_latest_n(dataset: SarefDataset, collector: ResultCollector) -> None:
    total_iterations = CFG.warmup_iterations + CFG.iterations
    target_sensor = dataset.sensor_ids[0]

    print(f"\n  Sub-benchmark: latest_n (50 measurements for sensor {target_sensor})")

    css_latencies: list[float] = []
    kvasir_latencies: dict[str, list[float]] = {}

    query = f"""
{{
  saref_Measurement(
    saref_measurementMadeBy: "{target_sensor}",
    orderBy: ["-saref_hasTimestamp"],
    pageSize: 50
  ) {{
    id
    saref_hasTimestamp
    saref_hasValue
  }}
}}
"""

    for i in range(total_iterations):
        is_warmup = i < CFG.warmup_iterations
        label = (
            "warmup"
            if is_warmup
            else f"iter {i - CFG.warmup_iterations + 1}/{CFG.iterations}"
        )
        print(f"    [{label}] ", end="", flush=True)

        if CFG.run_css:
            t0 = time.perf_counter()
            content, _ = css_client.download_file(_NT_FILE)
            graph = rdflib.Graph()
            graph.parse(io.BytesIO(content), format="nt")
            measurements = sorted(
                [
                    (str(ts), subj)
                    for subj in graph.subjects(_SAREF_MADE_BY, URIRef(target_sensor))
                    for ts in graph.objects(subj, _SAREF_HAS_TIMESTAMP)
                ],
                reverse=True,
            )[:50]
            css_ms = (time.perf_counter() - t0) * 1000
            collector.add(
                _make_result(
                    "css",
                    "latest_n",
                    i,
                    css_ms,
                    is_warmup,
                    records_returned=len(measurements),
                )
            )
            if not is_warmup:
                css_latencies.append(css_ms)
            print(f"CSS={css_ms:.0f}ms ({len(measurements)} rec)  ", end="", flush=True)

        if CFG.run_kvasir:
            _run_kvasir_query(
                kvasir_client,
                "kvasir",
                "latest_n",
                query,
                "saref_Measurement",
                i,
                is_warmup,
                collector,
                kvasir_latencies,
            )

        if CFG.run_kvasir_candidate:
            _run_kvasir_query(
                kvasir_client_cand,
                "kvasir-candidate",
                "latest_n",
                query,
                "saref_Measurement",
                i,
                is_warmup,
                collector,
                kvasir_latencies,
            )

        print()

    print_comparison_table(
        "Latest N Records",
        _ordered_platform_latencies({"css": css_latencies, **kvasir_latencies}),
    )


# ---------------------------------------------------------------------------
# Main entry point
# ---------------------------------------------------------------------------


def run_scenario_2(collector: ResultCollector) -> None:
    print("╔══════════════════════════════════════════════════════════╗")
    print("║  Scenario 2: RDF Query                                   ║")
    print("╚══════════════════════════════════════════════════════════╝")
    print()
    print(f"  Iterations:       {CFG.iterations} (+ {CFG.warmup_iterations} warmup)")
    print(f"  Dataset size:     {CFG.rdf_dataset_size} records per sensor")
    print(f"  Platforms:        {', '.join(CFG.platforms)}")
    print()

    print("Setting up pods …")
    if CFG.run_css:
        css_client.ensure_pod_exists()
    if CFG.run_kvasir:
        kvasir_client.ensure_pod_exists()
    if CFG.run_kvasir_candidate:
        kvasir_client_cand.ensure_pod_exists()
    print()

    print("Generating SAREF dataset …")
    dataset = generate_saref_dataset(
        nr_sensors=5, records_per_sensor=CFG.rdf_dataset_size
    )

    print("Loading data into platforms …")
    _load_data(dataset)

    print("\n╔══════════════════════════════════════════════════════════╗")
    print("║  Phase 1: Full Dataset Retrieval                         ║")
    print("╚══════════════════════════════════════════════════════════╝")
    _benchmark_full_retrieval(dataset, collector)

    print("\n╔══════════════════════════════════════════════════════════╗")
    print("║  Phase 2: Targeted Single Entity                         ║")
    print("╚══════════════════════════════════════════════════════════╝")
    _benchmark_targeted_single(dataset, collector)

    print("\n╔══════════════════════════════════════════════════════════╗")
    print("║  Phase 3: Targeted Time Range                            ║")
    print("╚══════════════════════════════════════════════════════════╝")
    _benchmark_targeted_time_range(dataset, collector)

    print("\n╔══════════════════════════════════════════════════════════╗")
    print("║  Phase 4: Latest N Records                               ║")
    print("╚══════════════════════════════════════════════════════════╝")
    _benchmark_latest_n(dataset, collector)

    print("\n✓ Scenario 2 complete.")


if __name__ == "__main__":
    import os

    collector = ResultCollector()
    run_scenario_2(collector)

    os.makedirs(CFG.results_dir, exist_ok=True)
    csv_path = os.path.join(CFG.results_dir, "scenario_2_rdf_query.csv")
    collector.write_csv(csv_path)
    print(f"\nResults written to {csv_path}")
    print(
        f"Total measurements: {len(collector.results)} ({len(collector.non_warmup())} non-warmup)"
    )

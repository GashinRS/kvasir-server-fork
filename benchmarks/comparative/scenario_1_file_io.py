#!/usr/bin/env python3
"""
Scenario 1: File I/O — CSS Solid Protocol vs Kvasir S3 API vs Direct S3

Compares upload/download performance across file size categories
under concurrent load (configurable via CONCURRENCY_LEVELS, e.g. 1,4,8,16).
"""

from __future__ import annotations

import asyncio
import hashlib
import time
from typing import Callable

import aiohttp

from helpers.async_http import (
    RequestTiming,
    REQUEST_TIMEOUT_STATUS,
    run_concurrent_workload,
    timed_get,
    timed_put,
)
from helpers.config import CFG
from helpers.css_client import css_client, CSSClient
from helpers.data_generator import FILE_CATEGORIES, FileCategory, generate_file_content
from helpers.kvasir_client import (
    KvasirClient,
    kvasir_client_s1 as kvasir_client,
    kvasir_client_candidate_s1 as kvasir_client_cand,
)
from helpers.s3_client import s3_client, run_s3_concurrent_workload
from helpers.stats import BenchmarkResult, ResultCollector, print_comparison_table


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _conc_file_key(
    category: FileCategory,
    index: int,
    concurrency: int,
    iteration: int,
) -> str:
    """File path scoped to concurrency level + iteration."""
    return f"bench/{category.name}/conc_{concurrency}/iter_{iteration}/file_{index:04d}{category.extension}"


def _pregenerate_data() -> dict[str, list[bytes]]:
    data: dict[str, list[bytes]] = {}
    for cat in FILE_CATEGORIES:
        print(f"  Generating {cat.count} × {cat.name} ({cat.size_bytes // 1024} KB) …")
        data[cat.name] = [generate_file_content(cat, i) for i in range(cat.count)]
    return data


def _make_result(
    platform: str,
    operation: str,
    category: FileCategory,
    iteration: int,
    latency_ms: float,
    is_warmup: bool,
    concurrency: int = 1,
    throughput_mbps: float = 0.0,
    success: bool = True,
    status_code: int = 0,
    auth_failure: bool = False,
    timeout_failure: bool = False,
) -> BenchmarkResult:
    return BenchmarkResult(
        scenario="file_io",
        platform=platform,
        operation=operation,
        file_size_category=category.name,
        concurrency=concurrency,
        iteration=iteration,
        latency_ms=latency_ms,
        throughput_mbps=throughput_mbps,
        is_warmup=is_warmup,
        success=success,
        status_code=status_code,
        auth_failure=auth_failure,
        timeout_failure=timeout_failure,
    )


def _is_auth_failure(timing: RequestTiming) -> bool:
    return (not timing.success) and timing.status_code in {401, 403}


def _is_timeout_failure(timing: RequestTiming) -> bool:
    return (not timing.success) and timing.status_code == REQUEST_TIMEOUT_STATUS


def _print_failure_reasons(timings: list[RequestTiming]) -> None:
    reasons: dict[str, int] = {}
    for t in timings:
        if not t.success and t.failure_reason:
            reasons[t.failure_reason] = reasons.get(t.failure_reason, 0) + 1
    for reason, count in sorted(reasons.items(), key=lambda kv: -kv[1]):
        print(f"          ↳ {count}× {reason}")


# ---------------------------------------------------------------------------
# Generic HTTP write/read runner — shared by CSS and Kvasir platforms
# ---------------------------------------------------------------------------


async def _put_worker(
    session: aiohttp.ClientSession, item: tuple[str, bytes, dict[str, str]]
) -> RequestTiming:
    url, data, headers = item
    return await timed_put(session, url, data, headers)


async def _get_worker(
    session: aiohttp.ClientSession, item: tuple[str, dict[str, str]]
) -> RequestTiming:
    url, headers = item
    return await timed_get(session, url, headers)


def _run_http_writes(
    platform_label: str,
    work: list[tuple[str, bytes, dict[str, str]]],
    headers_base: dict[str, str],
    collector: ResultCollector,
    cat: FileCategory,
    conc: int,
    it: int,
    is_warmup: bool,
    tag: str,
    latencies: dict[str, list[float]],
    failures: dict[str, int],
    auth_failures: dict[str, int],
    timeouts: dict[str, int],
) -> None:
    print(f"  [{tag}] {platform_label} concurrent write …", end=" ", flush=True)
    timings, wall_ms = asyncio.run(
        run_concurrent_workload(
            work,
            _put_worker,
            conc,
            headers_base,
            timeout_seconds=CFG.workload_timeout_seconds,
            request_timeout_seconds=CFG.request_timeout_seconds,
            connector_limit=CFG.http_connector_limit,
            limit_per_host=CFG.http_limit_per_host,
        )
    )
    agg_tp = sum(len(w[1]) for w in work) / (wall_ms / 1000) / 1e6 if wall_ms > 0 else 0
    _record_timings(
        platform_label,
        "write",
        timings,
        cat,
        it,
        is_warmup,
        conc,
        agg_tp,
        wall_ms,
        collector,
        latencies,
        failures,
        auth_failures,
        timeouts,
    )


def _run_http_reads(
    platform_label: str,
    work: list[tuple[str, dict[str, str]]],
    headers_base: dict[str, str],
    collector: ResultCollector,
    cat: FileCategory,
    conc: int,
    it: int,
    is_warmup: bool,
    tag: str,
    latencies: dict[str, list[float]],
    failures: dict[str, int],
    auth_failures: dict[str, int],
    timeouts: dict[str, int],
) -> None:
    print(f"  [{tag}] {platform_label} concurrent read …", end=" ", flush=True)
    timings, wall_ms = asyncio.run(
        run_concurrent_workload(
            work,
            _get_worker,
            conc,
            headers_base,
            timeout_seconds=CFG.workload_timeout_seconds,
            request_timeout_seconds=CFG.request_timeout_seconds,
            connector_limit=CFG.http_connector_limit,
            limit_per_host=CFG.http_limit_per_host,
        )
    )
    agg_tp = cat.size_bytes * cat.count / (wall_ms / 1000) / 1e6 if wall_ms > 0 else 0
    _record_timings(
        platform_label,
        "read",
        timings,
        cat,
        it,
        is_warmup,
        conc,
        agg_tp,
        wall_ms,
        collector,
        latencies,
        failures,
        auth_failures,
        timeouts,
    )


def _record_timings(
    platform_label: str,
    operation: str,
    timings: list[RequestTiming],
    cat: FileCategory,
    it: int,
    is_warmup: bool,
    conc: int,
    agg_tp: float,
    wall_ms: float,
    collector: ResultCollector,
    latencies: dict[str, list[float]],
    failures: dict[str, int],
    auth_failures: dict[str, int],
    timeouts: dict[str, int],
) -> None:
    failed = sum(1 for t in timings if not t.success)
    auth_fail = sum(1 for t in timings if _is_auth_failure(t))
    timed_out = sum(1 for t in timings if _is_timeout_failure(t))
    failures[platform_label] += failed
    auth_failures[platform_label] += auth_fail
    timeouts[platform_label] += timed_out
    for t in timings:
        collector.add(
            _make_result(
                platform_label,
                operation,
                cat,
                it,
                t.latency_ms,
                is_warmup,
                concurrency=conc,
                throughput_mbps=agg_tp,
                success=t.success,
                status_code=t.status_code,
                auth_failure=_is_auth_failure(t),
                timeout_failure=_is_timeout_failure(t),
            )
        )
        if not is_warmup and t.success:
            latencies[platform_label].append(t.latency_ms)
    rps = (len(timings) * 1000 / wall_ms) if wall_ms > 0 else 0.0
    extra = (
        f", failures={failed}, auth={auth_fail}, timeouts={timed_out}"
        if failed > 0
        else ""
    )
    print(f"done ({wall_ms:.0f} ms wall, {rps:.1f} req/s{extra}).")
    if failed > 0:
        _print_failure_reasons(timings)


# ---------------------------------------------------------------------------
# Per-platform write/read builders
# ---------------------------------------------------------------------------


def _run_kvasir_writes(
    platform_label: str,
    client: KvasirClient,
    collector: ResultCollector,
    file_data: dict[str, list[bytes]],
    cat: FileCategory,
    conc: int,
    it: int,
    is_warmup: bool,
    tag: str,
    latencies: dict[str, list[float]],
    failures: dict[str, int],
    auth_failures: dict[str, int],
    timeouts: dict[str, int],
) -> None:
    client.ensure_token_fresh(min_remaining_seconds=120)
    headers_base = client.auth_headers_dict()
    work = [
        (
            f"{client.pod_uri}/s3/{_conc_file_key(cat, idx, conc, it)}",
            file_data[cat.name][idx],
            {
                **headers_base,
                "Content-Type": cat.content_type,
                "x-amz-content-sha256": hashlib.sha256(
                    file_data[cat.name][idx]
                ).hexdigest(),
            },
        )
        for idx in range(cat.count)
    ]
    _run_http_writes(
        platform_label,
        work,
        headers_base,
        collector,
        cat,
        conc,
        it,
        is_warmup,
        tag,
        latencies,
        failures,
        auth_failures,
        timeouts,
    )


def _run_kvasir_reads(
    platform_label: str,
    client: KvasirClient,
    collector: ResultCollector,
    cat: FileCategory,
    conc: int,
    read_iter_source: int,
    it: int,
    is_warmup: bool,
    tag: str,
    latencies: dict[str, list[float]],
    failures: dict[str, int],
    auth_failures: dict[str, int],
    timeouts: dict[str, int],
) -> None:
    client.ensure_token_fresh(min_remaining_seconds=120)
    headers_base = client.auth_headers_dict()
    work = [
        (
            f"{client.pod_uri}/s3/{_conc_file_key(cat, idx, conc, read_iter_source)}",
            headers_base,
        )
        for idx in range(cat.count)
    ]
    _run_http_reads(
        platform_label,
        work,
        headers_base,
        collector,
        cat,
        conc,
        it,
        is_warmup,
        tag,
        latencies,
        failures,
        auth_failures,
        timeouts,
    )


def _run_css_writes(
    collector: ResultCollector,
    file_data: dict[str, list[bytes]],
    cat: FileCategory,
    conc: int,
    it: int,
    is_warmup: bool,
    tag: str,
    latencies: dict[str, list[float]],
    failures: dict[str, int],
    auth_failures: dict[str, int],
    timeouts: dict[str, int],
) -> None:
    css_headers = css_client.auth_headers_dict()
    work = [
        (
            f"{css_client.pod_uri}/{_conc_file_key(cat, idx, conc, it)}",
            file_data[cat.name][idx],
            {**css_headers, "Content-Type": cat.content_type},
        )
        for idx in range(cat.count)
    ]
    _run_http_writes(
        "css",
        work,
        css_headers,
        collector,
        cat,
        conc,
        it,
        is_warmup,
        tag,
        latencies,
        failures,
        auth_failures,
        timeouts,
    )


def _run_css_reads(
    collector: ResultCollector,
    cat: FileCategory,
    conc: int,
    read_iter_source: int,
    it: int,
    is_warmup: bool,
    tag: str,
    latencies: dict[str, list[float]],
    failures: dict[str, int],
    auth_failures: dict[str, int],
    timeouts: dict[str, int],
) -> None:
    css_headers = css_client.auth_headers_dict()
    work = [
        (
            f"{css_client.pod_uri}/{_conc_file_key(cat, idx, conc, read_iter_source)}",
            css_headers,
        )
        for idx in range(cat.count)
    ]
    _run_http_reads(
        "css",
        work,
        css_headers,
        collector,
        cat,
        conc,
        it,
        is_warmup,
        tag,
        latencies,
        failures,
        auth_failures,
        timeouts,
    )


# ---------------------------------------------------------------------------
# Concurrent benchmarks
# ---------------------------------------------------------------------------


def _all_platforms() -> list[str]:
    return [
        p for p in ["css", "kvasir", "kvasir-candidate", "s3"] if p in CFG.platforms
    ]


def _run_concurrent_writes(
    collector: ResultCollector,
    file_data: dict[str, list[bytes]],
) -> None:
    total_iterations = CFG.warmup_iterations + CFG.iterations

    for cat in FILE_CATEGORIES:
        for conc in CFG.concurrency_levels:
            print(f"\n{'─' * 60}")
            print(f"Concurrent WRITE — {cat.name} × {conc} clients")
            print(f"{'─' * 60}")

            active = _all_platforms()
            latencies: dict[str, list[float]] = {p: [] for p in active}
            failures: dict[str, int] = {p: 0 for p in active}
            auth_failures: dict[str, int] = {p: 0 for p in active}
            timeouts: dict[str, int] = {p: 0 for p in active}

            for it in range(total_iterations):
                is_warmup = it < CFG.warmup_iterations
                tag = (
                    "warmup" if is_warmup else f"iter {it - CFG.warmup_iterations + 1}"
                )

                if CFG.run_css:
                    _run_css_writes(
                        collector,
                        file_data,
                        cat,
                        conc,
                        it,
                        is_warmup,
                        tag,
                        latencies,
                        failures,
                        auth_failures,
                        timeouts,
                    )

                if CFG.run_kvasir:
                    _run_kvasir_writes(
                        "kvasir",
                        kvasir_client,
                        collector,
                        file_data,
                        cat,
                        conc,
                        it,
                        is_warmup,
                        tag,
                        latencies,
                        failures,
                        auth_failures,
                        timeouts,
                    )

                if CFG.run_kvasir_candidate:
                    _run_kvasir_writes(
                        "kvasir-candidate",
                        kvasir_client_cand,
                        collector,
                        file_data,
                        cat,
                        conc,
                        it,
                        is_warmup,
                        tag,
                        latencies,
                        failures,
                        auth_failures,
                        timeouts,
                    )

                if CFG.run_s3:
                    print(f"  [{tag}] S3 concurrent write …", end=" ", flush=True)
                    s3_work = [
                        (
                            _conc_file_key(cat, idx, conc, it),
                            file_data[cat.name][idx],
                            cat.content_type,
                        )
                        for idx in range(cat.count)
                    ]

                    def s3_put_worker_sync(
                        item: tuple[str, bytes, str],
                    ) -> RequestTiming:
                        k, d, ct = item
                        return s3_client.upload_file_timed(k, d, ct)

                    timings, wall_ms = asyncio.run(
                        run_s3_concurrent_workload(s3_work, s3_put_worker_sync, conc)
                    )
                    agg_tp = (
                        sum(len(w[1]) for w in s3_work) / (wall_ms / 1000) / 1e6
                        if wall_ms > 0
                        else 0
                    )
                    _record_timings(
                        "s3",
                        "write",
                        timings,
                        cat,
                        it,
                        is_warmup,
                        conc,
                        agg_tp,
                        wall_ms,
                        collector,
                        latencies,
                        failures,
                        auth_failures,
                        timeouts,
                    )

            print_comparison_table(
                f"Concurrent WRITE — {cat.name} × {conc} clients",
                {p: latencies[p] for p in active},
                platform_failures={p: failures[p] for p in active},
                platform_auth_failures={p: auth_failures[p] for p in active},
                platform_timeouts={p: timeouts[p] for p in active},
            )


def _run_concurrent_reads(
    collector: ResultCollector,
    file_data: dict[str, list[bytes]],
) -> None:
    total_iterations = CFG.warmup_iterations + CFG.iterations
    read_iter_source = 0

    for cat in FILE_CATEGORIES:
        for conc in CFG.concurrency_levels:
            print(f"\n{'─' * 60}")
            print(f"Concurrent READ — {cat.name} × {conc} clients")
            print(f"{'─' * 60}")

            _ensure_concurrent_read_files(cat, conc, file_data)

            active = _all_platforms()
            latencies: dict[str, list[float]] = {p: [] for p in active}
            failures: dict[str, int] = {p: 0 for p in active}
            auth_failures: dict[str, int] = {p: 0 for p in active}
            timeouts: dict[str, int] = {p: 0 for p in active}

            for it in range(total_iterations):
                is_warmup = it < CFG.warmup_iterations
                tag = (
                    "warmup" if is_warmup else f"iter {it - CFG.warmup_iterations + 1}"
                )

                if CFG.run_css:
                    _run_css_reads(
                        collector,
                        cat,
                        conc,
                        read_iter_source,
                        it,
                        is_warmup,
                        tag,
                        latencies,
                        failures,
                        auth_failures,
                        timeouts,
                    )

                if CFG.run_kvasir:
                    _run_kvasir_reads(
                        "kvasir",
                        kvasir_client,
                        collector,
                        cat,
                        conc,
                        read_iter_source,
                        it,
                        is_warmup,
                        tag,
                        latencies,
                        failures,
                        auth_failures,
                        timeouts,
                    )

                if CFG.run_kvasir_candidate:
                    _run_kvasir_reads(
                        "kvasir-candidate",
                        kvasir_client_cand,
                        collector,
                        cat,
                        conc,
                        read_iter_source,
                        it,
                        is_warmup,
                        tag,
                        latencies,
                        failures,
                        auth_failures,
                        timeouts,
                    )

                if CFG.run_s3:
                    print(f"  [{tag}] S3 concurrent read …", end=" ", flush=True)
                    s3_read_work = [
                        _conc_file_key(cat, idx, conc, read_iter_source)
                        for idx in range(cat.count)
                    ]

                    def s3_get_worker_sync(key: str) -> RequestTiming:
                        return s3_client.download_file_timed(key)

                    timings, wall_ms = asyncio.run(
                        run_s3_concurrent_workload(
                            s3_read_work, s3_get_worker_sync, conc
                        )
                    )
                    agg_tp = (
                        cat.size_bytes * cat.count / (wall_ms / 1000) / 1e6
                        if wall_ms > 0
                        else 0
                    )
                    _record_timings(
                        "s3",
                        "read",
                        timings,
                        cat,
                        it,
                        is_warmup,
                        conc,
                        agg_tp,
                        wall_ms,
                        collector,
                        latencies,
                        failures,
                        auth_failures,
                        timeouts,
                    )

            print_comparison_table(
                f"Concurrent READ — {cat.name} × {conc} clients",
                {p: latencies[p] for p in active},
                platform_failures={p: failures[p] for p in active},
                platform_auth_failures={p: auth_failures[p] for p in active},
                platform_timeouts={p: timeouts[p] for p in active},
            )


def _ensure_concurrent_read_files(
    cat: FileCategory,
    conc: int,
    file_data: dict[str, list[bytes]],
) -> None:
    path = _conc_file_key(cat, 0, conc, 0)

    print(
        f"  [read-prep] checking seed objects for {cat.name}, conc={conc} ...",
        end=" ",
        flush=True,
    )
    t0 = time.perf_counter()

    try:
        if CFG.run_kvasir:
            if kvasir_client.file_exists(path, timeout=10):
                print(f"exists ({(time.perf_counter() - t0) * 1000:.0f} ms).")
                return
        elif CFG.run_kvasir_candidate:
            if kvasir_client_cand.file_exists(path, timeout=10):
                print(f"exists ({(time.perf_counter() - t0) * 1000:.0f} ms).")
                return
        elif CFG.run_s3:
            if s3_client.file_exists(path):
                print(f"exists ({(time.perf_counter() - t0) * 1000:.0f} ms).")
                return
        elif CFG.run_css:
            css_client.download_file(path)
            print(f"exists ({(time.perf_counter() - t0) * 1000:.0f} ms).")
            return
    except Exception as err:
        print(
            f"[WARN] seed check failed after {(time.perf_counter() - t0) * 1000:.0f} ms: {err}"
        )

    print(
        f"  Uploading {cat.count} seed files for concurrent read ({cat.name}, conc={conc}) ..."
    )
    for idx in range(cat.count):
        p = _conc_file_key(cat, idx, conc, 0)
        if CFG.run_css:
            css_client.upload_file(p, file_data[cat.name][idx], cat.content_type)
        if CFG.run_kvasir:
            kvasir_client.upload_file(p, file_data[cat.name][idx], cat.content_type)
        if CFG.run_kvasir_candidate:
            kvasir_client_cand.upload_file(
                p, file_data[cat.name][idx], cat.content_type
            )
        if CFG.run_s3:
            s3_client.upload_file(p, file_data[cat.name][idx], cat.content_type)


# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------


def _cleanup(file_data: dict[str, list[bytes]]) -> None:
    print(f"\n{'═' * 60}")
    print("Cleanup — deleting benchmark files")
    print(f"{'═' * 60}")

    total_iterations = CFG.warmup_iterations + CFG.iterations
    cleanup_errors = 0

    for cat in FILE_CATEGORIES:
        print(f"  Cleaning {cat.name} …", end=" ", flush=True)
        for conc in CFG.concurrency_levels:
            for it in range(total_iterations):
                for idx in range(cat.count):
                    path = _conc_file_key(cat, idx, conc, it)
                    for run_flag, delete_fn in [
                        (CFG.run_css, lambda p: css_client.delete_file(p)),
                        (CFG.run_kvasir, lambda p: kvasir_client.delete_file(p)),
                        (
                            CFG.run_kvasir_candidate,
                            lambda p: kvasir_client_cand.delete_file(p),
                        ),
                        (CFG.run_s3, lambda p: s3_client.delete_file(p)),
                    ]:
                        if run_flag:
                            try:
                                delete_fn(path)
                            except Exception as e:
                                cleanup_errors += 1
                                if cleanup_errors <= 5:
                                    print(
                                        f"\n    [WARN] cleanup failed for {path}: {e}"
                                    )
        print("done.")

    if cleanup_errors > 0:
        print(f"  [{cleanup_errors} cleanup error(s) total]")


# ---------------------------------------------------------------------------
# Main entry point
# ---------------------------------------------------------------------------


def run_scenario_1(collector: ResultCollector, cleanup: bool = False) -> None:
    print("╔══════════════════════════════════════════════════════════╗")
    print("║  Scenario 1: File I/O                                    ║")
    print("╚══════════════════════════════════════════════════════════╝")
    print()
    print(f"  Iterations:    {CFG.iterations} (+ {CFG.warmup_iterations} warmup)")
    print(f"  Concurrency:   {CFG.concurrency_levels}")
    print(f"  Platforms:     {', '.join(CFG.platforms)}")
    print(f"  File categories: {len(FILE_CATEGORIES)}")
    print(
        "  Async HTTP: "
        f"request_timeout={CFG.request_timeout_seconds}s, "
        f"workload_timeout={CFG.workload_timeout_seconds}s, "
        f"connector_limit={CFG.http_connector_limit}, "
        f"limit_per_host={CFG.http_limit_per_host}"
    )
    print()

    print("Setting up platforms …")
    if CFG.run_css:
        css_client.ensure_pod_exists()
    if CFG.run_kvasir:
        kvasir_client.ensure_pod_exists()
    if CFG.run_kvasir_candidate:
        kvasir_client_cand.ensure_pod_exists()
    if CFG.run_s3:
        s3_client.ensure_bucket_exists()

    print("\nPre-generating test data …")
    file_data = _pregenerate_data()
    print()

    try:
        print("\n╔══════════════════════════════════════════════════════════╗")
        print("║  Phase 1: Writes                                        ║")
        print("╚══════════════════════════════════════════════════════════╝")
        _run_concurrent_writes(collector, file_data)

        print("\n╔══════════════════════════════════════════════════════════╗")
        print("║  Phase 2: Reads                                         ║")
        print("╚══════════════════════════════════════════════════════════╝")
        _run_concurrent_reads(collector, file_data)
    finally:
        if cleanup:
            _cleanup(file_data)

    print("\n✓ Scenario 1 complete.")


if __name__ == "__main__":
    import argparse
    import os

    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--cleanup", action="store_true", help="Delete uploaded files after the run"
    )
    args = parser.parse_args()

    collector = ResultCollector()
    run_scenario_1(collector, cleanup=args.cleanup)

    os.makedirs(CFG.results_dir, exist_ok=True)
    csv_path = os.path.join(CFG.results_dir, "scenario_1_file_io.csv")
    collector.write_csv(csv_path)
    print(f"\nResults written to {csv_path}")
    print(
        f"Total measurements: {len(collector.results)} ({len(collector.non_warmup())} non-warmup)"
    )

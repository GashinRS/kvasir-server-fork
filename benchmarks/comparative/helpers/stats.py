"""
Statistics computation and result recording for benchmarks.
"""

from __future__ import annotations
import csv
import os
from dataclasses import dataclass, field


@dataclass
class BenchmarkResult:
    """A single benchmark measurement."""

    scenario: str  # "file_io" or "rdf_query"
    platform: str  # "kvasir" or "css"
    operation: str  # e.g. "write", "read", "targeted_single"
    file_size_category: str = ""  # e.g. "small_25kb"
    concurrency: int = 1
    dataset_size: int = 0
    iteration: int = 0
    latency_ms: float = 0.0
    throughput_mbps: float = 0.0
    records_returned: int = 0
    is_warmup: bool = False
    success: bool = True
    status_code: int = 0
    auth_failure: bool = False
    timeout_failure: bool = False


@dataclass
class ResultCollector:
    """Collects benchmark results and writes them to CSV."""

    results: list[BenchmarkResult] = field(default_factory=list)

    def add(self, result: BenchmarkResult) -> None:
        self.results.append(result)

    def non_warmup(self) -> list[BenchmarkResult]:
        return [r for r in self.results if not r.is_warmup]

    def write_csv(self, filepath: str) -> None:
        os.makedirs(os.path.dirname(filepath) or ".", exist_ok=True)
        rows = self.non_warmup()
        if not rows:
            return
        fieldnames = [
            "scenario",
            "platform",
            "operation",
            "file_size_category",
            "concurrency",
            "dataset_size",
            "iteration",
            "latency_ms",
            "throughput_mbps",
            "records_returned",
            "success",
            "status_code",
            "auth_failure",
            "timeout_failure",
        ]
        with open(filepath, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            for r in rows:
                writer.writerow(
                    {
                        "scenario": r.scenario,
                        "platform": r.platform,
                        "operation": r.operation,
                        "file_size_category": r.file_size_category,
                        "concurrency": r.concurrency,
                        "dataset_size": r.dataset_size,
                        "iteration": r.iteration,
                        "latency_ms": f"{r.latency_ms:.2f}",
                        "throughput_mbps": f"{r.throughput_mbps:.4f}"
                        if r.throughput_mbps
                        else "",
                        "records_returned": r.records_returned or "",
                        "success": r.success,
                        "status_code": r.status_code,
                        "auth_failure": r.auth_failure,
                        "timeout_failure": r.timeout_failure,
                    }
                )


def compute_stats(latencies: list[float]) -> dict[str, float]:
    """Compute min/avg/p50/p95/p99/max from a list of latencies (ms)."""
    if not latencies:
        return {}
    s = sorted(latencies)
    n = len(s)
    return {
        "min": s[0],
        "avg": sum(s) / n,
        "p50": s[n // 2],
        "p95": s[min(int(n * 0.95), n - 1)],
        "p99": s[min(int(n * 0.99), n - 1)],
        "max": s[-1],
    }


def compute_speedup(css_latency: float, kvasir_latency: float) -> float:
    """Compute speedup ratio: how much faster Kvasir is vs CSS.
    Returns percentage improvement. E.g. 30.0 means Kvasir is 30% faster.
    Returns NaN when either value is missing/zero (no meaningful comparison).
    """
    if css_latency <= 0 or kvasir_latency <= 0:
        return float("nan")
    return ((css_latency - kvasir_latency) / css_latency) * 100


def contention_penalty(latency_at_n: float, latency_at_1: float) -> float:
    """Compute contention penalty: ratio of latency at N clients vs 1 client."""
    if latency_at_1 <= 0:
        return float("inf")
    return latency_at_n / latency_at_1


def _fmt_speedup(sp: float) -> str:
    """Format a speedup value, handling NaN."""
    if sp != sp:  # NaN check
        return "N/A"
    return f"{sp:+.1f}%"


def print_comparison_table(
    title: str,
    platform_latencies: dict[str, list[float]],
    platform_failures: dict[str, int] | None = None,
    platform_auth_failures: dict[str, int] | None = None,
    platform_timeouts: dict[str, int] | None = None,
) -> None:
    """Print a formatted comparison table for any number of platforms."""
    platforms = [p for p in platform_latencies if platform_latencies[p]]
    if not platforms:
        return

    stats_by_platform = {p: compute_stats(platform_latencies[p]) for p in platforms}

    # Column widths
    col_w = 12
    bar_w = 14 + col_w * len(platforms) + (12 if len(platforms) == 2 else 0)
    bar = "═" * bar_w

    print()
    print(f"╔{bar}╗")
    print(f"║  {title}")
    print(f"╠{bar}╣")

    header = f"║  {'Metric':<12}"
    for p in platforms:
        header += f" {p.upper():>{col_w}}"
    if len(platforms) == 2:
        header += f" {'Speedup':>12}"
    print(header)
    print(f"╠{bar}╣")

    for key in ["min", "avg", "p50", "p95", "p99", "max"]:
        line = f"║  {key:<12}"
        vals = []
        for p in platforms:
            v = stats_by_platform[p].get(key, 0)
            vals.append(v)
            line += f" {v:>{col_w - 2}.1f}ms"
        if len(platforms) == 2:
            sp = compute_speedup(vals[0], vals[1])
            line += f" {_fmt_speedup(sp):>12}"
        print(line)

    # Failures
    pf = platform_failures or {}
    paf = platform_auth_failures or {}
    pt = platform_timeouts or {}
    has_failures = any(pf.get(p, 0) for p in platforms)
    if has_failures:
        print(f"╠{bar}╣")
        fail_line = "║  Failures    "
        auth_line = "║  Auth errors "
        to_line = "║  Timeouts    "
        for p in platforms:
            fail_line += f" {p.upper()}={pf.get(p, 0):<5}"
            auth_line += f" {p.upper()}={paf.get(p, 0):<5}"
            to_line += f" {p.upper()}={pt.get(p, 0):<5}"
        print(fail_line)
        print(auth_line)
        print(to_line)

    print(f"╠{bar}╣")
    if len(platforms) == 2:
        sp = compute_speedup(
            stats_by_platform[platforms[0]].get("avg", 0),
            stats_by_platform[platforms[1]].get("avg", 0),
        )
        print(f"║  Overall speedup (avg): {_fmt_speedup(sp)}")
    else:
        avgs = {p: stats_by_platform[p].get("avg", 0) for p in platforms}
        parts = [f"{p.upper()}={avgs[p]:.1f}ms" for p in platforms]
        print(f"║  Avg latencies: {', '.join(parts)}")
    print(f"╚{bar}╝")

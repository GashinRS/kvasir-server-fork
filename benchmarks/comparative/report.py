#!/usr/bin/env python3
"""
Report generator for the Kvasir comparative benchmark suite.

Reads one or more CSV result files and generates a self-contained HTML report.
Multiple CSVs are simply concatenated — no labelling needed, because each CSV
already carries the correct platform name in the 'platform' column.

Usage:
    python report.py                                         # default paths
    python report.py --csv results/results.csv              # single file
    python report.py --csv results-ab-baseline/results.csv  \\
                          results-ab/results.csv             \\
                     --output results-ab/report-ab.html      # A/B merge
"""
from __future__ import annotations

import argparse
import csv
import json
import os
from datetime import datetime, timezone
from statistics import mean


# ---------------------------------------------------------------------------
# Canonical platform colours — one fixed colour per platform name
# ---------------------------------------------------------------------------

PLATFORM_COLORS: dict[str, str] = {
    "css":               "#e74c3c",  # red
    "kvasir":            "#2ecc71",  # green
    "kvasir-candidate":  "#3498db",  # blue
    "s3":                "#f39c12",  # orange
}


def _platform_color(platform: str) -> str:
    return PLATFORM_COLORS.get(platform, "#9b59b6")


KVASIR_PLATFORM = "kvasir"

PLOTLY_CDN = "https://cdn.plot.ly/plotly-2.35.0.min.js"


# ---------------------------------------------------------------------------
# Data helpers
# ---------------------------------------------------------------------------

def _read_csvs(paths: list[str]) -> tuple[list[dict], str]:
    all_rows: list[dict] = []
    for path in paths:
        with open(path, newline="", encoding="utf-8") as f:
            all_rows.extend(csv.DictReader(f))
    source = ", ".join(os.path.basename(p) for p in paths)
    return all_rows, source


def _safe_float(val: str) -> float:
    try:
        return float(val)
    except (ValueError, TypeError):
        return 0.0


def _safe_int(val: str) -> int:
    try:
        return int(val)
    except (ValueError, TypeError):
        return 0


def _compute_stats(latencies: list[float]) -> dict[str, float]:
    if not latencies:
        return {"avg": 0, "min": 0, "max": 0, "p5": 0, "p50": 0, "p95": 0}
    s = sorted(latencies)
    n = len(s)
    return {
        "avg": mean(s),
        "min": s[0],
        "max": s[-1],
        "p5": s[max(int(n * 0.05), 0)],
        "p50": s[n // 2],
        "p95": s[min(int(n * 0.95), n - 1)],
    }


def _speedup_pct(reference_val: float, kvasir_val: float) -> float:
    """Positive = kvasir is better (lower latency)."""
    if reference_val <= 0:
        return 0.0
    return ((reference_val - kvasir_val) / reference_val) * 100.0


def _throughput_speedup_pct(reference_val: float, kvasir_val: float) -> float:
    """Positive = kvasir has higher throughput."""
    if reference_val <= 0:
        return 0.0
    return ((kvasir_val - reference_val) / reference_val) * 100.0


# ---------------------------------------------------------------------------
# HTML chart builders
# ---------------------------------------------------------------------------

_CHART_ID_COUNTER = 0


def _next_chart_id() -> str:
    global _CHART_ID_COUNTER
    _CHART_ID_COUNTER += 1
    return f"chart{_CHART_ID_COUNTER}"


def _plotly_bar(chart_id: str, title: str, categories: list[str],
                platform_vals: dict[str, list[float]],
                y_label: str,
                platform_error_minus: dict[str, list[float]] | None = None,
                platform_error_plus: dict[str, list[float]] | None = None,
                log_y: bool = False) -> str:
    traces = []
    for platform, vals in platform_vals.items():
        trace: dict = {
            "x": categories,
            "y": vals,
            "name": platform.upper(),
            "type": "bar",
            "marker": {"color": _platform_color(platform)},
        }
        if platform_error_minus and platform_error_plus:
            em = platform_error_minus.get(platform)
            ep = platform_error_plus.get(platform)
            if em and ep:
                trace["error_y"] = {
                    "type": "data", "symmetric": False,
                    "array": ep, "arrayminus": em,
                }
        traces.append(trace)

    layout: dict = {
        "title": title,
        "barmode": "group",
        "yaxis": {"title": y_label},
        "template": "plotly_white",
        "margin": {"t": 50, "b": 80},
    }
    if log_y:
        layout["yaxis"]["type"] = "log"

    return (
        f'<div class="chart"><div id="{chart_id}"></div></div>\n'
        f"<script>Plotly.newPlot('{chart_id}', "
        f"{json.dumps(traces)}, "
        f"{json.dumps(layout)}, {{responsive:true}});</script>\n"
    )


def _plotly_line(chart_id: str, title: str,
                 x_vals: list,
                 platform_y: dict[str, list[float]],
                 x_label: str, y_label: str) -> str:
    traces = []
    for platform, y_vals in platform_y.items():
        traces.append({
            "x": x_vals, "y": y_vals, "name": platform.upper(), "mode": "lines+markers",
            "line": {"color": _platform_color(platform), "width": 2},
            "marker": {"size": 6},
        })
    layout = {
        "title": title,
        "xaxis": {"title": x_label, "type": "category"},
        "yaxis": {"title": y_label},
        "template": "plotly_white",
        "margin": {"t": 50, "b": 80},
    }
    return (
        f'<div class="chart"><div id="{chart_id}"></div></div>\n'
        f"<script>Plotly.newPlot('{chart_id}', "
        f"{json.dumps(traces)}, "
        f"{json.dumps(layout)}, {{responsive:true}});</script>\n"
    )


def _plotly_speedup_bar(chart_id: str, title: str,
                        operations: list[str],
                        speedups: list[float],
                        y_label: str = "Improvement %") -> str:
    colors = [PLATFORM_COLORS["kvasir"] if s >= 0 else PLATFORM_COLORS["css"] for s in speedups]
    trace = {
        "x": operations,
        "y": speedups,
        "type": "bar",
        "marker": {"color": colors},
        "text": [f"{s:+.1f}%" for s in speedups],
        "textposition": "outside",
    }
    layout = {
        "title": title,
        "yaxis": {"title": y_label, "zeroline": True},
        "template": "plotly_white",
        "margin": {"t": 50, "b": 80},
        "showlegend": False,
    }
    return (
        f'<div class="chart"><div id="{chart_id}"></div></div>\n'
        f"<script>Plotly.newPlot('{chart_id}', "
        f"{json.dumps([trace])}, "
        f"{json.dumps(layout)}, {{responsive:true}});</script>\n"
    )


def _plotly_delta_bar(chart_id: str, title: str,
                      categories: list[str],
                      platform_pairs: list[tuple[str, str]],
                      op: str,
                      s1_rows: list[dict],
                      y_label: str = "Latency improvement % (positive = faster)") -> str:
    """
    Bar chart showing the latency delta between pairs of platforms across file
    categories.  Each pair (ref, cand) produces one trace.  The y-value for
    each category is (ref_avg - cand_avg) / ref_avg * 100.
    """
    traces = []
    for ref, cand in platform_pairs:
        vals = []
        for cat in categories:
            ref_lats = [
                _safe_float(r["latency_ms"]) for r in s1_rows
                if r["file_size_category"] == cat and r["platform"] == ref
                and r["operation"] == op and _safe_float(r["latency_ms"]) > 0
            ]
            cand_lats = [
                _safe_float(r["latency_ms"]) for r in s1_rows
                if r["file_size_category"] == cat and r["platform"] == cand
                and r["operation"] == op and _safe_float(r["latency_ms"]) > 0
            ]
            ref_avg  = mean(ref_lats)  if ref_lats  else 0.0
            cand_avg = mean(cand_lats) if cand_lats else 0.0
            vals.append(round(_speedup_pct(ref_avg, cand_avg), 1) if ref_avg > 0 else 0.0)

        color = _platform_color(cand)
        traces.append({
            "x": categories,
            "y": vals,
            "name": f"{cand.upper()} vs {ref.upper()}",
            "type": "bar",
            "marker": {"color": color},
            "text": [f"{v:+.1f}%" for v in vals],
            "textposition": "outside",
        })

    layout = {
        "title": title,
        "barmode": "group",
        "yaxis": {"title": y_label, "zeroline": True},
        "template": "plotly_white",
        "margin": {"t": 50, "b": 100},
    }
    return (
        f'<div class="chart"><div id="{chart_id}"></div></div>\n'
        f"<script>Plotly.newPlot('{chart_id}', "
        f"{json.dumps(traces)}, "
        f"{json.dumps(layout)}, {{responsive:true}});</script>\n"
    )


# ---------------------------------------------------------------------------
# Scenario 1 helpers
# ---------------------------------------------------------------------------

def _s1_categories(rows: list[dict]) -> list[str]:
    cats = []
    seen = set()
    for r in rows:
        if r["scenario"] == "file_io":
            c = r["file_size_category"]
            if c and c not in seen:
                cats.append(c)
                seen.add(c)
    return cats


def _s1_platforms(rows: list[dict]) -> list[str]:
    platforms = []
    seen = set()
    for r in rows:
        if r["scenario"] == "file_io":
            p = r["platform"]
            if p not in seen:
                platforms.append(p)
                seen.add(p)
    return platforms


def _s1_conc_levels(rows: list[dict]) -> list[int]:
    return sorted(set(_safe_int(r["concurrency"]) for r in rows
                      if r["scenario"] == "file_io" and _safe_int(r["concurrency"]) >= 1))


def _avg_metric_by_conc(rows: list[dict], cat: str, platform: str, op: str,
                         metric: str, conc_levels: list[int]) -> list[float]:
    result = []
    for cl in conc_levels:
        vals = [
            _safe_float(r[metric]) for r in rows
            if r["file_size_category"] == cat
            and r["platform"] == platform
            and r["operation"] == op
            and _safe_int(r["concurrency"]) == cl
            and _safe_float(r[metric]) > 0
        ]
        result.append(round(mean(vals), 4) if vals else 0.0)
    return result


def _avg_metric_across_cats(rows: list[dict], categories: list[str], platform: str,
                              op: str, metric: str, conc_levels: list[int]) -> list[float]:
    result = []
    for cl in conc_levels:
        vals = [
            _safe_float(r[metric]) for r in rows
            if r["file_size_category"] in categories
            and r["platform"] == platform
            and r["operation"] == op
            and _safe_int(r["concurrency"]) == cl
            and _safe_float(r[metric]) > 0
        ]
        result.append(round(mean(vals), 4) if vals else 0.0)
    return result


def _infer_comparison_pairs(platforms: list[str]) -> list[tuple[str, str]]:
    if "kvasir" in platforms and "kvasir-candidate" in platforms:
        return [("kvasir", "kvasir-candidate")]
    return []


# ---------------------------------------------------------------------------
# Build scenario sections
# ---------------------------------------------------------------------------

def _build_scenario_1(rows: list[dict]) -> str:
    s1_rows = [r for r in rows if r["scenario"] == "file_io"]
    if not s1_rows:
        return ""

    html = "<h2>Scenario 1: File I/O</h2>\n"
    categories = _s1_categories(rows)
    platforms = _s1_platforms(rows)
    conc_levels = _s1_conc_levels(s1_rows)
    conc_labels = [str(c) for c in conc_levels]

    ref_platforms = [p for p in platforms if p != KVASIR_PLATFORM]
    comparison_pairs = _infer_comparison_pairs(platforms)

    # --- Per-category concurrency scaling charts ---
    if len(conc_levels) > 1:
        for cat in categories:
            html += f'<h3 class="cat-header">File Category: {cat}</h3>\n'

            for op, op_label in [("write", "Write"), ("read", "Read")]:
                platform_lat = {
                    p: _avg_metric_by_conc(s1_rows, cat, p, op, "latency_ms", conc_levels)
                    for p in platforms
                }
                if any(v > 0 for vals in platform_lat.values() for v in vals):
                    html += _plotly_line(
                        _next_chart_id(),
                        f"Concurrency Scaling — {op_label} Latency ({cat})",
                        conc_labels, platform_lat,
                        "Concurrency", "Avg Latency (ms)",
                    )

                platform_tp = {
                    p: _avg_metric_by_conc(s1_rows, cat, p, op, "throughput_mbps", conc_levels)
                    for p in platforms
                }
                if any(v > 0 for vals in platform_tp.values() for v in vals):
                    html += _plotly_line(
                        _next_chart_id(),
                        f"Concurrency Scaling — {op_label} Throughput ({cat})",
                        conc_labels, platform_tp,
                        "Concurrency", "Aggregate MB/s",
                    )

            if KVASIR_PLATFORM in platforms and ref_platforms:
                for ref in ref_platforms:
                    cat_ops, cat_speedups = [], []
                    for op, op_label in [("write", "Write"), ("read", "Read")]:
                        for metric, metric_label, tp_mode in [
                            ("latency_ms", "Latency", False),
                            ("throughput_mbps", "Throughput", True),
                        ]:
                            kv_vals = [
                                _safe_float(r[metric]) for r in s1_rows
                                if r["file_size_category"] == cat
                                and r["platform"] == KVASIR_PLATFORM
                                and r["operation"] == op
                                and _safe_float(r[metric]) > 0
                            ]
                            ref_vals = [
                                _safe_float(r[metric]) for r in s1_rows
                                if r["file_size_category"] == cat
                                and r["platform"] == ref
                                and r["operation"] == op
                                and _safe_float(r[metric]) > 0
                            ]
                            kv_avg  = mean(kv_vals)  if kv_vals  else 0.0
                            ref_avg = mean(ref_vals) if ref_vals else 0.0
                            spd = round(
                                _throughput_speedup_pct(ref_avg, kv_avg) if tp_mode
                                else _speedup_pct(ref_avg, kv_avg), 1,
                            )
                            cat_ops.append(f"{op_label} {metric_label}")
                            cat_speedups.append(spd)

                    html += _plotly_speedup_bar(
                        _next_chart_id(),
                        f"Kvasir vs {ref.upper()} — Improvement % (avg all concurrency levels, {cat})",
                        cat_ops, cat_speedups,
                        y_label="Improvement % (positive = Kvasir better)",
                    )

    # --- A/B delta charts: candidate improvement over baseline per category ---
    if comparison_pairs:
        html += '<h3 class="cat-header">A/B Comparison — Latency delta per file category</h3>\n'
        html += '<p class="section-note">Positive = candidate is faster than baseline.</p>\n'
        for op, op_label in [("write", "Write"), ("read", "Read")]:
            html += _plotly_delta_bar(
                _next_chart_id(),
                f"{op_label} latency improvement — candidate vs baseline (avg all concurrency levels)",
                categories,
                comparison_pairs,
                op,
                s1_rows,
            )

    # --- Aggregate summary ---
    html += '<h3 class="cat-header">Aggregate Summary — All File Sizes</h3>\n'
    html += '<p class="section-note">All metrics averaged across all file size categories. ' \
            'Positive improvement = Kvasir is faster / has higher throughput.</p>\n'

    for op, op_label in [("write", "Write"), ("read", "Read")]:
        for metric, y_label, is_tp in [
            ("latency_ms", "Avg Latency (ms)", False),
            ("throughput_mbps", "Aggregate MB/s", True),
        ]:
            platform_y = {
                p: _avg_metric_across_cats(s1_rows, categories, p, op, metric, conc_levels)
                for p in platforms
            }
            if any(v > 0 for vals in platform_y.values() for v in vals):
                html += _plotly_line(
                    _next_chart_id(),
                    f"Aggregate {op_label} {'Throughput' if is_tp else 'Latency'} (avg all categories)",
                    conc_labels, platform_y,
                    "Concurrency", y_label,
                )

    if KVASIR_PLATFORM in platforms and ref_platforms:
        agg_ops, agg_speedups = [], []
        for ref in ref_platforms:
            for op, op_label in [("write", "Write"), ("read", "Read")]:
                for metric, metric_label, tp_mode in [
                    ("latency_ms", "Latency", False),
                    ("throughput_mbps", "Throughput", True),
                ]:
                    kv_vals = [
                        _safe_float(r[metric]) for r in s1_rows
                        if r["platform"] == KVASIR_PLATFORM and r["operation"] == op
                        and _safe_float(r[metric]) > 0
                    ]
                    ref_vals = [
                        _safe_float(r[metric]) for r in s1_rows
                        if r["platform"] == ref and r["operation"] == op
                        and _safe_float(r[metric]) > 0
                    ]
                    kv_avg  = mean(kv_vals)  if kv_vals  else 0.0
                    ref_avg = mean(ref_vals) if ref_vals else 0.0
                    spd = round(
                        _throughput_speedup_pct(ref_avg, kv_avg) if tp_mode
                        else _speedup_pct(ref_avg, kv_avg), 1,
                    )
                    label = (
                        f"{op_label} {metric_label}"
                        if len(ref_platforms) == 1
                        else f"{op_label} {metric_label}\nvs {ref.upper()}"
                    )
                    agg_ops.append(label)
                    agg_speedups.append(spd)

        if agg_ops:
            ref_label = " / ".join(r.upper() for r in ref_platforms)
            html += _plotly_speedup_bar(
                _next_chart_id(),
                f"Kvasir vs {ref_label} — Overall Improvement % (avg all categories & concurrency levels)",
                agg_ops, agg_speedups,
                y_label="Improvement % (positive = Kvasir better)",
            )

    return html


def _build_scenario_2(rows: list[dict]) -> str:
    s2_rows = [r for r in rows if r["scenario"] == "rdf_query"]
    if not s2_rows:
        return ""

    html = "<h2>Scenario 2: RDF Query</h2>\n"

    operations: list[str] = []
    seen: set[str] = set()
    for r in s2_rows:
        op = r["operation"]
        if op not in seen:
            operations.append(op)
            seen.add(op)

    platforms: list[str] = []
    seen_plat: set[str] = set()
    for r in s2_rows:
        p = r["platform"]
        if p not in seen_plat:
            platforms.append(p)
            seen_plat.add(p)

    platform_avgs: dict[str, list[float]] = {p: [] for p in platforms}
    platform_err_minus: dict[str, list[float]] = {p: [] for p in platforms}
    platform_err_plus: dict[str, list[float]] = {p: [] for p in platforms}

    for op in operations:
        for platform in platforms:
            matching = [
                _safe_float(r["latency_ms"]) for r in s2_rows
                if r["operation"] == op and r["platform"] == platform
            ]
            stats = _compute_stats(matching)
            platform_avgs[platform].append(round(stats["avg"], 2))
            platform_err_minus[platform].append(round(max(stats["avg"] - stats["p5"], 0), 2))
            platform_err_plus[platform].append(round(max(stats["p95"] - stats["avg"], 0), 2))

    all_avgs = [v for vals in platform_avgs.values() for v in vals if v > 0]
    use_log = (max(all_avgs) / min(all_avgs)) > 10 if len(all_avgs) >= 2 and min(all_avgs) > 0 else False

    html += _plotly_bar(
        _next_chart_id(), "Query Latency Comparison", operations,
        platform_avgs, "Avg Latency (ms)",
        platform_err_minus, platform_err_plus,
        log_y=use_log,
    )

    ref_platforms = [p for p in platforms if p != KVASIR_PLATFORM]
    if KVASIR_PLATFORM in platforms and ref_platforms:
        kv_avgs = platform_avgs[KVASIR_PLATFORM]
        for ref in ref_platforms:
            speedups = [
                round(_speedup_pct(platform_avgs[ref][i], kv_avgs[i]), 1)
                for i in range(len(operations))
            ]
            html += _plotly_speedup_bar(
                _next_chart_id(),
                f"Speedup — KVASIR vs {ref.upper()} (% improvement in latency)",
                operations, speedups,
            )

    return html


# ---------------------------------------------------------------------------
# Summary table
# ---------------------------------------------------------------------------

def _build_summary(rows: list[dict]) -> str:
    html = "<h2>Summary</h2>\n"

    all_platforms: list[str] = []
    seen_plat: set[str] = set()
    for r in rows:
        p = r["platform"]
        if p not in seen_plat:
            all_platforms.append(p)
            seen_plat.add(p)

    summary_rows: list[dict] = []

    for scenario in ["file_io", "rdf_query"]:
        s_rows = [r for r in rows if r["scenario"] == scenario]
        if not s_rows:
            continue

        operations: list[tuple[str, str]] = []
        seen: set[tuple[str, str]] = set()
        for r in s_rows:
            op  = r["operation"]
            cat = r.get("file_size_category", "")
            key = (op, cat)
            if key not in seen:
                operations.append(key)
                seen.add(key)

        for op, cat in operations:
            platform_avgs: dict[str, float] = {}
            for platform in all_platforms:
                lats = [
                    _safe_float(r["latency_ms"]) for r in s_rows
                    if r["operation"] == op
                    and r.get("file_size_category", "") == cat
                    and r["platform"] == platform
                    and _safe_int(r.get("concurrency", "1")) <= 1
                ]
                if lats:
                    platform_avgs[platform] = mean(lats)

            if len(platform_avgs) < 2:
                continue

            label = f"{op} ({cat})" if cat else op
            summary_rows.append({
                "scenario": scenario,
                "operation": label,
                "platform_avgs": platform_avgs,
            })

    html += '<table class="summary-table">\n'
    html += "<thead><tr>"
    html += "<th>Scenario</th><th>Operation</th>"
    for p in all_platforms:
        html += f"<th>{p.upper()} Avg (ms)</th>"
    html += "</tr></thead>\n<tbody>\n"

    for sr in summary_rows:
        html += "<tr>"
        html += f"<td>{sr['scenario']}</td>"
        html += f"<td>{sr['operation']}</td>"
        for p in all_platforms:
            avg = sr["platform_avgs"].get(p)
            html += f"<td>{avg:.1f}</td>" if avg is not None else "<td>—</td>"
        html += "</tr>\n"

    html += "</tbody></table>\n"
    return html


# ---------------------------------------------------------------------------
# Main generator
# ---------------------------------------------------------------------------

HTML_TEMPLATE = """\
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Comparative Benchmark Report</title>
    <script src="{plotly_cdn}"></script>
    <style>
        body {{
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            margin: 0; padding: 20px; background: #f5f5f5; color: #1a1a2e;
        }}
        .container {{ max-width: 1200px; margin: 0 auto; }}
        h1 {{ color: #1a1a2e; margin-bottom: 4px; }}
        h2 {{ color: #16213e; border-bottom: 2px solid #0f3460; padding-bottom: 8px; margin-top: 40px; }}
        h3.cat-header {{
            color: #0f3460; background: #eef2ff; padding: 8px 16px;
            border-left: 4px solid #0f3460; margin: 32px 0 8px 0;
            border-radius: 0 4px 4px 0;
        }}
        .timestamp {{ color: #666; font-size: 0.9em; margin-bottom: 20px; }}
        .section-note {{ color: #555; font-size: 0.9em; margin: 0 0 12px 0; font-style: italic; }}
        .chart {{
            background: white; border-radius: 8px; padding: 20px; margin: 20px 0;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }}
        .summary-table {{ width: 100%; border-collapse: collapse; background: white;
            border-radius: 8px; overflow: hidden;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }}
        .summary-table th, .summary-table td {{
            padding: 12px 16px; text-align: left; border-bottom: 1px solid #ddd;
        }}
        .summary-table th {{ background: #1a1a2e; color: white; }}
        .summary-table tr:hover {{ background: #f0f0f5; }}
        .config {{ background: white; padding: 16px 20px; border-radius: 8px;
            margin: 16px 0; box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            font-size: 0.9em;
        }}
        .config code {{ background: #eee; padding: 2px 6px; border-radius: 3px; }}
    </style>
</head>
<body>
<div class="container">
    <h1>Comparative Benchmark Report</h1>
    <p class="timestamp">Generated: {timestamp}</p>
    {config_section}
    {body}
</div>
</body>
</html>
"""


def generate_report(csv_paths: list[str] | str, output_path: str) -> None:
    """Generate HTML report from one or more CSV files."""
    global _CHART_ID_COUNTER
    _CHART_ID_COUNTER = 0

    paths = [csv_paths] if isinstance(csv_paths, str) else csv_paths
    rows, source_desc = _read_csvs(paths)
    if not rows:
        print("  Warning: no data found — generating empty report.")

    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    scenarios = sorted(set(r["scenario"] for r in rows)) if rows else []
    config_section = (
        '<div class="config">'
        f"<strong>Data:</strong> {len(rows)} measurements from "
        f"<code>{source_desc}</code> &mdash; "
        f"Scenarios: {', '.join(scenarios) if scenarios else 'none'}"
        "</div>"
    )

    body  = _build_scenario_1(rows)
    body += _build_scenario_2(rows)
    body += _build_summary(rows)

    html = HTML_TEMPLATE.format(
        plotly_cdn=PLOTLY_CDN,
        timestamp=timestamp,
        config_section=config_section,
        body=body,
    )

    os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(html)


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Generate benchmark report from one or more CSV files.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python report.py
  python report.py --csv results/results.csv --output results/report.html
  python report.py --csv results-ab-baseline/results.csv results-ab/results.csv \\
                   --output results-ab/report-ab.html
""",
    )
    parser.add_argument(
        "--csv",
        nargs="+",
        default=[os.path.join("results", "results.csv")],
        metavar="PATH",
        help="One or more CSV paths to merge into the report.",
    )
    parser.add_argument(
        "--output",
        default=os.path.join("results", "report.html"),
        help="Output HTML path (default: results/report.html)",
    )
    args = parser.parse_args()
    generate_report(args.csv, args.output)
    print(f"Report written to {args.output}")

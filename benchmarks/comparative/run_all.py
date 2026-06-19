#!/usr/bin/env python3
"""
Kvasir vs CSS vs S3 Comparative Benchmark Suite — Orchestrator

Sets up all platforms, runs both scenarios, collects results, and
generates reports.

Usage:
    python run_all.py                    # Run all scenarios
    python run_all.py --scenario 1       # Run only Scenario 1 (File I/O)
    python run_all.py --scenario 2       # Run only Scenario 2 (RDF Query)
    python run_all.py --report-only      # Only generate report from existing CSV
    python run_all.py --platform kvasir s3  # Run only Kvasir and S3
"""
from __future__ import annotations

import argparse
import os
import sys
import time

from helpers.config import CFG
from helpers.css_client import css_client
from helpers.kvasir_client import kvasir_client_s1, kvasir_client_s2, kvasir_client_candidate_s1, kvasir_client_candidate_s2
from helpers.s3_client import s3_client
from helpers.stats import ResultCollector
from report import generate_report
from scenario_1_file_io import run_scenario_1
from scenario_2_rdf_query import run_scenario_2


def _print_banner() -> None:
    print()
    print("╔══════════════════════════════════════════════════════════════╗")
    print("║       Comparative Benchmark Suite                            ║")
    print("╚══════════════════════════════════════════════════════════════╝")
    print()
    if CFG.run_css:
        print(f"  CSS URL:           {CFG.css_base_url}")
    if CFG.run_kvasir:
        print(f"  Kvasir URL:        {CFG.kvasir_base_url}")
        print(f"  Kvasir Pod (S1):   {CFG.kvasir_pod_name_s1} (auto-ingest-rdf=false)")
        print(f"  Kvasir Pod (S2):   {CFG.kvasir_pod_name_s2} (auto-ingest-rdf=true)")
    if CFG.run_kvasir_candidate:
        print(f"  Kvasir Candidate URL:       {CFG.kvasir_candidate_base_url}")
        print(f"  Kvasir Candidate Pod (S1):  {CFG.kvasir_candidate_pod_name} (auto-ingest-rdf=false)")
        print(f"  Kvasir Candidate Pod (S2):  {CFG.kvasir_candidate_pod_name_s2} (auto-ingest-rdf=true)")
    if CFG.run_s3:
        print(f"  S3 Endpoint:       {CFG.s3_endpoint_url}")
        print(f"  S3 Bucket:         {CFG.s3_bucket_name}")
    print(f"  Iterations:        {CFG.iterations} (+ {CFG.warmup_iterations} warmup)")
    print(f"  Concurrency:       {CFG.concurrency_levels}")
    print(f"  Platforms:         {', '.join(CFG.platforms)}")
    print(f"  RDF dataset size:  {CFG.rdf_dataset_size}")
    print(f"  Results dir:       {CFG.results_dir}")
    print()


def _setup(run_s1: bool, run_s2: bool) -> None:
    print("Setting up platforms …")
    if CFG.run_css:
        print("  [CSS]    ", end="", flush=True)
        css_client.ensure_pod_exists()
    if CFG.run_kvasir and run_s1:
        print("  [Kvasir S1] ", end="", flush=True)
        kvasir_client_s1.ensure_pod_exists()
    if CFG.run_kvasir and run_s2:
        print("  [Kvasir S2] ", end="", flush=True)
        kvasir_client_s2.ensure_pod_exists()
    if CFG.run_kvasir_candidate and run_s1:
        print("  [Kvasir Candidate S1] ", end="", flush=True)
        kvasir_client_candidate_s1.ensure_pod_exists()
    if CFG.run_kvasir_candidate and run_s2:
        print("  [Kvasir Candidate S2] ", end="", flush=True)
        kvasir_client_candidate_s2.ensure_pod_exists()
    if CFG.run_s3:
        print("  [S3]     ", end="", flush=True)
        s3_client.ensure_bucket_exists()
    print()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Comparative Benchmark Suite",
    )
    parser.add_argument(
        "--scenario",
        type=int,
        choices=[1, 2],
        default=None,
        help="Run only the specified scenario (1=File I/O, 2=RDF Query)",
    )
    parser.add_argument(
        "--report-only",
        action="store_true",
        help="Skip benchmarks; only generate the report from existing CSV",
    )
    parser.add_argument(
        "--csv",
        nargs="+",
        default=None,
        metavar="PATH",
        help="CSV file(s) for --report-only. Defaults to <RESULTS_DIR>/results.csv.",
    )
    parser.add_argument(
        "--platform",
        nargs="+",
        choices=["kvasir", "kvasir-candidate", "css", "s3"],
        default=None,
        help="Platforms to benchmark (default: all). Specify one or more: kvasir kvasir-candidate css s3",
    )
    parser.add_argument(
        "--skip-setup",
        action="store_true",
        help="Skip pod/bucket creation / setup phase",
    )
    parser.add_argument(
        "--cleanup",
        action="store_true",
        help="Delete uploaded benchmark files after scenario 1 completes",
    )
    args = parser.parse_args()

    if args.platform is not None:
        CFG.set_platforms(args.platform)

    csv_path = os.path.join(CFG.results_dir, "results.csv")
    report_path = os.path.join(CFG.results_dir, "report.html")

    if not args.report_only:
        _print_banner()

        run_s1 = args.scenario is None or args.scenario == 1
        run_s2 = args.scenario is None or args.scenario == 2

        if not args.skip_setup:
            _setup(run_s1=run_s1, run_s2=run_s2)

        collector = ResultCollector()
        t_start = time.perf_counter()

        if run_s1:
            run_scenario_1(collector, cleanup=args.cleanup)

        if run_s2:
            run_scenario_2(collector)

        elapsed = time.perf_counter() - t_start
        print(f"\n✓ Benchmarks complete in {elapsed:.1f}s")
        print(f"  Total measurements: {len(collector.results)} "
              f"({len(collector.non_warmup())} non-warmup)")

        os.makedirs(CFG.results_dir, exist_ok=True)
        collector.write_csv(csv_path)
        print(f"  CSV written to {csv_path}")

    # --- Report generation ---
    csv_paths = args.csv if (args.report_only and args.csv) else [csv_path]
    for path in csv_paths:
        if not os.path.exists(path):
            print(f"\n✗ CSV not found at {path}. Run benchmarks first.", file=sys.stderr)
            sys.exit(1)

    generate_report(csv_paths, report_path)
    print(f"  Report written to {report_path}")
    print()


if __name__ == "__main__":
    main()

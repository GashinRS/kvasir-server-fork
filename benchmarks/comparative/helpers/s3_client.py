"""Direct S3 client for benchmark operations (e.g. SeaweedFS)."""

from __future__ import annotations

import asyncio
import time
from typing import Any

import boto3
from botocore.config import Config as BotoConfig
from botocore.exceptions import ClientError

from .async_http import RequestTiming
from .config import CFG


class S3Client:
    """Synchronous S3 client using boto3 for direct bucket operations."""

    def __init__(self) -> None:
        self._s3 = boto3.client(
            "s3",
            endpoint_url=CFG.s3_endpoint_url,
            aws_access_key_id=CFG.s3_access_key,
            aws_secret_access_key=CFG.s3_secret_key,
            region_name=CFG.s3_region,
            config=BotoConfig(
                signature_version="s3v4",
                retries={"max_attempts": 0},
                max_pool_connections=50,
            ),
        )
        self._bucket = CFG.s3_bucket_name

    @property
    def bucket_name(self) -> str:
        return self._bucket

    def ensure_bucket_exists(self) -> None:
        """Create the benchmark bucket if it doesn't exist."""
        try:
            self._s3.head_bucket(Bucket=self._bucket)
            print(f"[S3] Bucket '{self._bucket}' already exists.")
        except ClientError as e:
            error_code = int(e.response["Error"]["Code"])
            if error_code == 404:
                self._s3.create_bucket(Bucket=self._bucket)
                print(f"[S3] Bucket '{self._bucket}' created.")
            else:
                raise

    # --- Synchronous timed operations ---

    def upload_file(
        self, key: str, data: bytes, content_type: str = "application/octet-stream"
    ) -> float:
        """Upload an object. Returns latency in ms."""
        t0 = time.perf_counter()
        self._s3.put_object(
            Bucket=self._bucket, Key=key, Body=data, ContentType=content_type
        )
        return (time.perf_counter() - t0) * 1000

    def download_file(self, key: str) -> tuple[bytes, float]:
        """Download an object. Returns (content, latency_ms)."""
        t0 = time.perf_counter()
        resp = self._s3.get_object(Bucket=self._bucket, Key=key)
        content = resp["Body"].read()
        elapsed_ms = (time.perf_counter() - t0) * 1000
        return content, elapsed_ms

    def delete_file(self, key: str) -> float:
        """Delete an object. Returns latency in ms."""
        t0 = time.perf_counter()
        self._s3.delete_object(Bucket=self._bucket, Key=key)
        return (time.perf_counter() - t0) * 1000

    def file_exists(self, key: str) -> bool:
        """Check if an object exists."""
        try:
            self._s3.head_object(Bucket=self._bucket, Key=key)
            return True
        except ClientError as e:
            if int(e.response["Error"]["Code"]) == 404:
                return False
            raise

    # --- Timed operations returning RequestTiming (for concurrent benchmarks) ---

    def upload_file_timed(
        self, key: str, data: bytes, content_type: str
    ) -> RequestTiming:
        """Upload and return a RequestTiming result."""
        t0 = time.perf_counter()
        try:
            self._s3.put_object(
                Bucket=self._bucket, Key=key, Body=data, ContentType=content_type
            )
            elapsed_ms = (time.perf_counter() - t0) * 1000
            return RequestTiming(
                latency_ms=elapsed_ms, status_code=200, response_size=0, success=True
            )
        except Exception as exc:
            elapsed_ms = (time.perf_counter() - t0) * 1000
            return RequestTiming(
                latency_ms=elapsed_ms,
                status_code=-4,
                response_size=0,
                success=False,
                failure_reason=f"{type(exc).__name__}: {exc}",
            )

    def download_file_timed(self, key: str) -> RequestTiming:
        """Download and return a RequestTiming result."""
        t0 = time.perf_counter()
        try:
            resp = self._s3.get_object(Bucket=self._bucket, Key=key)
            content = resp["Body"].read()
            elapsed_ms = (time.perf_counter() - t0) * 1000
            return RequestTiming(
                latency_ms=elapsed_ms,
                status_code=200,
                response_size=len(content),
                success=True,
            )
        except Exception as exc:
            elapsed_ms = (time.perf_counter() - t0) * 1000
            return RequestTiming(
                latency_ms=elapsed_ms,
                status_code=-4,
                response_size=0,
                success=False,
                failure_reason=f"{type(exc).__name__}: {exc}",
            )


# ---------------------------------------------------------------------------
# Async concurrent runner for S3 (uses thread pool under the hood)
# ---------------------------------------------------------------------------


async def run_s3_concurrent_workload(
    work_items: list[Any],
    worker_fn,  # Callable[[Any], RequestTiming]  (sync)
    concurrency: int,
    timeout_seconds: int = 60,
) -> tuple[list[RequestTiming], float]:
    """Run a concurrent S3 workload using asyncio.to_thread for true parallelism."""
    semaphore = asyncio.Semaphore(concurrency)

    async def bounded_worker(item: Any) -> RequestTiming:
        async with semaphore:
            try:
                return await asyncio.wait_for(
                    asyncio.to_thread(worker_fn, item),
                    timeout=timeout_seconds,
                )
            except asyncio.TimeoutError:
                return RequestTiming(
                    latency_ms=timeout_seconds * 1000,
                    status_code=-1,
                    response_size=0,
                    success=False,
                    failure_reason=f"worker timeout after {timeout_seconds}s",
                )
            except Exception as exc:
                return RequestTiming(
                    latency_ms=0,
                    status_code=-4,
                    response_size=0,
                    success=False,
                    failure_reason=f"{type(exc).__name__}: {exc}",
                )

    t0 = time.perf_counter()
    tasks = [asyncio.create_task(bounded_worker(item)) for item in work_items]
    results = await asyncio.gather(*tasks)
    wall_ms = (time.perf_counter() - t0) * 1000

    return list(results), wall_ms


# Module-level singleton
s3_client = S3Client()

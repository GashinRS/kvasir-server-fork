"""
Async HTTP helpers for concurrent benchmark execution.

Uses aiohttp for true concurrent HTTP requests across multiple
simulated clients.
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from typing import Any, Callable, Coroutine

import aiohttp

REQUEST_TIMEOUT_STATUS = -1
REQUEST_CANCELLED_STATUS = -2
REQUEST_CLIENT_ERROR_STATUS = -3
REQUEST_UNKNOWN_ERROR_STATUS = -4


@dataclass
class RequestTiming:
    """Timing result for a single HTTP request."""

    latency_ms: float
    status_code: int
    response_size: int
    success: bool
    failure_reason: str = ""


async def timed_put(
    session: aiohttp.ClientSession,
    url: str,
    data: bytes,
    headers: dict[str, str],
) -> RequestTiming:
    """PUT with timing."""
    t0 = time.perf_counter()
    try:
        async with session.put(url, data=data, headers=headers) as resp:
            body = await resp.read()
            elapsed = (time.perf_counter() - t0) * 1000
            reason = "" if 200 <= resp.status < 300 else f"HTTP {resp.status}"
            return RequestTiming(
                latency_ms=elapsed,
                status_code=resp.status,
                response_size=len(body),
                success=200 <= resp.status < 300,
                failure_reason=reason,
            )
    except asyncio.TimeoutError as exc:
        elapsed = (time.perf_counter() - t0) * 1000
        print(f"[WARN] Request timeout (PUT): {url}")
        return RequestTiming(
            latency_ms=elapsed,
            status_code=REQUEST_TIMEOUT_STATUS,
            response_size=0,
            success=False,
            failure_reason=f"timeout: {exc}",
        )
    except aiohttp.ClientError as exc:
        elapsed = (time.perf_counter() - t0) * 1000
        return RequestTiming(
            latency_ms=elapsed,
            status_code=REQUEST_CLIENT_ERROR_STATUS,
            response_size=0,
            success=False,
            failure_reason=f"client error: {exc}",
        )
    except asyncio.CancelledError:
        # Propagate cancellation so wait_for() can convert it to a timeout when appropriate.
        raise
    except Exception as exc:
        elapsed = (time.perf_counter() - t0) * 1000
        return RequestTiming(
            latency_ms=elapsed,
            status_code=REQUEST_UNKNOWN_ERROR_STATUS,
            response_size=0,
            success=False,
            failure_reason=f"{type(exc).__name__}: {exc}",
        )


async def timed_get(
    session: aiohttp.ClientSession,
    url: str,
    headers: dict[str, str],
) -> RequestTiming:
    """GET with timing."""
    t0 = time.perf_counter()
    try:
        async with session.get(url, headers=headers) as resp:
            body = await resp.read()
            elapsed = (time.perf_counter() - t0) * 1000
            reason = "" if 200 <= resp.status < 300 else f"HTTP {resp.status}"
            return RequestTiming(
                latency_ms=elapsed,
                status_code=resp.status,
                response_size=len(body),
                success=200 <= resp.status < 300,
                failure_reason=reason,
            )
    except asyncio.TimeoutError as exc:
        elapsed = (time.perf_counter() - t0) * 1000
        print(f"[WARN] Request timeout (GET): {url}")
        return RequestTiming(
            latency_ms=elapsed,
            status_code=REQUEST_TIMEOUT_STATUS,
            response_size=0,
            success=False,
            failure_reason=f"timeout: {exc}",
        )
    except aiohttp.ClientError as exc:
        elapsed = (time.perf_counter() - t0) * 1000
        return RequestTiming(
            latency_ms=elapsed,
            status_code=REQUEST_CLIENT_ERROR_STATUS,
            response_size=0,
            success=False,
            failure_reason=f"client error ({type(exc).__name__}): {exc}",
        )
    except asyncio.CancelledError:
        # Propagate cancellation so wait_for() can convert it to a timeout when appropriate.
        raise
    except Exception as exc:
        elapsed = (time.perf_counter() - t0) * 1000
        return RequestTiming(
            latency_ms=elapsed,
            status_code=REQUEST_UNKNOWN_ERROR_STATUS,
            response_size=0,
            success=False,
            failure_reason=f"{type(exc).__name__}: {exc}",
        )


async def timed_delete(
    session: aiohttp.ClientSession,
    url: str,
    headers: dict[str, str],
) -> RequestTiming:
    """DELETE with timing."""
    t0 = time.perf_counter()
    try:
        async with session.delete(url, headers=headers) as resp:
            body = await resp.read()
            elapsed = (time.perf_counter() - t0) * 1000
            reason = "" if 200 <= resp.status < 300 else f"HTTP {resp.status}"
            return RequestTiming(
                latency_ms=elapsed,
                status_code=resp.status,
                response_size=len(body),
                success=200 <= resp.status < 300,
                failure_reason=reason,
            )
    except asyncio.TimeoutError as exc:
        elapsed = (time.perf_counter() - t0) * 1000
        print(f"[WARN] Request timeout (DELETE): {url}")
        return RequestTiming(
            latency_ms=elapsed,
            status_code=REQUEST_TIMEOUT_STATUS,
            response_size=0,
            success=False,
            failure_reason=f"timeout: {exc}",
        )
    except aiohttp.ClientError as exc:
        elapsed = (time.perf_counter() - t0) * 1000
        return RequestTiming(
            latency_ms=elapsed,
            status_code=REQUEST_CLIENT_ERROR_STATUS,
            response_size=0,
            success=False,
            failure_reason=f"client error: {exc}",
        )
    except asyncio.CancelledError:
        # Propagate cancellation so wait_for() can convert it to a timeout when appropriate.
        raise
    except Exception as exc:
        elapsed = (time.perf_counter() - t0) * 1000
        return RequestTiming(
            latency_ms=elapsed,
            status_code=REQUEST_UNKNOWN_ERROR_STATUS,
            response_size=0,
            success=False,
            failure_reason=f"{type(exc).__name__}: {exc}",
        )


async def run_concurrent_workload(
    work_items: list[Any],
    worker_fn: Callable[
        [aiohttp.ClientSession, Any], Coroutine[Any, Any, RequestTiming]
    ],
    concurrency: int,
    headers: dict[str, str] | None = None,
    timeout_seconds: int = 10,
    request_timeout_seconds: int = 10,
    connector_limit: int | None = None,
    limit_per_host: int = 0,
) -> tuple[list[RequestTiming], float]:
    """Run a workload across N concurrent clients."""
    semaphore = asyncio.Semaphore(concurrency)
    timings: list[RequestTiming] = []

    async def bounded_worker(
        session: aiohttp.ClientSession, item: Any
    ) -> RequestTiming:
        async with semaphore:
            try:
                return await asyncio.wait_for(
                    worker_fn(session, item), timeout=request_timeout_seconds
                )
            except asyncio.TimeoutError:
                print(f"[WARN] Worker timeout after {request_timeout_seconds}s")
                return RequestTiming(
                    latency_ms=request_timeout_seconds * 1000,
                    status_code=REQUEST_TIMEOUT_STATUS,
                    response_size=0,
                    success=False,
                    failure_reason=f"worker timeout after {request_timeout_seconds}s",
                )
            except asyncio.CancelledError:
                return RequestTiming(
                    latency_ms=0,
                    status_code=REQUEST_CANCELLED_STATUS,
                    response_size=0,
                    success=False,
                    failure_reason="cancelled",
                )
            except aiohttp.ClientError as exc:
                return RequestTiming(
                    latency_ms=0,
                    status_code=REQUEST_CLIENT_ERROR_STATUS,
                    response_size=0,
                    success=False,
                    failure_reason=f"client error: {exc}",
                )
            except Exception as exc:
                return RequestTiming(
                    latency_ms=0,
                    status_code=REQUEST_UNKNOWN_ERROR_STATUS,
                    response_size=0,
                    success=False,
                    failure_reason=f"{type(exc).__name__}: {exc}",
                )

    timeout = aiohttp.ClientTimeout(total=request_timeout_seconds)
    effective_connector_limit = (
        connector_limit
        if connector_limit is not None
        else max(concurrency * 2, concurrency)
    )
    # Keep connections alive within a workload to avoid reconnect overhead and
    # reduce partial-read failures under concurrent download pressure.
    connector = aiohttp.TCPConnector(
        limit=max(1, effective_connector_limit),
        limit_per_host=max(0, limit_per_host),
        force_close=False,
    )

    t0 = time.perf_counter()
    async with aiohttp.ClientSession(
        timeout=timeout,
        connector=connector,
        headers=headers or {},
    ) as session:
        tasks = [
            asyncio.create_task(bounded_worker(session, item)) for item in work_items
        ]
        done, pending = await asyncio.wait(tasks, timeout=timeout_seconds)

        if pending:
            print(
                f"[WARN] Workload timeout after {timeout_seconds}s (cancelling pending tasks)"
            )
            for task in pending:
                task.cancel()

        for task in done:
            try:
                timings.append(task.result())
            except Exception as exc:
                timings.append(
                    RequestTiming(
                        latency_ms=0,
                        status_code=REQUEST_UNKNOWN_ERROR_STATUS,
                        response_size=0,
                        success=False,
                        failure_reason=f"task error ({type(exc).__name__}): {exc}",
                    )
                )

        for _ in pending:
            timings.append(
                RequestTiming(
                    latency_ms=timeout_seconds * 1000,
                    status_code=REQUEST_TIMEOUT_STATUS,
                    response_size=0,
                    success=False,
                    failure_reason=f"workload timeout after {timeout_seconds}s",
                )
            )
    wall_clock_ms = (time.perf_counter() - t0) * 1000

    return list(timings), wall_clock_ms

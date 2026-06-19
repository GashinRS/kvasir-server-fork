# Comparative Benchmarks

Reproducible benchmark suite comparing [Kvasir](../../README.MD),
the [Community Solid Server (CSS)](https://communitysolidserver.github.io/CommunitySolidServer/latest/),
and direct S3-compatible storage (e.g. SeaweedFS) across two scenarios:

| Scenario | What it tests | Kvasir API | CSS API | S3 API |
|----------|--------------|------------|---------|--------|
| **1 — File I/O** | Upload/download across file sizes (25 KB–25 MB) at various concurrency levels | S3 API (via Kvasir proxy) | Solid `PUT`/`GET` | Direct `PutObject`/`GetObject` |
| **2 — RDF Query** | Targeted data retrieval from RDF datasets (single entity, time range, latest N) | GraphQL `@filter` | Full file download + client-side parse | *(not applicable)* |

**Performance targets:** ≥ 25 % faster writes, ≥ 30 % faster reads.

## Prerequisites

| Tool | Install |
|------|---------|
| Python ≥ 3.11 | [python.org](https://www.python.org) |
| Docker + Compose v2 | [Docker Desktop](https://docs.docker.com/get-started/get-docker/) |
| just | [packages](https://github.com/casey/just#packages) |
| Running Kvasir stack | `just compose-up` or `just dev` (see root README) |

## Quick Start

```bash
# 1. Install Python dependencies
just bench-compare-install

# 2. Configure (edit .env with your Kvasir client credentials)
cp benchmarks/comparative/.env.example benchmarks/comparative/.env

# 3. Start CSS
just bench-compare-css-up

# 4. Start Kvasir (in another terminal, if not already running)
just compose-up

# 5. Run all benchmarks (all platforms)
just bench-compare

# 6. View the report
just bench-compare-serve
```

## Running Individual Scenarios

```bash
# File I/O only
just bench-compare-files

# RDF Query only
just bench-compare-rdf

# Select specific platforms (one or more)
just bench-compare -- --platform kvasir
just bench-compare -- --platform css s3
just bench-compare -- --platform kvasir s3

# S3 only (no Kvasir/CSS setup needed — just the S3 service)
just bench-compare -- --platform s3

# Re-generate report from existing CSV
just bench-compare-report

# Skip platform setup (pods/buckets already exist)
just bench-compare -- --skip-setup
```

## Configuration

Copy `.env.example` to `.env` and adjust:

| Variable | Default                  | Description |
|----------|--------------------------|-------------|
| `CSS_BASE_URL` | `http://localhost:3000`  | CSS server URL |
| `CSS_POD_NAME` | `benchmark`              | CSS pod name |
| `CSS_EMAIL` | `benchmark@example.com`  | CSS account email (must match seed config) |
| `CSS_PASSWORD` | `benchmark-password`     | CSS account password |
| `KVASIR_BASE_URL` | `http://localhost:8080`  | Kvasir server URL |
| `KVASIR_POD_NAME` | `benchmark-compare`      | Kvasir pod name |
| `KVASIR_CLIENT_ID` | `benchmark-compare`      | Keycloak client ID |
| `KVASIR_CLIENT_SECRET` | *(required)*             | Keycloak client secret |
| `KVASIR_POD_NAME_S1` | `KVASIR_POD_NAME`        | Scenario 1 pod (auto-ingest-rdf disabled) |
| `KVASIR_CLIENT_ID_S1` | `KVASIR_CLIENT_ID`       | Scenario 1 Keycloak client ID |
| `KVASIR_CLIENT_SECRET_S1` | `KVASIR_CLIENT_SECRET`   | Scenario 1 Keycloak client secret |
| `KVASIR_POD_NAME_S2` | `benchmark-compare-rdf`  | Scenario 2 pod (auto-ingest-rdf enabled) |
| `KVASIR_CLIENT_ID_S2` | `benchmark-compare-rdf`  | Scenario 2 Keycloak client ID |
| `KVASIR_CLIENT_SECRET_S2` | *(required)*             | Scenario 2 Keycloak client secret |
| `S3_ENDPOINT_URL` | `http://localhost:28333` | S3-compatible endpoint URL |
| `S3_ACCESS_KEY` | `kvasir`                 | S3 access key ID |
| `S3_SECRET_KEY` | `kvasirkvasir`           | S3 secret access key |
| `S3_REGION` | `us-east-1`              | S3 region |
| `S3_BUCKET_NAME` | `benchmark`              | S3 bucket name (created automatically if missing) |
| `ITERATIONS` | `5`                      | Measured iterations per benchmark |
| `WARMUP_ITERATIONS` | `1`                      | Warm-up iterations (discarded) |
| `CONCURRENCY_LEVELS` | `1,4,8,16`               | Concurrent client counts for Scenario 1 |
| `RDF_DATASET_SIZE` | `10000`                  | Records per sensor for Scenario 2 |
| `REQUEST_TIMEOUT_SECONDS` | `10`                     | Per-request timeout for async HTTP workloads |
| `WORKLOAD_TIMEOUT_SECONDS` | `60`                     | Timeout for a whole concurrent batch |
| `HTTP_CONNECTOR_LIMIT` | `100`                    | Total aiohttp connection pool size |
| `HTTP_LIMIT_PER_HOST` | `0`                      | Per-host connection cap (`0` = aiohttp default/no explicit cap) |

## Scenario Details

### Scenario 1 — File I/O

Compares raw file storage performance using six file size categories:

| Category | Size | Count | Content type |
|----------|------|-------|-------------|
| small_25kb | 25 KB | 100 | text/plain |
| medium_250kb | 250 KB | 50 | text/plain |
| large_2500kb | 2.5 MB | 20 | text/plain |
| xl_25mb | 25 MB | 5 | binary |
| rdf_500kb | 500 KB | 30 | N-Triples |
| binary_1mb | 1 MB | 20 | binary |

Each category is tested at every configured concurrency level (default: 1, 4, 8, 16
clients). Set `CONCURRENCY_LEVELS=1` to run a single-client baseline.

The concurrency test highlights the architectural difference:
- **CSS** uses file-system locking → writes serialise under load
- **Kvasir** uses Vert.x multi-reactor + SeaweedFS → near-linear scaling
- **S3 Direct** bypasses the Kvasir proxy entirely → raw storage baseline

### Scenario 2 — RDF Query

Pre-loads SAREF sensor + measurement data to both platforms, then measures
read/query performance:

| Sub-benchmark | CSS approach | Kvasir approach |
|--------------|-------------|----------------|
| Full retrieval | `GET` full file + rdflib parse | GraphQL query (paginated) |
| Single entity | `GET` full file + filter by ID | GraphQL `id:` argument |
| Time range | `GET` full file + filter timestamps | GraphQL `@filter` |
| Latest N | `GET` full file + sort + limit | GraphQL `orderBy` + `pageSize` |

This demonstrates the architectural advantage: CSS must download everything
regardless of what you need; Kvasir does server-side filtering.

> **Note:** S3 is not included in Scenario 2 — it has no query capabilities.

## Output

Results are written to `results/`:

| File | Description |
|------|------------|
| `results.csv` | Raw timing data (all iterations) |
| `report.html` | Interactive HTML report with Plotly.js charts |

The report includes:
- Bar charts comparing latencies per operation
- Concurrency scaling charts (latency + throughput vs client count)
- Speedup ratio visualisation
- Pass/fail summary against performance targets

## Architecture Notes

- **Python-based** — consistent with the existing Kvasir benchmarks in `../`
- **CSS runs in Docker** — no Node.js install needed, reproducible deployment
- **Concurrent tests** use `asyncio` + `aiohttp` (CSS/Kvasir) and `asyncio.to_thread` + `boto3` (S3)
- **CSS auth** uses `CSS-Account-Token` (account API login)
- **Kvasir auth** uses Keycloak client credentials (same as existing benchmarks)
- **S3 auth** uses AWS Signature V4 via `boto3` (access key + secret key)

"""
Shared helpers for Kvasir KG benchmark scripts.

Handles:
- Configuration loading (.env / environment variables)
- Keycloak client-credentials token acquisition (with auto-refresh)
- Common HTTP wrappers for the Kvasir Changes and Query APIs
"""

from __future__ import annotations

import json
import os
import sys
import time
from typing import Any

import requests
from dotenv import load_dotenv

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

load_dotenv()


def _require(key: str) -> str:
    val = os.getenv(key)
    if not val:
        print(f"[ERROR] Required env var '{key}' is not set. Copy .env.example to .env and fill it in.", file=sys.stderr)
        sys.exit(1)
    return val


class Config:
    base_url: str = os.getenv("KVASIR_BASE_URL", "http://localhost:8080").rstrip("/")
    pod_name: str = os.getenv("KVASIR_POD_NAME", "benchmark")
    keycloak_url: str = os.getenv("KEYCLOAK_URL", "http://localhost:8280").rstrip("/")
    keycloak_realm: str = os.getenv("KEYCLOAK_REALM", "kvasir")
    client_id: str = os.getenv("CLIENT_ID", "")
    client_secret: str = os.getenv("CLIENT_SECRET", "")
    nr_of_sensors: int = int(os.getenv("NR_OF_SENSORS", "20"))
    records_per_sensor: int = int(os.getenv("RECORDS_PER_SENSOR", "100000"))
    insert_batch_size: int = int(os.getenv("INSERT_BATCH_SIZE", "10000"))
    upload_wave_size: int = int(os.getenv("UPLOAD_WAVE_SIZE", "20"))
    query_iterations: int = int(os.getenv("QUERY_ITERATIONS", "5"))
    benchmarks: str = os.getenv("BENCHMARKS", "slice")
    single_id_lookup_sensors: int = int(os.getenv("SINGLE_ID_LOOKUP_SENSORS", "5"))
    time_range_seconds: int = int(os.getenv("TIME_RANGE_SECONDS", "60"))
    state_file: str = os.getenv("STATE_FILE", "benchmark_state.json")

    @property
    def pod_uri(self) -> str:
        return f"{self.base_url}/{self.pod_name}"

    @property
    def token_endpoint(self) -> str:
        return (
            f"{self.keycloak_url}/realms/{self.keycloak_realm}"
            f"/protocol/openid-connect/token"
        )


CFG = Config()

# SAREF / JSON-LD constants (mirrors kvasir.definitions.rdf.SAREFVocab + TestConstants)
SAREF_BASE = "https://saref.etsi.org/core/"
SAREF_SENSOR = f"{SAREF_BASE}Sensor"
SAREF_MEASUREMENT = f"{SAREF_BASE}Measurement"
SAREF_HAS_TIMESTAMP = f"{SAREF_BASE}hasTimestamp"
SAREF_HAS_VALUE = f"{SAREF_BASE}hasValue"
SAREF_MEASUREMENT_MADE_BY = f"{SAREF_BASE}measurementMadeBy"

RDFS_BASE = "http://www.w3.org/2000/01/rdf-schema#"
RDFS_LABEL = f"{RDFS_BASE}label"

EXAMPLE_BASE = "http://example.org/"

JSONLD_CONTEXT = {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "ex": EXAMPLE_BASE,
    "so": "http://schema.org/",
    "saref": SAREF_BASE,
    "rdfs": RDFS_BASE,
}

# ---------------------------------------------------------------------------
# Token management
# ---------------------------------------------------------------------------

_token_cache: dict[str, Any] = {}


def get_token() -> str:
    """Obtain (or reuse) a bearer token via the client-credentials flow."""
    now = time.time()
    if _token_cache.get("expires_at", 0) > now + 30:
        return _token_cache["access_token"]

    if not CFG.client_id or not CFG.client_secret:
        print(
            "[ERROR] CLIENT_ID and CLIENT_SECRET must be set. "
            "Copy .env.example to .env and configure them.",
            file=sys.stderr,
        )
        sys.exit(1)

    resp = requests.post(
        CFG.token_endpoint,
        data={"grant_type": "client_credentials"},
        auth=(CFG.client_id, CFG.client_secret),
        timeout=30,
    )
    resp.raise_for_status()
    body = resp.json()
    _token_cache["access_token"] = body["access_token"]
    _token_cache["expires_at"] = now + body.get("expires_in", 300)
    return _token_cache["access_token"]


def auth_headers(extra: dict[str, str] | None = None) -> dict[str, str]:
    headers = {"Authorization": f"Bearer {get_token()}"}
    if extra:
        headers.update(extra)
    return headers


# ---------------------------------------------------------------------------
# Pod helpers
# ---------------------------------------------------------------------------

def ensure_pod_exists() -> None:
    """Create the benchmark pod if it does not already exist.

    The pod is created with:
    - ``adminClientId`` / ``adminClientSecret`` from the .env config, which grants
      the calling client *reader*, *writer*, and *deleter* rights on the pod root.
    - ``auto-ingest-rdf: true`` so that N-Triples files uploaded to S3 are
      automatically ingested into the knowledge graph.

    Note: ``POST /`` is ``@PermitAll`` so no bearer token is needed for creation.
    The client credentials only become valid in Keycloak *after* Kvasir has
    provisioned them, so we wait for the token endpoint to accept them before
    proceeding with authenticated requests.
    """
    if not CFG.client_id or not CFG.client_secret:
        print(
            "[ERROR] CLIENT_ID and CLIENT_SECRET must be set to create the benchmark pod. "
            "Copy .env.example to .env and configure them.",
            file=sys.stderr,
        )
        sys.exit(1)

    pod_uri = CFG.pod_uri

    # Check existence without auth first — the client may not exist in Keycloak yet.
    resp = requests.get(pod_uri, headers={"Accept": "application/ld+json"}, timeout=30)
    if resp.status_code == 200:
        print(f"[INFO] Pod '{pod_uri}' already exists.")
        return
    if resp.status_code not in (404, 401, 403):
        resp.raise_for_status()

    # Pod configuration: enable auto-ingest so S3 uploads are processed by the
    # RDF ingester and written to the knowledge graph automatically.
    pod_configuration = json.dumps({"auto-ingest-rdf": True})

    print(f"[INFO] Creating pod '{CFG.pod_name}' with client '{CFG.client_id}' as admin…")
    body = {
        "@context": {"@vocab": "https://kvasir.discover.ilabt.imec.be/vocab#"},
        "name": CFG.pod_name,
        "ownerUserId": CFG.pod_name,
        # Serialised JSON string that configures the pod (see PodConfigOverride).
        "configuration": pod_configuration,
        # Registering the calling client grants it reader/writer/deleter access
        # on the pod root via OpenFGA (see RegisterPodInput.generateClients()).
        "adminClientId": CFG.client_id,
        "adminClientSecret": CFG.client_secret,
    }
    # POST / is @PermitAll — no bearer token required.
    resp = requests.post(
        CFG.base_url + "/",
        json=body,
        headers={"Content-Type": "application/ld+json"},
        timeout=60,
    )
    if resp.status_code == 409:
        print(f"[INFO] Pod '{CFG.pod_name}' already exists (race).")
        return
    resp.raise_for_status()
    print(f"[INFO] Pod '{CFG.pod_name}' created.")

    # Kvasir provisions the Keycloak client asynchronously.  Wait until the
    # token endpoint accepts our credentials before moving on.
    _wait_for_token(timeout=120)
    _wait_for_pod()


def _wait_for_token(timeout: int = 120, interval: float = 2.0) -> None:
    """Retry the token endpoint until the client credentials are accepted."""
    print("[INFO] Waiting for Keycloak client to be provisioned…")
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            resp = requests.post(
                CFG.token_endpoint,
                data={"grant_type": "client_credentials"},
                auth=(CFG.client_id, CFG.client_secret),
                timeout=10,
            )
            if resp.status_code == 200:
                body = resp.json()
                _token_cache["access_token"] = body["access_token"]
                _token_cache["expires_at"] = time.time() + body.get("expires_in", 300)
                print("[INFO] Keycloak client is ready.")
                return
        except requests.RequestException:
            pass
        time.sleep(interval)
    print("[ERROR] Timed out waiting for Keycloak client to be provisioned.", file=sys.stderr)
    sys.exit(1)


def _wait_for_pod(timeout: int = 120, interval: float = 2.0) -> None:
    """Poll the pod URI (with auth) until it returns 200."""
    print("[INFO] Waiting for pod initialisation…")
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            resp = requests.get(
                CFG.pod_uri,
                headers=auth_headers({"Accept": "application/ld+json"}),
                timeout=10,
            )
            if resp.status_code == 200:
                print("[INFO] Pod is ready.")
                return
        except requests.RequestException:
            pass
        time.sleep(interval)
    print("[ERROR] Timed out waiting for pod to become ready.", file=sys.stderr)
    sys.exit(1)


# ---------------------------------------------------------------------------
# Changes API helpers
# ---------------------------------------------------------------------------

def post_change_request(payload: dict) -> str:
    """
    POST a change request to the pod's /changes endpoint.

    Returns the Location header (pending change-request URL).
    """
    resp = requests.post(
        f"{CFG.pod_uri}/changes",
        json=payload,
        headers=auth_headers({"Content-Type": "application/ld+json"}),
        timeout=120,
    )
    resp.raise_for_status()
    location = resp.headers.get("Location")
    if not location:
        raise RuntimeError("POST /changes did not return a Location header")
    return location


def wait_for_committed(pending_url: str, timeout: int = 600, interval: float = 1.0) -> None:
    """
    Poll the pending change-request URL until the server redirects to the
    committed change report (i.e. status != 200 any more).

    The /pending/{id} endpoint returns:
    - 200  → still pending (PendingChangeRequest body)
    - 303  → committed, redirected to /changes/{id}
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        resp = requests.get(
            pending_url,
            headers=auth_headers({"Accept": "application/ld+json"}),
            allow_redirects=False,
            timeout=30,
        )
        if resp.status_code in (303, 302):
            # Committed — follow the redirect to confirm status
            committed_url = resp.headers.get("Location", pending_url)
            detail = requests.get(
                committed_url,
                headers=auth_headers({"Accept": "application/ld+json"}),
                timeout=30,
            )
            body = detail.json()
            result_code = body.get("kss:resultCode") or body.get(
                "https://kvasir.discover.ilabt.imec.be/vocab#resultCode"
            )
            if result_code and result_code != "COMMITTED":
                raise RuntimeError(f"Change request failed with status: {result_code} — {body}")
            return
        if resp.status_code == 200:
            time.sleep(interval)
            continue
        resp.raise_for_status()

    raise TimeoutError(f"Change request did not commit within {timeout}s: {pending_url}")


def post_change_and_wait(payload: dict) -> None:
    """Convenience wrapper: post a change request and block until committed."""
    pending_url = post_change_request(payload)
    wait_for_committed(pending_url)


# ---------------------------------------------------------------------------
# Query API helper
# ---------------------------------------------------------------------------

def run_query(graphql: str, context: dict | None = None) -> dict:
    """Execute a GraphQL query against the pod's /query endpoint."""
    body: dict[str, Any] = {"query": graphql}
    if context is not None:
        body["@context"] = context
    resp = requests.post(
        f"{CFG.pod_uri}/query",
        json=body,
        headers=auth_headers({"Content-Type": "application/json", "Accept": "application/json"}),
        timeout=300,
    )
    resp.raise_for_status()
    return resp.json()


# ---------------------------------------------------------------------------
# Stats helper
# ---------------------------------------------------------------------------

def print_stats(
    title: str,
    dataset: str,
    window: str | None,
    runs: int,
    latencies_ms: list[float],
) -> None:
    sorted_ms = sorted(latencies_ms)
    mn = sorted_ms[0]
    mx = sorted_ms[-1]
    avg = sum(sorted_ms) / len(sorted_ms)
    p95_idx = min(int(len(sorted_ms) * 0.95), len(sorted_ms) - 1)
    p95 = sorted_ms[p95_idx]

    bar = "═" * 78
    print()
    print(f"╔{bar}╗")
    print(f"║  {title}")
    print(f"╠{bar}╣")
    print(f"║  Dataset : {dataset}")
    if window:
        print(f"║  Window  : {window}")
    print(f"║  Runs    : {runs}")
    print(f"╠{bar}╣")
    print(f"║  Min     : {mn:.0f} ms")
    print(f"║  Avg     : {avg:.0f} ms")
    print(f"║  p95     : {p95:.0f} ms")
    print(f"║  Max     : {mx:.0f} ms")
    print(f"╚{bar}╝")


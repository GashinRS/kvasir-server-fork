"""Kvasir HTTP client for benchmark operations."""

from __future__ import annotations

import json
import time

import requests

from .auth import KvasirAuth
from .config import CFG


class KvasirClient:
    """Synchronous Kvasir client for S3 and query operations."""

    def __init__(
        self,
        pod_name: str,
        auto_ingest_rdf: bool,
        auth: KvasirAuth,
        base_url_override: str | None = None,
    ) -> None:
        self._pod_name = pod_name
        self._auto_ingest_rdf = auto_ingest_rdf
        self._auth = auth
        self._base_url = (base_url_override or CFG.kvasir_base_url).rstrip("/")

    @property
    def pod_uri(self) -> str:
        return f"{self._base_url}/{self._pod_name}"

    def ensure_pod_exists(self) -> None:
        """Create the benchmark pod if it doesn't exist."""
        resp = requests.get(
            self.pod_uri,
            headers={"Accept": "application/ld+json"},
            timeout=10,
        )
        if resp.status_code == 200:
            print(f"[Kvasir] Pod '{self.pod_uri}' already exists.")
            return

        pod_configuration = json.dumps({"auto-ingest-rdf": self._auto_ingest_rdf})
        body = {
            "@context": {"@vocab": "https://kvasir.discover.ilabt.imec.be/vocab#"},
            "name": self._pod_name,
            "ownerUserId": self._pod_name,
            "configuration": pod_configuration,
            "adminClientId": self._auth.client_id,
            "adminClientSecret": self._auth.client_secret,
        }
        resp = requests.post(
            f"{self._base_url}/",
            json=body,
            headers={"Content-Type": "application/ld+json"},
            timeout=10,
        )
        if resp.status_code == 409:
            print(f"[Kvasir] Pod '{self._pod_name}' already exists (race).")
            return
        resp.raise_for_status()
        print(f"[Kvasir] Pod '{self._pod_name}' created.")

        self._wait_for_token()
        self._wait_for_pod()

    def _wait_for_token(self, timeout: int = 120) -> None:
        """Wait until Keycloak accepts our credentials."""
        print("[Kvasir] Waiting for Keycloak client provisioning...")
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                self._auth.get_token()
                print("[Kvasir] Keycloak client ready.")
                return
            except Exception:
                time.sleep(2)
        raise TimeoutError("Keycloak client not provisioned in time")

    def _wait_for_pod(self, timeout: int = 120) -> None:
        """Wait until pod returns 200."""
        print("[Kvasir] Waiting for pod initialisation...")
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                resp = requests.get(
                    self.pod_uri,
                    headers=self._auth.headers({"Accept": "application/ld+json"}),
                    timeout=10,
                )
                if resp.status_code == 200:
                    print("[Kvasir] Pod ready.")
                    return
            except Exception:
                pass
            time.sleep(2)
        raise TimeoutError("Pod not ready in time")

    # --- S3 operations (Scenario 1) ---

    def upload_file(
        self, path: str, data: bytes, content_type: str = "application/octet-stream"
    ) -> float:
        """Upload via S3 API. Returns latency in ms."""
        url = f"{self.pod_uri}/s3/{path}"
        t0 = time.perf_counter()
        resp = requests.put(
            url,
            data=data,
            headers=self._auth.headers({"Content-Type": content_type}),
            timeout=10,
        )
        elapsed_ms = (time.perf_counter() - t0) * 1000
        resp.raise_for_status()
        return elapsed_ms

    def download_file(self, path: str) -> tuple[bytes, float]:
        """Download via S3 API. Returns (content, latency_ms)."""
        url = f"{self.pod_uri}/s3/{path}"
        t0 = time.perf_counter()
        resp = requests.get(
            url,
            headers=self._auth.headers(),
            timeout=10,
        )
        content = resp.content
        elapsed_ms = (time.perf_counter() - t0) * 1000
        resp.raise_for_status()
        return content, elapsed_ms

    def delete_file(self, path: str) -> float:
        """Delete via S3 API. Returns latency in ms."""
        url = f"{self.pod_uri}/s3/{path}"
        t0 = time.perf_counter()
        resp = requests.delete(
            url,
            headers=self._auth.headers(),
            timeout=10,
        )
        elapsed_ms = (time.perf_counter() - t0) * 1000
        if resp.status_code not in (200, 204, 404):
            resp.raise_for_status()
        return elapsed_ms

    def file_exists(self, path: str, timeout: int = 10) -> bool:
        """Check if a file exists via S3 API with a short timeout.

        Prefer this over full downloads for pre-flight checks in benchmarks.
        """
        url = f"{self.pod_uri}/s3/{path}"
        headers = self._auth.headers()

        # HEAD should be the cheapest path; fallback to streamed GET if unsupported.
        resp = requests.head(url, headers=headers, timeout=timeout)
        if resp.status_code == 404:
            return False
        if resp.status_code == 405:
            fallback = requests.get(url, headers=headers, timeout=timeout, stream=True)
            try:
                if fallback.status_code == 404:
                    return False
                fallback.raise_for_status()
                return True
            finally:
                fallback.close()

        resp.raise_for_status()
        return True

    # --- Query operations (Scenario 2) ---

    def run_query(
        self, graphql: str, context: dict | None = None
    ) -> tuple[dict, float]:
        """Run a GraphQL query. Returns (result_json, latency_ms)."""
        body: dict = {"query": graphql}
        if context:
            body["@context"] = context
        t0 = time.perf_counter()
        resp = requests.post(
            f"{self.pod_uri}/query",
            json=body,
            headers=self._auth.headers(
                {
                    "Content-Type": "application/json",
                    "Accept": "application/json",
                }
            ),
            timeout=10,
        )
        elapsed_ms = (time.perf_counter() - t0) * 1000
        resp.raise_for_status()
        return resp.json(), elapsed_ms

    def post_change_request(self, payload: dict) -> str:
        """POST a change request. Returns the pending URL (Location header)."""
        resp = requests.post(
            f"{self.pod_uri}/changes",
            json=payload,
            headers=self._auth.headers({"Content-Type": "application/ld+json"}),
            timeout=10,
        )
        resp.raise_for_status()
        location = resp.headers.get("Location")
        if not location:
            raise RuntimeError("POST /changes did not return a Location header")
        return location

    def wait_for_committed(self, pending_url: str, timeout: int = 600) -> None:
        """Poll pending URL until committed or failed."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            resp = requests.get(
                pending_url,
                headers=self._auth.headers({"Accept": "application/ld+json"}),
                allow_redirects=False,
                timeout=10,
            )
            if resp.status_code in (302, 303):
                committed_url = resp.headers.get("Location", pending_url)
                detail = requests.get(
                    committed_url,
                    headers=self._auth.headers({"Accept": "application/ld+json"}),
                    timeout=10,
                )
                body = detail.json()
                code = body.get("kss:resultCode") or body.get(
                    "https://kvasir.discover.ilabt.imec.be/vocab#resultCode"
                )
                if code and code != "COMMITTED":
                    raise RuntimeError(f"Change failed: {code}")
                return
            if resp.status_code == 200:
                time.sleep(1)
                continue
            resp.raise_for_status()
        raise TimeoutError(f"Change not committed within {timeout}s")

    def post_change_and_wait(self, payload: dict) -> None:
        """Post a change request and block until committed."""
        url = self.post_change_request(payload)
        self.wait_for_committed(url)

    def upload_rdf_to_s3(self, key: str, ntriples: bytes) -> float:
        """Upload N-Triples to S3 (for auto-ingest). Returns latency in ms."""
        return self.upload_file(key, ntriples, "application/n-triples")

    def wait_for_s3_key_committed(self, s3_key: str, timeout: int = 600) -> None:
        """Poll changes endpoint until the S3 key is committed."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            resp = requests.get(
                f"{self.pod_uri}/changes?pageSize=200",
                headers=self._auth.headers({"Accept": "application/ld+json"}),
                timeout=10,
            )
            resp.raise_for_status()
            body = resp.json()
            items = body if isinstance(body, list) else body.get("@graph", [])
            for report in items:
                refs = report.get("kss:associatedReferences", [])
                if isinstance(refs, dict):
                    refs = [refs]
                for ref in refs:
                    inner = ref.get("kss:reference", {})
                    if inner.get("kss:key") == s3_key:
                        code = report.get("kss:statusCode")
                        if code == "COMMITTED":
                            return
                        if code in ("ASSERTION_FAILED", "NO_MATCHES", "INTERNAL_ERROR"):
                            raise RuntimeError(
                                f"Auto-ingest of '{s3_key}' failed: {code}"
                            )
            time.sleep(3)
        raise TimeoutError(f"S3 key '{s3_key}' not committed within {timeout}s")

    def auth_headers_dict(self) -> dict[str, str]:
        """Get auth headers as a plain dict for aiohttp."""
        return self._auth.headers()

    def ensure_token_fresh(self, min_remaining_seconds: int = 120) -> None:
        """Ensure the cached token is valid for at least *min_remaining_seconds*.

        Call before entering a timed workload so that ``auth_headers_dict()``
        during the batch never triggers a blocking Keycloak round-trip.
        """
        self._auth.ensure_fresh(min_remaining_seconds)


kvasir_client_s1 = KvasirClient(
    pod_name=CFG.kvasir_pod_name_s1,
    auto_ingest_rdf=False,
    auth=KvasirAuth(CFG.kvasir_client_id_s1, CFG.kvasir_client_secret_s1),
)

kvasir_client_s2 = KvasirClient(
    pod_name=CFG.kvasir_pod_name_s2,
    auto_ingest_rdf=True,
    auth=KvasirAuth(CFG.kvasir_client_id_s2, CFG.kvasir_client_secret_s2),
)

kvasir_client_candidate_s1 = KvasirClient(
    pod_name=CFG.kvasir_candidate_pod_name,
    auto_ingest_rdf=False,
    auth=KvasirAuth(
        CFG.kvasir_candidate_client_id,
        CFG.kvasir_candidate_client_secret,
        token_endpoint=CFG.kvasir_candidate_keycloak_token_endpoint,
    ),
    base_url_override=CFG.kvasir_candidate_base_url or None,
)

kvasir_client_candidate_s2 = KvasirClient(
    pod_name=CFG.kvasir_candidate_pod_name_s2,
    auto_ingest_rdf=True,
    auth=KvasirAuth(
        CFG.kvasir_candidate_client_id_s2,
        CFG.kvasir_candidate_client_secret_s2,
        token_endpoint=CFG.kvasir_candidate_keycloak_token_endpoint,
    ),
    base_url_override=CFG.kvasir_candidate_base_url or None,
)

# Backwards-compatible alias.
kvasir_client = kvasir_client_s1

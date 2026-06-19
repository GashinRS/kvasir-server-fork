"""CSS (Community Solid Server) HTTP client for benchmark operations."""

from __future__ import annotations

import time

import requests

from .auth import css_auth
from .config import CFG


class CSSClient:
    """Synchronous CSS client for Solid protocol operations."""

    @property
    def pod_uri(self) -> str:
        return CFG.css_pod_uri

    def ensure_pod_exists(self) -> None:
        """Ensure the benchmark pod exists on CSS.

        CSS with seed config should auto-create the pod. This method
        verifies it's accessible and logs in.
        """
        css_auth.login()
        resp = requests.get(
            f"{self.pod_uri}/",
            headers=css_auth.headers({"Accept": "text/turtle"}),
            timeout=10,
        )
        if resp.status_code == 404:
            self._create_pod()
        elif resp.status_code >= 400:
            resp.raise_for_status()
        print(f"[CSS] Pod accessible at {self.pod_uri}/")

    def _create_pod(self) -> None:
        """Create a pod via the CSS account API."""
        controls = css_auth.controls
        pod_url = controls.get("account", {}).get("pod")
        if not pod_url:
            raise RuntimeError("CSS account API does not expose pod creation endpoint")

        resp = requests.post(
            pod_url,
            json={"name": CFG.css_pod_name},
            headers=css_auth.account_headers({"Content-Type": "application/json"}),
            timeout=10,
        )
        resp.raise_for_status()
        print(f"[CSS] Created pod '{CFG.css_pod_name}'")

    def ensure_container(self, container_path: str) -> None:
        """Ensure a container (directory) exists by creating it if needed.

        In Solid, containers are created implicitly when a resource is
        PUT inside them, but we can also explicitly create them.
        """
        url = f"{self.pod_uri}/{container_path.strip('/')}/"
        resp = requests.head(url, headers=css_auth.headers(), timeout=10)
        if resp.status_code == 404:
            requests.put(
                url,
                headers=css_auth.headers(
                    {
                        "Content-Type": "text/turtle",
                        "Link": '<http://www.w3.org/ns/ldp#BasicContainer>; rel="type"',
                    }
                ),
                data="",
                timeout=10,
            )

    def upload_file(self, path: str, data: bytes, content_type: str) -> float:
        """Upload a file and return latency in ms."""
        url = f"{self.pod_uri}/{path}"
        t0 = time.perf_counter()
        resp = requests.put(
            url,
            data=data,
            headers=css_auth.headers({"Content-Type": content_type}),
            timeout=10,
        )
        elapsed_ms = (time.perf_counter() - t0) * 1000
        resp.raise_for_status()
        return elapsed_ms

    def download_file(self, path: str) -> tuple[bytes, float]:
        """Download a file and return (content, latency_ms)."""
        url = f"{self.pod_uri}/{path}"
        t0 = time.perf_counter()
        resp = requests.get(
            url,
            headers=css_auth.headers(),
            timeout=10,
        )
        content = resp.content
        elapsed_ms = (time.perf_counter() - t0) * 1000
        resp.raise_for_status()
        return content, elapsed_ms

    def delete_file(self, path: str) -> float:
        """Delete a file and return latency in ms."""
        url = f"{self.pod_uri}/{path}"
        t0 = time.perf_counter()
        resp = requests.delete(
            url,
            headers=css_auth.headers(),
            timeout=10,
        )
        elapsed_ms = (time.perf_counter() - t0) * 1000
        if resp.status_code not in (200, 204, 205, 404):
            resp.raise_for_status()
        return elapsed_ms

    def upload_rdf(self, path: str, ntriples: bytes) -> float:
        """Upload an N-Triples file and return latency in ms."""
        return self.upload_file(path, ntriples, "application/n-triples")

    def download_rdf(self, path: str) -> tuple[bytes, float]:
        """Download an RDF resource and return (content, latency_ms)."""
        return self.download_file(path)

    def auth_headers_dict(self) -> dict[str, str]:
        """Get auth headers as a plain dict for aiohttp."""
        return css_auth.headers()


css_client = CSSClient()

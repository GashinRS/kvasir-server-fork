"""
Shared configuration for the comparative benchmark suite.
Loaded from .env file or environment variables.

Four canonical platforms are supported:
  css               Community Solid Server
  kvasir            Kvasir baseline (the reference build)
  kvasir-candidate  Kvasir under test (MR image / perf branch)
  s3                Direct S3/SeaweedFS (raw storage baseline)

For simultaneous A/B runs set KVASIR_CANDIDATE_BASE_URL to the second
instance.  For sequential runs leave it unset and merge the two result
CSVs at report time with `just bench-ab-report`.
"""

from __future__ import annotations
import os
from dotenv import load_dotenv

load_dotenv()


class BenchmarkConfig:
    # CSS
    css_base_url: str = os.getenv("CSS_BASE_URL", "http://localhost:3000").rstrip("/")
    css_pod_name: str = os.getenv("CSS_POD_NAME", "benchmark")
    css_email: str = os.getenv("CSS_EMAIL", "benchmark@example.com")
    css_password: str = os.getenv("CSS_PASSWORD", "benchmark-password")

    # Keycloak (shared by both Kvasir instances)
    keycloak_url: str = os.getenv("KEYCLOAK_URL", "http://localhost:8280").rstrip("/")
    keycloak_realm: str = os.getenv("KEYCLOAK_REALM", "kvasir")

    # Kvasir baseline
    kvasir_base_url: str = os.getenv("KVASIR_BASE_URL", "http://localhost:8080").rstrip(
        "/"
    )
    kvasir_pod_name: str = os.getenv("KVASIR_POD_NAME", "benchmark-compare")
    kvasir_client_id: str = os.getenv("KVASIR_CLIENT_ID", "benchmark-compare")
    kvasir_client_secret: str = os.getenv("KVASIR_CLIENT_SECRET", "")

    # Scenario-specific overrides for baseline (fall back to the values above)
    kvasir_pod_name_s1: str = os.getenv("KVASIR_POD_NAME_S1", kvasir_pod_name)
    kvasir_client_id_s1: str = os.getenv("KVASIR_CLIENT_ID_S1", kvasir_client_id)
    kvasir_client_secret_s1: str = os.getenv(
        "KVASIR_CLIENT_SECRET_S1", kvasir_client_secret
    )
    kvasir_pod_name_s2: str = os.getenv("KVASIR_POD_NAME_S2", "benchmark-compare-rdf")
    kvasir_client_id_s2: str = os.getenv("KVASIR_CLIENT_ID_S2", "benchmark-compare-rdf")
    kvasir_client_secret_s2: str = os.getenv("KVASIR_CLIENT_SECRET_S2", "")

    # Kvasir candidate (second instance for simultaneous A/B).
    # Leave KVASIR_CANDIDATE_BASE_URL empty to use sequential mode (just bench-ab + just bench-ab-report).
    kvasir_candidate_base_url: str = os.getenv("KVASIR_CANDIDATE_BASE_URL", "").rstrip(
        "/"
    )
    kvasir_candidate_keycloak_url: str = os.getenv(
        "KVASIR_CANDIDATE_KEYCLOAK_URL", ""
    ).rstrip("/")
    kvasir_candidate_pod_name: str = os.getenv(
        "KVASIR_CANDIDATE_POD_NAME", "benchmark-compare-candidate"
    )
    kvasir_candidate_client_id: str = os.getenv(
        "KVASIR_CANDIDATE_CLIENT_ID", "benchmark-compare-candidate"
    )
    kvasir_candidate_client_secret: str = os.getenv(
        "KVASIR_CANDIDATE_CLIENT_SECRET", ""
    )
    kvasir_candidate_pod_name_s2: str = os.getenv(
        "KVASIR_CANDIDATE_POD_NAME_S2", "benchmark-compare-candidate-rdf"
    )
    kvasir_candidate_client_id_s2: str = os.getenv(
        "KVASIR_CANDIDATE_CLIENT_ID_S2", "benchmark-compare-candidate-rdf"
    )
    kvasir_candidate_client_secret_s2: str = os.getenv(
        "KVASIR_CANDIDATE_CLIENT_SECRET_S2", ""
    )

    # Direct S3
    s3_endpoint_url: str = os.getenv(
        "S3_ENDPOINT_URL", "http://localhost:28333"
    ).rstrip("/")
    s3_access_key: str = os.getenv("S3_ACCESS_KEY", "kvasir")
    s3_secret_key: str = os.getenv("S3_SECRET_KEY", "kvasirkvasir")
    s3_region: str = os.getenv("S3_REGION", "us-east-1")
    s3_bucket_name: str = os.getenv("S3_BUCKET_NAME", "benchmark")

    # Benchmark parameters
    iterations: int = int(os.getenv("ITERATIONS", "5"))
    warmup_iterations: int = int(os.getenv("WARMUP_ITERATIONS", "1"))
    concurrency_levels: list[int] = [
        int(x) for x in os.getenv("CONCURRENCY_LEVELS", "1,4,8,16").split(",")
    ]

    # Async HTTP tuning
    request_timeout_seconds: int = int(os.getenv("REQUEST_TIMEOUT_SECONDS", "10"))
    workload_timeout_seconds: int = int(os.getenv("WORKLOAD_TIMEOUT_SECONDS", "60"))
    http_connector_limit: int = int(os.getenv("HTTP_CONNECTOR_LIMIT", "100"))
    http_limit_per_host: int = int(os.getenv("HTTP_LIMIT_PER_HOST", "0"))

    # RDF dataset size for scenario 2
    rdf_dataset_size: int = int(os.getenv("RDF_DATASET_SIZE", "10000"))

    # Active platform set — driven by --platform CLI flag or set_platforms()
    platforms: list[str] = ["css", "kvasir", "s3"]

    # Output directory for CSVs and HTML reports
    results_dir: str = os.getenv("RESULTS_DIR", "results")

    # ------------------------------------------------------------------
    # Derived properties
    # ------------------------------------------------------------------

    @property
    def run_css(self) -> bool:
        return "css" in self.platforms

    @property
    def run_kvasir(self) -> bool:
        return "kvasir" in self.platforms

    @property
    def run_kvasir_candidate(self) -> bool:
        return "kvasir-candidate" in self.platforms

    @property
    def run_s3(self) -> bool:
        return "s3" in self.platforms

    def set_platforms(self, platforms: list[str]) -> None:
        self.platforms = list(dict.fromkeys(platforms))

    @property
    def css_pod_uri(self) -> str:
        return f"{self.css_base_url}/{self.css_pod_name}"

    @property
    def kvasir_pod_uri_s1(self) -> str:
        return f"{self.kvasir_base_url}/{self.kvasir_pod_name_s1}"

    @property
    def kvasir_pod_uri_s2(self) -> str:
        return f"{self.kvasir_base_url}/{self.kvasir_pod_name_s2}"

    @property
    def keycloak_token_endpoint(self) -> str:
        return f"{self.keycloak_url}/realms/{self.keycloak_realm}/protocol/openid-connect/token"

    @property
    def kvasir_candidate_keycloak_token_endpoint(self) -> str:
        base = self.kvasir_candidate_keycloak_url or self.keycloak_url
        return f"{base}/realms/{self.keycloak_realm}/protocol/openid-connect/token"


CFG = BenchmarkConfig()

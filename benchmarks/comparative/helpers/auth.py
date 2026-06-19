"""Auth helpers for CSS and Kvasir."""

from __future__ import annotations

import base64
import time

import requests

from .config import CFG


class KvasirAuth:
    """Keycloak client-credentials token manager."""

    def __init__(
        self, client_id: str, client_secret: str, token_endpoint: str | None = None
    ) -> None:
        self._client_id = client_id
        self._client_secret = client_secret
        self._token_endpoint = token_endpoint or CFG.keycloak_token_endpoint
        self._token: str = ""
        self._expires_at: float = 0

    @property
    def client_id(self) -> str:
        return self._client_id

    @property
    def client_secret(self) -> str:
        return self._client_secret

    def get_token(self) -> str:
        now = time.time()
        if self._expires_at > now + 30:
            return self._token
        return self._refresh_token()

    def _refresh_token(self) -> str:
        now = time.time()
        resp = requests.post(
            self._token_endpoint,
            data={"grant_type": "client_credentials"},
            auth=(self._client_id, self._client_secret),
            timeout=10,
        )
        resp.raise_for_status()
        body = resp.json()
        self._token = body["access_token"]
        self._expires_at = now + body.get("expires_in", 300)
        return self._token

    def ensure_fresh(self, min_remaining_seconds: int = 120) -> None:
        """Proactively refresh the token if it expires within *min_remaining_seconds*.

        Call this **before** a timed workload so that every subsequent
        ``get_token()`` call during the batch is a cheap cache-hit with no
        HTTP round-trip to Keycloak.
        """
        now = time.time()
        if self._expires_at <= now + min_remaining_seconds:
            self._refresh_token()

    def headers(self, extra: dict[str, str] | None = None) -> dict[str, str]:
        h = {"Authorization": f"Bearer {self.get_token()}"}
        if extra:
            h.update(extra)
        return h


class CSSAuth:
    """CSS auth manager.

    Provides two token types:
    - Account token (CSS-Account-Token): for the /.account/ management API only.
    - Resource token (Bearer): for Solid pod resource operations (PUT/GET/DELETE).

    Resource tokens are obtained via the CSS client credentials → OIDC flow:
      1. Login with email/password → CSS-Account-Token
      2. Create client credentials (id + secret) via /.account/ API
      3. Exchange at the OIDC token endpoint → Bearer access token
    """

    def __init__(self) -> None:
        self._account_token: str = ""
        self._controls: dict = {}
        self._client_id: str = ""
        self._client_secret: str = ""
        self._resource_token: str = ""
        self._resource_token_expires: float = 0
        self._oidc_token_endpoint: str = ""

    # ------------------------------------------------------------------
    # Account (/.account/) layer
    # ------------------------------------------------------------------

    def login(self) -> None:
        """Login with email/password and cache the CSS-Account-Token."""
        # Step 1: discover controls (unauthenticated)
        idx = requests.get(f"{CFG.css_base_url}/.account/", timeout=10)
        idx.raise_for_status()
        controls = idx.json().get("controls", {})

        # Step 2: login
        login_url = controls.get("password", {}).get("login")
        if not login_url:
            raise RuntimeError(
                "CSS account API does not expose password login endpoint"
            )
        login_resp = requests.post(
            login_url,
            json={"email": CFG.css_email, "password": CFG.css_password},
            headers={"Content-Type": "application/json"},
            timeout=10,
        )
        login_resp.raise_for_status()
        self._account_token = login_resp.json().get("authorization", "")
        if not self._account_token:
            raise RuntimeError("CSS login did not return an authorization token")

        # Step 3: get full (authenticated) controls
        full = requests.get(
            f"{CFG.css_base_url}/.account/",
            headers={"Authorization": f"CSS-Account-Token {self._account_token}"},
            timeout=10,
        )
        full.raise_for_status()
        self._controls = full.json().get("controls", {})

    def _ensure_logged_in(self) -> None:
        if not self._account_token:
            self.login()

    def account_headers(self, extra: dict[str, str] | None = None) -> dict[str, str]:
        """Headers for /.account/ API calls."""
        self._ensure_logged_in()
        h = {"Authorization": f"CSS-Account-Token {self._account_token}"}
        if extra:
            h.update(extra)
        return h

    @property
    def controls(self) -> dict:
        self._ensure_logged_in()
        return self._controls

    # ------------------------------------------------------------------
    # Resource (Solid pod) layer — Bearer token via OIDC
    # ------------------------------------------------------------------

    def _get_oidc_token_endpoint(self) -> str:
        if not self._oidc_token_endpoint:
            disc = requests.get(
                f"{CFG.css_base_url}/.well-known/openid-configuration",
                timeout=10,
            )
            disc.raise_for_status()
            self._oidc_token_endpoint = disc.json()["token_endpoint"]
        return self._oidc_token_endpoint

    def _ensure_client_credentials(self) -> None:
        """Create a CSS client credentials token (id + secret) if not yet done."""
        if self._client_id and self._client_secret:
            return
        self._ensure_logged_in()
        cred_url = self._controls.get("account", {}).get("clientCredentials")
        if not cred_url:
            raise RuntimeError("CSS controls do not expose clientCredentials endpoint")
        web_id = f"{CFG.css_pod_uri}/profile/card#me"
        resp = requests.post(
            cred_url,
            json={"name": "benchmark-resource-client", "webId": web_id},
            headers={"Authorization": f"CSS-Account-Token {self._account_token}"},
            timeout=10,
        )
        resp.raise_for_status()
        body = resp.json()
        self._client_id = body["id"]
        self._client_secret = body["secret"]

    def get_resource_token(self) -> str:
        """Obtain (or reuse) a Bearer token for Solid resource operations."""
        now = time.time()
        if self._resource_token and self._resource_token_expires > now + 30:
            return self._resource_token

        self._ensure_client_credentials()
        token_endpoint = self._get_oidc_token_endpoint()

        credentials = base64.b64encode(
            f"{self._client_id}:{self._client_secret}".encode()
        ).decode()
        resp = requests.post(
            token_endpoint,
            headers={
                "Authorization": f"Basic {credentials}",
                "Content-Type": "application/x-www-form-urlencoded",
            },
            data="grant_type=client_credentials&scope=webid",
            timeout=10,
        )
        resp.raise_for_status()
        body = resp.json()
        self._resource_token = body["access_token"]
        self._resource_token_expires = now + body.get("expires_in", 3600)
        return self._resource_token

    def headers(self, extra: dict[str, str] | None = None) -> dict[str, str]:
        """Bearer token headers for Solid pod resource operations."""
        h = {"Authorization": f"Bearer {self.get_resource_token()}"}
        if extra:
            h.update(extra)
        return h


# Singletons
css_auth = CSSAuth()

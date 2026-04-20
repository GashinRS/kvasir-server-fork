# Kubernetes Deployment

> The Timoni-based deployment described on this page is **experimental** and under active development.
> The module schema, default values, and deployment workflow will see frequent changes across releases.
> {style="warning"}

Kvasir ships a [Timoni](https://timoni.sh) module for deploying to Kubernetes. Timoni uses
[CUE](https://cuelang.org) for type-safe, composable configuration and packages modules as
OCI artifacts — no Helm templating required.

For local development and testing, the repository also provides a
[Kind](https://kind.sigs.k8s.io) quickstart that stands up a complete Kvasir stack
(including all dependencies) on a single-node cluster.

## Dependencies

Kvasir requires the following backing services. We provide minimal configuration manifests
to get them running in a Kind cluster, but **we defer to each project's official documentation
for production installation, upgrades, and operational guidance**. The manifests in `kind/manifests/`
are intentionally simple and suited for development only.

| Service | Purpose | Docs |
|---------|---------|------|
| **ClickHouse** | Knowledge graph storage | [clickhouse.com/docs](https://clickhouse.com/docs) |
| **Apache Kafka** (KRaft) | Event streaming / CQRS | [kafka.apache.org](https://kafka.apache.org/documentation/) |
| **SeaweedFS** | S3-compatible blob storage | [github.com/seaweedfs/seaweedfs](https://github.com/seaweedfs/seaweedfs/wiki) |
| **PostgreSQL** | OpenFGA backing store | [postgresql.org/docs](https://www.postgresql.org/docs/) |
| **OpenFGA** | Fine-grained authorization | [openfga.dev/docs](https://openfga.dev/docs) |
| **Keycloak** | Identity provider (OIDC) | [keycloak.org/documentation](https://www.keycloak.org/documentation) |
| **Traefik** | Ingress controller | [doc.traefik.io/traefik](https://doc.traefik.io/traefik/) |

Our deployment only configures the options that Kvasir needs to connect to these services.
Refer to the linked documentation for topics such as high availability, persistence,
TLS termination, and version upgrades.

## Prerequisites

### Tools

| Tool | Min version | Install |
|------|-------------|---------|
| **Docker** | any recent | [Docker Desktop](https://docs.docker.com/get-started/get-docker/) |
| **kind** | any recent | [kind.sigs.k8s.io](https://kind.sigs.k8s.io/docs/user/quick-start/#installation) |
| **kubectl** | 1.28 | [kubernetes.io](https://kubernetes.io/docs/tasks/tools/) |
| **helm** | 3.x | [helm.sh](https://helm.sh/docs/intro/install/) |
| **timoni** | 0.22 | [timoni.sh](https://timoni.sh/install/) |
| **just** | 1.46 | [packages](https://github.com/casey/just#packages) |

Optional for running API tests after deployment:

| Tool | Min version | Install |
|------|-------------|---------|
| **hurl** | 7.0 | [hurl.dev](https://hurl.dev/docs/installation.html) |

### Resources

The Kind cluster runs all dependencies in a single node. Ensure at least **3 GB of free RAM**
and access to the GitLab container registry
(`gitlab.ilabt.imec.be:4567/kvasir/kvasir-server`).

## Kind Quickstart

### Start the cluster

A single command creates the Kind cluster, deploys all dependency manifests,
installs Traefik via Helm, and deploys Kvasir via Timoni:

```bash
just kind-up
```

Once complete, Kvasir is available at:

- **Kvasir:** `http://kvasir.localhost`
- **Keycloak:** `http://keycloak.localhost`

The cluster is named `kvasir` and uses the configuration in `kind/cluster-config.yaml`.
Host ports 80 and 443 are forwarded to the cluster's Traefik ingress.

> **Host resolution required.** The Kind setup uses `*.localhost` domains routed through
> Traefik IngressRoutes. Inside the cluster, a CoreDNS patch rewrites these domains to
> the Traefik service, but your **host machine** must also resolve them to `127.0.0.1`.
>
> Most modern browsers and operating systems already resolve `*.localhost` to the loopback
> address ([RFC 6761](https://www.rfc-editor.org/rfc/rfc6761#section-6.3)), but not all do.
> If `http://kvasir.localhost` does not work after `just kind-up`, add the following
> entries to `/etc/hosts` (Linux/macOS) or `C:\Windows\System32\drivers\etc\hosts` (Windows):
>
> ```
> 127.0.0.1  kvasir.localhost
> 127.0.0.1  keycloak.localhost
> ```
>
> In CI environments, the `kind-test` recipe accepts a `KIND_RESOLVE_ARGS` environment variable
> to pass `--resolve` flags to hurl, bypassing DNS entirely.

### Run API tests

With the cluster running, execute the API test suite:

```bash
just kind-test
```

Results are written to `api-tests/junit-kind.xml`.

### Tear down

```bash
just kind-down
```

### Deploy with a local image

To test a locally built Kvasir image instead of pulling from the registry:

```bash
# Option 1: Load image into Kind and redeploy
just kind-load-image kvasir/monolith:local
KVASIR_IMAGE=kvasir/monolith KVASIR_TAG=local KVASIR_PULL_POLICY=Never \
  just kind-deploy-kvasir

# Option 2: Environment variables only (image must be pullable)
KVASIR_IMAGE=my-registry/monolith KVASIR_TAG=dev \
  just kind-deploy-kvasir
```

## Timoni Module

### Overview

The Timoni module lives in `timoni/kvasir/` and is published as an OCI artifact.
It generates Kubernetes resources for a monolith deployment: ServiceAccount, Service,
ConfigMap (with the Kvasir `application.yaml`), and Deployment.

### Install from the OCI registry

```bash
timoni -n kvasir apply kvasir oci://harbor.discover.ilabt.imec.be/library/kvasir
```

### Install from a local checkout

```bash
timoni -n kvasir apply kvasir timoni/kvasir --values my-values.cue
```

### Values

The module is configured through CUE values files. The full schema is defined in
`timoni/kvasir/templates/config.cue` and the application-level config in
`timoni/kvasir/templates/kvasir-app-config.cue`.

See the [Configuration Reference](Configuration-Reference.md) for all available
Kvasir application properties.

A minimal values file:

```cue
values: {
    serviceName: "monolith"

    applicationConfig: {
        http: {
            "base-uri":      "https://kvasir.example.com/"
            "webclient-uri": "https://kvasir.example.com/_ui/"
        }
        auth: keycloak: {
            url: "https://auth.example.com"
            "admin-client": {
                "server-url": "http://keycloak.keycloak:8280"
            }
        }
    }
}
```

For Kind-specific values, see `kind/values-kind.cue` which configures localhost
URLs, bootstrap pods, and Keycloak internal routing.

### Validate

Lint the CUE schema without applying:

```bash
just timoni-lint
```

### Publish

Push the module to the OCI registry (version is read from `pom.xml`):

```bash
just timoni-push
```

### List published versions

```bash
just timoni-list
```

## What the Kind setup deploys

The `just kind-up` recipe orchestrates the following steps:

1. **Create cluster** — `kind create cluster` with `kind/cluster-config.yaml`
   (single control-plane node, host ports 80/443 mapped)
2. **Deploy dependencies** — raw manifests from `kind/manifests/`:
   - Namespaces (`clickhouse`, `kafka`, `seaweedfs`, `openfga`, `keycloak`, `kvasir`, `traefik`)
   - PostgreSQL → OpenFGA → ClickHouse → Kafka → SeaweedFS → Keycloak
   - Each deployment is waited on with `kubectl rollout status`
3. **Install Traefik** — via Helm chart with custom values, IngressRoutes for
   `kvasir.localhost` and `keycloak.localhost`, and a CoreDNS patch for `*.localhost` resolution
4. **Deploy Kvasir** — `timoni apply` with `kind/values-kind.cue`

### Available recipes

| Recipe | Description |
|--------|-------------|
| `just kind-up` | Full setup: cluster + deps + Traefik + Kvasir |
| `just kind-down` | Delete the Kind cluster |
| `just kind-deploy-deps` | Deploy only dependency manifests |
| `just kind-deploy-traefik` | Install/upgrade Traefik and IngressRoutes |
| `just kind-deploy-kvasir` | Deploy/redeploy Kvasir via Timoni |
| `just kind-test` | Run API tests against the cluster |
| `just kind-load-image <image>` | Load a Docker image into the cluster |
| `just timoni-lint` | Validate the Timoni module schema |
| `just timoni-push` | Publish module to OCI registry |
| `just timoni-list` | List published module versions |

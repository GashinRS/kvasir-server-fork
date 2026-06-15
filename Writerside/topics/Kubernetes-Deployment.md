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

| Service                  | Purpose                    | Docs                                                                          |
| ------------------------ | -------------------------- | ----------------------------------------------------------------------------- |
| **ClickHouse**           | Knowledge graph storage    | [clickhouse.com/docs](https://clickhouse.com/docs)                            |
| **Apache Kafka** (KRaft) | Event streaming / CQRS     | [kafka.apache.org](https://kafka.apache.org/documentation/)                   |
| **SeaweedFS**            | S3-compatible blob storage | [github.com/seaweedfs/seaweedfs](https://github.com/seaweedfs/seaweedfs/wiki) |
| **PostgreSQL**           | OpenFGA backing store      | [postgresql.org/docs](https://www.postgresql.org/docs/)                       |
| **OpenFGA**              | Fine-grained authorization | [openfga.dev/docs](https://openfga.dev/docs)                                  |
| **Keycloak**             | Identity provider (OIDC)   | [keycloak.org/documentation](https://www.keycloak.org/documentation)          |
| **Traefik**              | Ingress controller         | [doc.traefik.io/traefik](https://doc.traefik.io/traefik/)                     |

Our deployment only configures the options that Kvasir needs to connect to these services.
Refer to the linked documentation for topics such as high availability, persistence,
TLS termination, and version upgrades.

## Prerequisites

### Tools

| Tool        | Min version | Install                                                                          |
| ----------- | ----------- | -------------------------------------------------------------------------------- |
| **Docker**  | any recent  | [Docker Desktop](https://docs.docker.com/get-started/get-docker/)                |
| **kind**    | any recent  | [kind.sigs.k8s.io](https://kind.sigs.k8s.io/docs/user/quick-start/#installation) |
| **kubectl** | 1.28        | [kubernetes.io](https://kubernetes.io/docs/tasks/tools/)                         |
| **helm**    | 3.x         | [helm.sh](https://helm.sh/docs/intro/install/)                                   |
| **timoni**  | 0.22        | [timoni.sh](https://timoni.sh/install/)                                          |
| **just**    | 1.46        | [packages](https://github.com/casey/just#packages)                               |

Optional for running API tests after deployment:

| Tool     | Min version | Install                                             |
| -------- | ----------- | --------------------------------------------------- |
| **hurl** | 7.0         | [hurl.dev](https://hurl.dev/docs/installation.html) |

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

## Deployment Modes

Kvasir supports two deployment modes controlled by the `deploymentMode` value:

### Monolith (default)

All Kvasir services run in a single container. This is the simplest deployment and
recommended for development, testing, and small-scale production use.

```cue
values: {
    deploymentMode: "monolith"
}
```

Generates:

- 1 Deployment (all services)
- 1 Service
- 1 ConfigMap

### Microservices

Each Kvasir service runs as a separate Deployment with its own container image.
Use this mode for production deployments requiring independent scaling, resource
allocation, or fault isolation.

```cue
values: {
    deploymentMode: "microservices"
}
```

Generates per-service:

- Deployments: `kg-query-api`, `kg-changes-api`, `kg-change-processor`, `kg-stream-api`,
  `solid-api`, `storage-api`, `pod-management-api`, `ui-service`, `simple-rdf-ingester`
- Services (for HTTP services only)
- Traefik IngressRoute with per-service routing
- Optional: `init-service` Job for bootstrap tasks

#### Init Job

In microservices mode, an init Job handles bootstrap tasks (Keycloak realm setup,
OpenFGA model sync, initial pod creation). Configure via:

```cue
values: {
    deploymentMode: "microservices"
    init: {
        enabled:        true   // default
        exitAfterSetup: true   // terminate after bootstrap
        backoffLimit:   3      // retry attempts
    }
}
```

#### Per-service configuration

Override resources, replicas, or image settings per service:

```cue
values: {
    deploymentMode: "microservices"
    services: {
        "kg-query-api": {
            replicas: 3
            resources: {
                requests: { cpu: "500m", memory: "512Mi" }
                limits:   { cpu: "2",    memory: "2Gi" }
            }
        }
        "kg-change-processor": {
            replicas: 2
        }
    }
}
```

## Timoni Module

### Overview

The Timoni module lives in `timoni/kvasir/` and is published as an OCI artifact.
It generates Kubernetes resources based on the deployment mode and configuration values.

### Install from the OCI registry

```bash
timoni -n kvasir apply kvasir oci://harbor.discover.ilabt.imec.be/library/kvasir
```

### Install from a local checkout

```bash
timoni -n kvasir apply kvasir timoni/kvasir --values my-values.cue
```

### Validate without applying

Render the manifests to inspect them:

```bash
timoni build kvasir ./timoni/kvasir --namespace kvasir \
  --values my-values.cue --output yaml
```

### Uninstall

```bash
timoni -n kvasir delete kvasir
```

## Module Configuration

### General values

| Key                         | Type     | Default          | Description                              |
| --------------------------- | -------- | ---------------- | ---------------------------------------- |
| `deploymentMode`            | `string` | `"monolith"`     | `"monolith"` or `"microservices"`        |
| `image.tag`                 | `string` | module version   | Container image tag                      |
| `image.digest`              | `string` | —                | Image digest (takes precedence over tag) |
| `image.repository`          | `string` | GitLab registry  | Container image repository               |
| `image.pullPolicy`          | `string` | `"IfNotPresent"` | Image pull policy                        |
| `replicas`                  | `int`    | `1`              | Number of pod replicas                   |
| `resources.requests.cpu`    | `string` | `"10m"`          | CPU request                              |
| `resources.requests.memory` | `string` | `"32Mi"`         | Memory request                           |
| `service.port`              | `int`    | `80`             | Service HTTP port                        |

### Security context

The module ships with a hardened container security context complying with the
[restricted pod security standard](https://kubernetes.io/docs/concepts/security/pod-security-standards/):

```cue
securityContext: {
    allowPrivilegeEscalation: false
    privileged:               false
    capabilities: drop: ["ALL"]
}
```

For full compliance, also set pod-level context:

```cue
values: {
    podSecurityContext: {
        runAsUser:  65532
        runAsGroup: 65532
        fsGroup:    65532
    }
    securityContext: {
        seccompProfile: type: "RuntimeDefault"
    }
}
```

### Health probes

Probes use [SmallRye Health](https://quarkus.io/guides/smallrye-health) endpoints
on the management port (`9100`):

| Probe            | Path              | Description                                 |
| ---------------- | ----------------- | ------------------------------------------- |
| `livenessProbe`  | `/q/health/live`  | Process alive check (10s delay, 30s period) |
| `readinessProbe` | `/q/health/ready` | Ready after Initializer completes           |
| `startupProbe`   | `/q/health/live`  | Bootstrap budget (default 5 min)            |

The startup probe gives Kvasir time to complete bootstrap tasks (Keycloak realm,
OpenFGA model sync, Kafka topics, pod registration). Configure via:

| Key                             | Type     | Default            | Description              |
| ------------------------------- | -------- | ------------------ | ------------------------ |
| `startupProbe.enabled`          | `bool`   | `true`             | Enable startup probe     |
| `startupProbe.path`             | `string` | `"/q/health/live"` | Health endpoint path     |
| `startupProbe.periodSeconds`    | `int`    | `10`               | Seconds between attempts |
| `startupProbe.failureThreshold` | `int`    | `30`               | Failures before restart  |
| `startupProbe.timeoutSeconds`   | `int`    | `1`                | Per-attempt timeout      |

## Application Configuration

The `applicationConfig` block controls Kvasir's runtime configuration. It is marshaled
into `application.yaml` and mounted at `/home/jboss/config/` inside the container.

For the full property reference, see [Configuration Reference](Configuration-Reference.md).

### Required configuration groups

| Group             | Description                                     |
| ----------------- | ----------------------------------------------- |
| `http`            | Public base URI and web client URI              |
| `kg.clickhouse`   | ClickHouse connection (host, port, credentials) |
| `messaging.kafka` | Kafka bootstrap servers                         |
| `storage.s3`      | S3-compatible storage endpoint and credentials  |
| `auth.keycloak`   | Keycloak URL, realm, and admin client           |
| `pep.openfga`     | OpenFGA URL for policy enforcement              |

### Optional configuration groups

| Group       | Description                                       |
| ----------- | ------------------------------------------------- |
| `pod`       | Pod-scoped defaults (OIDC/UMA, DPoP, auto-ingest) |
| `bootstrap` | Pods and OIDC clients to create at startup        |

### Example: Production values

```cue
values: {
    applicationConfig: {
        http: {
            "base-uri":      "https://kvasir.example.com/"
            "webclient-uri": "https://kvasir.example.com/_ui/"
        }

        kg: clickhouse: {
            host: "clickhouse.data.svc.cluster.local"
            port: 8123
        }

        messaging: kafka: "bootstrap-servers": "kafka-bootstrap.kafka:9093"

        storage: s3: {
            endpoint: "https://s3.eu-west-1.amazonaws.com"
            region:   "eu-west-1"
        }

        auth: keycloak: {
            url:   "https://auth.example.com"
            realm: "kvasir"
            "admin-client": {
                "grant-type":  "client_credentials"
                "client-id":   "kvasir-admin"
                "server-url":  "https://auth.example.com"
                "realm":       "master"
            }
        }

        pep: openfga: url: "http://openfga.openfga:8380"
    }

    secrets: {
        keycloak:   existingSecret: "kvasir-keycloak"
        s3:         existingSecret: "kvasir-s3"
        clickhouse: existingSecret: "kvasir-clickhouse"
    }
}
```

## Secret Management

Sensitive credentials should not live in a ConfigMap. The `secrets` block sources
them from Kubernetes Secrets, scrubs them from the generated `application.yaml`,
and injects them as environment variables.

### Supported integrations

| Integration      | Fields                                                                 | Default Secret keys                                                          |
| ---------------- | ---------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| `keycloak`       | `adminUsername`, `adminPassword`, `adminClientId`, `adminClientSecret` | `admin-username`, `admin-password`, `admin-client-id`, `admin-client-secret` |
| `s3`             | `accessKey`, `secretKey`                                               | `access-key`, `secret-key`                                                   |
| `clickhouse`     | `user`, `password`                                                     | `user`, `password`                                                           |
| `policyEnforcer` | `basicAuthPassword`, `apiKeyValue`                                     | `basic-auth-password`, `api-key-value`                                       |

### Using existing Secrets (recommended for production)

Reference pre-created Secrets:

```cue
values: secrets: {
    keycloak:   existingSecret: "kvasir-keycloak"
    s3:         existingSecret: "kvasir-s3"
    clickhouse: existingSecret: "kvasir-clickhouse"
}
```

Create the Secrets with expected keys:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: kvasir-keycloak
  namespace: kvasir
type: Opaque
stringData:
  admin-username: "admin"
  admin-password: "secure-password"
  admin-client-id: "kvasir-admin"
  admin-client-secret: "secure-client-secret"
```

### Custom key names

When using External Secrets Operator or other tools with different key names:

```cue
values: secrets: keycloak: {
    existingSecret: "kvasir-keycloak-from-vault"
    fields: {
        adminUsername:     key: "KC_ADMIN_USERNAME"
        adminPassword:     key: "KC_ADMIN_PASSWORD"
        adminClientId:     key: "KC_ADMIN_CLIENT_ID"
        adminClientSecret: key: "KC_ADMIN_CLIENT_SECRET"
    }
}
```

### Module-managed Secrets (dev/local only)

For local development, the module can create Secrets from inline values:

```cue
values: {
    applicationConfig: {
        storage: s3: {
            "access-key": "dev-access-key"
            "secret-key": "dev-secret-key"
        }
    }
    secrets: s3: manage: true
}
```

This emits `Secret/<instance>-s3-secret` and wires environment variables automatically.

> **Warning:** Do not use `manage: true` in production. Inline values are visible
> in your values files and version control.
> {style="warning"}

### Dev overlay

The repository includes `timoni/kvasir/dev-values.cue` with placeholder credentials
for local clusters:

```bash
timoni -n kvasir apply kvasir ./timoni/kvasir \
    --values ./timoni/kvasir/dev-values.cue
```

## Ingress Configuration

In microservices mode, enable Traefik IngressRoute for external access:

```cue
values: {
    deploymentMode: "microservices"
    ingress: {
        enabled:    true
        entryPoint: "web"  // or "websecure" for HTTPS, you will have to manage TLS certs in addition to this
        host:       "kvasir.example.com"
    }
}
```

This generates an IngressRoute with per-service routing rules based on URL patterns.

> Currently only Traefik IngressRoute are supported in microservices mode.
> Other ingress controllers may work, but are not tested or supported.
> {style="note"}

## Kafka TLS

Configure server-only TLS (not mTLS) for Kafka connections:

```cue
values: kafkaTLS: {
    enabled: true
    "trust-secret": {
        name: "kafka-ca-cert"  // Secret containing CA certificate
        key:  "ca.crt"         // Key within the Secret
    }
}
```

| Key                            | Type     | Default                   | Description                         |
| ------------------------------ | -------- | ------------------------- | ----------------------------------- |
| `kafkaTLS.enabled`             | `bool`   | `false`                   | Enable TLS for Kafka                |
| `kafkaTLS."config-name"`       | `string` | `"kafka"`                 | Quarkus TLS config name             |
| `kafkaTLS."security-protocol"` | `string` | `"SSL"`                   | Kafka security protocol             |
| `kafkaTLS."mount-path"`        | `string` | `"/home/jboss/tls/kafka"` | CA cert mount path                  |
| `kafkaTLS."trust-secret".name` | `string` | —                         | Secret name (required when enabled) |
| `kafkaTLS."trust-secret".key`  | `string` | `"ca.crt"`                | Key within Secret                   |

> To operate Kvasir as a fully confidential system with in flight encryption and encryption at rest,
> we defer to the [Kvasir encryption POC](https://gitlab.ilabt.imec.be/kvasir/kvasir-encryption-poc)
> for the latest status and recommendations.
> {style="note"}

### Setting up with cert-manager

1. Create a self-signed CA:

```yaml
apiVersion: cert-manager.io/v1
kind: Issuer
metadata:
  name: kafka-selfsigned
  namespace: kvasir
spec:
  selfSigned: {}
---
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: kafka-ca
  namespace: kvasir
spec:
  isCA: true
  commonName: kafka-ca
  secretName: kafka-ca-cert
  duration: 87600h
  privateKey:
    algorithm: ECDSA
    size: 256
  issuerRef:
    name: kafka-selfsigned
    kind: Issuer
```

1. Reference in Timoni values:

```cue
values: kafkaTLS: {
    enabled: true
    "trust-secret": name: "kafka-ca-cert"
}
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

## Available recipes

| Recipe                         | Description                                   |
| ------------------------------ | --------------------------------------------- |
| `just kind-up`                 | Full setup: cluster + deps + Traefik + Kvasir |
| `just kind-down`               | Delete the Kind cluster                       |
| `just kind-deploy-deps`        | Deploy only dependency manifests              |
| `just kind-deploy-traefik`     | Install/upgrade Traefik and IngressRoutes     |
| `just kind-deploy-kvasir`      | Deploy/redeploy Kvasir via Timoni             |
| `just kind-test`               | Run API tests against the cluster             |
| `just kind-load-image <image>` | Load a Docker image into the cluster          |
| `just timoni-lint`             | Validate the Timoni module schema             |
| `just timoni-push`             | Publish module to OCI registry                |
| `just timoni-list`             | List published module versions                |

## Troubleshooting

### Pod stuck in CrashLoopBackOff

Check logs for the failing container:

```bash
kubectl logs -n kvasir deployment/kvasir --previous
```

Common causes:

- Missing or incorrect secrets configuration
- Unreachable backing services (ClickHouse, Kafka, Keycloak)
- Startup probe timeout (increase `failureThreshold`)

### Bootstrap fails

If the init job or monolith fails during bootstrap:

1. Check Keycloak is accessible and the admin credentials are correct
2. Verify OpenFGA is running and reachable
3. Check Kafka topics can be created (permissions, broker availability)

### Secrets not working

Verify the Secret exists and has the expected keys:

```bash
kubectl get secret -n kvasir kvasir-keycloak -o yaml
```

Check environment variables are injected:

```bash
kubectl exec -n kvasir deployment/kvasir -- env | grep KVASIR
```

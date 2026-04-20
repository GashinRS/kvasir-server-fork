# kvasir

A [timoni.sh](http://timoni.sh) module for deploying kvasir to Kubernetes clusters.

## Install

To create an instance using the default values:

```shell
timoni -n default apply kvasir oci://<container-registry-url>
```

To change the [default configuration](#configuration),
create one or more `values.cue` files and apply them to the instance.

For example, create a file `my-values.cue` with the following content:

```cue
values: {
	resources: requests: {
		cpu:    "100m"
		memory: "128Mi"
	}
}
```

And apply the values with:

```shell
timoni -n default apply kvasir oci://<container-registry-url> \
--values ./my-values.cue
```

## Uninstall

To uninstall an instance and delete all its Kubernetes resources:

```shell
timoni -n default delete kvasir
```

## Configuration

### General values

| Key                          | Type                                    | Default                    | Description                                                                                                                                  |
|------------------------------|-----------------------------------------|----------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| `image: tag:`                | `string`                                | `<latest version>`         | Container image tag                                                                                                                          |
| `image: digest:`             | `string`                                | `<latest digest>`          | Container image digest, takes precedence over `tag` when specified                                                                           |
| `image: repository:`         | `string`                                | `gitlab.ilabt.imec.be:4567/kvasir/kvasir-server/<serviceName>` | Container image repository                                                                                                 |
| `image: pullPolicy:`         | `string`                                | `IfNotPresent`             | [Kubernetes image pull policy](https://kubernetes.io/docs/concepts/containers/images/#image-pull-policy)                                     |
| `metadata: labels:`          | `{[ string]: string}`                   | `{}`                       | Common labels for all resources                                                                                                              |
| `metadata: annotations:`     | `{[ string]: string}`                   | `{}`                       | Common annotations for all resources                                                                                                         |
| `podAnnotations:`            | `{[ string]: string}`                   | `{}`                       | Annotations applied to pods                                                                                                                  |
| `imagePullSecrets:`          | `[...timoniv1.ObjectReference]`         | `[]`                       | [Kubernetes image pull secrets](https://kubernetes.io/docs/concepts/containers/images/#specifying-imagepullsecrets-on-a-pod)                 |
| `tolerations:`               | `[ ...corev1.#Toleration]`              | `[]`                       | [Kubernetes toleration](https://kubernetes.io/docs/concepts/scheduling-eviction/taint-and-toleration)                                        |
| `affinity:`                  | `corev1.#Affinity`                      | `{}`                       | [Kubernetes affinity and anti-affinity](https://kubernetes.io/docs/concepts/scheduling-eviction/assign-pod-node/#affinity-and-anti-affinity) |
| `resources:`                 | `timoniv1.#ResourceRequirements`        | `cpu: 10m, memory: 32Mi`  | [Kubernetes resource requests and limits](https://kubernetes.io/docs/concepts/configuration/manage-resources-containers)                     |
| `topologySpreadConstraints:` | `[...corev1.#TopologySpreadConstraint]` | `[]`                       | [Kubernetes pod topology spread constraints](https://kubernetes.io/docs/concepts/scheduling-eviction/topology-spread-constraints)            |
| `podSecurityContext:`        | `corev1.#PodSecurityContext`            | `{}`                       | [Kubernetes pod security context](https://kubernetes.io/docs/tasks/configure-pod-container/security-context)                                 |
| `securityContext:`           | `corev1.#SecurityContext`               | `see below`                | [Kubernetes container security context](https://kubernetes.io/docs/tasks/configure-pod-container/security-context)                           |
| `service: annotations:`      | `{[ string]: string}`                   | `{}`                       | Annotations applied to the Kubernetes Service                                                                                                |
| `service: port:`             | `int`                                   | `80`                       | Kubernetes Service HTTP port                                                                                                                 |
| `test: enabled:`             | `bool`                                  | `false`                    | Run end-to-end tests at install and upgrades                                                                                                 |

#### Default security context

The module ships with a hardened container security context that complies with the restricted [Kubernetes pod security standard](https://kubernetes.io/docs/concepts/security/pod-security-standards/):

```cue
securityContext: {
	allowPrivilegeEscalation: false
	privileged:               false
	capabilities: drop: ["ALL"]
}
```

For full compliance with the restricted profile, also set the pod-level context and seccomp profile:

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

### Application configuration

`applicationConfig` controls everything Kvasir itself reads from `application.yaml` —
HTTP, pod defaults, bootstrap, knowledge graph, messaging, storage, authentication,
and policy enforcement. The module marshals this block into `application.yaml` and
mounts it at `/home/jboss/config/` inside the container.

For the full schema (every key, type, default, and meaning), refer to the
[Configuration Reference](https://kvasir.docs.solid.imec-int.com/configuration-reference.html).
The summary below is a fast lookup of the top-level groups.

| Group                       | Required | Description                                                                                                                  |
|-----------------------------|----------|------------------------------------------------------------------------------------------------------------------------------|
| `http`                      | yes      | Public base URI (`base-uri`) and web client URI. Defaults derive from `<name>.<namespace>.svc.cluster.local` — override for ingress. |
| `pod`                       | no       | Pod-scoped defaults: default context, RDF auto-ingest, OIDC/UMA, DPoP, HTTP endpoint policy enforcer.                        |
| `bootstrap`                 | no       | Pods to create at startup, generated OIDC clients, optional `exit-after-setup` for one-shot bootstrap jobs.                  |
| `kg.clickhouse`             | yes      | ClickHouse host/port and optional credentials for the knowledge graph backend.                                               |
| `messaging.kafka`           | yes      | Kafka `bootstrap-servers`. Combine with [Kafka TLS](#kafka-tls) for TLS-enabled brokers.                                     |
| `storage.s3`                | yes      | S3-compatible blob store endpoint, region, and credentials. Move credentials to a [Secret](#secret-management).              |
| `auth.keycloak`             | yes      | Keycloak server URL, realm, and admin client. Move admin credentials to a [Secret](#secret-management).                      |
| `pep.openfga`               | yes      | OpenFGA URL for policy enforcement.                                                                                          |

#### Realistic example

A production-shaped values file overriding the in-cluster defaults, wiring sensitive
credentials to a Kubernetes Secret, and pointing Kvasir at an external Keycloak:

```cue
values: {
	applicationConfig: {
		http: {
			"base-uri":      "https://kvasir.example.com/"
			"webclient-uri": "https://kvasir.example.com/_ui/"
		}

		kg: clickhouse: {
			host:     "clickhouse.data.svc.cluster.local"
			port:     8123
			user:     "kvasir"
			password: "${CLICKHOUSE_PASSWORD}"
		}

		messaging: kafka: "bootstrap-servers": "kafka-bootstrap.kafka:9093"

		storage: s3: {
			endpoint:     "https://s3.eu-west-1.amazonaws.com"
			region:       "eu-west-1"
			"access-key": "AKIA..."
		}

		auth: keycloak: {
			url:   "https://auth.example.com"
			realm: "kvasir-prod"
			"admin-client": {
				"grant-type": "client_credentials"
				"client-id":  "kvasir-admin"
				"server-url": "https://auth.example.com"
				"realm":      "master"
			}
		}

		pep: openfga: url: "http://openfga.openfga:8380"
	}

	// sensitive admin-client + s3 credentials sourced from Secrets — see "Secret management"
	secrets: {
		keycloak:   existingSecret: "kvasir-keycloak"
		s3:         existingSecret: "kvasir-s3"
		clickhouse: existingSecret: "kvasir-clickhouse"
	}
}
```

### Secret management

Sensitive credentials should not live in a ConfigMap. The `secrets` block sources
them per integration from Kubernetes Secrets, scrubs them from the generated
`application.yaml`, and injects them as environment variables via `secretKeyRef`.
Quarkus picks them up through [MicroProfile Config env-var
mapping](https://download.eclipse.org/microprofile/microprofile-config-3.1/microprofile-config-spec-3.1.html#default_configsources.env.mapping).

**Sensitive fields have no schema defaults.** Setting an inline value in
`applicationConfig` for a registered sensitive field requires choosing one of the
sources below. A bare inline value with no source is a build/vet-time error — this
prevents secrets from accidentally leaking into the rendered ConfigMap.

For local/dev usage with managed placeholder secrets, see
[`dev-values.cue`](./dev-values.cue) and the
[Dev/local overlay](#devlocal-overlay) section.

#### Supported integrations and fields

| Integration  | Field               | Default Secret key         | Quarkus property                                        | Container env var                                    |
|--------------|---------------------|----------------------------|---------------------------------------------------------|------------------------------------------------------|
| `keycloak`   | `adminUsername`     | `admin-username`           | `kvasir.auth.keycloak.admin-client.username`            | `KVASIR_AUTH_KEYCLOAK_ADMIN_CLIENT_USERNAME`         |
| `keycloak`   | `adminPassword`     | `admin-password`           | `kvasir.auth.keycloak.admin-client.password`            | `KVASIR_AUTH_KEYCLOAK_ADMIN_CLIENT_PASSWORD`         |
| `keycloak`   | `adminClientId`     | `admin-client-id`          | `kvasir.auth.keycloak.admin-client.client-id`           | `KVASIR_AUTH_KEYCLOAK_ADMIN_CLIENT_CLIENT_ID`        |
| `keycloak`   | `adminClientSecret` | `admin-client-secret`      | `kvasir.auth.keycloak.admin-client.client-secret`       | `KVASIR_AUTH_KEYCLOAK_ADMIN_CLIENT_CLIENT_SECRET`    |
| `s3`         | `accessKey`         | `access-key`               | `kvasir.storage.s3.access-key`                          | `KVASIR_STORAGE_S3_ACCESS_KEY`                       |
| `s3`         | `secretKey`         | `secret-key`               | `kvasir.storage.s3.secret-key`                          | `KVASIR_STORAGE_S3_SECRET_KEY`                       |
| `clickhouse` | `user`              | `user`                     | `kvasir.kg.clickhouse.user`                             | `KVASIR_KG_CLICKHOUSE_USER`                          |
| `clickhouse` | `password`          | `password`                 | `kvasir.kg.clickhouse.password`                         | `KVASIR_KG_CLICKHOUSE_PASSWORD`                      |
| `policyEnforcer` | `basicAuthPassword` | `basic-auth-password`  | `kvasir.pod.auth.http-endpoint-policy-enforcer.basic-auth.password` | `KVASIR_POD_AUTH_HTTP_ENDPOINT_POLICY_ENFORCER_BASIC_AUTH_PASSWORD` |
| `policyEnforcer` | `apiKeyValue`       | `api-key-value`        | `kvasir.pod.auth.http-endpoint-policy-enforcer.api-key.key-value`   | `KVASIR_POD_AUTH_HTTP_ENDPOINT_POLICY_ENFORCER_API_KEY_KEY_VALUE`   |

#### Resolution order

Per (integration, field), the highest-priority source wins:

1. **Per-field `ref`** — `secrets.<integration>.fields.<field>.ref` points at any
   Secret/key. Highest priority; useful for cross-Secret overrides.
2. **Integration `existingSecret`** — `secrets.<integration>.existingSecret` plus
   `fields.<field>.key` (defaulted per field, see table). The recommended
   production wiring.
3. **Module-managed Secret** — `secrets.<integration>.manage: true`. The module
   materializes a Secret named `<instance>-<integration>-secret` from the inline
   values present in `applicationConfig`, scrubs them from the ConfigMap, and
   wires env refs. Mutually exclusive with `existingSecret`. Requires at least
   one inline value for the integration.
4. **No source** — leaving all of the above unset is valid only when no inline
   value is set for the field (the field stays unset; Quarkus uses its own
   default if any). An inline value with no source is a vet/build-time error.

#### Schema

| Key                                                        | Type     | Default   | Description                                                                                                    |
|------------------------------------------------------------|----------|-----------|----------------------------------------------------------------------------------------------------------------|
| `secrets.<integration>: existingSecret:`                   | `string` | *unset*   | Name of an existing Secret for this integration. Mutually exclusive with `manage`.                             |
| `secrets.<integration>: manage:`                           | `bool`   | `false`   | Materialize a module-managed Secret from inline values. Mutually exclusive with `existingSecret`.              |
| `secrets.<integration>: fields.<field>: key:`              | `string` | see table | Key within `existingSecret` (or the managed Secret) for this field. Defaults are listed in the table above.    |
| `secrets.<integration>: fields.<field>: ref: name:`        | `string` | *unset*   | Per-field override: name of a different Secret to source this field from.                                      |
| `secrets.<integration>: fields.<field>: ref: key:`         | `string` | *unset*   | Per-field override: key within the referenced Secret.                                                          |

Integrations: `keycloak`, `s3`, `clickhouse`, `policyEnforcer`.

#### Examples

**Single Secret per integration (recommended):**

```cue
values: secrets: {
	keycloak:   existingSecret: "kvasir-keycloak"
	s3:         existingSecret: "kvasir-s3"
	clickhouse: existingSecret: "kvasir-clickhouse"
}
```

Sample Secret for the `keycloak` integration using the default key names:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: kvasir-keycloak
  namespace: kvasir
type: Opaque
stringData:
  admin-username:      "admin"
  admin-password:      "..."
  admin-client-id:     "kvasir-admin"
  admin-client-secret: "..."
```

**Custom upstream key names (External Secrets Operator / OpenBAO):**

When the upstream secret store dictates the key names, override `fields.<field>.key`:

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

**Mixed: integration Secret + per-field cross-Secret refs:**

```cue
values: secrets: {
	keycloak: {
		existingSecret: "kvasir-keycloak"
		fields: {
			adminPassword:     ref: {name: "platform-keycloak-admin", key: "password"}
			adminClientSecret: ref: {name: "platform-keycloak-admin", key: "client-secret"}
		}
	}
	s3:         existingSecret: "kvasir-s3"
	clickhouse: existingSecret: "kvasir-clickhouse"
}
```

**Module-managed Secret from inline values (dev/CI):**

```cue
values: {
	applicationConfig: {
		storage: s3: {
			endpoint:     "http://seaweedfs:8333"
			region:       "us-east-1"
			"access-key": "dev-ak"
			"secret-key": "dev-sk"
		}
	}
	secrets: s3: manage: true
}
```

This emits `Secret/<instance>-s3-secret` with keys `access-key` / `secret-key`,
strips them from the ConfigMap, and wires `KVASIR_STORAGE_S3_ACCESS_KEY` /
`KVASIR_STORAGE_S3_SECRET_KEY` env vars on the Deployment via `secretKeyRef`.

The module never renders the Secret itself for `existingSecret` or `ref` modes —
provide it via your secrets-management flow of choice (sealed-secrets, External
Secrets Operator, SOPS-encrypted manifests, out-of-band `kubectl create secret`,
OpenBAO, etc.).

The framework is extensible: adding a new integration requires one entry in `#Secrets`
and one row in `#SecretFieldRegistry` in the module.

#### Dev/local overlay

The repository ships [`dev-values.cue`](./dev-values.cue) — a ready-to-apply
overlay that uses `manage: true` on every integration with placeholder
credentials. Use it for local clusters (kind, k3d, minikube) where out-of-band
Secret provisioning would be friction:

```shell
timoni -n kvasir apply kvasir oci://<registry>/kvasir \
  --values ./timoni/kvasir/dev-values.cue
```

Or render to inspect the full output (ConfigMap, Deployment, Secrets) without
applying:

```shell
timoni build dev ./timoni/kvasir --namespace kvasir \
  --values ./timoni/kvasir/dev-values.cue --output yaml
```

Do not use `dev-values.cue` in production — the placeholders are well-known and
offer zero security.

#### Bootstrap & dev/test secrets

`applicationConfig.bootstrap.generate-clients[].client-secret` is **rendered inline
into the ConfigMap** and is intentionally not covered by the Secret pipeline above.

The pipeline is map-keyed (one row per integration field in `#SecretFieldRegistry`),
which doesn't model list-shaped sensitive fields like one client-secret per generated
client. Bootstrap is also primarily a dev/test convenience — production deployments
typically provision OIDC clients out-of-band (Terraform, Keycloak admin, GitOps) and
omit `bootstrap` entirely.

For environments where bootstrap is convenient but the inline secret is unacceptable,
overlay the sensitive entries from a separately-managed Secret or ConfigMap:

```yaml
# Mount an additional file that Quarkus loads after application.yaml.
# Quarkus reads every *.yaml in /home/jboss/config/ and later files override earlier ones.
volumes:
  - name: bootstrap-secrets
    secret:
      secretName: kvasir-bootstrap-clients
volumeMounts:
  - name: bootstrap-secrets
    mountPath: /home/jboss/config/application-bootstrap.yaml
    subPath: application-bootstrap.yaml
    readOnly: true
```

with the Secret holding only the sensitive overlay:

```yaml
stringData:
  application-bootstrap.yaml: |
    kvasir:
      bootstrap:
        pods:
          - name: my-pod
            generate-clients:
              - client-id: my-client
                client-secret: "real-secret-from-vault"
```

Until a real consumer needs first-class list-aware secret handling, the schema
carries a CUE comment on `client-secret` flagging the dev/test scope so it shows
up at vet/edit time.

### Probes

Liveness and readiness probes hit the [SmallRye Health](https://quarkus.io/guides/smallrye-health)
endpoints exposed on the management port (`9100`):

| Probe         | Path               | Notes                                                                                                                                             |
|---------------|--------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| `livenessProbe`  | `/q/health/live`  | Process alive check. Fixed schedule (10s initial delay, 30s period).                                                                              |
| `readinessProbe` | `/q/health/ready` | Application readiness. Reports UP only after Kvasir's `Initializer` finishes (Keycloak realm, OpenFGA model sync, Kafka topics, pod bootstrap).  |
| `startupProbe`   | `/q/health/live`  | Bootstrap budget — mirrors liveness endpoint per k8s guidance.                                                                                    |

#### Startup probe

Kvasir's bootstrap can take a while: it provisions the Keycloak realm, syncs the
OpenFGA authorization model, creates Kafka topics, and registers each configured
pod. The startup probe gives the container a generous window for this work before
the liveness probe starts firing and risks a restart loop.

Per [Kubernetes guidance](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/#define-startup-probes),
the default path is `/q/health/live` — the startup probe mirrors the liveness
endpoint with a generous failure budget. Once startup passes, the liveness probe
takes over checking the same signal.

Override `path: "/q/health/ready"` to keep the pod in "starting" state until
Kvasir's `Initializer` finishes (Keycloak realm, OpenFGA model, Kafka topics, pod
bootstrap). Trade-off: the pod won't accept traffic at all until fully bootstrapped,
giving a clean "Kvasir is fully up" signal at the cost of slower rollout signaling.

| Key                                       | Type     | Default             | Description                                                                                  |
|-------------------------------------------|----------|---------------------|----------------------------------------------------------------------------------------------|
| `startupProbe: enabled:`                  | `bool`   | `true`              | Disable to fall back to liveness/readiness only (not recommended).                            |
| `startupProbe: path:`                     | `string` | `"/q/health/live"`  | Health endpoint path on the management port.                                                  |
| `startupProbe: periodSeconds:`            | `int`    | `10`                | Seconds between attempts.                                                                     |
| `startupProbe: failureThreshold:`         | `int`    | `30`                | Consecutive failures tolerated. Default budget: `30 × 10s = 5 min`.                           |
| `startupProbe: timeoutSeconds:`           | `int`    | `1`                 | Per-attempt HTTP timeout.                                                                     |
| `startupProbe: initialDelaySeconds:`      | `int`    | `0`                 | Delay before the first probe attempt.                                                         |

Tighten in fast environments, loosen in environments with slow image pulls or slow
external dependencies (Keycloak/OpenFGA cold starts):

```cue
values: startupProbe: {
	periodSeconds:    5
	failureThreshold: 24
}
```

### Kafka TLS

Configures server-only TLS (not mTLS) for Kafka connections. Kvasir will verify the Kafka broker's certificate against a provided CA but does not present a client certificate.

Disabled by default. When enabled, the module mounts a CA certificate from a Kubernetes Secret into the container and configures the [Quarkus TLS Registry](https://quarkus.io/guides/tls-registry-reference) so the Kafka client trusts the broker.

| Key                              | Type     | Default                  | Description                                          |
|----------------------------------|----------|--------------------------|------------------------------------------------------|
| `kafkaTLS: enabled:`             | `bool`   | `false`                  | Enable TLS for Kafka connections                     |
| `kafkaTLS: "config-name":`       | `string` | `"kafka"`                | Quarkus TLS Registry configuration name              |
| `kafkaTLS: "security-protocol":` | `string` | `"SSL"`                  | Kafka security protocol                              |
| `kafkaTLS: "mount-path":`        | `string` | `"/home/jboss/tls/kafka"`| Container path where the CA cert is mounted          |
| `kafkaTLS: "trust-secret": name:`| `string` | *(required when enabled)*| Kubernetes Secret containing the CA certificate      |
| `kafkaTLS: "trust-secret": key:` | `string` | `"ca.crt"`               | Key within the Secret that holds the CA certificate  |

#### Setting up with cert-manager

**1. Create a self-signed root CA Issuer and its Certificate:**

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
  duration: 87600h # 10 years
  privateKey:
    algorithm: ECDSA
    size: 256
  issuerRef:
    name: kafka-selfsigned
    kind: Issuer
```

cert-manager will create the Secret `kafka-ca-cert` with a `ca.crt` key containing the CA certificate.

**2. Create an Issuer backed by that CA (for issuing broker certs):**

```yaml
apiVersion: cert-manager.io/v1
kind: Issuer
metadata:
  name: kafka-ca
  namespace: kvasir
spec:
  ca:
    secretName: kafka-ca-cert
```

Use this issuer to create the Kafka broker's serving certificate (not covered here — that is configured on the broker side).

**3. Reference the CA Secret in your Timoni values:**

```cue
values: kafkaTLS: {
	enabled: true
	"trust-secret": name: "kafka-ca-cert"
}
```

The CA cert from `kafka-ca-cert` is mounted at `/home/jboss/tls/kafka/ca.crt` inside the container. The generated `application.yaml` will contain:

```yaml
quarkus:
  tls:
    kafka:
      trust-store:
        pem:
          certs: /home/jboss/tls/kafka/ca.crt
kafka:
  security:
    protocol: SSL
  tls-configuration-name: kafka
```

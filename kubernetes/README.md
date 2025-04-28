# Deployment

## Prerequisites

- [Kubectl](https://kubernetes.io/docs/tasks/tools/install-kubectl/)
- [Helm](https://helm.sh/docs/intro/install/)
- [Helmfile](https://helmfile.readthedocs.io/en/latest/)
- [Docker](https://docs.docker.com/get-docker/) (Kind setup)
- [Kind](https://kind.sigs.k8s.io/docs/user/quick-start/) (Kind setup)
- [mkcert](https://github.com/FiloSottile/mkcert) (Kind setup)

  All these prerequisites, except docker, are included in the `devbox.json` file in the project root.
  You can launch a shell with these tools installed using [devbox](https://www.jetify.com/devbox)

```bash
devbox shell
```

## Local setup using Kind

### Using local images

If you want to test the deployment with local images, you can build the images and push them to the local registry
that is connected to the kind cluster.

Building local images under `localhost:5001/kvasir` and pushing them to the local registry:

```bash
./mvnw clean install -DskipTests -D"quarkus.container-image.push=true" \
    -D"quarkus.container-image.registry=localhost:5001" \
    -D"quarkus.container-image.group=kvasir"  \
    -D"quarkus.container-image.insecure=true"
```

### Installion script

The Kvasir kind cluster can be started using a script which will create the following:

- Kind cluster named 'kvasir'
- Local registry connected to the kind cluster
- Locally trusted certificates with `mkcert` in the `.certs` directory, used for local HTTPS (required for UI)
- Deployment of Kvasir, Kvasir UI and all dependencies using Helmfile

All commands assume your current working directory is `kubernetes/`

```bash
./kvasir-kind.sh
```

Create the kind cluster along with a local docker registry:

If a cluster with the same name already exists, the script will error if the cluster does not
match the expected configuration.

You can push images to the local registry making them available to the kind cluster:

```bash
docker push localhost:5001/kvasir/kvasir-ui:latest
docker push localhost:5001/kvasir/monolith:latest
```

Kvasir needs to be hosted with a domain name, for a local setup we use [nip.io](https://nip.io/).
The script will end with the following output, showing the URLS to access Kvasir, UI and Keycloak:

```bash
UPDATED RELEASES:
NAME                  NAMESPACE    CHART                                              VERSION   DURATION
minio                 minio        bitnami/minio                                      16.0.8          1s
kafka-operator        kafka        strimzi/strimzi-kafka-operator                     0.45.0          4s
clickhouse-operator   clickhouse   clickhouse-operator/altinity-clickhouse-operator   0.24.5          4s
traefik               traefik      traefik/traefik                                    35.1.0          4s
keycloak              keycloak     bitnami/keycloak                                   24.6.1       2m16s
clickhouse            clickhouse   ./dependencies/clickhouse/custom-resources         1.0.0           0s
kafka                 kafka        ./dependencies/kafka/custom-resources              1.0.0           1s
kvasir                kvasir       ./kvasir                                           0.1.0           1s
kvasir-ui             kvasir       discover/kvasir-ui                                 0.1.1           1s

Kvasir deployed to https://kvasir-10-10-134-243.nip.io
Kvasir UI: https://kvasir-10-10-134-243.nip.io/_ui
Keycloak Admin UI: https://keycloak-10-10-134-243.nip.io/auth/admin
```

It is possible that the first install will time-out because the bootstrapping of namely Keycloak takes a while.
In that case, just run the command again. It should succeed the second time.

## Deploying to Kubernetes

The entire stack can be setup with helmfile, `helmfile.yaml.gotmpl` holds the configuration for all dependencies, Kvasir and Kvasir UI. Though some initial input is required, see `environments/default.yaml.gotmpl`:

```yaml
kvasirHost: {{ requiredEnv "KVASIR_HOST" }}
keycloakHost: {{ env "KEYCLOAK_HOST" }}
minioRootpassword: {{ env "MINIO_ROOT_PASSWORD" | default "miniopassword" }}
keycloakAdminPassword:
  {{ env "KEYCLOAK_ADMIN_PASSWORD" | default "kcpassword" }}
tlsEnabled: {{ env "TLS_ENABLED" | default "false" }}
proxy: {{ env "PROXY" | default "edge" }}
```

By default the `state-values` are setup through Environment variables. You can also provide
a custom enviroment YAML file or set values on the commandline with `--state-values-set`.

- `KVASIR_HOST` is the domain name used to access Kvasir, this should be a valid domain name whose traffic is routed to the Traefik ingress controller.
- `KEYCLOAK_HOST` is the domain name used to access Keycloak, this should be a valid domain name whose traffic is routed to the Traefik ingress controller.
- `MINIO_ROOT_PASSWORD` is the password used to access Minio, this should be a strong password.
- `KEYCLOAK_ADMIN_PASSWORD` is the password used to access Keycloak, this should be a strong password.
- `TLS_ENABLED` is a boolean value that enables TLS for the Kvasir and Keycloak services. This should be set to `true` if you want to use TLS, otherwise it should be set to `false`. If performing TLS termination at the ingress controller, the necessary certificates should be provided as well.
- `PROXY` is the proxy type used for Kvasir and Keycloak, when you terminate TLS at a Proxy this should be set to `edge`, otherwise it should be set to `none` or `passthrough`. This is only relevant if TLS is enabled.

After setting the necessary environment variables, you can run the following command to deploy Kvasir and all dependencies:

```bash
helmfile sync
```

Alternatively refer to a custom file

```bash
helmfile sync --state-values-file=custom-state-values.yaml
```

Or set values on the commandline:

```bash
helmfile sync --state-values-set kvasirHost=kvasir.example.com \
    --state-values-set keycloakHost=auth.example.com \
    --state-values-set minioRootpassword=miniopassword \
    --state-values-set keycloakAdminPassword=kcpassword \
    --state-values-set tlsEnabled=true \
    --state-values-set proxy=edge
```

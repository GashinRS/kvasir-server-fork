# Getting started

## Running with Docker Compose

The fastest way to get a dev server (with persistent storage) up and running is to use Docker Compose.

1. Make sure you install <a href="https://www.docker.com/products/docker-desktop/#:~:text=Download%20Docker%20Desktop" target="_blank">Docker Desktop</a> and enable **Docker host networking**:

![](docker_host_networking.png)

2. Now clone [this repository](https://gitlab.ilabt.imec.be/kvasir/kvasir-server) and run the following commands:

```bash
cd .deployment/docker-compose
docker compose up -d
```

3. This will automatically create a pod at <a href="http://localhost:8080/alice" target="_blank">http://localhost:8080/alice</a> for you to play with.
The settings for this pod can be modified via the file `application.yaml` in the `kvasir-config` folder.

4. You can view the [Kvasir UI](Kvasir-UI.md) at <a href="http://localhost:8080/_ui/" target="_blank">http://localhost:8080/_ui/</a> to play around with your pod. 

> Be sure to read the [Authentication & Access Control](Access-Control.md) section when you want to develop your own clients.
{style="warning"}



## Running on Kubernetes

Provided here is a short overview of how to deploy Kvasir on Kubernetes. 
See [Deploying to Kubernetes](Deploying-to-Kubernetes.md) for full instructions on how to deploy Kvasir on Kubernetes.

This project includes [devbox](https://github.com/jetify-com/devbox) configuration to manage development environment.

Install devbox:
```sh
curl -fsSL https://get.jetpack.io/devbox | bash
```

### Local setup using Kind
You can run Kvasir on a local Kubernetes cluster with [Kind](https://kind.sigs.k8s.io/docs/user/quick-start/) by using the provided helper script.

Then run the Kvasir local-cluster setup script:
```sh
devbox run local-cluster
```

Or run the script directly:
```sh
devbox shell
kubernetes/kvasir-kind.sh
```

The script will output the URLs to access Kvasir, UI and Keycloak after the setup is complete. For example:

```sh
Kvasir deployed to https://kvasir-10-10-134-243.nip.io
Kvasir UI: https://kvasir-10-10-134-243.nip.io/_ui
Keycloak Admin UI: https://keycloak-10-10-134-243.nip.io/auth/admin
````

### Hosted Kubernetes cluster

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

## Running in dev mode

If you want to experiment with modifications to the code, you can run the server in dev mode via the Maven wrapper.
This requires you to have Java JDK 21 installed.

```bash
docker compose up -d
./mvnw compile quarkus:dev
```

## Issues

The project is still in a very early stage of development, so there are many issues and missing features. If you find
any, please report them in the [Issues](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/-/issues) section.

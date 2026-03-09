## Running with Docker or Podman

This project provides a modular Compose setup that works with both Docker and Podman for different scenarios.

### Default: Full Stack (Kvasir + Dev Services)

This is the recommended setup for getting started. It runs the Kvasir application alongside all its backing services (ClickHouse, Kafka, SeaweedFS, Keycloak, OpenFGA, PostgreSQL).

```bash
docker compose up
```

A demo pod will be available at `http://localhost:8080/alice`.

### Development: Dev Services Only

For local development, the backing services lifecycle is managed by Maven. From the project root:

```bash
# Start services
./mvnw compile

# Stop and remove services
./mvnw clean
```

Skip compose management with `-Dcompose.skip=true`. Switch container engine with `-Dcompose.executable=podman` (default: `docker`).

To manage dev services manually:

```bash
docker compose -f compose.devservices.yml up
```

### SELinux (Fedora / RHEL)

On SELinux-enforcing systems, bind mounts require an extra label so the container process can read them. Create a `compose/.env` file:

```
SELINUX_SUFFIX=:z,ro
```

This is picked up automatically by both `docker compose` and `podman compose`. All other platforms (Ubuntu, macOS, Windows, CI) leave this unset — the `${SELINUX_SUFFIX:-}` pattern in the compose files expands to nothing safely.

See `compose/.env.example` for a template.

### CI: Dev Services for Continuous Integration

In GitLab CI with Docker-in-Docker, containers are **not** reachable via `localhost`. The Docker daemon itself is accessible via the hostname `docker`, so all service addresses must use that hostname instead.

A single variable, `COMPOSE_HOSTNAME`, controls all host references:

| Variable           | Default     | CI value | Purpose                                                             |
| ------------------ | ----------- | -------- | ------------------------------------------------------------------- |
| `COMPOSE_HOSTNAME` | `localhost` | `docker` | Host used by Kafka advertised listeners and SeaweedFS `externalUrl` |

Setting `COMPOSE_HOSTNAME=docker` in CI ensures:

- **Kafka** advertises `PLAINTEXT://docker:9092` so brokers are reachable from the test JVM
- **SeaweedFS** uses `docker:8333` as its external host for AWS Signature V4 verification (required because the Vert.x proxy adds an `X-Forwarded-Host` header that SeaweedFS would otherwise use, causing signature mismatches)

In addition, the `KVASIR_*` application variables are set explicitly in CI so the test JVM connects to the `docker` hostname rather than `localhost`:

```yaml
variables:
  COMPOSE_HOSTNAME: docker
  KVASIR_KG_CLICKHOUSE_HOST: docker
  KVASIR_MESSAGING_KAFKA_BOOTSTRAP_SERVERS: docker:9092
  KVASIR_STORAGE_S3_ENDPOINT: http://docker:8333
  KVASIR_AUTH_KEYCLOAK_URL: http://docker:8280
  KVASIR_PEP_OPENFGA_URL: http://docker:8380
```

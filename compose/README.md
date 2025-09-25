## Running with Docker or Podman

This project provides a modular Compose setup that works with both Docker and Podman for different scenarios.

### Default: Full Stack (Kvasir + Dev Services)

This is the recommended setup for getting started. It runs the Kvasir application and all its backing services.

**Docker:**

```bash
docker compose up
```

**Podman:**
If you are on a system with SELinux enabled (like Fedora), you need to configure Compose to add the `:Z` flag to volume mounts. You can do this by creating a file named `.env` in this directory (`compose/.env`) with the following content:

```
SELINUX_MOUNT_FLAG=:Z
```

`podman compose` will automatically use this environment variable. Then you can run the standard commands:

- **Full Stack:**

  ```bash
  podman compose up
  ```

### Development: Dev Services Only

For development or testing, you can run only the backing services (databases, Kafka, etc.).

**Docker:**

```bash
docker compose -f compose.devservices.yml up
```

**Podman:**

```bash
podman compose -f compose.devservices.yml up
```

### CI: Dev Services for Continuous Integration

On CI compose ran services can't be reached via localhost, instead
the alias of the service must be used. In our case this is `docker`.

Setting the `KAFKA_ADVERTISED_LISTENERS` environment variable to
`PLAINTEXT://docker:9092` ensures that Kafka advertises the correct hostname
that other services can use to connect to it.

The compose file has a `KAFKA_ADVERTISED_HOSTNAME` variable (default `localhost`)
which is used to control the ADVERTISED_LISTENERS setting. Setting this to
the alias of the service (`docker`) ensures that Kafka is reachable

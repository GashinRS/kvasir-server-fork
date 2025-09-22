## Running with Docker or Podman

This project provides a Compose setup that works with both Docker and Podman.

This set up uses the OpenFGA based authorization flavour of Kvasir.

### Docker
If you’re running Docker (Linux, Docker Desktop on Mac/Windows):
```bash
docker compose up
```

## Running with Podman (Fedora/RHEL/CentOS)

This project’s Compose setup works with both Docker and Podman.  
If you’re running on Fedora, RHEL, or CentOS with **SELinux enforcing**, you need to add the `:Z` option to bind-mounted volumes. Without it, you’ll see `Permission denied` errors inside containers.

We provide an override file for Podman users: [`docker-compose.podman.override.yml`](./docker-compose.podman.override.yml).

Run Compose with both files:

```bash
podman-compose -f docker-compose.yml -f docker-compose.podman.override.yml up
```


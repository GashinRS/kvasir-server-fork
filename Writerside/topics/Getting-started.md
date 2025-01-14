# Getting started

## Running with Docker Compose

The fastest way to get a dev server (with persistent storage) up and running is to use Docker Compose.

Clone [this repository](https://gitlab.ilabt.imec.be/kvasir/kvasir-server) and run the following commands:

```bash
cd .deployment/docker-compose
docker compose up -d
```

This will automatically create a pod at `http://localhost:8080/alice` for you to play with.
The settings for this pod can be modified via the file `application.yaml` in the `kvasir-config` folder.

> Be sure to read the [Authentication](Authentication.md) section when you want to develop your own clients.
{style="warning"}



## Running on Kubernetes
(A guide will be added in the future)

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

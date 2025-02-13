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

4. You can view the [Kvasir UI](Kvasir-UI.md) at <a href="http://localhost:8081" target="_blank">http://localhost:8081</a> to play around with your pod. 

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

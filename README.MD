# Kvasir Server

Technical preview of the Kvasir Data Storage Server

## Documentation

Visit the documentation pages at
[https://kvasir.pages.ilabt.imec.be/kvasir-server/](https://kvasir.pages.ilabt.imec.be/kvasir-server/).

## Quick Start

This guide provides basic instructions for running the server using Compose. For more advanced scenarios, including Podman support, please see the [compose/README.md](compose/README.md) file.

### Prerequisites

- Docker Desktop with `Enable host networking` checked in `Settings > Resources > Network`.

### Run the Full Stack

To start the Kvasir server along with all its backing services, run the following commands:

```bash
cd compose
docker compose up -d
```

This will create a demo pod at `http://localhost:8080/alice`.

## Development and Testing

If you want to modify the code, you can run the application in development mode.

1.  **Start backing services:**
    From the project root, run:
    ```bash
    docker compose -f compose/compose.devservices.yml up -d
    ```

2.  **Run the application in dev mode:**
    This requires Java JDK 21.
    ```bash
    ./mvnw compile quarkus:dev
    ```

## Issues

The project is still in a very early stage of development, so there are many
issues and missing features. If you find
any, please report them in the
[Issues](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/-/issues) section.

## Future Work

* Decentralized authentication and authorization (waiting to integrate with the
  Knows UMA Policy Server implementation)
* Complete the GraphQL query model with more advanced features
* Complete the subgraph (Slices) prototype implementation
* Provide a decent cursor-based pagination mechanism
  * E.g. via Link headers for REST APIs (Changes API, Slices API)
  * E.g. via Relay-style pagination for GraphQL APIs
* Optimize and harden the current prototype

## Developer tips

### Git strategy and release flow

We adhere to a modified Git Flow strategy, see our [RELEASES.md](RELEASES.md)
for details.

### Locally create and render OpenApi
**Prerequisites:** install a recent nodejs version (comes with npm)

```bash
npx @redocly/cli build-docs -o ./_openapi/index.html \
  ./services/monolith/target/openapi/openapi.yaml \
  --theme.openapi.hideHostname=true \
  --theme.openapi.pathInMiddlePanel=true
cd _openapi
npx http-server -y
```

Open http://localhost:8080 (or another port if that one was taken, see output
console above)

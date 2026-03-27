# Kvasir Server

Technical preview of the Kvasir Data Storage Server.

> Kvasir is a cloud-native, microservice-based platform implementing a
> scalable data broker in the context of Solid and related Linked Data
> applications. The name Kvasir refers to a figure in Norse mythology known
> for spreading knowledge and associated with peacemaking — symbolising the
> platform's mission: storing data to generate knowledge and bridging the
> gap between a decentralized Web and modern Cloud development.

## Documentation

Visit the documentation pages at
<https://kvasir.pages.ilabt.imec.be/kvasir-server/>.

## Roadmap & Issues

Planned work and upcoming features are tracked on the
[Roadmap wiki][roadmap].
Found a bug or have a feature request? Open an issue in the
[issue tracker][issues].

[roadmap]: https://gitlab.ilabt.imec.be/kvasir/kvasir-server/-/wikis/roadmap
[issues]: https://gitlab.ilabt.imec.be/kvasir/kvasir-server/-/issues

## Quick Start

This guide covers running the server using Compose. For advanced scenarios
including Podman support, see [compose/README.md](compose/README.md).

### Prerequisites

Required for running and developing Kvasir:

| Tool | Min version | Install |
|------|-------------|---------|
| **JDK** | 25 | [Adoptium](https://adoptium.net) · [SDKMAN](https://sdkman.io) |
| **Docker** | any recent (Compose v2 plugin) | [Docker Desktop](https://docs.docker.com/get-started/get-docker/) |
| **just** | 1.46 | [packages](https://github.com/casey/just#packages) |

Optional — only needed for specific recipe groups:

| Tool | Min version | Install | Needed for |
|------|-------------|---------|------------|
| **hurl** | 7.0 | [hurl.dev](https://hurl.dev/docs/installation.html) | `api-test*` |
| **Node.js** | 22 | [nodejs.org](https://nodejs.org) | `ui-*`, `openapi` |

Maven is bundled via `./mvnw` — no separate installation required.

> **Docker Desktop users:** enable host networking under
> `Settings > Resources > Network → Enable host networking`.
> This is required for the Kvasir container to reach backing services.

Run `just prereqs` to verify your setup:

```bash
just prereqs
```

### Run the Full Stack

```bash
just compose-up
```

This starts Kvasir and all backing services. A demo pod will be available
at `http://localhost:8080/alice`.

## Development

### Dev mode

Backing services start automatically via Maven on first run (on 28xxx
ports). Kvasir itself runs on port 28080 in dev mode.

```bash
just dev            # start with hot reload
just dev-no-ui      # skip UI build for faster startup
just clean          # stop backing services and clean build output
```

### Tests

```bash
just test                                    # full unit test suite
just test-service kg-query-api               # single service + dependencies
just test-class QueryApiTest                 # single test class
just test-method QueryApiTest#testGetPerson  # single test method
```

### API tests

Requires a running stack. Copy `api-tests/hurl.env.example` →
`api-tests/hurl.env` first.

```bash
just api-test              # against full compose stack (8080)
just api-test-dev          # against dev mode (28080)
just api-test --report     # also generate HTML report
just serve-api-report      # serve latest report in browser
```

### OpenAPI docs

Requires the monolith to be packaged first (`just build-fast`).

```bash
just openapi
```

## Contributing

See [RELEASE.md](RELEASE.md) for our branching strategy and release flow.
All commits must follow
[Conventional Commits](https://www.conventionalcommits.org/) format.

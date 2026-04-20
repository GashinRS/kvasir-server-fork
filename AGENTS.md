# AGENTS.md — AI Agent Coding Guide

**Generated:** 2026-04-08
**Commit:** fbd3741b
**Branch:** feat/kubernetes-cue

---

## Overview

Kvasir is a Solid-compatible knowledge graph server built on Kotlin/Quarkus. It exposes
RDF data via GraphQL, manages pod-scoped storage (S3/ClickHouse), and enforces access
control via OpenFGA. CQRS architecture: writes flow through Kafka, reads go through
GraphQL resolvers backed by ClickHouse.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.3 (JVM target Java 25) |
| Framework | Quarkus 3.x (reactive, CDI, RESTEasy Reactive) |
| Build | Maven multi-module (`./mvnw`), task runner (`just`) |
| Reactive | SmallRye Mutiny (`Uni<T>`, `Multi<T>`) |
| Storage | ClickHouse (KG), S3/SeaweedFS (blobs) |
| Messaging | Apache Kafka (MicroProfile Reactive Messaging) |
| Auth | Keycloak (OIDC) + OpenFGA (policy) |
| Testing | JUnit 5 + `@QuarkusTest` + RestAssured |
| Frontend | Angular 21 + pnpm (embedded in `services/ui-service`) |
| Deployment | CUE manifests, Timoni modules, Helm charts, Docker Compose |
| CI | GitLab CI (`.ci/*.yml`) |

---

## Build & Run Commands

```bash
# Prerequisites check
just prereqs

# Full build (skips tests, no Docker required)
./mvnw -DskipTests package -Pservices -Dcompose.skip=true -Dmodel-tx.skip=true

# Fast parallel build (services only)
just build-fast

# Run all unit tests (Maven manages devservices automatically)
just test
# equivalent: ./mvnw test -Pservices -Dmodel-tx.skip=true

# Dev mode (hot reload, starts backing services via Docker Compose)
just dev
# equivalent: ./mvnw -T 0.5C compile quarkus:dev

# Dev mode without UI build overhead
just dev-no-ui

# Start backing services only (28xxx ports, for use with just dev)
just dev-services-up

# Full stack via Docker Compose (standard ports)
just compose-up

# Stop and clean
./mvnw clean
```

### Running a Single Test

```bash
# Single test class
just test-class QueryApiTest

# Single test method
just test-method QueryApiTest#testGetPerson

# Module-scoped (manual Maven)
./mvnw test -pl services/kg-query-api -Dtest=QueryApiTest \
  -Dcompose.skip=true -Dmodel-tx.skip=true
```

### Important Maven Flags

| Flag | Purpose |
|---|---|
| `-Pservices` | Required for test runs; without it Maven starts from `definitions` (no tests) |
| `-Dcompose.skip=true` | Prevents Maven from starting/stopping devservices |
| `-Dmodel-tx.skip=true` | Skips OpenFGA DSL→JSON model transform (Docker dependency) |
| `-Dsurefire.failIfNoSpecifiedTests=false` | Needed with `-Dtest=` when targeting specific class across modules |
| `-Dui-service.phase=none` | Skips frontend build in dev mode |

### Ports

| Context | Kvasir | Keycloak | ClickHouse | Kafka | S3 | OpenFGA |
|---|---|---|---|---|---|---|
| Full compose stack | 8080 | 8280 | 8123 | 9092 | 8333 | 8380 |
| Dev services (28xxx) | — | 28280 | 28123 | 29092 | 28333 | 28380 |
| Dev mode Kvasir | 28080 | — | — | — | — | — |

---

## Repository Layout

```
libs/
  definitions/          # Core interfaces, data classes, config mappings, RDF vocab
  utils/                # Runtime helpers (HTTP, RDF, GraphQL, cursors, pod setup)
  test-utils/           # AbstractPodTest, TestDataGenerator, TestHelpers, SSEClient
  clickhouse-test-utils/ # ClickHouse-specific test utilities
plugins/
  clickhouse-knowledge-graph/   # ClickHouse KG implementation + GraphQL resolvers
  kafka-messaging/              # Kafka channel bindings + emitter providers
  openfga-pep/                  # OpenFGA policy enforcement point
  openfga-policy-agent/         # OpenFGA policy agent
  keycloak-policy-agent/        # Keycloak policy agent
  a4ds-policy-agent/            # A4DS policy agent
  uma-policy-agent/             # UMA policy agent
  s3-storage/                   # S3 blob storage implementation
  s3-reference-loader/          # S3 reference loader for change pipeline
  default-knowledge-graph/      # Default in-memory KG + schema generation
  common-http-extensions/       # Shared HTTP extensions (OpenAPI filters, etc.)
services/
  monolith/             # Main deployable — aggregates all service modules
  kg-query-api/         # GraphQL query REST API
  kg-changes-api/       # Change request ingestion API
  kg-change-processor/  # Change processing Kafka consumers
  kg-stream-api/        # SSE streaming API (Kafka → SSE)
  solid-api/            # Solid-compatible HTTP API (Vert.x routes)
  storage-api/          # S3 proxy REST API
  pod-management-api/   # Pod lifecycle management
  simple-rdf-ingester/  # RDF storage mutation listener (S3 → KG)
  init-service/         # Bootstrap/initialization service
  api-services/         # Shared API service utilities
  ui-service/           # Frontend (Angular/pnpm in src/main/frontend)
compose/                # Docker Compose files (devservices + full stack)
api-tests/              # Hurl-based integration tests
kubernetes-revamp/cue/  # CUE-based Kubernetes manifests (preferred)
timoni/                 # Timoni OCI module for Kubernetes deployment
kubernetes/             # Legacy Helm charts (deprecated in favor of CUE)
Writerside/             # Product documentation (JetBrains Writerside)
```

---

## Architecture

**Three-layer layout:** `libs/` → `plugins/` → `services/`

- **libs/** — shared contracts and utilities. Define interfaces here.
- **plugins/** — interchangeable backends selected at runtime via CDI. Implement interfaces here.
- **services/** — Quarkus microservices. `monolith` bundles all for single-process deployment.

**Primary extension points** (interface in `libs/definitions`, implementations in `plugins/`):
- `KnowledgeGraph` — query/change processing
- `StorageLifecycleManager` — storage initialization/cleanup
- `PolicyEnforcementPoint` — auth policy decisions
- `AuthHandler` / `AuthLifecycleManager` — authentication lifecycle

**Write path (CQRS):**
```
Changes API / Storage API → Kafka → Change Processor → ClickHouse / S3
```

**Read path:**
```
Query API (GraphQL POST) → KnowledgeGraph interface → ClickHouse
```

**HTTP routing — two patterns coexist:**
- **JAX-RS resources** (`@Path`): Pod management, Query API, Changes API, OpenFGA endpoints
- **Vert.x route handlers** (`@Observes Router`): Solid API, Storage API (S3 proxy), UI SPA handler

**Kafka integration — two patterns coexist:**
- **MicroProfile Reactive Messaging** (`@Incoming`/`MutinyEmitter`): Change processors, RDF ingester
- **Vert.x Kafka client** (direct consumer): Stream API SSE endpoints

---

## Code Style Guidelines

### Language & Formatting

- **Kotlin only** in `src/main/kotlin` and `src/test/kotlin`.
- Standard [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
- 4-space indentation, 120-character line length (soft), trailing lambdas outside parentheses.
- `when` expressions preferred over `if/else if` chains.

### Naming

| Element | Convention | Example |
|---|---|---|
| Classes / Interfaces | `PascalCase` | `KnowledgeGraph`, `SolidHttpRoutes` |
| Functions / properties | `camelCase` | `handlePatch()`, `podId` |
| Constants (top-level) | `SCREAMING_SNAKE_CASE` | `QUERY_API_PATH`, `DEFAULT_PAGE_SIZE` |
| Packages | `lowercase.dot.separated` | `kvasir.definitions.kg.changes` |
| Test classes | `FooTest` or `TestFoo` | `QueryApiTest`, `TestClickhouseChangeLog` |
| Config mapping interfaces | `FooConfig` | `HttpConfig`, `PodConfig` |
| Enum values | `SCREAMING_SNAKE_CASE` | `QUEUED`, `INTERNAL_ERROR` |

### Package Naming

```
kvasir.definitions.*             # libs/definitions
kvasir.utils.*                   # libs/utils
kvasir.plugins.kg.clickhouse.*   # plugins/clickhouse-knowledge-graph
kvasir.plugins.policyagent.*     # plugins/openfga-pep
kvasir.services.api.kg.query.*   # services/kg-query-api
```

### Imports

- Explicit imports; avoid wildcards except for `kvasir.definitions.rdf.*` (many vocab constants).
- Jakarta EE uses `jakarta.*` (not `javax.*`).
- Order: stdlib → third-party → internal `kvasir.*` (IDE-managed).

### Types & Nullability

- Prefer non-nullable types; `?` only when `null` is genuinely meaningful.
- `Optional<T>` only in `@ConfigMapping` interfaces (MicroProfile convention).
- Prefer `data class` for DTOs/domain objects with `init { require(...) }` for invariants.
- `@GenerateNoArgConstructor` on data classes that need no-arg constructors for Jackson/Quarkus.

### Reactive (Mutiny)

- All async → `Uni<T>` (single) or `Multi<T>` (stream). **Never block** in production code.
- Bridge functions in `kvasir.definitions.reactive.MutinyUtils`: `toUni()`, `toMulti()`, `skipToLast()`, `notNullOrFail()`, `conditionalUni()`.
- Chain: `.chain {}`, `.map {}`, `.onItem().transformToUni {}`.
- Failures: `.onFailure().recoverWithUni { }`.
- Blocking calls only in tests (`.await().indefinitely()`).

### CDI

- Constructor injection for mandatory deps (preferred).
- `@Inject lateinit var` for field injection in test classes.
- `@Singleton` for stateless beans.
- `Instance<T>` for optional deps (check `.isResolvable` before `.get()`).
- `@All` + `MutableList<T>` for all implementations of an interface.

### REST API

- JAX-RS resources under `.../api/` — no `@ApplicationScoped` needed (Quarkus handles lifecycle).
- `@Path("")` at class level + `@Path("{podId}/...")` at method level for pod-scoped resources.
- Return `Uni<T>` from resource methods.
- `@Operation` + `@Tag` on all public endpoints.
- `@ServerExceptionMapper` in dedicated `ExceptionMappers` for centralized error handling.

### Error Handling

- HTTP errors: `jakarta.ws.rs.NotFoundException` / `BadRequestException`.
- Pipeline errors: typed exceptions extending `ChangePipelineException`.
- Log unexpected: `Log.error("...", throwable)` before re-throw.
- Vert.x handlers: `.onFailure().recoverWithUni` + explicit HTTP status code.

### Configuration

- `@ConfigMapping(prefix = "kvasir.xxx")` interfaces for typed config.
- Config in `src/main/resources/application.properties` or `application.yaml`.
- Test overrides in `src/test/resources/application.properties`.
- Property names use `kebab-case`.

### Custom Annotations

- `@GenerateNoArgConstructor` — generates no-arg constructor (Kotlin compiler plugin `no-arg`).
- `@Persistent(storageLevel, collectionName)` — marks entities stored in DB.
- Kotlin compiler plugin `all-open` opens classes annotated with `@Path`, `@ApplicationScoped`,
  `@RequestScoped`, `@QuarkusTest`.

---

## Testing Guidelines

- All tests: `@QuarkusTest` (boots real Quarkus). No `@QuarkusIntegrationTest` in this project.
- HTTP tests: RestAssured `given().when().then()` DSL.
- Lifecycle: `@TestInstance(Lifecycle.PER_CLASS)` + `@BeforeAll`/`@AfterAll`.
- Base class: extend `AbstractPodTest` → auto pod create/teardown, provides `podUri` / `podName`.
- Auth: `@TestSecurity(user = "alice")` for mock identity. `TestHelpers.getTokenForClient()` for real OIDC.
- Data: `TestDataGenerator.generatePersonData()` for sample JSON-LD payloads.
- Async wait: `TestHelpers.waitForChangeRequest()` / `waitForCondition()` — Mutiny polling, not Thread.sleep.
- Mocking: CDI bean replacement in test sources (e.g., `MockPodStoreFactory`), not Mockito.
- Docker: devservices via Docker Compose (not Testcontainers). Maven starts them unless `-Dcompose.skip=true`.
- Test config: per-module `src/test/resources/application.properties` typically sets `openfga.pep.enabled=false`.

---

## Branching & Commit Conventions

- **`main`** is the primary long-lived branch.
- Feature branches: branch from `main`, open MR, squash merge using MR title.
- **MR titles must follow [Conventional Commits](https://www.conventionalcommits.org/)**.
- `feat:` / `fix:` → changelog + version bump. `ci:` / `chore:` → excluded.
- Breaking changes: `feat!:` → bumps MINOR while in `0.x`. For non-trivial
  breaks, include a `BREAKING CHANGE:` footer in the commit body with a
  migration description (see `RELEASE.md` § Breaking Changes).
- **Never squash the release PR** (branch `releasaurus/main/vX.Y.Z`) — merge normally.
- Releases automated by Releasaurus (`releasaurus.toml`).

---

## Where to Look

| Task | Location | Notes |
|------|----------|-------|
| Add/change KG interface | `libs/definitions/.../kg/KnowledgeGraph.kt` | Core contract |
| Add RDF vocab constants | `libs/definitions/.../rdf/KvasirVocab.kt` | Also update `JsonLdHelper.defaultContext` |
| Add Mutiny helper | `libs/definitions/.../reactive/MutinyUtils.kt` | Used everywhere |
| Add GraphQL extension fn | `libs/utils/.../graphql/GraphQLExtensions.kt` | Used by resolvers |
| Add config mapping | `libs/definitions/.../config/KvasirConfig.kt` | Register in module's `application.properties` |
| Implement new KG backend | `plugins/` — new module | Implement `KnowledgeGraph` interface |
| Add REST endpoint | `services/*/src/main/kotlin/.../api/` | JAX-RS resource |
| Add Vert.x route | `services/solid-api/.../SolidHttpRoutes.kt` | `@Observes Router` |
| Add Kafka consumer | `services/kg-change-processor/.../ChangeProcessors.kt` | `@Incoming` |
| See Kafka channel map | `plugins/kafka-messaging/.../ChannelInitializer.kt` | All topic bindings |
| Add test | Extend `AbstractPodTest` in service module | `libs/test-utils` |
| Change pod setup | `libs/utils/.../pod/PodSetupHelper.kt` | Used by init + tests |
| Modify deployment | `kubernetes-revamp/cue/config.cue` | Single source of truth for CUE manifests |
| Frontend changes | `services/ui-service/src/main/frontend/` | Angular app, see `services/ui-service/AGENTS.md` |

---

## Anti-Patterns (THIS PROJECT)

- **Never block** with `.await().indefinitely()` in production code — only in tests.
- **Never alias GraphQL ID fields** — messes up internal logic (`GraphQLExtensions.kt`).
- **Never configure non-natural-order store** in `ChangeTopologyProducer` — keys must maintain natural order.
- **Never squash release PRs** — breaks Releasaurus tag anchoring.
- **Never commit** `compose/.env` or `api-tests/hurl.env` — git-ignored, contains local config.
- **Do not edit child module `<version>`** — all inherit from root `pom.xml` `<revision>`.
- **Do not skip DPoP ATH checks** in production — `skip-dpop-ath-check` is for legacy compat only.
- Delete statements execute before inserts in change requests — ordering matters.

---

## Key Gotchas

- **Java 25 required** (`maven.compiler.release=25`). Not an LTS — get JDK 25 from Adoptium.
- Maven starts Docker Compose on `./mvnw compile` unless `-Dcompose.skip=true`.
- Build-time OpenFGA model transform runs a Docker container — skip with `-Dmodel-tx.skip=true`.
- **No Dockerfiles** — container images built via Quarkus Jib integration.
- `monolith` is the only deployable artifact — all services are Maven deps assembled there.
- `releasaurus.toml` drives releases; `cliff.toml` is for local changelog preview only.
- Docker images pushed only on MR/tag pipelines, never on plain `main` pushes.
- Two HTTP routing patterns coexist (JAX-RS + Vert.x). Vert.x routes bypass JAX-RS filters.
- Two Kafka patterns coexist (MP Reactive Messaging + direct Vert.x client).
- Existing AGENTS.md in subdirectories: `libs/definitions/`, `plugins/clickhouse-knowledge-graph/`, `services/ui-service/`.

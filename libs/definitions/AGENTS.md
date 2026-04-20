# libs/definitions — Core Contracts & Shared Types

Central module defining all interfaces, data classes, config mappings, and RDF vocabulary
constants. Every other module depends on this. Changes here cascade everywhere.

---

## Structure

```
src/main/kotlin/kvasir/definitions/
├── kg/                  # KnowledgeGraph interface + domain types
│   ├── KnowledgeGraph.kt    # Primary KG contract (process, query, rollback)
│   ├── Pod.kt               # Pod entity
│   ├── changes/             # ChangeRequest, ProcessedChange, Assertion, Reference
│   ├── exceptions/          # ChangePipelineException hierarchy
│   ├── graphql/             # KvasirTypes, KvasirEnums, KvasirDirectives, Constants
│   └── slices/              # Slice abstractions
├── rdf/                 # RDF vocabulary & JSON-LD helpers
│   ├── KvasirVocab.kt       # Kvasir-specific terms, named graphs, default context
│   ├── RDFVocab.kt          # rdf:type, rdf:langString
│   ├── RDFSVocab.kt         # rdfs:Resource, rdfs:label
│   ├── XSDVocab.kt          # XSD datatypes (used for GraphQL→RDF scalar mapping)
│   ├── SAREFVocab.kt        # SAREF ontology terms
│   ├── JsonLdHelper.kt      # ObjectMapper, encode/decode, compact/expand helpers
│   ├── RDFStatement.kt      # Core RDF triple/quad data class
│   └── RDFMediaTypes.kt     # Content-type constants
├── config/              # @ConfigMapping interfaces
│   └── KvasirConfig.kt      # HttpConfig, PodConfig, BootstrapConfig, auth configs
├── reactive/            # Mutiny extension functions
│   └── MutinyUtils.kt       # toUni(), toMulti(), skipToLast(), notNullOrFail()
├── storage/             # Storage lifecycle contract
│   ├── StorageLifecycleManager.kt
│   └── StorageEvent.kt
├── auth/                # Auth contracts
│   ├── AuthHandler.kt       # Vert.x auth handler interface
│   ├── AuthLifecycleManager.kt
│   └── AuthConstants.kt
├── persistence/         # Repository abstractions
│   └── Repositories.kt
├── annotations/         # Custom annotations
│   ├── GenerateNoArgConstructor.kt
│   └── Persistent.kt
└── openapi/             # OpenAPI doc tags & constants
```

---

## Key Interfaces

| Interface | Purpose | Implementors |
|---|---|---|
| `KnowledgeGraph` | Query + change processing | `DefaultKnowledgeGraph`, ClickHouse plugin |
| `StorageLifecycleManager` | Pod storage init/cleanup | S3 storage plugin |
| `AuthHandler` | Vert.x auth route handler | Policy agent plugins |
| `AuthLifecycleManager` | Auth provider lifecycle | Keycloak, OpenFGA plugins |
| `TypeRegistry` | GraphQL type introspection | KG plugins |

---

## Conventions

- `@ConfigMapping` interfaces use `Optional<T>` for optional config values (MicroProfile convention).
- `PodConfigOverride` uses `@JsonInclude(NON_ABSENT)` — absent Optional = follow platform default, null = explicit override.
- Data classes requiring Jackson deserialization: annotate with `@GenerateNoArgConstructor`.
- `@Persistent(storageLevel, collectionName)` marks entities for persistence discovery.
- RDF vocab objects are `object` singletons with `const val` properties containing full IRIs.

---

## Anti-Patterns

- Do not add blocking code in this module — everything is reactive (`Uni`/`Multi`).
- Do not use `Optional<T>` outside `@ConfigMapping` interfaces — use nullable Kotlin types.
- Do not add runtime logic here — this module is for contracts and types only.
- Changing `KvasirVocab.context` affects JSON-LD output across all APIs.
- Changing `KnowledgeGraph` interface affects all KG plugin implementations.

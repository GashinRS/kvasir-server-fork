# plugins/clickhouse-knowledge-graph — ClickHouse KG Implementation

Implements `KnowledgeGraph` and `ChangeRecordBackend` interfaces against ClickHouse.
Handles GraphQL query resolution via SQL CTE builders and custom datafetchers.

---

## Structure

```
src/main/kotlin/kvasir/plugins/kg/clickhouse/
├── graphql/
│   ├── CHDataFetcher.kt               # Top-level GraphQL datafetcher (entry point)
│   ├── CHGraphQLSchemaHelper.kt        # Schema wiring helpers
│   └── resolver/
│       ├── CTEBuilders.kt             # SQL CTE generation (pagination, filtering)
│       ├── Utils.kt                   # Resolver utilities (@optional vs @mustExist)
│       └── nodeimpl/
│           ├── CompositeNode.kt       # Interface/union type resolution
│           ├── RDFNode.kt             # RDF resource node (synthetic fields)
│           └── ScalarValueNode.kt     # Scalar value resolution + SQL rewriting
├── CHChangeRecordBackend.kt           # ChangeRecordBackend impl (write path)
├── CHRepository.kt                    # ClickHouse persistence layer
└── ClickhouseLifecycleManager.kt      # Pod lifecycle (create/drop tables)

src/main/resources/
└── templates/
    └── init-pod-db.sql                # Qute SQL template for pod DB init

src/test/kotlin/
└── TestClickhousePodStore.kt          # Integration test (uses real ClickHouse via devservices)
```

---

## Where to Look

| Task | File |
|------|------|
| Fix GraphQL query SQL | `graphql/resolver/CTEBuilders.kt` |
| Fix scalar value resolution | `graphql/resolver/nodeimpl/ScalarValueNode.kt` |
| Fix interface/union handling | `graphql/resolver/nodeimpl/CompositeNode.kt` |
| Add new KG column/field | `CHRepository.kt` + `init-pod-db.sql` |
| Fix change write path | `CHChangeRecordBackend.kt` |
| Debug datafetcher wiring | `CHDataFetcher.kt` |

---

## Conventions

- SQL is generated dynamically via CTE builders — not raw string concatenation.
- `CTEBuilders.kt` always includes base projections (subject, predicate, object, graph, timestamp).
- Node implementations follow a tree: `CompositeNode` → `RDFNode` → `ScalarValueNode`.
- `@optional` vs `@mustExist` directives control join semantics — throwing error if misused.
- Tests require ClickHouse devservices running. Use `ClickhouseLifecycleManager.initialize()` in test setup.

---

## Anti-Patterns

- Do not use raw SQL strings — use CTE builder patterns.
- Do not change init-pod-db.sql without verifying existing pod table migration.
- Scalar SQL rewriting in `ScalarValueNode` is complex — test thoroughly before modifying.

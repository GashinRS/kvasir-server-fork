# Querying

<show-structure depth="2"/>

The standard query mechanism for the Pod KG uses GraphQL (inspired by Ruben
Taelman's [GraphQL to SPARQL library](https://github.com/rubensworks/graphql-to-sparql.js) and
the [Stardog GraphQL API](https://docs.stardog.com/query-stardog/graphql)).

The query endpoint is available at `/{podId}/query` and accepts `POST` requests whose body conforms to
the [GraphQL-over-HTTP specification](https://graphql.org/learn/serving-over-http/#post-request). The body may contain
a `@context` object to provide aliases for the predicate IRIs used in the query. If no context is provided, the system
falls back to the default mapping configured for the pod (see [Pod Management](Pod-Management.md#default-context)).

> **Alternatives**: you can also use the `GET` method with query parameters, but you won't be able to provide a
> JSON-LD context. This is mainly useful when [querying a Slice](Slices.md). See the [API Reference](API-Reference.md) for details.

While a global query endpoint is useful for exploring the entire Knowledge Graph, in practice it will rarely occur that
an application requires access to all of a Pod's data. Kvasir introduces the concept of [Slices](Slices.md) to define
restricted subsets of the Knowledge Graph with which clients interact. The query endpoint for a specific Slice is
available at `/{podId}/slices/{sliceId}/query`.

> The following sections explain the basic usage of the **global** query endpoint. The same principles apply to Slice
> specific GraphQL query endpoints; see [Slices](Slices.md) for the differences.

## Why GraphQL?

When choosing a query language for the Knowledge Graph, we considered several options: SPARQL, GraphQL, RESTful APIs
centred around collections of specific RDF classes, or a proprietary query language (e.g. similar to what
[Fluree](https://developers.flur.ee/docs/learn/foundations/querying/) is doing). We chose GraphQL as the main querying
mechanism **[1]** for the following reasons:

* GraphQL is a widely adopted query language that is easy to learn and use, especially in the context of modern web
  applications.
* GraphQL is technology-agnostic, meaning it can be used with any backend. This lets us experiment with different
  storage solutions without coupling the query language to a specific RDF storage technology (as SPARQL would).
* GraphQL strikes a nice balance between expressiveness and simplicity. It allows for complex queries while remaining
  easy to understand, limits the implementation scope, and simplifies the process for third parties to develop a
  Kvasir-compatible API.

> **[1]**: The architecture of Kvasir is modular and flexible, so additional query mechanisms can be added in the
> future if needed.

## How to read the examples

The query endpoint accepts a JSON body containing a `query` field and an optional `@context` field. To keep the
examples readable, the **full HTTP call is shown once below**, and all subsequent code blocks show only the GraphQL
query (and where relevant the response `data` object), assuming the same endpoint and context.

**Full example — POST** `http://localhost:8080/alice/query`

```json
{
  "@context": {
    "ex":     "http://example.org/",
    "schema": "http://schema.org/"
  },
  "query": "{ ex_Person { schema_givenName schema_email } }"
}
```

Response:

```json
{
  "data": {
    "ex_Person": [
      { "schema_givenName": ["Alice"], "schema_email": ["alice@example.org"] },
      { "schema_givenName": ["Bob"],   "schema_email": ["bob@example.org"]   }
    ]
  }
}
```

> In all following examples the endpoint is `POST http://localhost:8080/alice/query` and the context contains at
> least `"ex": "http://example.org/"` and `"schema": "http://schema.org/"`, unless stated otherwise.
> {style="note"}

## Basic usage

The top-level field in the query represents the RDF class of resources you want to retrieve. The exact GraphQL schema
is auto-generated from the inserted content; see [Auto-generated schema](#auto-generated-schema) for details.

The following query retrieves all `ex:Person` resources that have a `schema:givenName` and a `schema:email`:

```graphql
{ ex_Person { schema_givenName schema_email } }
```

Nested traversal is also supported:

```graphql
{
  ex_Person {
    schema_givenName
    schema_email
    ex_knows {
      schema_givenName
      schema_email
    }
  }
}
```

Response:

```json
{
  "ex_Person": [
    {
      "schema_email":    ["alice@example.org"],
      "schema_givenName": ["Alice"],
      "ex_knows": [
        { "schema_email": ["bob@example.org"], "schema_givenName": ["Bob"] }
      ]
    }
  ]
}
```

## Additional features

### Namespace prefixes

The examples above use namespace prefixes (`ex_`, `schema_`) where the underscore substitutes the colon that GraphQL
does not allow in field names. The full prefix mapping is supplied in the `@context` object of the request body.

### Context language-tag

By default, Kvasir returns all values for language-tagged string literals. You can request a specific language by
adding `@language` to the context:

```json
{
  "@context": { "so": "http://schema.org/", "ex": "http://example.org/", "@language": "en" },
  "query": "{ ex_Person { so_givenName so_email } }"
}
```

This returns only values tagged `en` (or values with no language tag).

### Arguments

Use GraphQL arguments to filter resources by a specific value. The most common case is filtering by `id`:

```graphql
{ ex_Person(id: "ex:bob") { id schema_givenName schema_email } }
```

Response:

```json
{
  "ex_Person": [
    { "schema_email": ["bob@example.org"], "schema_givenName": ["Bob"], "id": "http://example.org/bob" }
  ]
}
```

Pass an array to match multiple values:

```graphql
{ ex_Person(id: ["ex:bob", "ex:alice"]) { id schema_givenName schema_email } }
```

> This also works for other properties, not just `id`. When the property is a relation, the argument value should be
> the URI (or an array of URIs) of the target resource(s).
> {style="note"}

### Filters

Use `@filter` directives to narrow down results with [RSQL](https://github.com/nstdio/rsql-parser) expressions:

```graphql
{ ex_Person @filter(if: "schema_givenName==Bob") { schema_givenName } }
```

If your expression only references a single selected field, you can move the filter down to that field:

```graphql
{ ex_Person { schema_givenName @filter(if: "schema_givenName==Bob") } }
```

In that field-level form, you can shorten the expression by referring to the field value as `it`:

```graphql
{ ex_Person { schema_givenName @filter(if: "it==Bob") } }
```

Response:

```json
{
  "ex_Person": [
    { "schema_givenName": ["Bob"] }
  ]
}
```

> **Tip**: use `it` to refer to the annotated field's value when the filter is placed on that field.

### Optional fields (`@optional`)

By default, requesting a field behaves like an **inner join** on that relation/property: resources for which the field
has no value are not included in the result path for that selection.

Use `@optional` when you want **left-join-like** behavior: keep matching resources even if the selected field has no
value.

Without `@optional`:

```graphql
{ ex_Person { id schema_email } }
```

With `@optional`:

```graphql
{ ex_Person { id schema_email @optional } }
```

In the second query, persons without `schema_email` are still returned, with an empty value for that field in the
response path.

`@optional` is also useful on nested relations when you want to keep the parent result even if the relation is missing:

```graphql
{
  ex_Person {
    id
    ex_knows @optional {
      schema_givenName
    }
  }
}
```

> `@optional` is a query-time directive. In Slice schemas, if a field is marked with `@mustExist`, that field cannot be
> queried with `@optional`. See [Field presence and visibility](Slice-Field-Presence-and-Visibility.md) for the
> Slice-specific visibility directives.
> {style="note"}

### Sorting

Use the `orderBy` argument with a list of field names to sort the results. Prefix a field name with `-` to sort
descending:

```graphql
{ ex_Person(orderBy: ["schema_email", "-schema_givenName"]) { id schema_givenName schema_email } }
```

### Pagination

Use `pageSize` to limit the number of results per page and `cursor` to navigate to the next page. When the result set
is larger than `pageSize`, the `extensions.pagination` block in the response will contain a `next` cursor:

```graphql
{ ex_Person(pageSize: 3) { id schema_givenName schema_email } }
```

Response:

```json
{
  "data": {
    "ex_Person": [
      { "id": "http://example.org/alice", "schema_givenName": ["Alice"], "schema_email": ["alice@example.org"] },
      { "id": "http://example.org/john",  "schema_givenName": ["John"],  "schema_email": ["jdoe@example.org"]  },
      { "id": "http://example.org/bob",   "schema_givenName": ["Bob"],   "schema_email": ["bob@example.org"]   }
    ]
  },
  "extensions": {
    "pagination": [
      {
        "@id": "kvasir:qr-page-info:c4d9182b427cce57",
        "path": "/ex_Person",
        "class": "http://example.org/Person",
        "totalCount": 4,
        "next": "gaFvAw=="
      }
    ]
  }
}
```

Use the `next` cursor to fetch the remaining results:

```graphql
{ ex_Person(pageSize: 3, cursor: "gaFvAw==") { id schema_givenName schema_email } }
```

### Time travel

Since the Knowledge Graph retains a complete history of all changes, you can query its state at a specific point in
time by adding one of these fields to the request body:

* `atTimestamp` — query the state of the KG at the specified ISO 8601 timestamp.
* `atChangeId` — query the state right after a specific change request was committed.

```json
{
  "@context": { "ex": "http://example.org/", "schema": "http://schema.org/" },
  "query": "{ ex_Person { schema_givenName } }",
  "atChangeId": "http://localhost:8080/alice/changes/716131e7-a373-4f31-8b4f-fc37c5af19cc"
}
```

### Reversing traversal

Kvasir supports the JSON-LD `@reverse` keyword in the context for introducing reverse relationships:

```json
{
  "@context": {
    "ex": "http://example.org/",
    "schema": "http://schema.org/",
    "children": { "@reverse": "ex:parent" }
  },
  "query": "{ ex_Person(id: \"ex:trudy\") { children { id } } }"
}
```

Response:

```json
{
  "ex_Person": [
    { "children": [ { "id": "http://example.org/bob" }, { "id": "http://example.org/alice" } ] }
  ]
}
```

### Type selection

Query only linked resources of a specific type using inline fragments:

```graphql
{
  ex_Person {
    id
    ex_knows {
      ... on ex_Musician { id }
    }
  }
}
```

Alternatively, use the special field `_types` together with a filter (see also
[Resource](#resource-implements-rdfnode)):

```graphql
{
  ex_Person {
    id
    ex_knows @filter(if: "_types==ex:Musician") { id }
  }
}
```

## Introspection

The query endpoint implements the standard [GraphQL introspection mechanism](https://graphql.org/learn/introspection/),
allowing clients to discover the schema of the Knowledge Graph including all available types and fields.

This means you can point [GraphiQL](https://github.com/graphql/graphiql/) or any other GraphQL tooling at the query
endpoint to explore the schema and run queries interactively with auto-completion.

![](graphiql.png)

> For authenticated environments, use the GraphiQL views in the Kvasir UI. The built-in UI supports both the global Pod
> query endpoint and Slice-specific query endpoints.
> {style="note"}

## Outputting JSON-LD

By default the query endpoint adheres to the GraphQL specification and returns a JSON `data` object. You can request
JSON-LD output by setting the `Accept` header to `application/ld+json`:

**POST** `http://localhost:8080/alice/query`  
**Accept**: `application/ld+json`

```json
{
  "@context": { "so": "http://schema.org/", "ex": "http://example.org/" },
  "query": "{ ex_Person { so_givenName so_email ex_knows { so_givenName so_email } } }"
}
```

Response:

```json
{
  "@context": { "so": "http://schema.org/", "ex": "http://example.org/" },
  "ex:Person": {
    "ex:knows":     { "so:email": "bob@example.org",   "so:givenName": "Bob"   },
    "so:email":     "alice@example.org",
    "so:givenName": "Alice"
  }
}
```

> When using GraphQL aliases in JSON-LD output mode, make sure to include those aliases in your context (or use
> prefixed names).
> {style="warning"}

## Auto-generated schema

A GraphQL interface is defined by its schema, which is typically authored upfront in SDL or generated from programmatic
definitions. What makes Kvasir different is that we don't know beforehand what data will be available for querying.
A GraphQL schema for the entire KG is **auto-generated** based on the data inserted via [Changes API](Changes.md) for
the global Pod endpoint `/{podId}/query`.

This auto-generation mechanism does **not** apply to [Slices](Slices.md). Slice schemas are authored explicitly by the
Slice author in GraphQL SDL (with Kvasir directives), and Kvasir then post-processes those definitions with additional
system types/fields and helper arguments.

Although accepting only RDF data (which is contextually qualified) helps with schema generation, it is not always
possible to deduce the full structure of the incoming data.

**The automated schema generation for the KG is a best-effort approach, with the goal of allowing users to quickly
explore the entire content.** If a specific, stable structure is required, use [Slices](Slices.md).

Limitations include:

* If type information is spread over multiple change requests (e.g. change 1 adds a relation between A and B, change 2
  adds type info for B), Kvasir may not have full type information. Inserting important type info together with the
  instance data in a single batch helps.
* Kvasir does not assume any vocabularies, shapes or ontologies **[2]**. Predicates used for a Resource are associated
  with all RDF classes that resource is an instance of.
* Complex type hierarchies are abstracted away via supertypes such as `RDFNode` and `Resource`
  (see [next section](#common-supertypes)).

> **[2]**: In regard to the full KG. When requesting changes to a Slice via the Changes API, SHACL Shape restrictions
> may apply.

## Common supertypes

### `RDFNode`

A common interface for values that can be either a Resource or a Literal. Exposes a single field `_rawRDF`:

* Resource: `{ "@id": "http://example.org/alice" }`
* Literal: `{ "@value": "1024", "@type": "http://www.w3.org/2001/XMLSchema#integer" }`

### `Resource` _implements `RDFNode`_ {id="resource-implements-rdfnode"}

Common supertype for RDF resources. Exposes an `id` field (the IRI) and utility fields for exploration:

* `_relations` — discover relations between resources:

```graphql
{ ex_Person(id: "ex:alice") { _relations(id: "ex:bob") } }
```

Response:

```json
{ "ex_Person": [ { "_relations": ["http://example.org/knows"] } ] }
```

* `_predicates` — list all predicates a Resource uses.
* `_types` — list all RDF classes the Resource is an instance of.
* `_object` — force-retrieve the value for a specific predicate that may not be part of the auto-generated schema:

```graphql
{ Resource(id: "ex:alice") { _object(predicate: "schema:givenName") { _rawRDF } } }
```

Response:

```json
{
  "Resource": [
    { "_object": [ { "_rawRDF": { "@type": "http://www.w3.org/2001/XMLSchema#string", "@value": "Alice" } } ] }
  ]
}
```

> The fields `_relations`, `_predicates` and `_object` are **not** available when querying a [Slice](Slices.md),
> since they could expose data outside the Slice's defined boundaries.
> {style="warning"}

### `BoxedLiteral` _implements `RDFNode`_

Represents a boxed literal. Useful in combination with `RDFNode` to support fields that can hold either resources or
literals.

For Slice authoring patterns that use these types explicitly, see [Fields with multiple types](Slice-Multi-Type-Fields.md).

```graphql
{ ex_Musician { id ex_plays { _rawRDF } } }
```

Response:

```json
{
  "ex_Musician": [
    { "id": "http://example.org/alice", "ex_plays": [ { "_rawRDF": { "@id": "http://example.org/guitar" } } ] },
    { "id": "http://example.org/john",  "ex_plays": [ { "_rawRDF": { "@type": "http://www.w3.org/2001/XMLSchema#string",  "@value": "piano" } } ] },
    { "id": "http://example.org/trudy", "ex_plays": [ { "_rawRDF": { "@type": "http://www.w3.org/2001/XMLSchema#integer", "@value": "234"   } } ] }
  ]
}
```

<seealso>
    <category ref="api-ref">
            <a href="API-Reference.md">API Reference</a>
    </category>
</seealso>


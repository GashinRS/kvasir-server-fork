# Querying

<show-structure depth="2"/>

The standard query mechanism for the Pod KG uses GraphQL (inspired by Ruben
Taelman's [GraphQL to SPARQL library](https://github.com/rubensworks/graphql-to-sparql.js) and
the [Stardog GraphQL API](https://docs.stardog.com/query-stardog/graphql)).

The query endpoint is available at `/{podId}/kg/query` and accepts POST requests with a JSON body **[1]**, which should
conform
to the [GraphQL specification](https://graphql.org/learn/serving-over-http/#post-request). The request body may contain
a `@context` object, to provide aliases for the predicate IRIs used in the query. If no content is explicitly provided,
the system will fall back to the default mapping that is configured for the pod (
see [](Pod-Management.md#default-context)).

> **[1]**: Alternatively, you can also use the `GET` method with query parameters, but you won't be able to provide a
> JSON-LD context. This approach primarily has it uses when [querying a Slice](Slices.md). See
> the [](API-Reference.md) for more information.

While a global query endpoint is useful for exploring the entire Knowledge Graph, in practice it will rarely occur that
an application requires access to all of a Pod's data (let alone gets granted such a permission). Kvasir introduces to
concept of [](Slices.md) which allows defining restricted subsets of the Knowledge Graph with which clients can
then interact with. The query endpoint for a specific Slice is available at `/{podId}/slices/{sliceId}/query`.

The following sections explain the basic usage of the global query endpoint, but the same principles apply to the Slice
specific GraphQL query endpoints.

## Why GraphQL?

When choosing a query language for the Knowledge Graph, we considered several options, including SPARQL, GraphQL,
RESTful APIs (centered around collections of specific RDF classes), or a proprietary query language (e.g. similar to
what [Fluree](https://developers.flur.ee/docs/learn/foundations/querying/) is doing). In the end we chose GraphQL
as the main querying mechanism **[2]** for the following reasons:

* GraphQL is a widely adopted query language that is easy to learn and use. It is especially popular in the context of
  modern web applications and APIs. By using GraphQL, we aim to make the Knowledge Graph accessible to a broad audience.
* GraphQL is technology-agnostic, meaning that it can be used with any backend system. This allows us to experiment with
  different
  storage solutions for the Knowledge Graph. Whereas with SPARQL, the query language is tightly coupled to the RDF data
  model and storage technologies that exist within the Semantic Web ecosystem.
* GraphQL strikes a nice balance between expressiveness and simplicity. It allows for complex queries, while still being
  easy to understand and use. This is important for users who are not familiar with RDF or SPARQL. More importantly, it
  limits the implementation scope, enhancing performance and simplifying the process for third parties to develop a
  Kvasir-compatible API.

> **[2]**: The architecture of Kvasir is designed to be modular and flexible, so it is possible to add additional query
> mechanisms in the future, if needed.

## Basic usage

The top-level field in the query represents the type of resource you want to retrieve. The exact GraphQL schema for your
pod is auto-generated, based on the inserted content, see [](#auto-generated-schema) for more details.
E.g. The following query retrieves all resources of the type `http://example.org/Person` that have a name and an email
address:

**POST** `http://localhost:8080/alice/query`

Request body:

```json
{
  "@context": {
    "Person": "http://example.org/Person",
    "name": "http://schema.org/givenName",
    "email": "http://schema.org/email"
  },
  "query": "{ Person { name email } }"
}
```

Response body:

```json
{
  "data": {
    "Person": [
      {
        "email": [
          "alice@example.org"
        ],
        "name": [
          "Alice"
        ]
      },
      {
        "email": [
          "bob@example.org"
        ],
        "name": [
          "Bob"
        ]
      }
    ]
  }
}
```

Nested queries are also supported:

**POST** `http://localhost:8080/alice/query`

```json
{
  "@context": {
    "Person": "http://example.org/Person",
    "name": "http://schema.org/givenName",
    "email": "http://schema.org/email",
    "knows": "http://example.org/knows"
  },
  "query": "{ Person { name email knows { name email } } }"
}
```

Returns:

```json
{
  "data": {
    "Person": [
      {
        "email": [
          "alice@example.org"
        ],
        "name": [
          "Alice"
        ],
        "knows": [
          {
            "email": [
              "bob@example.org"
            ],
            "name": [
              "Bob"
            ]
          }
        ]
      }
    ]
  }
}
```

## Additional features

For more advanced querying, the current prototype already supports some useful features:

### Namespace prefixes

Up until now, the examples used complete aliases for the fields, declared in the `@context` instance. However, the
system also supports namespace prefixes. Typically, prefixes are separated from the field name by a colon,
e.g. `ex:name` instead of `http://example.org/name`. However, GraphQL does not support colons in field names. Therefore,
Kvasir uses the underscore character `_` as a substitute for the colon. For example, the previous example query can be
rewritten as:

```json
{
  "@context": {
    "so": "http://schema.org/",
    "ex": "http://example.org/"
  },
  "query": "{ ex_Person { so_givenName so_email ex_knows { so_givenName so_email } } }"
}
```

### Context language-tag

By default, Kvasir will return all possible values for language-tagged string literals. However, you can request a
specific language
by adding an entry for `@language` in the `@context` object. For example, the following query retrieves the name of a
Person, only if it is in English (en) or when no language is specified:

**POST** `http://localhost:8080/alice/query`

```json
{
  "@context": {
    "so": "http://schema.org/",
    "ex": "http://example.org/",
    "@language": "en"
  },
  "query": "{ ex_Person { so_givenName so_email } }"
}
```

### Arguments

You can use GraphQL arguments to impose additional conditions on resources or linked resources. For example, the
following query retrieves the Person resource with a specific id:

**POST** `http://localhost:8080/alice/query`

```json
{
  "@context": {
    "so": "http://schema.org/",
    "ex": "http://example.org/"
  },
  "query": "{ ex_Person(id: \"ex:bob\") { id so_givenName so_email } }"
}
```

This returns:

```json
{
  "data": {
    "ex_Person": [
      {
        "so_email": [
          "bob@example.org"
        ],
        "so_givenName": [
          "Bob"
        ],
        "id": "http://example.org/bob"
      }
    ]
  }
}
```

You can use an array to match multiple values:

**POST** `http://localhost:8080/alice/query`

```json
{
  "@context": {
    "so": "http://schema.org/",
    "ex": "http://example.org/"
  },
  "query": "{ ex_Person(id: [\"ex:bob\", \"ex:alice\"]) { id so_givenName so_email } }"
}
```

> This feature also works for other properties, not just the `id` field. When the property is a relation, the expected
> argument value is a string (or string array), representing the URI(s) of the target resource(s).
> {style="note"}

### Filters

You can use filter directives to further restrict the results. The filter expressions are written in a simple expression
language ([RSQL](https://github.com/nstdio/rsql-parser)) that allows you to compare values and combine checks using
logical operators.

For example, the following query retrieves the person with the name 'Bob':

**POST** `http://localhost:8080/alice/query`

```json
{
  "@context": {
    "ex": "http://example.org/",
    "schema": "http://schema.org/"
  },
  "query": "{ ex_Person { schema_givenName @filter(if: \"schema_givenName==Bob\") } }"
}
```

Returns:

```json
{
  "data": {
    "ex_Person": [
      {
        "schema_givenName": [
          "Bob"
        ]
      }
    ]
  }
}
```

> **Tip**: you can refer to the annotated field using `it` in the filter directive. The filter expression in the
> previous example can thus be abbreviated to `@filter(if: "it==Bob")`.

### Sorting

You can specify a sorting order for fields that return multiple results by providing an `orderBy` argument.
Multiple sorting fields are supported, as well as modifying the ordering (ascending or descending) for each individual
field.

The `orderBy` argument expects a list of strings, with each entry referring to a **GraphQL field name** (and not the RDF
property URI). To sort a specific field in descending order, prefix the field name with a `-` character.

For example, the following query retrieves a list of persons, first ordered by `schema_email` and then
`schema_givenName` in descending order:

**POST** `http://localhost:8080/alice/query`

```json
{
  "@context": {
    "ex": "http://example.org/",
    "schema": "http://schema.org/"
  },
  "query": "{ ex_Person(orderBy: [\"schema_email\", \"-schema_givenName\"]) { id schema_givenName schema_email } }"
}
```

### Pagination

Some GraphQL query paths may return a large number of results. Kvasir supports paginating through the results via a
cursor-based mechanism. Each field that returns a collection has two additional system arguments: `pageSize` allows you
to set the maximum size of the returned collection and `cursor` allows you to provide a cursor which points to the range
of data to retrieve. When the query results do not contain the full result (the provided maximum `pageSize` was
reached),
the `extensions` part of the GraphQL response will contain a reference to the collection, with pagination information,
such as cursors to the next and previous page, the total count of the collection, etc.

For example the following query retrieves the first three entries for the `ex_Person` collection:

```json
{
  "@context": {
    "ex": "http://example.org/",
    "schema": "http://schema.org/"
  },
  "query": "{ ex_Person(pageSize: 3) { id schema_givenName schema_email } }"
}
```

Returns:

```json
{
  "data": {
    "ex_Person": [
      {
        "id": "http://example.org/alice",
        "schema_givenName": [
          "Alice"
        ],
        "schema_email": [
          "alice@example.org"
        ]
      },
      {
        "id": "http://example.org/john",
        "schema_givenName": [
          "John"
        ],
        "schema_email": [
          "jdoe@example.org"
        ]
      },
      {
        "id": "http://example.org/bob",
        "schema_givenName": [
          "Bob"
        ],
        "schema_email": [
          "bob@example.org"
        ]
      }
    ]
  },
  "errors": [],
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

The `next` cursor in `extensions.pagination` for the path `/ex_Person` can then be used to fetch the remaining results:

```json
{
  "@context": {
    "ex": "http://example.org/",
    "schema": "http://schema.org/"
  },
  "query": "{ ex_Person(pageSize: 3, cursor: \"gaFvAw==\") { id schema_givenName schema_email } }"
}
```

Returns:

```json
{
  "data": {
    "ex_Person": [
      {
        "id": "http://example.org/trudy",
        "schema_givenName": [
          "Trudy"
        ],
        "schema_email": [
          "trudy@example.org"
        ]
      }
    ]
  }
}
```

### Time travel

Since the Knowledge Graph retains a complete history of all changes, it is possible to query the state of the graph at a
specific point in time. This is done by adding a field to the request body:

* `atTimestamp`: Perform the query on the state of the Knowledge Graph at the specified ISO 8601 timestamp.
* `atChangeRequest`: Perform the query on the state of the Knowledge Graph right after the specified change request was
  committed.

For example, the following query retrieves the state of the Knowledge Graph at a change request:

**POST** `http://localhost:8080/alice/query`

```json
{
  "@context": {
    "ex": "http://example.org/",
    "schema": "http://schema.org/"
  },
  "query": "{ ex_Person { schema_givenName } }",
  "atChangeRequest": "http://localhost:8080/alice/changes/716131e7-a373-4f31-8b4f-fc37c5af19cc"
}
```

### Reversing traversal

Kvasir supports the JSON-LD `@reverse` keyword in the provided context for introducing reverse relationships,
which can then be used for querying.

For example: say we have some Person resources with an `ex:parent` relation to another person. By defining a relation
`children` as the reverse of `ex:parent`, we can query the parents for a specific person as follows:

**POST** `http://localhost:8080/alice/query`

```json
{
  "@context": {
    "ex": "http://example.org/",
    "schema": "http://schema.org/",
    "children": {
      "@reverse": "ex:parent"
    }
  },
  "query": "{ ex_Person(id: \"ex:trudy\") { children { id } } }"
}
```

Returns:

```json
{
  "data": {
    "ex_Person": [
      {
        "children": [
          {
            "id": "http://example.org/bob"
          },
          {
            "id": "http://example.org/alice"
          }
        ]
      }
    ]
  }
}
```

### Type selection

For example, querying people a Person knows that are also musicians:

```graphql
{
  ex_Person {
    id
    ex_knows {
      ... on ex_Musician {
        id
      }
    }
  }
}
``` 

Alternatively, you can also use the special field `_types` (see also [Resource](#resource-implements-rdfnode)):

```graphql
{
  ex_Person {
    id
    ex_knows @filter(if:"_types==ex:Musician") {
      id
    }
  }
}
```

## Introspection

The Query endpoint implements the standard [GraphQL introspection mechanism](https://graphql.org/learn/introspection/).
This allows clients to discover the schema of the Knowledge Graph, including the types and fields that are available for
querying.

This means that you can run [GraphiQL](https://github.com/graphql/graphiql/) or other GraphQL tools against the Query
endpoint to explore the schema and run queries interactively, with support for auto-completion, etc.

![](graphiql.png)

> Note that GraphiQL will not work out-of-the-box once the endpoints are protected by authentication. Our goal is to
> provide a GraphiQL build that includes an extension that allows you to authenticate in a Solid-compatible way.
> {style="note"}

## Outputting JSON-LD

By default the query endpoint adheres to the GraphQL specification, which means that the response is a JSON object with
a `data` key, holding the results as an array of JSON objects. However, the system also supports outputting JSON-LD
directly, by setting the `Accept` header to `application/ld+json`.

For example:

**POST** `http://localhost:8080/alice/query`

`Accept: application/ld+json`

```json
{
  "@context": {
    "so": "http://schema.org/",
    "ex": "http://example.org/"
  },
  "query": "{ ex_Person { so_givenName so_email ex_knows { so_givenName so_email } } }"
}
```

Returns:

```json
{
  "@context": {
    "so": "http://schema.org/",
    "ex": "http://example.org/"
  },
  "ex:Person": {
    "ex:knows": {
      "so:email": "bob@example.org",
      "so:givenName": "Bob"
    },
    "so:email": "alice@example.org",
    "so:givenName": "Alice"
  }
}
```

> When using GraphQL aliases in JSON-LD output mode, make sure to include these aliases in your context (or use prefixed
> names).
> {style="warning"}

<seealso>
    <category ref="api-ref">
            <a href="API-Reference.md">API Reference</a>
    </category>
</seealso>

## Auto-generated schema

A GraphQL interface is defined by its schema, which is typically authored upfront in SDL, or generated based on the
programmatic definitions of the various types and fields. What makes Kvasir different, is that we don't know beforehand
what data will be available for querying. A GraphQL schema for the entire KG is auto-generated based on the data that is
inserted via [](Changes.md). Although only accepting RDF data, which has the benefit of being contextually qualified,
helps with generating a usable schema, it is not always possible to deduct the full structure of the incoming data.

**The automated schema generation for the KG is a best effort approach, with the goal of allowing users to quickly
explore the entire content.** If a specific structure is required, we refer to [](Slices.md).

Some limitations include:

* If type information is spread out over multiple change requests (e.g. change request 1 adds a relation between
  resources A and B, while change request 2 adds type information for resource B), Kvasir may not be aware of detailed
  type information. Users can assist the schema generation by inserting important type information via concrete
  instances in a single insert batch.
* Kvasir does not assume any vocabularies, shapes or ontologies to apply **[3]**. This means e.g. that we will associate
  predicates used for a specific Resource, with all RDF classes the Resource is an instance of.
* Complex type hierarchies are automatically abstracted away via common supertypes such as `RDFNode` and `Resource` (see
  [next section](#common-supertypes)). It is than up to the user to have knowledge of which subtypes are available for a
  specific relation (although the GraphQL interface provides introspection and discovery mechanisms).

> **[3]**: In regard to the full KG. When requesting changes to a Slice via the Changes API, SHACL Shape restrictions
> may apply!

## Common supertypes

### `RDFNode`

A common interface for representing values that can either be a Resource or a Literal. Exposes a single field `_rawRDF`,
which allows accessing the raw RDF representation of the instance.

E.g. the IRI when the node is a Resource: `{ "@id": "http://example.org/alice>" }`
E.g. a JSON-LD object instance representing a Literal value when the node is a Literal:

```json
{
  "@value": "1024",
  "@type": "http://www.w3.org/2001/XMLSchema#integer"
}
```

### `Resource` _implements `RDFNode`_

Common supertype for representing RDF resources. Exposes an `id` field (IRI of the Resource) and a number of utility
fields that can be used for exploration.

Use `_relations` to discover relations between Resources, for example:

```graphql
{
  ex_Person(id: "ex:alice") {
    _relations(id: "ex:bob")
  }
}
```

Returns:

```json
{
  "data": {
    "ex_Person": [
      {
        "_relations": [
          "http://example.org/knows"
        ]
      }
    ]
  }
}

```

Use `_predicates` to get a list of predicates a Resources uses.

Use `_types` to get a list of the RDF classes the Resource is an instance of.

Use `_object` to force retrieving the value for a specific predicate, without it being explicitly being a part of the
auto-generated schema.

For example, get the email address of Alice, via the Resource entry-point:

```GRAPHQL
{
  Resource(id: "ex:alice") {
    _object(predicate: "schema:givenName") {
      _rawRDF
    }
  }
}
```

Returns:

```JSON
{
  "data": {
    "Resource": [
      {
        "_object": [
          {
            "_rawRDF": {
              "@type": "http://www.w3.org/2001/XMLSchema#string",
              "@value": "Alice"
            }
          }
        ]
      }
    ]
  }
}
```

### `BoxedLiteral` _implements `RDFNode`_

This type represents a boxed literal, can be useful to use in combination with the RDFNode supertype, in order to
support fields which can either have Resources or literals as values.

Example:

```GraphQL
{
  ex_Musician {
    id
    ex_plays {
      _rawRDF
    }
  }
}
```

Returns:

```JSON
{
  "data": {
    "ex_Musician": [
      {
        "id": "http://example.org/alice",
        "ex_plays": [
          {
            "_rawRDF": {
              "@id": "http://example.org/guitar"
            }
          }
        ]
      },
      {
        "id": "http://example.org/john",
        "ex_plays": [
          {
            "_rawRDF": {
              "@type": "http://www.w3.org/2001/XMLSchema#string",
              "@value": "piano"
            }
          }
        ]
      },
      {
        "id": "http://example.org/trudy",
        "ex_plays": [
          {
            "_rawRDF": {
              "@type": "http://www.w3.org/2001/XMLSchema#integer",
              "@value": "234"
            }
          }
        ]
      }
    ]
  }
}
```
# Querying

<show-structure depth="2"/>

The standard query mechanism for the Pod KG uses schemaless GraphQL (inspired by Ruben
Taelman's [GraphQL to SPARQL library](https://github.com/rubensworks/graphql-to-sparql.js) and
the [Stardog GraphQL API](https://docs.stardog.com/query-stardog/graphql)).

The query endpoint is available at `/{podId}/kg/query` and accepts POST requests with a JSON body, which should conform
to the [GraphQL specification](https://graphql.org/learn/serving-over-http/#post-request). The request body may contain
a `@context` object, to provide aliases for the predicate IRIs used in the query. If no content is explicitly provided,
the system will fall back to the default mapping that is configured for the pod (TODO: see Pod config).

## Why GraphQL?

When choosing a query language for the Knowledge Graph, we considered several options, including SPARQL, GraphQL,
RESTful APIs (centered around collections of specific RDF classes), or a proprietary query language (e.g. similar to
what [Fluree](https://developers.flur.ee/docs/learn/foundations/querying/) is doing). In the end we chose GraphQL
as the main querying mechanism[^1] for the following reasons:

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

## Basic usage

The top-level field in the query represents the type of resource you want to retrieve. E.g. The following query
retrieves all resources of the type `http://example.org/Person` that have a name and an email address:

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

Use the RDF parent class `rdfs:Resource` to retrieve all resources, regardless of their type. This is useful if you
don't know the type of the resources, but you know which specific properties you are looking for. For example, the
following query retrieves all resources that have a name and an email address:

**POST** `http://localhost:8080/alice/query`

```json
{
  "@context": {
    "Resource": "http://www.w3.org/2000/01/rdf-schema#Resource",
    "name": "http://schema.org/givenName",
    "email": "http://schema.org/email"
  },
  "query": "{ Resource { name email } }"
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

## Time travel

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

> Beware that the field name used in the query, or possible aliases, no longer have an effect on the output, as this is
> now purely based on the predicate IRIs and the context supplied in the request.
> {style="warning"}

> This feature cannot be used with introspection queries, as the introspection mechanism does not return Linked-Data.
> Our aim is to support this with a feature update, while still adhering to the GraphQL specification.
> {style="warning"}

<seealso>
    <category ref="api-ref">
            <a href="API-Reference.md">API Reference</a>
    </category>
</seealso>

[^1]: The architecture of Kvasir is designed to be modular and flexible, so it is possible to add additional query
mechanisms in the future, if needed.
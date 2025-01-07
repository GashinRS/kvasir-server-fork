# Slices

<show-structure depth="2"/>

> This feature is still work in progress.
> {style="note"}

The Changes and Query APIs are expressive ways to interact with the Knowledge Graph of a Pod. However, they provide
access to the entire Knowledge Graph, which may not always be necessary or desirable. In some cases, it may be more
efficient to work with a subset of the Knowledge Graph, known as a "slice". Also from the perspective of access control,
it is easier to manage permissions on a slice than on the entire Knowledge Graph.

In a way, a slice is similar to a view in a relational database, where only a subset of the data is exposed to the user.
Slices can be used to filter the data based on certain criteria, such as a specific type of resource, a particular
property, a specific value range, etc. However, unlike views, slices are not restricted to read-only access; they can
also be used for write operations (e.g. restricting the data that can be inserted for a specific slice to a certain
shape).

## Defining a Slice

A slice is defined by a set of criteria that determine which resources are included in the slice. These criteria can be
specified using a GraphQL schema, annotated with Kvasir directives to fully qualify the graph elements, or to express
additional constraints.

For example, the following schema defines a slice that includes only resources of type `Person` and restricts the
visible properties to `givenName`, `familyName` and `email`:

```graphql
type Query {
    persons: [Person!]!
}

type Person @class(iri: "http://schema.org/Person") {
    id: ID!
    givenName: String! @predicate(iri: "http://schema.org/givenName")
    familyName: String! @predicate(iri: "http://schema.org/familyName")
    email: [String!] @predicate(iri: "http://schema.org/email")
}
```

By also supplying a JSON-LD context for the Slice definition, the schema can be simplified through prefixes (see
also: [Querying - Namespace prefixes](Querying.md#namespace-prefixes)).

**JSON-LD context**

```json
{
  "schema": "http://schema.org/"
}
```

**Simplified schema**

```graphql
type Query {
    persons: [schema_Person!]!
}

type schema_Person {
    id: ID!
    schema_givenName: String!
    schema_familyName: String!
    schema_email: [String!]
}
```

### Constraints

Using the `@shape` directive, additional constraints can be specified for the resources in the slice. For example, the
following schema defines a slice that includes only resources of type `Person`, which have at least one email address,
ending on `@example.com`:

```graphql
type Query {
    persons: [schema_Person!]!
}

type schema_Person {
    id: ID!
    schema_givenName: String!
    schema_familyName: String!
    schema_email: [String!] @shape(pattern: ".*@example\.org$")
}
```

More information and detailed documentation on the available constraints will be added in the future.

### Registering the definition

To register the Slice, post the definition to the `/slices` endpoint of the Pod:

**POST** `http://localhost:8080/alice/slices`

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "schema": "http://schema.org/"
  },
  "kss:name": "PersonDemoSlice",
  "kss:description": "Demo Slice exposing Persons that have an '@example.org' email address",
  "kss:schema": "type Query { persons: [schema_Person!]! } type schema_Person { id: ID! schema_givenName: String! schema_familyName: String! schema_email: [String!]! @shape(pattern: \".*@example\\\\.org$\") }"
}
```

If the Slice is registered successfully, the operation will return a `201 Created` status code and the response headers
will include a `Location` header with the URL of the newly created Slice.

## Inspecting a Slice

You can inspect the definition of a Slice by sending a `GET` request to the URL of the Slice:

**GET** `http://localhost:8080/alice/slices/PersonDemoSlice`

The response will include the Slice definition in the body of the response.

Kvasir also converts the Slice definition to a SHACL shape, which is used for input validation.
You can review the SHACL shape by sending a `GET` request to the `shacl` endpoint of the Slice:

**GET** `http://localhost:8080/alice/slices/PersonDemoSlice/shacl`

The response will include the SHACL shape in Turtle format in the body of the response.

## Using a Slice

Once a Slice is registered, all operations that are available for the Knowledge Graph can also be performed on the
Slice. The base URL for the API operations on a Slice is the URL of the Slice (e.g.
`http://localhost:8080/alice/slices/PersonDemoSlice/`) compared to the base URL of the pod (e.g.
`http://localhost:8080/alice`) when performing Knowledge Graph global operations.

### Retrieving Slice data

Querying a Slice is similar to [querying the entire Knowledge Graph](Querying.md), but the query is restricted by the
pre-defined
schema, instead of relying on an auto-generated schema for the entire Knowledge Graph.

For example, to retrieve all persons from the `PersonDemoSlice`, you can send a `POST` request to the `query` endpoint:

**POST** `http://localhost:8080/alice/slices/PersonDemoSlice/query`

```json
{
  "query": "{ persons { id schema_givenName schema_familyName schema_email } }"
}
```

### Mutations on a Slice

When enabled, a Slice can also expose a Changes API, allowing mutations on the Slice. The mutations are restricted by
the
pre-defined schema, ensuring that only resources that match the criteria of the Slice can be created, updated or
deleted.


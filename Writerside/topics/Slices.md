# Slices

<show-structure depth="2"/>

A **Slice** is a named, schema-driven subset of a Pod's Knowledge Graph and is the **primary way for applications to
interact with Kvasir**. In the real world, a data owner will almost never grant an application access to the full
Knowledge Graph. Instead, the owner publishes one or more Slices — each exposing exactly the data the intended audience
is allowed to see and modify — and then grants the application access to those Slices only.

Conceptually, a Slice is similar to a view in a relational database: only a subset of the data is exposed. However,
Slices are considerably more powerful:

* **Fine-grained access control by design.** The schema structure combined with `@filter` directives and input
  constraints defines what data is readable and writable. There is no separate, parallel policy configuration needed
  for common access-control scenarios.
* **Not read-only.** Unlike database views, Slices can expose full read/write/subscribe capabilities through a clean
  GraphQL interface, comprising Query, Mutation and Subscription types.
* **Self-describing.** Slices carry their own schema, context and metadata, so clients don't need any prior knowledge
  of the underlying RDF model.

```D2
```

{ src="../diagrams/slices.d2" }

The diagram above contrasts the Pod-level APIs with the Slice-specific GraphQL API.

At the Pod level, the global GraphQL API resolves queries against an auto-generated schema for the full Knowledge
Graph, while the global Changes API accepts general change requests. A Slice, by contrast, exposes its own GraphQL API
based on a user-defined schema that is part of the Slice definition.

That Slice schema does more than describe the response shape:

* It limits what data can be queried.
* It defines which mutations are allowed.
* It determines which subscriptions can be triggered by matching changes.

In other words, the Slice-specific GraphQL API is both the contract and the boundary for read, write and subscription
operations on the Slice.

> A Slice can also expose a Slice-specific Changes API at `/{podId}/slices/{sliceId}/changes`.
> Change requests posted to this endpoint are validated against the Slice schema before they are applied to the
> Knowledge Graph via the [Changes Processor service](Architecture.md#kg-changes-processor-service).
> This additional ingest path is intentionally omitted from the diagram to keep the main flow readable.
> {style="note"}

> For a guided, end-to-end example of authoring a Slice from scratch, see [Walkthrough](Walkthrough.md).

The following table summarizes the differences between the capabilities of the global KG GraphQL interface and the Slice
specific GraphQL interface:

|                                         | Global KG GraphQL interface                                                                                                  | Slice GraphQL interface                                                                                                                                         |
|-----------------------------------------|------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Endpoint**                            | `/{podId}/query`                                                                                                             | `/{podId}/slices/{sliceId}/query`                                                                                                                               |
| **Schema**                              | [Auto-generated](Querying.md#auto-generated-schema) based on incoming changes. Best effort, cannot be customized.            | User-defined. Slice author has full control of the exposed Graph structure (and its mapping from RDF).                                                          |
| **Requires JSON-LD context?**           | Yes <br/> _Although a prefix mapping is auto-generated when no context is provided, resulting in a quite unreadable schema._ | No <br/> _The context is embedded in the Slice definition._                                                                                                     |
| **Supports GraphQL mutations?**         | No.                                                                                                                          | Yes, see [Mutation type](#mutation-type).                                                                                                                       |
| **Supports GraphQL subscriptions?**     | No.                                                                                                                          | Yes, see [Subscription type](#subscription-type).                                                                                                               |
| **Allows fine-grained access control?** | No, all data in the KG is retrievable.                                                                                       | Yes, the schema structure in combination with additional directives, give the author full control on what data can be retrieved from and modified on the Slice. |

## Defining a Slice

### Query type

A slice is defined by a set of criteria that determine which resources are included in the slice. These criteria can be
specified using a GraphQL schema, annotated with Kvasir directives to fully qualify the graph elements, or to express
additional constraints.

For example, the following schema defines a read-only slice that includes only resources of type `Person` and restricts
the visible properties to `givenName`, `familyName` and `email`:

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

> A field name cannot start with an underscore (`_`), as this is reserved for system fields.
> {style="note"}

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

> By adding a `@vocab` entry to the context, you can define types and predicates without the need for a prefix. For
> example,
> by adding `"@vocab": "http://schema.org/"` to the previous context, the schema can be further simplified to:
> ```graphql
> type Query {
>    persons: [Person!]!
> }
> 
> type Person {
>   id: ID!
>   givenName: String!
>   familyName: String!
>   email: [String!]
> }
> ```
> {style="note"}

#### Data restrictions

To further restrict the data retrievable via the Slice, use the [`@filter` directive](Querying.md#filters) similarly to
how you would narrow down results for a Query. The filters associated with the Slice schema are combined with those in
the query document when Kvasir executes a GraphQL query. This ensures that the Slice interfaces cannot expose data
beyond the reach of its defined subgraph.

For example, the following schema defines a slice that includes only resources of type `Person`, which have at least one
email address, ending on `@example.com`:

```graphql
type Query {
    persons: [schema_Person!]!
}

type schema_Person {
    id: ID!
    schema_givenName: String!
    schema_familyName: String!
    schema_email: [String!] @filter(if: "it==*@example.org")
}
```

#### Scalar types and RDF mapping

The Kvasir GraphQL type system is based on the following scalar types:

| GraphQL Type | Compatible RDF literal data type(s)      |
|--------------|------------------------------------------|
| ID           | IRI reference                            |
| String       | `xsd:string`, `rdf:langString`           |
| Boolean      | `xsd:boolean`                            |
| Float        | `xsd:float`, `xsd:double`, `xsd:decimal` |
| Int          | `xsd:int`, `xsd:integer`                 |
| Time         | `xsd:time` *                             |
| Date         | `xsd:date` *                             |
| DateTime     | `xsd:dateTime` *                         |

*: if RFC 3339 compliant

#### System types, fields and arguments

Kvasir enhances the user-defined schema with additional types, fields and arguments as a quality-of-life improvement
when authoring Slices:

- All user-defined types automatically implement the `Resource` interface, which provides a `id` field
  representing the URI of the resource and other system fields (useful for introspection). For more information,
  see [Resource](Querying.md#resource-implements-rdfnode). As such, the `id` field in the previous examples can
  be omitted.
- All fields that have a List type (e.g. `persons: [schema_Person!]!`), are modified to support arguments for
  filtering, sorting and pagination. These arguments include `id` (see filtering by
  id [Arguments](Querying.md#arguments)),
  `pageSize`, `cursor` (see pagination [Pagination](Querying.md#pagination)) and `orderBy` (
  see [Sorting](Querying.md#sorting)).

### Mutation type

A Slice can specify mutations (insertions, deletions, updates) that can be applied using the
standard [GraphQL Mutation Type and input types](https://graphql.org/learn/mutations/). However, there are a couple of
restrictions in place (in order to make sure Kvasir can interpret and execute these mutations on top of the Knowledge
Graph):

* Mutations names must start with a `insert`, `add`, `delete`, `remove`, `update` or `set` prefix, indicating the intent of the Mutation.
* Arguments for the mutation must be an Input Type or an array of an Input Type, annotated with the `@class` directive,
  so it is fully semantically qualified.
* The mutation must have `ID` as return type. The return value will be the URI of the [Change Request](Changes.md) that
  results from the mutation.

We chose having to explicitly model the mutations, instead of automatically deriving possible mutations from the
Query schema. This allows the Slice author to have full control of what data is readable vs. what data can be changed.

As a result, mutations on a Slice can be executed in two ways:

1. Via the Slice-specific Changes API at `/{podId}/slices/{sliceId}/changes`.
2. Via the Slice-specific GraphQL interface at `/{podId}/slices/{sliceId}/query`, using the defined Mutation Type.

For example: the following GraphQL document defines mutations for adding or removing Person information (with context
entry `"schema": "http://schema.org/"`):

```graphql
type Mutation {
  add(person: [PersonInput!]!): ID!
  remove(person: [PersonInput!]!): ID!
}

input PersonInput @class(iri: "schema:Person") {
  id: ID!
  schema_givenName: String!
  schema_familyName: String!
  schema_email: [String!]
}
```

This example allows clients with write-access to the Slice, to add or remove Persons, which must have a `givenName`,
`familyName` and zero or multiple email addresses.

#### Input type constraints

Just like the `@filter` directive can be used to limit the view-aspect of a Slice, Kvasir supports modeling constraints
for the possible mutation input data via the `@shape` directive. This directive is inspired
by [SHACL](https://www.w3.org/TR/shacl/).

The `@shape` directive supports the following arguments:

| Argument     | GraphQL Type | Description                                                                                             |
|--------------|--------------|---------------------------------------------------------------------------------------------------------|
| minExclusive | String       | All values must be greater than the provided String representation of the reference value.              |
| minInclusive | String       | All values must be greater than or equal to the provided String representation of the reference value.  |
| maxExclusive | String       | All values must be less than the provided String representation of the reference value.                 |
| maxInclusive | String       | All values must be less than or equal to the provided String representation of the reference value.     |
| minLength    | Int          | To be used with String properties. The input String value should be at least of the provided length.    |
| maxLength    | Int          | To be used with String properties. The input String value should be no longer than the provided length. | 
| pattern      | String       | To be used with String properties. The input String value should match the provided regex.              |
| flags        | String       | Use in combination with `pattern` to provide flags, such as `i` to ignore case.                         |
| hasValue     | String       | The input value must be equal to the provided value (as String representation).                         |
| in           | [String]     | The input value must be a member of the provided list of value String representations.                  |
| minCount     | Int          | The minimum number of values required for the field. Combined with GraphQL non-null (`!`): the stricter (larger) bound wins. A nullable field with `@shape(minCount: 1)` behaves as required. |
| maxCount     | Int          | The maximum number of values allowed for the field. Combined with GraphQL list type: a non-list field implicitly has `maxCount: 1`. Enforced inline for inserts and blanket-replace updates. For `_add` updates on `maxCount`-constrained fields, Kvasir adds a POST count-bound assertion when the Change Request is state-dependent; otherwise the update is rejected because the post-state cannot be verified safely. |

For example: we could add a constraint to the previous example, expressing that only Persons with the `familyName`
`"Doe"`
can be inserted or deleted from the Slice, and that the `firstName` must contain at least two characters:

```graphql
type Mutation {
  add(person: [PersonInput!]!): ID!
  remove(person: [PersonInput!]!): ID!
}

input PersonInput @class(iri: "schema:Person") {
  id: ID!
  schema_givenName: String! @shape(minLength: 2)
  schema_familyName: String! @shape(hasValue: "Doe")
  schema_email: [String!]
}
```

#### Auto-generating input types and mutations

It can be cumbersome to manually define mutations for a Slice, especially when there are many types involved and the
input types mirror the query types. To ease this process, Kvasir supports auto-generating the input types and mutation
operations via the `@generateMutations` directive.

When this directive is applied to a query type, Kvasir will automatically generate matching input types and add the
necessary arguments to the mutation operations. For example, the first example of this section can also be achieved by
annotating `schema_Person``:

```graphql
type Query {
    persons: [schema_Person!]!
}

type schema_Person @generateMutations {
    id: ID!
    schema_givenName: String!
    schema_familyName: String!
    schema_email: [String!]
}
```

To generate input types and mutations for the entire schema,
apply the `@generateMutations` directive to the `Query` type

```graphql
type Query @generateMutations {
    persons: [schema_Person!]!
}
```

We allow the use of the `@shape` directive on the fields of query types that are annotated with `@generateMutations`, so
this quality-of-life improvement remains useful for a range of common cases. For example, the second example of this
section (with the `@shape` directives) can also be achieved using the auto-generation feature in the following way:

```graphql
type schema_Person @generateMutations {
    id: ID!
    schema_givenName: String! @shape(minLength: 2)
    schema_familyName: String! @shape(hasValue: "Doe")
    schema_email: [String!]
}
```

The shape annotations are copied into the definition of the generated input type and have no impact on read operations.
However, for complex mutations, it is still recommended to manually define the input types and mutations, allowing the
author to have full control over the definition, while having a clear separation between the query and mutation
operations.

The `generateMutations` directive has an optional argument `operations`, which allows explicitly specifying which
operations the author wants to generate. If no operations are explicitly specified, `add` and `remove` are generated by
default. This feature enables restricting mutations for the type to only insertions or deletions, or customizing the
mutation name (as long as it starts with `add`, `insert`, `remove`, `delete`, `update` or `set`).

In the following example, only an `insertPerson` mutation is generated:

```graphql
type schema_Person @generateMutations(operations: ["insertPerson"]) {
    id: ID!
    schema_givenName: String! @shape(minLength: 2)
    schema_familyName: String! @shape(hasValue: "Doe")
    schema_email: [String!]
}
```

> A `generateMutations` directive on a type takes precedence over a `generateMutations` directive on the `Query` type.
> If both are present, the one on the type is used. This allows specifying a default behaviour on the `Query` type,
> while restricting or customizing the mutations for specific types.
> {style="note"}

#### Update mutations { id="update-mutations" }

Mutations whose name starts with `update` or `set` (e.g. `updatePerson`, `setPerson`) are treated as **partial
in-place updates**. Both prefixes are equivalent — use whichever reads most naturally for your domain.

The standard pattern is to define a dedicated update input type alongside the insert input type. Fields in the update
type are **all nullable** (absent fields are not touched), while the insert type remains the authoritative definition
of what a valid, complete resource looks like.

```graphql
type Mutation {
  add(person: [PersonInput!]!): ID!
  update(person: [PersonUpdateInput!]!): ID!
}

input PersonInput @class(iri: "ex:Person") {
  id: ID!
  ex_givenName: String! @shape(minLength: 2)
  ex_score: Int! @shape(maxInclusive: "100")
}

input PersonUpdateInput @class(iri: "ex:Person") {
  id: ID!
  ex_givenName: String
  ex_score: _UpdatableInt   # built-in type that supports _set, _increment, _decrement
}
```

Key behaviours:

* **Constraints from the insert type are always enforced** — even for fields that are only mentioned in the update type.
* **Atomic operations** on numeric and string fields (`_increment`, `_append`, etc.) are available via the built-in
  `_Updatable*` input types. The resulting value is fully resolved before validation, so constraint violations are
  always caught inline.
* **Every update generates a PRE assertion** verifying the target resource exists. Updating a non-existent resource
  results in `ASSERTION_FAILED`.

> For the full reference — including all `_Updatable*` types and operations, constraint merging rules, delete
> semantics, and the optional delete input type — see [Update mutations](Slice-Update-Mutations.md).
> {style="note"}

### Subscription type

A Slice can specify GraphQL subscriptions that are triggered by specific events occurring on
the [Changes event stream](Changes.md#streaming-changes) of the Pod's Knowledge Graph. Each field defined within the
Subscription type for the Slice, represents such a trigger, allowing the client to be notified of specific changes,
receiving the requested data selection as a "real-time" update.

A trigger is represented by the following components:

| Component Name | Description                                                                                                                                              | Default value                                     |
|----------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------|
| type           | There are two types of basic triggers: those reacting to data insertions (`INSERT`) and those reacting to data deletions (`DELETE`).                     | n/a                                               |
| subject        | List of URIs of the specific subjects the trigger reacts to. Change records with a subject represented in this list may activate the trigger.            | n/a (optional property)                           |
| predicate      | List of URIs of the specific predicates the trigger reacts to. Change records with a predicate represented in this list may activate the trigger.        | `http://www.w3.org/1999/02/22-rdf-syntax-ns#type` |
| object         | List of URIs or literals of the specific objects the trigger reacts to. Change records with an object represented in this list may activate the trigger. | The output type of the Subscription field         |

These components can be defined manually by annotating the subscription fields with an `@trigger` directive. If no such
annotation is found, Kvasir assumes the trigger should react to data being added or deleted of a specific type,
determined by the output type of the Subscription field. When the subscription field ends in `Added` or `Inserted`, the
type `INSERT` is assumed. If the fields ends in `Removed` or `Deleted`, the trigger type `DELETE` is assumed.

For example: we can allow Slice clients to subscribe to instances of Person being added or removed from the Knowledge
Graph, by defining the following Subscription type:

```graphql
type Subscription {
  onPersonAdded: Person!
  onPersonRemoved: Person!
}
```

This definition relies on the Kvasir default trigger settings, to have more control, we can use the `@trigger`
directive. For example: say we also want to allow Clients to subscribe to acquaintances being added to a Person (based
on the `ex:knows` relation).

```graphql
type Subscription {
  onPersonAdded: Person!
  onPersonRemoved: Person!
  onAcquaintanceAdded: Person! @trigger(type: INSERT, predicate: "ex:knows", object: [])
}
```

Just as with mutations, this approach to subscriptions and streaming data, allows the Kvasir framework to automatically
provide an implementation based on the provided schema, with a certain degree of flexibility, while limiting the
conceptual complexity.

## Registering the definition

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
  "kss:schema": {
    "@type": "kss:EmbeddedSliceSchema",
    "kss:sdl": "type Query { persons: [schema_Person!]! } type schema_Person { id: ID! schema_givenName: String! schema_familyName: String! schema_email: [String!]! @shape(pattern: \".*@example\\\\.org$\") }"
  }
}
```

The `kss:schema` property is an object rather than a plain string, allowing different schema representations to be
supported in the future. Currently, only `kss:EmbeddedSliceSchema` is supported, which embeds the GraphQL SDL directly
in the Slice definition via the `kss:sdl` property.

If the Slice is registered successfully, the operation will return a `201 Created` status code and the response headers
will include a `Location` header with the URL of the newly created Slice.

## Inspecting a Slice

You can inspect the definition of a Slice by sending a `GET` request to the URL of the Slice:

**GET** `http://localhost:8080/alice/slices/PersonDemoSlice`

The response will include the Slice definition in the body of the response.

## Using a Slice

Once a Slice is registered, operations that are available for the Knowledge Graph can also be performed on the
Slice. The base URL for the API operations on a Slice is the URL of the Slice (e.g.
`http://localhost:8080/alice/slices/PersonDemoSlice/`) compared to the base URL of the pod (e.g.
`http://localhost:8080/alice`) when performing Knowledge Graph global operations.

### Retrieving Slice data

Querying a Slice is similar to [querying the entire Knowledge Graph](Querying.md), but the query is restricted by the
pre-defined schema, instead of relying on an auto-generated schema for the entire Knowledge Graph.

For example, to retrieve all persons from the `PersonDemoSlice`, you can send a `POST` request to the `query` endpoint:

**POST** `http://localhost:8080/alice/slices/PersonDemoSlice/query`

```json
{
  "query": "{ persons { id schema_givenName schema_familyName schema_email } }"
}
```

### Mutations on a Slice

By defining Mutation Types in the Slice, a new Slice Changes API is exposed (at `/{podId}/slices/{sliceId}/changes`).
Change requests sent to this API are always restricted by
the pre-defined schema, ensuring that only resources that match the criteria of the Slice can be created, updated or
deleted.
Of course, these mutations may also be performed using the GraphQL interface directly, and they are likewise restricted
by the pre-defined schema.

For example, to add a Person, you can send a `POST` request to the `query` endpoint:

```json
{
  "query": "mutation { add(person: { id: \"ex:jdoe\" schema_givenName: \"John\" schema_familyName: \"Doe\" }) }"
}
```

### Subscriptions on a Slice

If a Slice defines a Subscription Type, clients can subscribe to insertion or deletion events using the GraphQL
interface.

For example, to subscribe to the name and email of Persons being inserted, you can send a `POST` request with the
`Accept: text/event-stream` header to the `query` endpoint:

```json
{
  "query": "subscription { onPersonInserted { id schema_givenName schema_familyName schema_email } }"
}
```

This will return a Server-Sent-Event (SSE) response in which GraphQL query result instances will be streamed, each time
a Person is inserted. Make sure your GraphQL client can handle SSE as a transport protocol for GraphQL subscriptions.

<seealso>
    <category ref="related">
        <a href="Walkthrough.md">Walkthrough — end-to-end example</a>
        <a href="Slice-Update-Mutations.md">Update mutations — full reference</a>
        <a href="Slice-Field-Presence-and-Visibility.md">Field presence and visibility</a>
        <a href="Slice-Multi-Type-Fields.md">Fields with multiple types</a>
        <a href="Slice-Versioning-and-Tags.md">Slice versioning with tags</a>
        <a href="Changes.md">Changes API</a>
        <a href="Querying.md">Query API</a>
        <a href="API-Reference.md">API Reference</a>
    </category>
</seealso>

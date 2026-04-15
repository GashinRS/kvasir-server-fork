# Slice walkthrough

<show-structure depth="2"/>

This guide walks through a complete Slice workflow on a Pod:

1. Define a query model for the data you want to expose.
2. Add filters so the Slice only exposes an allowed subset.
3. Add mutations for controlled insert/delete operations.
4. Execute mutations and query the result back through the same Slice.
5. Add a subscription so clients can react to real-time changes.

The walkthrough intentionally focuses on the Slice interface, because in real deployments applications typically interact
with [Slices](Slices.md), not with the full Pod Knowledge Graph API.

## Scenario

Assume Pod `alice` contains `schema:Person` resources. We want to expose a Slice for a partner app that can:

- read only persons with an `@example.org` email,
- add or remove those persons,
- subscribe to new persons becoming available.

For clarity, we use this context in all examples:

```json
{
  "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
  "schema": "http://schema.org/"
}
```

## 1. Define a basic query model

Start with a read-only Slice schema that exposes only the fields the client needs.

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

At this point, the client can query only those fields, not arbitrary graph predicates.

Example query:

```graphql
{ persons { id schema_givenName schema_familyName schema_email } }
```

Example result (note that emails are not restricted yet):

```json
{
  "data": {
    "persons": [
      {
        "id": "http://example.org/bob",
        "schema_givenName": ["Bob"],
        "schema_familyName": ["Doe"],
        "schema_email": ["bob@example.org"]
      },
      {
        "id": "http://example.org/mallory",
        "schema_givenName": ["Mallory"],
        "schema_familyName": ["Roe"],
        "schema_email": ["mallory@other.org"]
      }
    ]
  }
}
```

## 2. Restrict visible data with an email filter

Now tighten the Slice so `schema_email` values are restricted to `@example.org` addresses.

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

Querying with `schema_email` in the selection now yields the intended filtered result:

```graphql
{ persons { id schema_givenName schema_familyName schema_email } }
```

```json
{
  "data": {
    "persons": [
      {
        "id": "http://example.org/bob",
        "schema_givenName": ["Bob"],
        "schema_familyName": ["Doe"],
        "schema_email": ["bob@example.org"]
      }
    ]
  }
}
```

This is a good start, but it has an important caveat: the filter is evaluated on the `schema_email` field.

If the client omits `schema_email` from the selection set, the filter is not part of the query execution path.

Example query:

```graphql
{ persons { id schema_givenName schema_familyName } }
```

Potentially incorrect result shape:

```json
{
  "data": {
    "persons": [
      { "id": "http://example.org/bob", "schema_givenName": ["Bob"], "schema_familyName": ["Doe"] },
      { "id": "http://example.org/eve", "schema_givenName": ["Eve"], "schema_familyName": ["Roe"] }
    ]
  }
}
```

In this scenario, `eve` should not be visible (for example because she has no email or only non-`@example.org` email).

## 3. Enforce visibility with `@mustExist`

To make the Slice boundary robust even when `schema_email` is not selected, mark the field with `@mustExist`.

```graphql
type Query {
  persons: [schema_Person!]!
}

type schema_Person {
  id: ID!
  schema_givenName: String!
  schema_familyName: String!
  schema_email: [String!] @mustExist @filter(if: "it==*@example.org")
}
```

Now the same query as above:

```graphql
{ persons { id schema_givenName schema_familyName } }
```

returns only valid Slice members:

```json
{
  "data": {
    "persons": [
      { "id": "http://example.org/bob", "schema_givenName": ["Bob"], "schema_familyName": ["Doe"] }
    ]
  }
}
```

## 4. Add mutations with email validation

Extend the schema with `add` and `remove` mutations and enforce that inserted emails end with `@example.org`.

```graphql
type Query {
  persons: [schema_Person!]!
}

type Mutation {
  add(person: [PersonInput!]!): ID!
  remove(person: [PersonInput!]!): ID!
}

type schema_Person {
  id: ID!
  schema_givenName: String!
  schema_familyName: String!
  schema_email: [String!] @mustExist @filter(if: "it==*@example.org")
}

input PersonInput @class(iri: "schema:Person") {
  id: ID!
  schema_givenName: String!
  schema_familyName: String!
  schema_email: [String!]! @shape(pattern: ".*@example\\.org$")
}
```

Each mutation returns the URI of the corresponding [Changes](Changes.md) request.

## 5. Register the Slice

Create the Slice by posting its definition to Alice's `/slices` endpoint.

**POST** `http://localhost:8080/alice/slices`

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "schema": "http://schema.org/"
  },
  "kss:name": "PersonDemoSlice",
  "kss:description": "Persons with required @example.org emails, with add/remove and subscriptions",
  "kss:schema": "type Query { persons: [schema_Person!]! } type Mutation { add(person: [PersonInput!]!): ID! remove(person: [PersonInput!]!): ID! } type schema_Person { id: ID! schema_givenName: String! schema_familyName: String! schema_email: [String!] @mustExist @filter(if: \"it==*@example.org\") } input PersonInput @class(iri: \"schema:Person\") { id: ID! schema_givenName: String! schema_familyName: String! schema_email: [String!]! @shape(pattern: \".*@example\\\\.org$\") }"
}
```

If successful, the response is `201 Created` and the `Location` header points to the new Slice URL.

## 6. Query through the Slice

Use the Slice-specific endpoint:

`POST http://localhost:8080/alice/slices/PersonDemoSlice/query`

```graphql
{ persons { id schema_givenName schema_familyName schema_email } }
```

Only resources matching the Slice schema and embedded filters are returned.

## 7. Add data via Slice mutation and query again

Insert a new person via the same Slice endpoint:

```graphql
mutation {
  add(
    person: {
      id: "http://example.org/jane"
      schema_givenName: "Jane"
      schema_familyName: "Doe"
      schema_email: ["jane@example.org"]
    }
  )
}
```

If the payload passes validation, the mutation returns a change URI.

Trying to insert a person with a non-matching email should fail validation:

```graphql
mutation {
  add(
    person: {
      id: "http://example.org/mallory"
      schema_givenName: "Mallory"
      schema_familyName: "Doe"
      schema_email: ["mallory@other.org"]
    }
  )
}
```

Expected outcome: the associated change request result is `VALIDATION_ERROR`.

Then query again:

```graphql
{ persons(id: "http://example.org/jane") { id schema_givenName schema_email } }
```

Expected result shape:

```json
{
  "data": {
    "persons": [
      {
        "id": "http://example.org/jane",
        "schema_givenName": ["Jane"],
        "schema_email": ["jane@example.org"]
      }
    ]
  }
}
```

## 8. Remove data via Slice mutation

```graphql
mutation {
  remove(
    person: {
      id: "http://example.org/jane"
      schema_givenName: "Jane"
      schema_familyName: "Doe"
      schema_email: ["jane@example.org"]
    }
  )
}
```

After the change is committed, querying `persons(id: "http://example.org/jane")` should return an empty list.

## 9. Add a subscription

Add a `Subscription` type to be notified when matching persons are added.

```graphql
type Subscription {
  onPersonAdded: schema_Person!
}
```

With this field name, Kvasir infers an `INSERT` trigger for `schema_Person` changes in the Slice scope.

To consume the subscription, send a GraphQL subscription request to the same Slice `query` endpoint with
`Accept: text/event-stream`:

```graphql
subscription {
  onPersonAdded {
    id
    schema_givenName
    schema_email
  }
}
```

When a matching insert occurs, the client receives an SSE event whose payload contains the GraphQL result.

## Where to go next

- For full Slice semantics and directive reference, see [Slices](Slices.md).
- For query capabilities (pagination, sorting, language selection), see [Query API](Querying.md).
- For asynchronous processing and change status tracking, see [Changes API](Changes.md).

<seealso>
    <category ref="related">
        <a href="Knowledge-Graph.md">Knowledge Graph</a>
        <a href="Slices.md">Slices</a>
        <a href="Querying.md">Query API</a>
        <a href="Changes.md">Changes API</a>
    </category>
</seealso>



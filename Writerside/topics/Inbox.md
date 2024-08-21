# Inbox

A single inbox endpoint accepts mutations to the Knowledge Graph of a Pod.

## Insert mutation

For example, the following operation writes some RDF statements as JSON-LD into the pod of Alice.

**POST** `http://localhost:8080/alice/kg/inbox`

Request body:

```json
{
  "@context": {
    "kss": "http://kvasir.discover.ilabt.imec.be/vocab#",
    "ex": "http://example.org/"
  },
  "kss:insert": [
    {
      "@id": "ex:alice",
      "@type": "ex:Person",
      "ex:bestFriend": {
        "@id": "ex:bob"
      },
      "ex:name": "Alice",
      "ex:email": "alice@example.org"
    },
    {
      "@id": "ex:bob",
      "@type": "ex:Person",
      "ex:name": "Bob",
      "ex:email": "bob@example.org"
    },
    {
      "@id": "ex:john",
      "@type": "ex:Person",
      "ex:name": "John",
      "ex:email": "jdoe@example.org"
    }
  ]
}
```

This should immediately return a `202 Accepted` response. The Kvasir Knowledge Graph follows an eventual consistency
model, so the changes may not be immediately visible in queries.

## Delete mutation

Delete mutations work similarly to insert mutations, but with a different keyword. For example, the following operation
removes John's email-address from the pod of Alice.

**POST** `http://localhost:8080/alice/kg/inbox`

Request body:

```json
{
  "@context": {
    "kss": "http://kvasir.discover.ilabt.imec.be/vocab#",
    "ex": "http://example.org/"
  },
  "kss:delete": [
    {
      "@id": "ex:john",
      "ex:email": "jdoe@example.org"
    }
  ]
}
```

## Assertions

Sometimes it can be useful to only transact a change request if a certain condition holds. For example, the following
operation only inserts the statement if the email address of Alice is not already known.

**POST** `http://localhost:8080/alice/kg/inbox`

Request body:

```json
{
  "@context": {
    "kss": "http://kvasir.discover.ilabt.imec.be/vocab#",
    "ex": "http://example.org/"
  },
  "kss:assert": [
    {
      "@type": "kss:AssertEmptyResult",
      "kss:query": "{ id(_:\"ex:alice\") ex_email }"
    }
  ],
  "kss:insert": [
    {
      "@id": "ex:alice",
      "ex:email": "alice@example.org"
    }
  ]
}
```

Note the `kss:assert` keyword, which is followed by an array of assertions. Each assertion must specify the type of
assertion and a GraphQL query [(see Querying)](Querying.md) that should return an empty result (in case of
type `kss:AssertEmptyResult`) or a
non-empty result (in case of type `kss:AssertNonEmptyResult`). If one of the assertions fails, the entire transaction is
discarded.

## With clause

The `kss:with` keyword can be used to bind a set of variables that can be used in the insert and delete operations. For
example, the following operation binds the id of a Person with the first name `Alice` and then uses this id to add some
additional personal information in the insert operation:

**POST** `http://localhost:8080/alice/kg/inbox`

Request body:

```json
{
  "@context": {
    "kss": "http://kvasir.discover.ilabt.imec.be/vocab#",
    "ex": "http://example.org/"
  },
  "kss:with": "{ ... on ex_Person { id ex_givenName(_:\"Alice\") } }",
  "kss:insert": [
    "{ \"@id\": id, \"ex:knows\": { \"@id\": \"ex:jdoe\" } }"
  ]
}
```

The with-clause value is a GraphQL query [(see Querying)](Querying.md). The results of this query can be referenced in
the insert and delete operations using [JSONata](https://jsonata.org) template strings. JSONata is a powerful JSON query
and transformation language (inspired by XPath for XML) which allows the user to construct the data that needs to be
deleted or inserted in a flexible way.

The expression in the example above operates on the result-set of the with-query at the time the change request is
processed. Conceptually you could think of this being the following JSON array:

```json
[
  {
    "id": "http://example.org/alice",
    "ex_givenName": [
      "Alice"
    ]
  }
]
```

You can then write a JSONata expression that extracts the `id` from the first element of the array and uses it in the
insert operation to add a triple with predicate `ex:knows`, referencing the Person with id `ex:jdoe`.

> **Tip**: Use the [JSONata Playground](https://try.jsonata.org/) to test your JSONata expressions, to see if it
> transforms the with-query result into the desired output.

## Delete wildcard

In addition to JSONata expressions in the insert and delete operations, the `kss:delete` operation also supports a
wildcard expression. For example, the following operation deletes all triples that match the with-clause of the change
request.

**POST** `http://localhost:8080/alice/kg/inbox`

Request body:

```json
{
  "@context": {
    "kss": "http://kvasir.discover.ilabt.imec.be/vocab#",
    "ex": "http://example.org/"
  },
  "kss:with": "{ ... on ex_Person { id ex_givenName(_:\"Alice\") ex_familyName ex_bestFriend ex_knows } }",
  "kss:delete": [
    "*"
  ]
}
```

<seealso>
    <category ref="api-ref">
            <a href="API_Reference.topic">API Reference</a>
    </category>
</seealso>
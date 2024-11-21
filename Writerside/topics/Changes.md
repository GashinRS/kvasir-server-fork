# Changes

<show-structure depth="2"/>

## Requesting changes

A single inbox endpoint accepts mutations to the Knowledge Graph of a Pod and can be used to insert or delete RDF data.

### Insert mutation

For example, the following operation writes some RDF statements as JSON-LD into the pod of Alice.

**POST** `http://localhost:8080/alice/changes`

Content-Type: `application/ld+json`

Request body:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "so": "http://schema.org/",
    "ex": "http://example.org/"
  },
  "kss:insert": [
    {
      "@id": "ex:alice",
      "@type": "ex:Person",
      "ex:knows": {
        "@id": "ex:bob"
      },
      "so:givenName": "Alice",
      "so:email": "alice@example.org"
    },
    {
      "@id": "ex:bob",
      "@type": "ex:Person",
      "so:givenName": "Bob",
      "so:email": "bob@example.org"
    },
    {
      "@id": "ex:john",
      "@type": "ex:Person",
      "so:givenName": "John",
      "so:email": "jdoe@example.org"
    }
  ]
}
```

This should immediately return a `201 Created` response. The Kvasir Knowledge Graph follows an eventual consistency
model, so the changes may not be immediately visible in queries. However, the response will contain a `Location` header,
which refers to the change request resource. This resource can be queried to check the status of the change request (
see [Reviewing a specific change](#reviewing-a-specific-change)).

### Delete mutation

Delete mutations work similarly to insert mutations, but with a different keyword. For example, the following operation
removes John's email-address from the pod of Alice.

**POST** `http://localhost:8080/alice/changes`

Content-Type: `application/ld+json`

Request body:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "so": "http://schema.org/",
    "ex": "http://example.org/"
  },
  "kss:delete": [
    {
      "@id": "ex:john",
      "so:email": "jdoe@example.org"
    }
  ]
}
```

<warning>
When a change request includes both insert and delete statements, the delete statements are always executed first.
</warning>

### Assertions

Sometimes it can be useful to only transact a change request if a certain condition holds. For example, the following
operation only inserts the statement if the email address of Alice is not already known.

**POST** `http://localhost:8080/alice/changes`

Content-Type: `application/ld+json`

Request body:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "so": "http://schema.org/",
    "ex": "http://example.org/"
  },
  "kss:assert": [
    {
      "@type": "kss:AssertEmptyResult",
      "kss:query": "{ ex_Person(id:\"ex:alice\") { so_email } }"
    }
  ],
  "kss:insert": [
    {
      "@id": "ex:alice",
      "so:email": "alice@example.org"
    }
  ]
}
```

Note the `kss:assert` keyword, which is followed by an array of assertions. Each assertion must specify the type of
assertion and a GraphQL query [(see Querying)](Querying.md) that should return an empty result (in case of
type `kss:AssertEmptyResult`) or a
non-empty result (in case of type `kss:AssertNonEmptyResult`). If one of the assertions fails, the entire transaction is
discarded.

Use the URL returned via the `Location` header to check the status of the change request. When the change resource
is available on the server (remember: eventual consistency), you should see a `resultCode` of `ASSERTION_FAILED`.

### With clause

The `kss:with` keyword can be used to bind a set of variables that can be used in the insert and delete operations. For
example, the following operation binds the id of a Person with the first name `Alice` and then uses this id to add some
additional personal information in the insert operation:

**POST** `http://localhost:8080/alice/changes`

Content-Type: `application/ld+json`

Request body:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "so": "http://schema.org/",
    "ex": "http://example.org/"
  },
  "kss:with": "{ ex_Person { id so_givenName @filter(if:\"it==Alice\") } }",
  "kss:insert": [
    "{ \"@id\": ex_Person.id, \"ex:knows\": { \"@id\": \"ex:john\" } }"
  ]
}
```

The with-clause value is a GraphQL query [(see Querying)](Querying.md). The results of this query can be referenced in
the insert and delete operations using [JSONata](https://jsonata.org) template strings. JSONata is a powerful JSON query
and transformation language (inspired by XPath for XML) which allows the user to construct the data that needs to be
deleted or inserted in a flexible way.

The expression in the example above operates on the result-set of the with-query at the time the change request is
processed. Conceptually you could think of this being the following JSON object:

```json
{
  "ex_Person": [
    {
      "so_givenName": [
        "Alice"
      ],
      "id": "http://example.org/alice"
    }
  ]
}
```

You can then write a JSONata expression that extracts the `id` from the first element of the array and uses it in the
insert operation to add a triple with predicate `ex:knows`, referencing the Person with id `ex:john`.

> **Tip**: Use the [JSONata Playground](https://try.jsonata.org/) to test your JSONata expressions, to see if it
> transforms the with-query result into the desired output.
> {style="tip"}

Use the URL returned via the `Location` header to check the status of the change request. When the change resource
is available on the server, you should see a `resultCode` of `COMMITTED`. To review the actual content that was inserted
or deleted via the with-clause,
you can view details by appending the path `/records` to the change request URL (see
also: [Reviewing a specific change](#reviewing-a-specific-change)).

### Delete wildcard

In addition to JSONata expressions in the insert and delete operations, the `kss:delete` operation also supports a
wildcard expression. For example, the following operation deletes all triples that match the with-clause of the change
request.

**POST** `http://localhost:8080/alice/changes`

Content-Type: `application/ld+json`

Request body:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "so": "http://schema.org/",
    "ex": "http://example.org/"
  },
  "kss:with": "{ ex_Person { so_givenName @filter(if:\"it==Alice\") } }",
  "kss:delete": [
    "*"
  ]
}
```

## Change history

A full log of the changes made to a pod's Knowledge Graph can be retrieved by querying the changes endpoint.

**GET** `http://localhost:8080/alice/changes`

Accept: `application/ld+json`

This should return a `200 OK` response with a JSON-LD object containing the change history of the pod.

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#"
  },
  "@graph": [
    {
      "@id": "http://localhost:8080/alice/changes/9af70968-541f-40dd-ace1-68cd50574fea",
      "kss:nrOfInserts": 10,
      "kss:podId": "http://localhost:8080/alice",
      "kss:resultCode": "COMMITTED",
      "kss:timestamp": "2024-11-18T12:53:04.609Z"
    },
    {
      "@id": "http://localhost:8080/alice/changes/f65997cb-80c2-466e-82f1-03ea48d72a91",
      "kss:nrOfDeletes": 1,
      "kss:podId": "http://localhost:8080/alice",
      "kss:resultCode": "COMMITTED",
      "kss:timestamp": "2024-11-18T12:36:54.285Z"
    },
    {
      "@id": "http://localhost:8080/alice/changes/762066cd-78d5-4610-b1d2-589914abbd05",
      "kss:nrOfInserts": 5,
      "kss:podId": "http://localhost:8080/alice",
      "kss:resultCode": "COMMITTED",
      "kss:timestamp": "2024-11-18T12:17:13.395Z"
    }
  ]
}
```

### Reviewing a specific change

You can query the status of a specific change request.

**GET** `http://localhost:8080/alice/changes/f65997cb-80c2-466e-82f1-03ea48d72a91`

Accept: `application/ld+json`

This should return a `200 OK` response with a JSON-LD object containing the details of the change request.

```json
{
  "@id": "http://localhost:8080/alice/changes/f65997cb-80c2-466e-82f1-03ea48d72a91",
  "kss:nrOfDeletes": 1,
  "kss:podId": "http://localhost:8080/alice",
  "kss:resultCode": "COMMITTED",
  "kss:timestamp": "2024-11-18T12:36:54.285Z",
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#"
  }
}
```

In this case the resultCode is `COMMITTED`, which means the change request was successfully processed. Other possible
values are:

* `ASSERTION_FAILED`: The Change Request was not applied because one or more assertions failed.
* `NO_MATCHES`: The Change Request was not applied because the with-clause did not return any results.
* `TOO_MANY_MATCHES`: The Change Request was not applied because the with-clause returned too many results.
* `VALIDATION_ERROR`: The Change Request was not applied because of a validation error.
* `INTERNAL_ERROR`: The Change Request was not applied because of an internal error.

In case of an error, the response will contain an `errorMessage` field with a description of the error.

To review the actual content that was inserted or deleted, you can view details by appending the path `/records` to the
change request URL.

**GET** `http://localhost:8080/alice/changes/f65997cb-80c2-466e-82f1-03ea48d72a91/records`

Accept: `application/ld+json`

This should return a `200 OK` response with a JSON-LD object containing the records that were inserted or deleted.

```json
{
  "@id": "http://localhost:8080/alice/changes/f65997cb-80c2-466e-82f1-03ea48d72a91",
  "kss:delete": {
    "@id": "http://example.org/john",
    "http://schema.org/email": "jdoe@example.org"
  },
  "kss:timestamp": "2024-11-18T12:36:54.285Z",
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#"
  }
}
```

## Streaming changes

You can subcribe to changes in a pod by using the changes endpoint with the `Accept: text/event-stream` header. This
will return a stream of Server-Sent Events (SSE), containing the changes that are being made to the Pod in
near-realtime.

**GET** `http://localhost:8080/alice/changes` with header `Accept: text/event-stream`

This will return a stream of events, where each event is a JSON-LD instance containing information on changes made to
the Pod's Knowledge Graph.

```
data:{"@context":{"kss":"http://localhost:8080/"},"@id":"http://localhost:8080/alice/changes/762066cd-78d5-4610-b1d2-589914abbd05","https://kvasir.discover.ilabt.imec.be/vocab#timestamp":"2024-11-18T12:17:13.395Z","https://kvasir.discover.ilabt.imec.be/vocab#insert":[{"@id":"http://example.org/alice","http://schema.org/email":[{"@value":"alice@example.org"}],"http://schema.org/givenName":[{"@value":"Alice"}],"http://schema.org/knows":[{"@id":"http://example.org/bob"}],"@type":["http://schema.org/Person"]},{"@id":"http://example.org/bob","http://schema.org/email":[{"@value":"bob@example.org"}],"http://schema.org/givenName":[{"@value":"Bob"}],"http://schema.org/knows":[{"@id":"http://example.org/risto"}],"@type":["http://schema.org/Person"]},{"@id":"http://example.org/risto","http://schema.org/givenName":[{"@value":"Risto"}],"@type":["http://example.org/Cat"]}]}

data:{"@context":{"kss":"http://localhost:8080/"},"@id":"http://localhost:8080/alice/changes/f65997cb-80c2-466e-82f1-03ea48d72a91","https://kvasir.discover.ilabt.imec.be/vocab#timestamp":"2024-11-18T12:36:54.285Z","https://kvasir.discover.ilabt.imec.be/vocab#delete":[{"@id":"http://example.org/alice","http://schema.org/email":[{"@value":"alice@example.org"}]}]}
```

<seealso>
    <category ref="api-ref">
            <a href="API-Reference.md">API Reference</a>
    </category>
</seealso>
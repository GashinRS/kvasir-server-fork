PoC of a possible future Kvasir Data Storage Server

# Running in dev mode
To run the server in dev mode, you need Docker (for the dependencies Kafka and [Xtdb](https://xtdb.com)) and a Java 21 JDK.

```bash
docker compose up -d
./gradlew :services:monolith:quarkusDev
```

# API examples

## Inbox endpoint

A single inbox endpoint accepts mutations to the Knowledge Graph of a Pod.
For example, the following operation writes some RDF statements as JSON-LD into the pod of Alice.

**POST** `http://localhost:8080/alice/kg/inbox`

Request body:

```json
{
  "@context" : {
    "kss":"http://kvasir.discover.ilabt.imec.be/vocab#",
    "ex": "http://example.org/"
  },
  "kss:inserts": [
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

This should immediately return a `202 Accepted` response.

Note: at the moment, only inserts are supported. Updates and deletes will be added in the future.

## Query endpoint

### Basic usage
The standard query mechanism for the Pod KG uses schemaless GraphQL (inspired by Ruben Taelman's [GraphQL to SPARQL library](https://github.com/rubensworks/graphql-to-sparql.js) and the [Stardog GraphQL API](https://docs.stardog.com/query-stardog/graphql)).

E.g. the following query retrieves all resources that have a name and an email address:

**POST** `http://localhost:8080/alice/kg/query`

Request body:

```json
{
  "@context": {
    "name": "http://example.org/name",
    "email": "http://example.org/email"
  },
  "query": "{ name email }"
}
```

Response body:

```json
{
  "data": [
    {
      "name": [
        "Alice"
      ],
      "email": [
        "alice@example.org"
      ]
    },
    {
      "name": [
        "John"
      ],
      "email": [
        "jdoe@example.org"
      ]
    },
    {
      "name": [
        "Bob"
      ],
      "email": [
        "bob@example.org"
      ]
    }
  ]
}
```

Nested queries are also supported:

**POST** `http://localhost:8080/alice/kg/query`

```json
{
  "@context": {
    "name": "http://example.org/name",
    "email": "http://example.org/email",
    "bestFriend": "http://example.org/bestFriend"
  },
  "query": "{ name email bestFriend { name email } }"
}
```

Returns:

```json
{
  "data": [
    {
      "name": [
        "Alice"
      ],
      "email": [
        "alice@example.org"
      ],
      "bestFriend": [
        {
          "name": [
            "Bob"
          ],
          "email": [
            "bob@example.org"
          ]
        }
      ]
    }
  ]
}
```

### Additional features
For more advanced querying, the demo already supports some of the features of the GraphQL to SPARQL library, such as filtering by value, aliases, the `__typename` field for introspection, fragments and some directives: `@optional`, `@single` (partially).

#### Namespace prefixes
Up until now, the examples used complete aliases for the fields, declared in the `@context` instance. However, the system also supports namespace prefixes. Typically, prefixes are separated from the field name by a colon, e.g. `ex:name` instead of `http://example.org/name`. However, GraphQL does not support colons in field names. Therefore, Kvasir uses the underscore character `_` as a substitute for the colon. For example, the previous example query can be rewritten as:

```json
{
  "@context": {
    "ex": "http://example.org/"
  },
  "query": "{ ex_name ex_email ex_bestFriend { ex_name ex_email } }"
}
```

#### Single field
By default, all values will be considered plural, and values will always be emitted in an array. To retrieve a single value, use the `@single` directive:

**POST** `http://localhost:8080/alice/kg/query`

```json
{
  "@context": {
    "ex": "http://example.org/"
  },
  "query": "{ ex_name @single ex_email @single ex_bestFriend @single} "
}
```

Returns a slightly more compact response:

```json
{
  "data": [
    {
      "ex_name": "Alice",
      "ex_email": "alice@example.org",
      "ex_bestFriend": "http://example.org/bob"
    }
  ]
}
```

#### Fragments (querying by type)
For example, the following query retrieves all resources that are of type `ex:Person`:

**POST** `http://localhost:8080/alice/kg/query`

```json
{
  "@context": {
    "name": "http://example.org/name",
    "email": "http://example.org/email",
    "bestFriend": "http://example.org/bestFriend",
    "Person": "http://example.org/Person"
  },
  "query": "{ ... on Person { id __typename name @single email @single }}"
}
```

Returns:

```json
{
  "data": [
    {
      "id": "http://example.org/bob",
      "__typename": [
        "http://example.org/Person"
      ],
      "name": "Bob",
      "email": "bob@example.org"
    },
    {
      "id": "http://example.org/john",
      "__typename": [
        "http://example.org/Person"
      ],
      "name": "John",
      "email": "jdoe@example.org"
    },
    {
      "id": "http://example.org/alice",
      "__typename": [
        "http://example.org/Person"
      ],
      "name": "Alice",
      "email": "alice@example.org"
    }
  ],
  "@context": {
    "name": "http://example.org/name",
    "email": "http://example.org/email",
    "bestFriend": "http://example.org/bestFriend",
    "Person": "http://example.org/Person"
  }
}
```

Note the use of the built-in fields `id` and `__typename` for introspection. Additionally, you can use the system field `__fieldnames` to retrieve all possible fields (predicate IRIs) for a selection.

For example:

**POST** `http://localhost:8080/alice/kg/query`

```json
{
  "@context": {
    "Person": "http://example.org/Person"
  },
  "query": "{ ... on Person { __fieldnames }}"
}
```

Returns:

```json
{
  "data": [
    {
      "__fieldnames": [
        "http://example.org/bestFriend",
        "http://example.org/email",
        "http://example.org/name"
      ]
    }
  ]
}
```

#### Filtering by value

For example, the following query retrieves the person with the name 'Bob':

**POST** `http://localhost:8080/alice/kg/query`

```json
{
  "@context": {
    "name": "http://example.org/name",
    "Person": "http://example.org/Person"
  },
  "query": "{ ... on Person { id name(_: \"Bob\") @single }}"
}
```

Returns:

```json
{
  "data": [
    {
      "id": "http://example.org/bob",
      "name": "Bob"
    }
  ],
  "@context": {
    "name": "http://example.org/name",
    "Person": "http://example.org/Person"
  }
}
```

## S3 compatible storage API
Each Kvasir pod exposes an Amazon S3 compatible storage API at the base path `/{podId}/s3`. This API can be used to store and retrieve files in the pod's storage.
Kvasir can be configured to automatically ingest files uploaded to the S3 API into the Knowledge Graph. At the time of writing, this feature is restricted to files of limited size in JSON-LD or Turtle format.

The following example uploads a text-file to the S3 API of the pod of Alice:

**PUT** `http://localhost:8080/alice/s3/test.txt`

Request headers must include a sha256 hash of the file content for verification:

```
X-Amz-Content-Sha256: beaead3198f7da1e70d03ab969765e0821b24fc913697e929e726aeaebf0eba3
```

Request body:

```
Hello World!
```

This should return a `200 OK` response.

The following example retrieves the file from the S3 API of the pod of Alice:

**GET** `http://localhost:8080/alice/s3/test.txt`


# Future Work
* Authentication and authorization
* Complete the GraphQL query model with more advanced features
* Complete the inbox API with updates and deletes
* Design and implement a model for Virtual Knowledge Graphs (subsets of the KG based on a specific schema, with their own restricted inbox/query endpoints)
* Design and implement an API for low-level storage (e.g. based on S3 ~~or LDP~~). More advanced features can be supported via this layer in the future:
  * Auto-ingestion of semantic/structured data into the Knowledge Graph from uploaded files. [Status: a PoC was implemented for JSON-LD and Turtle files.]
  * Support for streaming audio/video by adding pipelines for transcoding and indexing.
* Add time-travel and historic access for KG data
* Proper JSON-LD request & response types for the Query endpoint, while still conforming to the GraphQL spec w.r.t. HTTP binding.
* Etc
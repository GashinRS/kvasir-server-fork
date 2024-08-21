# Querying
<show-structure depth="2"/>

## Basic usage
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

## Additional features
For more advanced querying, the demo already supports some of the features of the GraphQL to SPARQL library, such as filtering by value, aliases, the `__typename` field for introspection, fragments and some directives: `@optional`, `@single` (partially).

### Namespace prefixes
Up until now, the examples used complete aliases for the fields, declared in the `@context` instance. However, the system also supports namespace prefixes. Typically, prefixes are separated from the field name by a colon, e.g. `ex:name` instead of `http://example.org/name`. However, GraphQL does not support colons in field names. Therefore, Kvasir uses the underscore character `_` as a substitute for the colon. For example, the previous example query can be rewritten as:

```json
{
  "@context": {
    "ex": "http://example.org/"
  },
  "query": "{ ex_name ex_email ex_bestFriend { ex_name ex_email } }"
}
```

### Single field
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

### Fragments (querying by type)
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

### Filtering by value

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

<seealso>
    <category ref="api-ref">
            <a href="API_Reference.topic">API Reference</a>
    </category>
</seealso>
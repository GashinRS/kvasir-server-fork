# Custom backends

<show-structure depth="2"/>

> This feature is still work in progress.
> {style="note"}

The data pipeline for the Knowledge Graph is designed to be easily reconfigurable and extensible. One aspect of this
design is a feature called "Custom data backends". The Kvasir Knowledge Graph can be extended with `StorageBackend`
implementations that can handle specific types of data in a different or optimized way, while still being fully
integrated with the Kvasir [](Changes.md) and [Query API](Querying.md), and all associated features (
e.g., [time traveling](Querying.md#time-travel)).

A built-in example of such a "Custom data backend" is the way in which Kvasir handles (SAREF) time-series.

## Why treat time-series differently?

The default KG backend stores data in a schema that reflects its RDF-based nature.
A record is inserted for each individual RDF quad statement, with separate columns for its `subject`, `predicate`,
`object` and `graph` components. This is fine as a general-purpose solution, and by building on top of columnar database
technology, Kvasir can provide decent performance and scalability for a wide range of use cases.

However, time-series are typically associated with use-cases where high-frequency, large volume data is common.
In such cases, data storage can benefit from various optimizations provided by specialized techniques such compression
encodings (double delta, gorilla), tailored partitioning and sorting strategies, reverse lookup tables, etc. These
optimizations result in a significant reduction in storage size and lead to faster scan times, resulting in improved
query performance.

Our dedicated time-series schema stores a record for each individual measurement/observation, whereas the same data
would result in multiple records (one for each RDF statement) in the generic data backend. It is then the responsibility
of a dedicated custom data backend implementation to integrate this alternative schema within the Kvasir
framework for tight integration with the Knowledge Graph.

## How does this work for SAREF measurements?

> This is a prototype implementation to demonstrate the platform capabilities, we are open to suggestions on how we can
> improve this feature into a flexible solution for working with time-series data.
> {style="note"}

### Inserting data

In the current implementation, the SAREF storage backend will trigger for any resource that is inserted in the Knowledge
Graph that expresses [SAREF hasTimestamp](https://saref.etsi.org/core/hasTimestamp)
and [SAREF hasValue](https://saref.etsi.org/core/hasValue) relationships in the insert buffer.

For example, when inserting the following data in the Knowledge Graph of Alice:

```json
{
  "@context": {
    "saref": "https://saref.etsi.org/core/",
    "ex": "http://example.org/",
    "xsd": "http://www.w3.org/2001/XMLSchema#",
    "rdfs": "http://www.w3.org/2000/01/rdf-schema#"
  },
  "@graph": [
    {
      "@id": "ex:Sensor1",
      "@type": "saref:Sensor",
      "rdfs:label": "An example sensor producing SAREF measurements."
    },
    {
      "@id": "ex:Observation1",
      "@type": "saref:Measurement",
      "saref:measurementMadeBy": {
        "@id": "ex:Sensor1"
      },
      "saref:hasTimestamp": {
        "@type": "xsd:dateTime",
        "@value": "2022-01-03T09:04:55.000000"
      },
      "saref:hasValue": 1013.1
    },
    {
      "@id": "ex:Observation2",
      "@type": "saref:Measurement",
      "saref:measurementMadeBy": {
        "@id": "ex:Sensor1"
      },
      "saref:hasTimestamp": {
        "@type": "xsd:dateTime",
        "@value": "2022-01-03T09:06:52.000000"
      },
      "saref:hasValue": 1018.3
    },
    {
      "@id": "ex:Observation3",
      "@type": "saref:Measurement",
      "saref:measurementMadeBy": {
        "@id": "ex:Sensor1"
      },
      "saref:hasTimestamp": {
        "@type": "xsd:dateTime",
        "@value": "2022-01-03T09:08:49.000000"
      },
      "saref:hasValue": 1006.9
    },
    {
      "@id": "ex:Observation4",
      "@type": "saref:Measurement",
      "saref:measurementMadeBy": {
        "@id": "ex:Sensor1"
      },
      "saref:hasTimestamp": {
        "@type": "xsd:dateTime",
        "@value": "2022-01-03T09:10:30.000000"
      },
      "saref:hasValue": 1159.3
    },
    {
      "@id": "ex:Observation5",
      "@type": "saref:Measurement",
      "saref:measurementMadeBy": {
        "@id": "ex:Sensor1"
      },
      "saref:hasTimestamp": {
        "@type": "xsd:dateTime",
        "@value": "2022-01-03T09:12:54.000000"
      },
      "saref:hasValue": 1029.30
    }
  ]
}
```

The sensor metadata (`ex:Sensor1`) is stored in the generic backend, while all observations (`ex:ObservationX`) are
stored in the time-series optimized backend instead. Note that this still happened within the same transaction (change
request).

This can be verified by listing the Change History of the Knowledge graph (see [](Changes.md#change-history)).

### Querying data

The Kvasir GraphQL-based query API provides an integrated querying experience, regardless of in which `StorageBackend`
implementation the RDF data is stored.

This works by requiring each implementation to provide a GraphQL query transformation and a GraphQL data fetcher.
These are then used by the framework to coördinate the query resolving process.

* First each storage backend gets a pass in transforming the query, according to their placement in the configured KG
  processing pipeline. The goal of the transformation is to indicate which parts of the query paths are retrievable via
  said backend, by inserting Kvasir `@storage` directives.
* Kvasir will then break down the query into segments based on this information, and call the data fetcher associated
  with a specific storage backend when an `@storage` directive is encountered.
* When no specific storage backend is associated with a node in the GraphQL query, the default generic backend is used.

Using the [`@reverse` keyword feature](Querying.md#reversing-traversal), we can retrieve all observations measured by
specific sensor as follows:

**POST** `http://localhost:8080/alice/query`

```json
{
  "@context": {
    "ex": "http://example.org/",
    "schema": "http://schema.org/",
    "saref": "https://saref.etsi.org/core/",
    "rdfs": "http://www.w3.org/2000/01/rdf-schema#",
    "hasMeasurement": {
      "@reverse": "saref:measurementMadeBy"
    }
  },
  "query": "{ saref_Sensor(id: \"ex:Sensor1\") { rdfs_label hasMeasurement { id saref_hasTimestamp saref_hasValue } } }"
}
```

Returns:

```json
{
  "data": {
    "saref_Sensor": [
      {
        "rdfs_label": [
          "An example sensor producing SAREF measurements."
        ],
        "hasMeasurement": [
          {
            "id": "http://example.org/Observation3",
            "saref_hasTimestamp": [
              "2022-01-03T09:08:49.000Z"
            ],
            "saref_hasValue": [
              1006.9
            ]
          },
          {
            "id": "http://example.org/Observation2",
            "saref_hasTimestamp": [
              "2022-01-03T09:06:52.000Z"
            ],
            "saref_hasValue": [
              1018.3
            ]
          },
          {
            "id": "http://example.org/Observation5",
            "saref_hasTimestamp": [
              "2022-01-03T09:12:54.000Z"
            ],
            "saref_hasValue": [
              1029.3
            ]
          },
          {
            "id": "http://example.org/Observation4",
            "saref_hasTimestamp": [
              "2022-01-03T09:10:30.000Z"
            ],
            "saref_hasValue": [
              1159.3
            ]
          },
          {
            "id": "http://example.org/Observation1",
            "saref_hasTimestamp": [
              "2022-01-03T09:04:55.000Z"
            ],
            "saref_hasValue": [
              1013.1
            ]
          }
        ]
      }
    ]
  }
}
```
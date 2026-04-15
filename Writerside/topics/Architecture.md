# Architecture

## Introduction

Kvasir is designed primarily as a high-performance data exchange layer. To optimize storage and retrieval, the platform
categorizes data into two distinct streams:

* **Structured Data**: All semantic data is modeled as RDF and persisted in a specialized quad/triple store (the
  Knowledge Graph).
* **Unstructured Data**: Large objects such as binaries, media, and documents are managed via an S3-compatible object
  store.

The system utilizes a modular, microservice-based architecture that enforces a strict separation of read and write
operations. This decoupling allows each path to scale independently and optimizes the data flow for both high-frequency
ingest and complex querying.

Central to this design is an event-driven message bus that captures all state mutations. This enables real-time data
processing and allows external services to integrate seamlessly by reacting to system events. By avoiding tight coupling
between APIs and storage engines, Kvasir provides a highly flexible environment that adapts to diverse application
requirements without vendor or protocol lock-in.

```D2
```

{ src="../diagrams/architecture.d2" }

## Components

Following the signal flow depicted in the diagram (from top to bottom), the key components of the Kvasir architecture
are as follows:

### kg-changes-api [service]

The Changes API is responsible for processing all mutations on structured data, i.e. RDF statements to be added to or
deleted from the Knowledge Graph. It receives these mutations as change requests events and publishes them to a
message queue (`incoming-changes`) for asynchronous processing.

### storage-api [service]

The Storage API provides an interface for downloading, uploading or deleting unstructured data. The
preferred interface is S3-compatible, but Kvasir may provide alternative implementations (e.g. a Solid compliant
interface).

### low-level storage [infrastructure]

The underlying storage layer for unstructured data, which is typically an S3-compatible object store.

### storage-events [message queue]

All interactions with the Storage API are published as events to the `storage-events` message queue. This allows other
services to react to changes in the unstructured data layer, such as new file uploads or deletions.

### query-events [message queue]

The Query API publishes events to the `query-events` message queue whenever a query is executed. This can be used for
monitoring, auditing, or triggering other processes based on query activity.

### rdf-ingester [service]

Kvasir can be configured to automatically ingest files with recognized RDF media types (such as Turtle, JSON-LD, or
RDF/XML) directly into the Knowledge Graph. This is implemented via
the RDF Ingester service, which subscribes to the `storage-events` queue and processes relevant events to extract RDF
data and apply it to the Knowledge Graph as incoming change requests (cfr. what is produced by the Changes API).

### incoming-changes [message queue]

The `incoming-changes` message queue serves as the central hub for all change requests to the Knowledge Graph.
A change request can be seen as an atomic unit of work that represents a set of RDF statements to be added or deleted.
The queue allows for asynchronous processing of these requests, enabling the system to handle high volumes of mutations
without blocking the API layer.

### kg-changes-processor [service]

The Changes Processor is responsible for consuming change requests from the `incoming-changes` queue and applying them
to the Knowledge Graph. It ensures that all mutations are processed in a consistent and reliable manner, handling any
necessary validation, change request/state dependencies, and error handling.

### structured-data [infrastructure]

The underlying storage layer for structured data, broken down into individual RDF statements (quads or triples) and
persisted in a specialized database.

### outgoing-changes [message queue]

The `outgoing-changes` message queue captures all change request that have been completed (either successfully or not).
This allows (external) services to react to changes in the structured data layer, such as triggering data subscriptions,
or keeping an external system in-sync.

### kg-query-api [service]

The Query API is responsible for reading data from the Knowledge Graph. It also provides a mechanism for defining
subsets of the Knowledge Graph as Slices, which can be queried independently. This is a powerful concept for managing
access control and data sharing in collaboration with the Policy Enforcer.

### kg-stream-api [service]

The Stream API allows clients to subscribe to event streams (outgoing changes, storage events, query events).

## Microservices vs. Monolith

Kvasir adopts a pragmatic approach in implementing a Microservice architecture, balancing the modularity of distributed
systems with the development velocity of a unified codebase. While structured as distinct services, the platform
utilizes a monorepo and shared internal libraries to streamline maintenance. Communication is handled via Kafka for
asynchronous events, though services may interface directly with shared infrastructure—such as ClickHouse or S3—when
performance or logic dictates. This design provides the scalability and flexibility of microservices while avoiding the
operational overhead of strict decoupling, leaving the path open for further separation as the platform matures.

To accommodate different environments, Kvasir is distributed in two distinct formats. For development and testing, an "
all-in-one" monolithic container image bundles all services (e.g., kg-changes-api, kg-query-api) into a single process
for easy local execution. For production deployments, however, individual microservice images should be used. This
ensures that each component can be scaled and managed independently to meet specific resource demands and traffic
patterns.

## Dependencies

Kvasir relies on a number of external dependencies to provide its core functionalities. These include:

* [**Clickhouse**](https://clickhouse.com): A high-performance columnar database used for storing structured RDF data.
* [**SeaweedFS**](https://seaweedfs.com): A distributed file system used for storing unstructured data in an
  S3-compatible manner. (1)
* [**Apache Kafka**](https://kafka.apache.org): A distributed event streaming platform used for managing the message
  queues
  that facilitate communication between microservices and enable real-time data processing.
* [**Keycloak**](https://www.keycloak.org): An open-source identity and access management solution used as the embedded
  identity provider and OpenID Connect broker for authentication in Kvasir.
* [**OpenFGA**](https://www.openfga.dev): An open-source fine-grained authorization system used for implementing the
  Policy
  Enforcer, which manages access control policies.
* [**PostgreSQL**](https://www.postgresql.org): A relational database, required for Keycloak and OpenFGA to store their
  respective internal data.

(1): Note: Kvasir is tested with SeaweedFS, but the architecture allows for alternative S3-compatible storage solutions
to be configured (e.g. Minio or an AWS S3 instance).
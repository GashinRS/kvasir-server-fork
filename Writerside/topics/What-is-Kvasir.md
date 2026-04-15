# Introduction to Kvasir

Kvasir is a cloud-native, microservice-based platform, implementing a scalable data broker in the context of Solid and
related Linked Data applications. The name Kvasir refers to a figure in Norse mythology, known for spreading knowledge
and associated with peacemaking. As such, Kvasir symbolizes the mission statement of the platform: storing data to
generate knowledge, bringing applications together through interoperability and bridging the gap between a decentralized
Web and modern Cloud development.

The platform now offers core functionalities and is being further developed at [IDLab](https://idlab.technology).

## Usage Scenarios

Kvasir provides a flexible, schema-agnostic data infrastructure where APIs and storage models are defined by the
interacting applications rather than the platform itself. By utilizing RDF as an underlying modeling language, Kvasir
ensures high interoperability while remaining protocol-agnostic, supporting data exchange directly as RDF (JSON-LD,
Turtle) or via GraphQL and S3-compatible interfaces.

Architecturally, Kvasir functions as a high-performance Data Management System (DBMS) exposed via HTTP. It offers native
support for multi-tenancy, fine-grained access control, and real-time push notifications, making it suitable for a
diverse range of deployment patterns:

* **Universal Application Backend**: Provides a ready-to-use infrastructure for web and mobile apps, offering built-in
  authentication, fine-grained access control, and standardized storage.
* **Decentralized Resource Server**: Acts as a storage provider in [Solid](https://solidproject.org) or [Linked Web
  Storage ecosystems](https://www.w3.org/2024/09/linked-web-storage-wg-charter.html), decoupling user data from
  application logic to ensure data sovereignty.
* **Data Spaces & Knowledge Graphs**: Facilitates secure, interorganizational data sharing. Kvasir’s semantic
  foundation allows disparate datasets to be integrated into machine-readable knowledge graphs.

## (Planned) Features

* **Scalable data backend** built using industry-proven technologies such as Kubernetes, Apache Kafka, Clickhouse,
  Minio, etc.
* **Powerful APIs**: ingest, query, stream and export large amounts of data using a range of APIs, optimized for
  different use-cases. Retrieve structured data via an RDF-compatible GraphQL query engine, upload or download files via
  an S3-compatible API, or interface with time-series or other data streams via special purpose APIs.
* **Secure**: Kvasir integrates with the latest developments in Solid authentication and authorization, built upon
  industry standards such as User Managed Access (UMA) 2.0. The entire platform can be configured to be encrypted at
  rest.
* **Extensible**: allow user-supplied transformations and other functions to be executed on the platform infrastructure,
  allowing direct tie-ins with the server-side data flow.
* **Interoperable**: the flexible architecture facilitates integration with a wide range of applications and
  technologies (e.g. Solid or W3C Linked Web Storage).

![](idlab.png){style="block"} ![](imec_ugent.png){style="block"}
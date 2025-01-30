# Introduction to Kvasir

Kvasir is a cloud-native, microservice-based platform, implementing a scalable data broker in the context of Solid and
related Linked Data applications. The name Kvasir refers to a figure in Norse mythology, known for spreading knowledge
and associated with peacemaking. As such, Kvasir symbolizes the mission statement of the platform: storing data to
generate knowledge, bringing applications together through interoperability and bridging the gap between a decentralized
Web and modern Cloud development.

The platform is being developed at [IDLab](https://idlab.technology) in the context of [SolidLab](https://solidlab.be)
and is currently in early prototype stage.

## Planned Features

* **Scalable data backend** built using industry-proven technologies such as Kubernetes, Apache Kafka, Clickhouse, Minio, etc.
* **Powerful APIs**: ingest, query, stream and export large amounts of data using a range of APIs, optimized for
  different use-cases. Retrieve structured data via an RDF-compatible GraphQL query engine, upload or download files via
  an S3-compatible API, or interface with time-series or other data streams via special purpose APIs.
* **Secure**: Kvasir integrates with the latest developments in Solid authentication and authorization, built upon
  industry standards such as User Managed Access (UMA) 2.0.
* **Extensible**: allow user-supplied transformations and other functions to be executed on the platform infrastructure,
  allowing direct tie-ins with the server-side data flow.
* **Interoperable**: the flexible architecture facilitates integration with a wide range of applications and
  technologies.

![](idlab.png){style="block"} ![](imec_ugent.png){style="block"}
# Architecture

## Introduction

Kvasir employs a modular microservice-based architecture, with a fundamental separation of read and write operations.
This design choice enhances scalability and performance by optimizing the data flow for both reading and writing.
Additionally, the system utilizes a message bus for all mutations and events, which simplifies the integration of new
services that can react to these events in real-time. This approach not only supports real-time data processing but also
facilitates seamless integration with other systems. As a result, Kvasir offers significant flexibility, avoiding
dependency on a single write API, querying mechanism, or storage solution.

![](kvasir-architecture.png)

## Components

Following the signal flow depicted in the diagram (from left to right), the key components of the Kvasir architecture
are as follows:

### Inbox (or Changes) API

The Inbox API is responsible for processing all mutations on structured data, i.e. RDF statements to be added to or
deleted from a pod's Knowledge Graph. It receives these mutations as change
requests events and publishes them to a message queue for asynchronous processing.

### Storage API (Write side)

In addition to the Inbox API, there is a low-level Storage API for uploading or deleting raw files, such as images,
documents or binary data.
Pods can be configured to ingest files with recognized RDF media types (such as Turtle, JSON-LD, or RDF/XML) directly
into the Knowledge Graph. This is implemented via a notification system that informs the Inbox API of new files to be
processed.

### Process and transform

The Process and Transform Component encompasses the message bus and all subscribing services that handle change requests
or other events. This component ensures that all change requests are consistently and promptly applied to the Knowledge
Graph. Additionally, it can feed other storage systems (optimized for various use cases), prepare data for streaming,
and create playlists and video segments for HLS streaming, etc.

### Query API

The Query API is responsible for reading data from the Knowledge Graph. It also provides a mechanism for defining
subsets of the Knowledge Graph as Slices, which can be queried independently. This is a powerful concept for managing
access control and data sharing in collaboration with the Policy Manager.

### Storage API (Read side)

A low-level storage API allows downloading raw files, such as images, documents, or binary data, from the storage layer.

### Alternative read APIs

Depending on how the Kvasir server is configured, there may be additional read APIs that provide alternative ways to
access the Knowledge Graph or data stored in the low-level storage layer. These APIs can be used for specific use cases,
such as streaming data to a client or providing a more efficient way to access data for a particular application (e.g.
time series).




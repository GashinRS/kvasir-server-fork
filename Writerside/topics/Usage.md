# Usage

<show-structure depth="2"/>

This section is the practical guide for working with Kvasir APIs.

If you are new to Kvasir, start with the Pod concept below, then follow the workflow links based on what you want to do
(query data, submit changes, define Slices, manage storage, or configure security).

## What is a Pod?

A **Pod** is the tenant boundary in Kvasir. Each Pod has its own base URI (for example
`http://localhost:8080/alice`), its own data, and its own runtime configuration.

Most API endpoints are scoped under that Pod URI:

- Global Knowledge Graph query endpoint: `/{podId}/query`
- Global change endpoint: `/{podId}/changes`
- Slice-scoped query endpoint: `/{podId}/slices/{sliceId}/query`
- Storage endpoint: `/{podId}/s3/...`
- Event streams: `/{podId}/events/...`

In real integrations, applications typically do not access the full Pod Knowledge Graph. They access one or more
[Slices](Slices.md), which expose controlled subsets of the graph.

## Choose a workflow

- **Understand the graph model first**: start with [Knowledge Graph](Knowledge-Graph.md).
- **Read/query data**: use [Query API](Querying.md).
- **Write RDF changes**: use [Changes API](Changes.md).
- **Define application-facing graph APIs**: use [Slices](Slices.md) and the [Slice walkthrough](Slice-Walkthrough.md).
- **Store and retrieve files**: use [Storage](Storage.md).
- **Configure Pod behavior**: use [Pod Management](Pod-Management.md) and [Configuration Reference](Configuration-Reference.md).
- **Secure and control access**: read [Identity & Security](Identity-and-Security.md), [Authentication](Authentication.md), and [Access Control](Access-Control.md).
- **Subscribe to operational streams**: see [Events](Events.md).

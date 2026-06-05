# Knowledge Graph

The Kvasir Knowledge Graph (KG) is an RDF graph database at the heart of every Pod. It stores data as RDF triples or
quads, provides a rich query interface, and maintains a complete, immutable history of every change ever made to it.

## Design

The Knowledge Graph API separates write and read operations:

* **Write** — mutations are submitted to the [Changes API](Changes.md), which processes them asynchronously via a
  message queue. A worker then applies the committed changes to the KG.
* **Read** — queries are executed against the [Query API](Querying.md).

This separation enables independent optimisation of the read and write paths and makes it easy to handle large volumes
of concurrent changes while preserving consistency.

The asynchronous write model also means that other processes can subscribe to the message queue and react in real-time
to Knowledge Graph changes (see [Streaming changes](Changes.md#streaming-changes)).

## Slices

In practice, applications rarely — if ever — need access to an owner's entire Knowledge Graph. Owners typically want to
expose only a well-defined, purpose-built window into their data. Kvasir formalises this through the concept of
**[Slices](Slices.md)**.

A Slice is a named, schema-backed subset of a Pod's Knowledge Graph. Rather than being an afterthought, Slices are the
**recommended way for applications to interact with the Knowledge Graph**:

* They expose exactly the types, fields and relationships the Slice author intends to share — nothing more.
* They carry embedded access-control semantics through filter directives and input constraints, removing the need for
  separate policy configuration for common cases.
* They support GraphQL queries, mutations _and_ subscriptions, giving clients a clean, self-describing API without
  having to know anything about the underlying RDF model.

Think of a Slice as a **typed, access-controlled view** of the Knowledge Graph, similar in spirit to a database view,
but considerably more powerful.

> In most real-world deployments a data owner publishes one or more Slices and grants third-party applications
> access only to those Slices. Direct access to the global Knowledge Graph APIs is typically reserved for the Pod
> owner or trusted administrator tooling.
> {style="note"}

For a hands-on introduction, see [Slices](Slices.md) and the guided [Walkthrough](Walkthrough.md).

<seealso>
    <category ref="related">
        <a href="Slices.md">Slices</a>
        <a href="Walkthrough.md">Walkthrough</a>
        <a href="Changes.md">Changes API</a>
        <a href="Querying.md">Query API</a>
    </category>
</seealso>

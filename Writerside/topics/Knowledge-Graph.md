# Knowledge Graph

The Kvasir Knowledge Graph is a graph database that stores and manages data in the form of RDF triples or quads. It is
used to store and query data in a structured way. The Knowledge Graph is the central component of the Kvasir platform
and is used to store and manage data from various sources.

## Design

The Knowledge Graph API is designed to separate write and read operations. Write operations are performed using
the [Changes API](Changes.md), while read operations are performed using the [Query API](Querying.md). This separation
allows for better scalability and performance by optimizing the read and write paths.

All mutations are posted as change requests to the [Changes API](Changes.md), which processes the requests
asynchronously by
publishing them to a message queue. The changes are then applied to the Knowledge Graph by a worker process. This makes
it easier to handle large volumes of changes, while ensuring consistency.

Finally, this design supports other processes to subscribe to the message queue and react to changes in the Knowledge
Graph. This enables real-time processing of data and facilitates the integration with other systems (
see [](Changes.md#streaming-changes)).
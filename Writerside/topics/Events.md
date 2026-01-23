# Events

We've discussed subscribing to Knowledge Graph changes in the [Changes API](Changes.md#streaming-changes) section, but
Kvasir provides additional events stream that may be useful for your application.

## Query request events

You can subscribe to query requests that have been executed on the Knowledge Graph (or a specific Slice), by sending an
HTTP request to the `{podId}/events/query` endpoint with the `Accept: text/event-stream` header. This will return a
stream of Server-Sent Events (SSE), containing information on the queries that are being executed on the Pod in
near-realtime.

**GET** `http://localhost:8080/alice/events/query` with `Accept: text/event-stream`

```
data:{"@id":"urn:kvasir:queries:3a5ec9a2-fa99-4f25-b14f-9f3ec0d22869","atTimestamp":"2025-03-31T15:00:03.722427500Z","podId":"http://localhost:8080/alice","query":"{ Resource { id so_givenName:_object(predicate: \"so:givenName\"){ _rawRDF } so_email:_object(predicate: \"so:email\"){ _rawRDF } } }","statusCode":"COMPLETED","timestamp":"2025-03-31T15:00:03.722427500Z","@context":{"ex":"http://example.org/","so":"http://schema.org/","@vocab":"https://kvasir.discover.ilabt.imec.be/vocab#"}}
```

A query request event contains the following properties (in the `https://kvasir.discover.ilabt.imec.be/vocab#`
namespace):

| Property            | Description                                                                                                                     |
|---------------------|---------------------------------------------------------------------------------------------------------------------------------|
| `@id`               | The unique identifier of the query request.                                                                                     |
| `@context`          | The context of the query request, including the namespaces used.                                                                |
| `timestamp`         | The timestamp when the query request was executed (UTC).                                                                        |
| `podId`             | The ID of the Pod the query was executed on.                                                                                    |
| `sliceId`           | ID of the Slice the query was executed on (Optional).                                                                           |
| `atTimestamp`       | The query targeted the state of the KG at a specified moment in time (see [](Querying.md#time-travel)) (Optional).              |
| `requestingUser`    | ID of the user or client that performed the query request.                                                                      |
| `atChangeRequestId` | The query targeted a specific state of the KG, by specifiying a change request id (see [](Querying.md#time-travel)) (Optional). |
| `statusCode`        | The status of the query request (e.g., `COMPLETED`, `FAILED`).                                                                  |
| `message`           | Error message in case the request was not completed successfully (Optional).                                                    |
| `query`             | The GraphQL query that was executed.                                                                                            |
| `variables`         | The variables used in the GraphQL query (Optional).                                                                             |
| `operationName`     | The name of the operation in the GraphQL query (Optional).                                                                      |

> For services that have direct access to the Kvasir infrastructure: you can subscribe to the same information by
> creating a Kafka consumer for the `query.requests` topic. This topic holds the query events for all Pods, so
> additional filtering on `podId` may be required.
> {style="note"}

## Life-cycle events

You can subscribe to life-cycle events of the Pod (config changes, slice creation, etc), by sending an HTTP request to
the`{podId}/events/life-cycle` endpoint with the `Accept: text/event-stream` header. This will return a stream of
Server-Sent-Events (SSE), containing information on the life-cycle events of the Pod in near-realtime.

**GET** `http://localhost:8080/alice/events/life-cycle` with `Accept: text/event-stream`

```
data:{"@id":"urn:kvasir:life-cycle-events:36851bea-7d52-4f34-b73c-59148d117db9","podId":"http://localhost:8080/alice","sliceId":"http://localhost:8080/alice/slices/PersonDemoSlice5","timestamp":"2025-03-31T15:13:55.791138500Z","type":"SLICE_CREATED","@context":{"@vocab":"https://kvasir.discover.ilabt.imec.be/vocab#"}}
```

A life-cycle event contains the following properties (in the `https://kvasir.discover.ilabt.imec.be/vocab#`):

| Property         | Description                                                                                               |
|------------------|-----------------------------------------------------------------------------------------------------------|
| `@id`            | The unique identifier of the life-cycle event.                                                            |
| `@context`       | The context of the life-cycle event, including the namespaces used.                                       |
| `timestamp`      | The timestamp when the life-cycle event occurred (UTC).                                                   |
| `podId`          | The ID of the Pod the life-cycle event occurred on.                                                       |
| `sliceId`        | ID of the Slice the life-cycle event occurred on (Optional).                                              |
| `requestingUser` | ID of the user or client that performed the API request that triggered this life-cycle event.             |
| `eventType`      | The type of the life-cycle event (e.g., `POD_UPDATED`, `SLICE_CREATED`, `SLICE_UPATED`, `SLICE_DELETED`). |

> For services that have direct access to the Kvasir infrastructure: you can subscribe to the same information by
> creating a Kafka consumer for the `lifecycle.events` topic. This topic holds the life-cycle events for all Pods, so
> additional filtering on `podId` may be required.
> {style="note"}

## Storage events

You can subscribe to storage events of the Pod (operations on the S3 store), by sending an HTTP request to the
`{podId}/events/s3` endpoint with the `Accept: text/event-stream` header. This will return a stream of
Server-Sent-Events (SSE), containing information on the performed S3 operations.

**GET** `http://localhost:8080/alice/events/s3` with `Accept: text/event-stream`

```
data:{"@id":"urn:kvasir:storage-events:3a5ec9a2-fa99-4f25-b14f-9f3ec0d22869","timestamp":"2025-03-31T15:00:03.722427500Z", "externalObjectUri":"http://localhost:8080/alice/s3/test.txt","internalObjectUri":"http://localhost:56443/c2d7dd29a08e40f4/test.txt","type":"PUT_OBJECT","objectId":"test.txt","podId":"http://localhost:8080/alice","versionId":"66f4b256-bafa-48f0-8dac-8838ef30cc81","@context":{"@vocab":"https://kvasir.discover.ilabt.imec.be/vocab#"}}
```

A storage event contains the following properties (in the `https://kvasir.discover.ilabt.imec.be/vocab#`):

| Property            | Description                                                                                                     |
|---------------------|-----------------------------------------------------------------------------------------------------------------|
| `@id`               | The unique identifier of the storage event.                                                                     |
| `@context`          | The context of the storage event, including the namespaces used.                                                |
| `timestamp`         | The timestamp when the operation was performed (UTC).                                                           |
| `podId`             | The ID of the Pod the operation was performed on.                                                               |
| `sliceId`           | ID of the Slice the operation was performed on (Optional).                                                      |
| `requestingUser`    | ID of the user or client that performed the operation.                                                          |
| `eventType`         | The type of operation performed on the object (e.g., `READ_OBJECT`, `PUT_OBJECT`, `DELETE_OBJECT`).             |
| `externalObjectUri` | The external URI of the object in the Pod's S3 store.                                                           |
| `internalObjectUri` | The URI of the object in the S3 store that is backing the Pod (may not be accessible from outside the cluster). |
| `objectId`          | The ID of the object in the S3 store.                                                                           |
| `versionId`         | The version ID of the object in the S3 store.                                                                   |

> For services that have direct access to the Kvasir infrastructure: you can subscribe to the same information by
> creating a Kafka consumer for the `storage.events` topic. This topic holds the storage events for all Pods, so
> additional filtering on `podId` may be required.
> {style="note"}
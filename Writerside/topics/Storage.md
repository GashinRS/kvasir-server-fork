# Storage

<show-structure depth="2"/>

Kvasir exposes two storage interfaces per Pod: an S3-compatible API and a Solid-compatible API backed by the same
underlying objects. This page shows the core operations and how they relate to optional RDF auto-ingest.

## S3-compatible storage API

Each Kvasir pod exposes an Amazon S3 compatible storage API at the base path `/{podId}/s3`. This API can be used to
store and retrieve files in the pod's storage.
Kvasir can be configured to automatically ingest files uploaded to the S3 API into the Knowledge Graph. At the time of
writing, this feature is restricted to files of limited size in JSON-LD or Turtle format.

### Uploading a file

The following example uploads a text-file to the S3 API of the pod of Alice:

**PUT** `http://localhost:8080/alice/s3/test.txt`

Request headers may include a sha256 hash of the file content for verification:

```
X-Amz-Content-Sha256: beaead3198f7da1e70d03ab969765e0821b24fc913697e929e726aeaebf0eba3
```

Request body:

```
Hello World!
```

This should return a `200 OK` response.

### Downloading a file

The following example retrieves the file from the S3 API of the pod of Alice:

**GET** `http://localhost:8080/alice/s3/test.txt`

Response body:

```
Hello World!
```

### Performance considerations

#### Client-Provided Content Hash (`x-amz-content-sha256`)

##### Context

For write operations (PUT/POST), the proxy must compute the SHA-256 hash of the request body
to produce a valid AWS Signature V4. By default, this requires the proxy to buffer the entire
request body in memory before forwarding it to S3. For large files this increases memory
pressure and latency.

##### Possible optimization

Clients can **pre-compute** the SHA-256 hash of the body and include it in the request via the
`x-amz-content-sha256` header. When this header is present:

1. The proxy **skips body buffering** entirely.
2. The request body is **streamed** directly through to S3 without being loaded into memory.
3. The provided hash value is used as-is for the AWS Signature V4 calculation.

This significantly reduces memory usage and improves upload throughput, especially for large
objects.

##### Usage

```http
PUT /{podId}/s3/path/to/object HTTP/1.1
Authorization: Bearer <token>
Content-Type: application/octet-stream
x-amz-content-sha256: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855

<binary body>
```

The hash value must be the lowercase hex-encoded SHA-256 digest of the raw request body.

## Solid-compatible storage API

Each Kvasir pod exposes a [Solid](https://solidproject.org) compatible storage API at the base path `/{podId}/solid/`.
This API can be used to interact with Solid Containers and Resources in the pod's storage.

The provided implementation is backed by S3. This implies that:

- Files uploaded via the S3 storage API are also accessible via the Solid storage API, and vice versa.
- Pod's with [auto-rdf-ingest](Pod-Management.md#auto-ingest-rdf) enabled, will automatically ingest RDF files uploaded
  to the Pod's Solid storage into the Kvasir Knowledge Graph.

### Known limitations

- Although the Solid storage API does support [Solid-OIDC (WebID) for authentication](Identity-and-Security.md), authorization via WAC or ACP is not implemented (use [Kvasir mechanisms](Access-Control.md) instead).
- The Solid storage API does not support Solid notifications.
- The Solid storage API does not implement locking at this time. This implies that concurrent modifications may lead to
  unexpected results.

<seealso>
    <category ref="api-ref">
            <a href="API-Reference.md">API Reference</a>
    </category>
</seealso>
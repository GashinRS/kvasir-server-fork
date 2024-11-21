# Storage

Each Kvasir pod exposes an Amazon S3 compatible storage API at the base path `/{podId}/s3`. This API can be used to
store and retrieve files in the pod's storage.
Kvasir can be configured to automatically ingest files uploaded to the S3 API into the Knowledge Graph. At the time of
writing, this feature is restricted to files of limited size in JSON-LD or Turtle format.

## Uploading a file

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

## Downloading a file

The following example retrieves the file from the S3 API of the pod of Alice:

**GET** `http://localhost:8080/alice/s3/test.txt`

Response body:

```
Hello World!
```

<seealso>
    <category ref="api-ref">
            <a href="API-Reference.md">API Reference</a>
    </category>
</seealso>

[//]: # (## Slice-specific storage)

[//]: # ()
[//]: # (Low-level S3 storage is also available for individual slices. The base path for slice storage is)

[//]: # (`/{podId}/slices/{sliceId}/s3`.)

[//]: # (This allows for validating data according to the slice's schema when e.g. uploading a turtle file to this location.)
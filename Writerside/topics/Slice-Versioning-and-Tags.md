# Slice versioning with tags

<show-structure depth="2"/>

A Slice evolves over time: schemas grow, filters change, and mutation capabilities expand. **Tags** let you pin
specific Slice revisions to human-readable names (for example `v1`, `2.1.0`, `stable`) so clients can keep using a
known-good contract while authors continue iterating.

## Tagging a Slice

Tags are applied by including `kss:tags` in a create (`POST /{podId}/slices`) or update
(`PUT /{podId}/slices/{sliceId}`) request. Each call stores the current schema as a new immutable revision and
associates that revision with all supplied tags.

**Example — create a Slice and tag it `v1` and `default`:**

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "schema": "http://schema.org/"
  },
  "kss:name": "PersonDemoSlice",
  "kss:schema": {
    "@type": "kss:EmbeddedSliceSchema",
    "kss:sdl": "..."
  },
  "kss:tags": [
    "v1",
    "default"
  ]
}
```

Later, publish an updated schema and assign a new tag:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "schema": "http://schema.org/"
  },
  "kss:name": "PersonDemoSlice",
  "kss:schema": {
    "@type": "kss:EmbeddedSliceSchema",
    "kss:sdl": "..."
  },
  "kss:tags": [
    "v2"
  ]
}
```

## The `default` tag

The tag `default` has special meaning: when clients use non-tagged Slice endpoints, Kvasir serves the revision pinned
to `default`. If no `default` tag exists, the newest persisted revision is used.

This allows safe rollout of breaking changes: pinned clients remain unaffected until `default` is intentionally moved.

## Tagged Slice endpoints

Tagged variants exist for all three Slice API surfaces. The tag appears in the URL as `.../tags/{tag}`.

### Slice management

| Method   | Path                                                          | Accept                | Description                                                                                                                                                  |
|----------|---------------------------------------------------------------|-----------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET`    | `/{podId}/slices/{sliceId}/tags`                              | `application/ld+json` | List all tags and their associated revisions.                                                                                                                |
| `GET`    | `/{podId}/slices/{sliceId}/tags/{tag}`                        | `application/ld+json` | Retrieve the Slice definition (JSON-LD) at the given tag.                                                                                                    |
| `PUT`    | `/{podId}/slices/{sliceId}/tags/{tag}`                        |                       | Update the Slice definition at the given tag (when no tags are explicitly specified, otherwise a new revision is created based on the content at this tag). |
| `GET`    | `/{podId}/slices/{sliceId}/tags/{tag}`                        | `text/plain`          | Retrieve the schema SDL (plain text) at the given tag.                                                                                                       |
| `DELETE` | `/{podId}/slices/{sliceId}/tags/{tag}`                        |                       | Remove a tag (the underlying revision is not deleted).                                                                                                       |
| `PUT`    | `/{podId}/slices/{sliceId}/tags/{sourceTag}/alias/{aliasTag}` |                       | Add an additional tag to the revision identified by `sourceTag`.                                                                                             |

### Querying

| Method         | Path                                         | Description                                                  |
|----------------|----------------------------------------------|--------------------------------------------------------------|
| `POST`         | `/{podId}/slices/{sliceId}/tags/{tag}/query` | Execute a GraphQL query against the schema at the given tag. |
| `GET` / `POST` | `/{podId}/slices/{sliceId}/tags/{tag}/query` | Subscribe (SSE) using the schema at the given tag.           |

### Changes

| Method | Path                                                              | Description                                                                                                                                             |
|--------|-------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `POST` | `/{podId}/slices/{sliceId}/tags/{tag}/changes`                    | Submit a change request validated against the schema at the given tag.                                                                                 |
| `GET`  | `/{podId}/slices/{sliceId}/tags/{tag}/changes`                    | List change reports for the Slice. Behaves like the non-tagged endpoint (tag is used only for change-request validation).                             |
| `GET`  | `/{podId}/slices/{sliceId}/tags/{tag}/changes/{changeId}`         | Retrieve a specific change report.                                                                                                                      |
| `GET`  | `/{podId}/slices/{sliceId}/tags/{tag}/changes/{changeId}/records` | Retrieve the individual RDF change records for a specific change.                                                                                       |

> A non-existent tag always returns `404 Not Found`.
> {style="note"}

<seealso>
    <category ref="related">
        <a href="Slices.md">Slices</a>
        <a href="Changes.md">Changes API</a>
        <a href="API-Reference.md">API Reference</a>
    </category>
</seealso>


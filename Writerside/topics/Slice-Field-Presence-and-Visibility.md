# Field presence and visibility

<show-structure depth="2"/>

This page describes two advanced Slice directives used to control resource visibility:

- `@mustExist` — requires that a field has a value for the resource to be part of the Slice result set.
- `@hidden` — keeps a field out of introspection and query selection, while still enforcing server-side constraints.

## Ensuring field presence with `@mustExist`

Use `@mustExist` on a field definition when resources should only be visible if that field has a value. This is
especially important when a Slice uses field-level restrictions (for example `@filter` on `schema_email`) and clients
may issue queries that do not select that restricted field.

For example:

```graphql
type Query {
  persons: [schema_Person!]!
}

type schema_Person {
  id: ID!
  schema_givenName: String!
  schema_familyName: String!
  schema_email: [String!] @mustExist @filter(if: "it==*@example.org")
}
```

With this definition:

- `schema_email` must exist for a resource to be considered a valid `schema_Person` in the Slice.
- The email value must match `*@example.org`.
- Queries that omit `schema_email` from the selection set still respect this visibility boundary.

In other words, `@mustExist` enforces membership at the type level, while `@filter` constrains allowed values.

## Hiding fields with `@hidden`

Use `@hidden` on a field definition when you want to enforce a filter condition or require field presence
(via `@mustExist`) **without exposing the field to clients**. A hidden field:

- **Does not appear in introspection** — clients that query `__type` or `__schema` will not see it.
- **Cannot be selected** — including a hidden field in a query document is a validation error.
- **Is still enforced server-side** — any `@filter` or `@mustExist` directive on the field is applied when
  Kvasir executes a query, exactly as it would be for a visible field.

This is useful when the data required to narrow the result set is sensitive in its own right and must not be
accessible to Slice consumers. The field acts as a purely structural guard that shapes the subgraph without
leaking its value.

### Example — restricting by a confidential attribute

Suppose each `Person` resource carries an internal `ex_clearanceLevel` property, and the Slice should only
surface persons whose clearance level is `"PUBLIC"`. Exposing `ex_clearanceLevel` itself would be
unacceptable. With `@hidden` you can apply the restriction invisibly:

```graphql
type Query {
    persons: [ex_Person!]!
}

type ex_Person {
    id: ID!
    so_givenName: String!
    so_familyName: String!
    ex_clearanceLevel: String @hidden @filter(if: "it==PUBLIC")
}
```

With this definition:

- Clients querying `{ persons { id so_givenName so_familyName } }` receive only persons whose
  `ex_clearanceLevel` equals `PUBLIC`.
- The `ex_clearanceLevel` field never appears in query responses, is invisible to introspection tools, and
  cannot be requested by name.
- Attempting to query `{ persons { ex_clearanceLevel } }` returns a validation error.

### Combining `@hidden` with `@mustExist`

`@hidden` and `@mustExist` can be combined when resources should be excluded entirely if the hidden
property is absent, rather than merely filtered by value:

```graphql
type ex_Person {
    id: ID!
    so_givenName: String!
    ex_verifiedEmail: String @hidden @mustExist @filter(if: "it==*@verified.example.org")
}
```

Here, a `Person` without a verified email is invisible through the Slice regardless of which fields the
client selects, and the email address itself is never surfaced.

> `@hidden` is a schema-definition-time directive (valid on `FIELD_DEFINITION` locations only). It has no
> meaning when used inside a query document.
> {style="note"}

<seealso>
    <category ref="related">
        <a href="Slices.md">Slices</a>
        <a href="Querying.md">Query API</a>
    </category>
</seealso>

